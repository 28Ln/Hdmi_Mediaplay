package com.btf.rk3568_hdmi_mediaplay.ui.main

import android.app.Application
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.ui.components.MessageType
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastData
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainViewModel"
    }
    
    private val settingsRepository: SettingsRepository = SettingsRepository(application)
    private val localStorageManager: LocalStorageManager = LocalStorageManager(application)
    
    // 设置
    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    
    // 播放器配置
    private val _playerConfigs = MutableStateFlow(
        List(4) { PlayerConfig(index = it) }
    )
    val playerConfigs: StateFlow<List<PlayerConfig>> = _playerConfigs.asStateFlow()
    
    // U盘状态
    private val _usbState = MutableStateFlow<UsbState>(UsbState.Disconnected)
    val usbState: StateFlow<UsbState> = _usbState.asStateFlow()
    
    // 拷贝进度
    private val _copyProgress = MutableStateFlow<CopyProgress?>(null)
    val copyProgress: StateFlow<CopyProgress?> = _copyProgress.asStateFlow()
    
    // 显示覆盖确认对话框
    private val _showOverwriteDialog = MutableStateFlow(false)
    val showOverwriteDialog: StateFlow<Boolean> = _showOverwriteDialog.asStateFlow()
    
    // Toast 消息
    private val _toastMessage = MutableStateFlow<ToastData?>(null)
    val toastMessage: StateFlow<ToastData?> = _toastMessage.asStateFlow()
    
    // 待处理的U盘路径
    private var pendingUsbPath: File? = null
    
    sealed class UsbState {
        object Disconnected : UsbState()
        data class Connected(val path: File, val hasMediaContent: Boolean) : UsbState()
        data class Error(val message: String) : UsbState()
    }
    
    data class CopyProgress(
        val playerIndex: Int,
        val progress: Float,
        val isComplete: Boolean = false,
        val error: String? = null
    )
    
    init {
        // 监听设置变化，更新存储管理器
        viewModelScope.launch {
            settings.collect { s ->
                localStorageManager.updateStorageSettings(s.storageLocation, s.customStoragePath)
            }
        }
        
        // 启动时加载本地内容
        loadLocalContent()
        
        // 启动时检查U盘状态
        checkUsbOnStartup()
    }
    
    /**
     * 启动时检查U盘状态
     */
    private fun checkUsbOnStartup() {
        safeLaunch {
            delay(1000) // 等待服务启动
            val context = getApplication<Application>()
            val usbPaths = withContext(Dispatchers.IO) {
                UsbUtils.getMountedUsbPaths(context)
            }
            
            if (usbPaths.isNotEmpty()) {
                val usbPath = usbPaths.first()
                val folderName = settings.value.usbScanFolderName
                val hasMedia = withContext(Dispatchers.IO) {
                    UsbUtils.hasValidMediaStructure(usbPath, folderName)
                }
                log("启动时检测到U盘: ${usbPath.absolutePath}, 有媒体: $hasMedia")
                _usbState.value = UsbState.Connected(usbPath, hasMedia)
            }
        }
    }
    
    /**
     * 显示 Toast 消息 - 线程安全
     */
    fun showToast(message: String, type: MessageType = MessageType.INFO) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            _toastMessage.value = ToastData(message, type)
        }
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
    
    /**
     * 安全执行协程任务
     */
    private fun safeLaunch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Task failed: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _toastMessage.value = ToastData("操作失败: ${e.message}", MessageType.ERROR)
                }
            }
        }
    }
    
    /**
     * 加载本地缓存内容
     */
    fun loadLocalContent() {
        safeLaunch {
            log("开始加载本地内容...")
            log("存储路径: ${localStorageManager.getCurrentStoragePath()}")
            
            val allMedia = withContext(Dispatchers.IO) {
                localStorageManager.getAllLocalMediaFiles()
            }
            
            var hasContent = false
            var totalFiles = 0
            
            _playerConfigs.update { configs ->
                configs.mapIndexed { index, config ->
                    val mediaItems = allMedia[index] ?: emptyList()
                    totalFiles += mediaItems.size
                    if (mediaItems.isNotEmpty()) {
                        hasContent = true
                        log("播放器 ${index + 1}: ${mediaItems.size} 个文件")
                        mediaItems.forEach { item ->
                            log("  - ${item.name} (${item.type})")
                        }
                    }
                    config.copy(
                        mediaItems = mediaItems,
                        state = if (mediaItems.isNotEmpty() && settings.value.autoPlayOnStart) 
                            PlayerState.PLAYING else PlayerState.IDLE
                    )
                }
            }
            
            if (hasContent) {
                log("本地内容加载完成，共 $totalFiles 个文件")
                showToast("已加载 $totalFiles 个文件", MessageType.SUCCESS)
            } else {
                log("本地无缓存内容")
            }
        }
    }
    
    /**
     * U盘连接
     */
    fun onUsbConnected(path: File, hasMediaContent: Boolean) {
        safeLaunch {
            log("U盘已连接: ${path.absolutePath}, 有媒体内容: $hasMediaContent")
            _usbState.value = UsbState.Connected(path, hasMediaContent)
            
            // 检查并应用U盘配置
            applyUsbConfigSettings()
            
            if (!hasMediaContent) {
                val folderName = settings.value.usbScanFolderName
                showToast("U盘已连接，未找到 /$folderName 目录", MessageType.WARNING)
                return@safeLaunch
            }
            
            showToast(StringResources.usbConnected, MessageType.SUCCESS)
            
            // 检查存储空间
            val requiredSpace = withContext(Dispatchers.IO) {
                calculateRequiredSpace(path)
            }
            val availableSpace = withContext(Dispatchers.IO) {
                localStorageManager.getAvailableSpaceMB() * 1024 * 1024
            }
            
            if (requiredSpace > availableSpace) {
                val required = FileUtils.formatFileSize(requiredSpace)
                val available = FileUtils.formatFileSize(availableSpace)
                showToast("存储空间不足！需要 $required，可用 $available", MessageType.ERROR)
                _usbState.value = UsbState.Error("存储空间不足")
                return@safeLaunch
            }
            
            // 检查是否需要确认覆盖
            val hasLocal = withContext(Dispatchers.IO) {
                localStorageManager.hasLocalContent()
            }
            
            if (settings.value.showOverwriteConfirm && hasLocal) {
                pendingUsbPath = path
                _showOverwriteDialog.value = true
            } else {
                copyFromUsb(path, settings.value.usbScanFolderName)
            }
        }
    }
    
    /**
     * U盘断开
     */
    fun onUsbDisconnected() {
        log("U盘已断开")
        _usbState.value = UsbState.Disconnected
        showToast(StringResources.usbDisconnected, MessageType.INFO)
    }
    
    /**
     * 确认覆盖
     */
    fun confirmOverwrite() {
        _showOverwriteDialog.value = false
        pendingUsbPath?.let { path ->
            copyFromUsb(path, settings.value.usbScanFolderName)
        }
        pendingUsbPath = null
    }
    
    /**
     * 取消覆盖
     */
    fun cancelOverwrite() {
        _showOverwriteDialog.value = false
        pendingUsbPath = null
        showToast("已取消覆盖", MessageType.INFO)
    }
    
    /**
     * 从U盘拷贝内容
     */
    private fun copyFromUsb(usbPath: File, folderName: String) {
        safeLaunch {
            log("开始从U盘拷贝: ${usbPath.absolutePath}/$folderName")
            log("目标路径: ${localStorageManager.getCurrentStoragePath()}")
            showToast(StringResources.copying, MessageType.INFO)
            
            _copyProgress.value = CopyProgress(0, 0f)
            
            val success = withContext(Dispatchers.IO) {
                localStorageManager.copyAllFromUsb(usbPath, folderName) { playerIndex, progress ->
                    _copyProgress.value = CopyProgress(playerIndex, progress)
                }
            }
            
            if (success) {
                _copyProgress.value = CopyProgress(3, 1f, isComplete = true)
                log("拷贝完成")
                showToast(StringResources.copyComplete, MessageType.SUCCESS)
                
                // 更新U盘状态为已连接且有内容
                _usbState.value = UsbState.Connected(usbPath, true)
            } else {
                _copyProgress.value = CopyProgress(0, 0f, error = StringResources.copyFailed)
                log("拷贝失败")
                showToast(StringResources.copyFailed, MessageType.ERROR)
            }
            
            delay(1500)
            _copyProgress.value = null
            
            if (success && settings.value.autoPlayAfterCopy) {
                loadLocalContent()
            }
        }
    }
    
    /**
     * 播放/暂停指定播放器
     */
    fun togglePlayPause(playerIndex: Int) {
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    val newState = when (config.state) {
                        PlayerState.PLAYING -> PlayerState.PAUSED
                        PlayerState.PAUSED -> PlayerState.PLAYING
                        PlayerState.IDLE -> {
                            if (config.mediaItems.isNotEmpty()) {
                                PlayerState.PLAYING
                            } else {
                                PlayerState.IDLE
                            }
                        }
                        else -> config.state
                    }
                    config.copy(state = newState)
                } else config
            }
        }
    }
    
    /**
     * 静音/取消静音指定播放器
     */
    fun toggleMute(playerIndex: Int) {
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(isMuted = !config.isMuted)
                } else config
            }
        }
    }
    
    /**
     * 设置播放器音量
     */
    fun setVolume(playerIndex: Int, volume: Float) {
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(volume = volume.coerceIn(0f, 1f))
                } else config
            }
        }
    }
    
    /**
     * 为指定播放器设置媒体文件
     */
    fun setMediaFiles(playerIndex: Int, mediaItems: List<MediaItem>) {
        Log.d(TAG, "setMediaFiles: playerIndex=$playerIndex, items=${mediaItems.size}")
        mediaItems.forEach { item ->
            Log.d(TAG, "  - ${item.name} (${item.type}) path=${item.path}")
        }
        
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    Log.d(TAG, "Updating player $playerIndex with ${mediaItems.size} items")
                    config.copy(
                        mediaItems = mediaItems,
                        currentIndex = 0,
                        state = if (mediaItems.isNotEmpty()) PlayerState.PLAYING else PlayerState.IDLE
                    )
                } else config
            }
        }
        
        if (mediaItems.isNotEmpty()) {
            showToast("已设置 ${mediaItems.size} 个文件", MessageType.SUCCESS)
        }
    }
    
    /**
     * 为指定播放器设置媒体文件（带重复检测）
     */
    fun setMediaFilesWithDuplicateCheck(playerIndex: Int, mediaItems: List<MediaItem>) {
        Log.d(TAG, "setMediaFilesWithDuplicateCheck: playerIndex=$playerIndex, items=${mediaItems.size}")
        
        val otherPlayerPaths = _playerConfigs.value
            .filterIndexed { index, _ -> index != playerIndex }
            .flatMap { it.mediaItems }
            .map { it.path }
            .toSet()
        
        Log.d(TAG, "Other player paths: ${otherPlayerPaths.size}")
        
        val filteredItems = mediaItems.filter { item ->
            item.path !in otherPlayerPaths
        }
        
        val duplicateCount = mediaItems.size - filteredItems.size
        Log.d(TAG, "Filtered items: ${filteredItems.size}, duplicates: $duplicateCount")
        
        if (filteredItems.isEmpty() && duplicateCount > 0) {
            showToast("所选文件已被其他播放器使用", MessageType.WARNING)
            return
        }
        
        if (duplicateCount > 0) {
            showToast("已过滤 $duplicateCount 个重复文件", MessageType.WARNING)
        }
        
        setMediaFiles(playerIndex, filteredItems)
    }
    
    /**
     * 播放全部
     */
    fun playAll() {
        var playCount = 0
        _playerConfigs.update { configs ->
            configs.map { config ->
                if (config.mediaItems.isNotEmpty()) {
                    playCount++
                    config.copy(state = PlayerState.PLAYING)
                } else config
            }
        }
        
        if (playCount > 0) {
            showToast("播放 $playCount 个", MessageType.SUCCESS)
        } else {
            showToast("无可播放内容", MessageType.WARNING)
        }
    }
    
    /**
     * 暂停全部
     */
    fun pauseAll() {
        _playerConfigs.update { configs ->
            configs.map { config ->
                config.copy(state = PlayerState.PAUSED)
            }
        }
        showToast("已暂停", MessageType.INFO)
    }
    
    /**
     * 更新设置
     */
    fun updateSettings(newSettings: AppSettings) {
        safeLaunch {
            // 更新存储管理器设置
            localStorageManager.updateStorageSettings(newSettings.storageLocation, newSettings.customStoragePath)
            
            withContext(Dispatchers.IO) {
                settingsRepository.updateSettings(newSettings)
            }
        }
    }
    
    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        safeLaunch {
            val sizeBefore = withContext(Dispatchers.IO) {
                localStorageManager.getCacheSizeMB()
            }
            
            withContext(Dispatchers.IO) {
                localStorageManager.clearAllCache()
            }
            
            _playerConfigs.update { configs ->
                configs.map { it.copy(mediaItems = emptyList(), state = PlayerState.IDLE) }
            }
            
            showToast("已清除 ${sizeBefore}MB", MessageType.SUCCESS)
        }
    }
    
    /**
     * 手动扫描U盘
     */
    fun scanUsb() {
        safeLaunch {
            showToast("扫描中...", MessageType.INFO)
            
            val context = getApplication<Application>()
            val usbPaths = withContext(Dispatchers.IO) {
                UsbUtils.getMountedUsbPaths(context)
            }
            
            log("扫描到 ${usbPaths.size} 个U盘路径")
            usbPaths.forEach { log("  - ${it.absolutePath}") }
            
            if (usbPaths.isEmpty()) {
                showToast("未检测到U盘", MessageType.WARNING)
                _usbState.value = UsbState.Disconnected
                return@safeLaunch
            }
            
            val usbPath = usbPaths.first()
            val folderName = settings.value.usbScanFolderName
            val hasMedia = withContext(Dispatchers.IO) {
                UsbUtils.hasValidMediaStructure(usbPath, folderName)
            }
            
            log("U盘路径: ${usbPath.absolutePath}, 有媒体: $hasMedia")
            
            if (!hasMedia) {
                showToast("未找到 /$folderName/player1~4", MessageType.WARNING)
                _usbState.value = UsbState.Connected(usbPath, false)
            } else {
                onUsbConnected(usbPath, hasMedia)
            }
        }
    }
    
    /**
     * 设置播放器错误状态
     */
    fun setPlayerError(playerIndex: Int, errorMessage: String) {
        log("播放器 $playerIndex 错误: $errorMessage")
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(state = PlayerState.ERROR)
                } else config
            }
        }
    }
    
    /**
     * 扫描本地媒体文件
     */
    fun scanLocalMedia(playerIndex: Int) {
        safeLaunch {
            showToast("扫描本地媒体...", MessageType.INFO)
            
            val context = getApplication<Application>()
            val mediaItems = withContext(Dispatchers.IO) {
                com.btf.rk3568_hdmi_mediaplay.util.LocalMediaScanner.scanExternalStorage(context)
            }
            
            if (mediaItems.isNotEmpty()) {
                setMediaFilesWithDuplicateCheck(playerIndex, mediaItems)
            } else {
                showToast("未找到媒体文件", MessageType.WARNING)
            }
        }
    }
    
    /**
     * 获取当前存储路径
     */
    fun getCurrentStoragePath(): String {
        return localStorageManager.getCurrentStoragePath()
    }
    
    /**
     * 计算U盘内容所需空间
     */
    private fun calculateRequiredSpace(usbPath: File): Long {
        return try {
            val folderName = settings.value.usbScanFolderName
            val mediaDir = File(usbPath, folderName)
            FileUtils.getDirectorySizeMB(mediaDir) * 1024 * 1024
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 应用U盘配置到设置
     */
    private fun applyUsbConfigSettings() {
        val configSettings = FeatureManager.getSettingsOverride() ?: return

        log("应用U盘配置设置...")

        val currentSettings = settings.value
        var newSettings = currentSettings

        // 应用各项设置覆盖
        configSettings.layoutMode?.let { mode ->
            try {
                newSettings = newSettings.copy(layoutMode = LayoutMode.valueOf(mode))
                log("  布局模式: $mode")
            } catch (e: Exception) {
                log("  无效的布局模式: $mode")
            }
        }

        configSettings.language?.let { lang ->
            try {
                val appLang = when (lang.lowercase()) {
                    "zh", "chinese" -> AppLanguage.CHINESE
                    "en", "english" -> AppLanguage.ENGLISH
                    else -> null
                }
                appLang?.let {
                    newSettings = newSettings.copy(language = it)
                    log("  语言: $lang")
                }
            } catch (e: Exception) {
                log("  无效的语言: $lang")
            }
        }

        configSettings.backgroundColor?.let { color ->
            try {
                val colorLong = Color.parseColor(color).toLong() or 0xFF000000
                newSettings = newSettings.copy(backgroundColor = colorLong)
                log("  背景色: $color")
            } catch (e: Exception) {
                log("  无效的背景色: $color")
            }
        }

        configSettings.defaultVolume?.let { volume ->
            newSettings = newSettings.copy(defaultVolume = volume.coerceIn(0, 100))
            log("  默认音量: $volume")
        }

        configSettings.defaultMuted?.let { muted ->
            newSettings = newSettings.copy(defaultMuted = muted)
            log("  默认静音: $muted")
        }

        configSettings.imageIntervalSeconds?.let { interval ->
            newSettings = newSettings.copy(imageIntervalSeconds = interval.coerceIn(1, 60))
            log("  图片间隔: ${interval}秒")
        }

        configSettings.imageTransition?.let { transition ->
            try {
                newSettings = newSettings.copy(imageTransition = ImageTransition.valueOf(transition))
                log("  图片过渡: $transition")
            } catch (e: Exception) {
                log("  无效的图片过渡: $transition")
            }
        }

        configSettings.loopMode?.let { mode ->
            try {
                newSettings = newSettings.copy(loopMode = LoopMode.valueOf(mode))
                log("  循环模式: $mode")
            } catch (e: Exception) {
                log("  无效的循环模式: $mode")
            }
        }

        configSettings.videoScaleMode?.let { mode ->
            try {
                newSettings = newSettings.copy(videoScaleMode = VideoScaleMode.valueOf(mode))
                log("  视频缩放: $mode")
            } catch (e: Exception) {
                log("  无效的视频缩放: $mode")
            }
        }

        configSettings.autoPlayOnStart?.let { auto ->
            newSettings = newSettings.copy(autoPlayOnStart = auto)
            log("  启动自动播放: $auto")
        }

        configSettings.autoPlayAfterCopy?.let { auto ->
            newSettings = newSettings.copy(autoPlayAfterCopy = auto)
            log("  拷贝后自动播放: $auto")
        }

        configSettings.keepScreenOn?.let { keep ->
            newSettings = newSettings.copy(keepScreenOn = keep)
            log("  屏幕常亮: $keep")
        }

        configSettings.usbScanFolderName?.let { folder ->
            newSettings = newSettings.copy(usbScanFolderName = folder)
            log("  U盘扫描目录: $folder")
        }

        configSettings.showOverwriteConfirm?.let { show ->
            newSettings = newSettings.copy(showOverwriteConfirm = show)
            log("  覆盖确认: $show")
        }

        // 如果有变化，更新设置
        if (newSettings != currentSettings) {
            updateSettings(newSettings)
            showToast("已应用U盘配置", MessageType.SUCCESS)
        }
    }
    
    /**
     * 日志输出
     */
    private fun log(message: String) {
        try {
            if (settings.value.enableDebugLog) {
                Log.d(TAG, message)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }
}
