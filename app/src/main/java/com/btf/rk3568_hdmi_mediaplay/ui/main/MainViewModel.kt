package com.btf.rk3568_hdmi_mediaplay.ui.main

import android.app.Application
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.ui.components.MessageType
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastData
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
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
        // 启动时加载本地内容
        loadLocalContent()
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
            
            val allMedia = withContext(Dispatchers.IO) {
                localStorageManager.getAllLocalMediaFiles()
            }
            
            var hasContent = false
            _playerConfigs.update { configs ->
                configs.mapIndexed { index, config ->
                    val mediaItems = allMedia[index] ?: emptyList()
                    if (mediaItems.isNotEmpty()) hasContent = true
                    config.copy(
                        mediaItems = mediaItems,
                        state = if (mediaItems.isNotEmpty() && settings.value.autoPlayOnStart) 
                            PlayerState.PLAYING else PlayerState.IDLE
                    )
                }
            }
            
            if (hasContent) {
                log("本地内容加载完成")
                showToast("已加载本地缓存", MessageType.SUCCESS)
            } else {
                log("本地无缓存内容")
                // 首次启动不显示提示，避免干扰
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
            
            if (!hasMediaContent) {
                val folderName = settings.value.usbScanFolderName
                showToast("U盘已连接，未找到 /$folderName 目录", MessageType.WARNING)
                return@safeLaunch
            }
            
            showToast("检测到U盘媒体内容", MessageType.INFO)
            
            // 检查存储空间
            val requiredSpace = withContext(Dispatchers.IO) {
                calculateRequiredSpace(path)
            }
            val availableSpace = getAvailableSpace()
            
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
        showToast("U盘已断开", MessageType.INFO)
        loadLocalContent()
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
            showToast("开始拷贝...", MessageType.INFO)
            
            _copyProgress.value = CopyProgress(0, 0f)
            
            val success = withContext(Dispatchers.IO) {
                localStorageManager.copyAllFromUsb(usbPath, folderName) { playerIndex, progress ->
                    _copyProgress.value = CopyProgress(playerIndex, progress)
                }
            }
            
            if (success) {
                _copyProgress.value = CopyProgress(3, 1f, isComplete = true)
                log("拷贝完成")
                showToast("拷贝完成！", MessageType.SUCCESS)
            } else {
                _copyProgress.value = CopyProgress(0, 0f, error = "拷贝失败")
                log("拷贝失败")
                showToast("拷贝失败", MessageType.ERROR)
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
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
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
            
            if (!hasMedia) {
                showToast("未找到 /$folderName/player1~4", MessageType.WARNING)
            }
            
            onUsbConnected(usbPath, hasMedia)
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
     * 获取可用存储空间
     */
    private fun getAvailableSpace(): Long {
        return try {
            val context = getApplication<Application>()
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Long.MAX_VALUE
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
