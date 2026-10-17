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
import com.btf.rk3568_hdmi_mediaplay.remote.PlayerCommandHandler
import com.btf.rk3568_hdmi_mediaplay.remote.PlayerStatusBroadcaster
import com.btf.rk3568_hdmi_mediaplay.remote.PlayerStatusInfo
import com.btf.rk3568_hdmi_mediaplay.ui.components.MessageType
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastData
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application), PlayerCommandHandler.ViewModelCallback {

    internal sealed interface UsbCopyDecision {
        data object NoMediaContent : UsbCopyDecision
        data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : UsbCopyDecision
        data object RequireOverwriteConfirmation : UsbCopyDecision
        data object CopyImmediately : UsbCopyDecision
    }
    
    companion object {
        private const val TAG = "MainViewModel"
        private const val PLAYBACK_ERROR_CODE = 5001

        /** U盘错误态自动重试的首次等待；之后每次翻倍 */
        private const val USB_RETRY_INITIAL_MS = 5_000L
        /** 单次等待上限，避免退避到几乎不重试 */
        private const val USB_RETRY_MAX_MS = 60_000L

        /**
         * 计算第 [attempt] 次重试前应等待的毫秒数（从 0 开始）。
         *
         * 抽取成函数是为了可测：退避策略一旦写错（例如每次都重置回初始值），
         * 表现是「每 5 秒重试一次」，与「指数退避」的意图完全相反，
         * 而这种现象在真机上很难看出来。
         */
        internal fun usbRetryDelayMs(attempt: Int): Long {
            if (attempt <= 0) return USB_RETRY_INITIAL_MS
            // 移位实现 2 的幂；限制最多左移 4 位（×16），再与上限取小
            val multiplier = 1L shl attempt.coerceAtMost(4)
            return (USB_RETRY_INITIAL_MS * multiplier).coerceAtMost(USB_RETRY_MAX_MS)
        }

        internal fun decideUsbCopyDecision(
            hasMediaContent: Boolean,
            requiredBytes: Long,
            availableBytes: Long,
            hasLocalContent: Boolean,
            showOverwriteConfirm: Boolean
        ): UsbCopyDecision {
            if (!hasMediaContent) return UsbCopyDecision.NoMediaContent
            if (requiredBytes > availableBytes) {
                return UsbCopyDecision.InsufficientSpace(requiredBytes, availableBytes)
            }
            if (showOverwriteConfirm && hasLocalContent) {
                return UsbCopyDecision.RequireOverwriteConfirmation
            }
            return UsbCopyDecision.CopyImmediately
        }
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
    private val _usbState = MutableStateFlow<UsbRuntimeState>(UsbRuntimeState.Disconnected)
    val usbState: StateFlow<UsbRuntimeState> = _usbState.asStateFlow()
    
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

    /**
     * 请求重新扫描U盘的通道。
     *
     * 「扫描」能力在 [com.btf.rk3568_hdmi_mediaplay.service.UsbMonitorService] 里，
     * 而错误态（如空间不足）由 ViewModel 判定 —— 两层需要一条回传路径。
     * MainActivity 绑定服务后把 `service::triggerScan` 挂上来。
     */
    var onRequestUsbRescan: (() -> Unit)? = null

    /** 错误态自动重试任务；新状态到来时取消，避免重复排队 */
    private var usbRetryJob: Job? = null

    /**
     * 本轮错误已经重试过多少次。
     *
     * 必须跨 [enterUsbErrorState] 的多次调用保留，否则每次重试都会把退避
     * 重置回最短间隔，指数退避失效（见该函数注释）。
     */
    private var usbRetryAttempt = 0
    
    data class CopyProgress(
        val playerIndex: Int,
        val progress: Float,
        val isComplete: Boolean = false,
        val error: String? = null
    )
    
    init {
        // 注册远程控制回调
        PlayerCommandHandler.setCallback(this)
        
        // 监听设置变化，更新存储管理器
        viewModelScope.launch {
            settings.collect { s ->
                localStorageManager.updateStorageSettings(s.storageLocation, s.customStoragePath)
            }
        }
        
        // 启动时加载本地内容
        loadLocalContent()

        // 发送应用启动广播
        PlayerStatusBroadcaster.sendAppStarted(getApplication())
    }
    
    override fun onCleared() {
        super.onCleared()
        // 清除远程控制回调
        PlayerCommandHandler.setCallback(null)
        // 发送应用停止广播
        PlayerStatusBroadcaster.sendAppStopping(getApplication())
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
    fun onUsbConnected(path: File, hasMediaContent: Boolean, hasConfig: Boolean = FeatureManager.hasUsbConfig()) {
        safeLaunch {
            log("U盘已连接: ${path.absolutePath}, 有媒体内容: $hasMediaContent")
            _usbState.value = UsbRuntimeState.Connected(path, hasMediaContent, hasConfig)
            
            // 检查并应用U盘配置
            applyUsbConfigSettings()

            // 检查存储空间
            val requiredSpace = withContext(Dispatchers.IO) {
                calculateRequiredSpace(path)
            }
            val availableSpace = withContext(Dispatchers.IO) {
                localStorageManager.getAvailableSpaceMB() * 1024 * 1024
            }

            // 检查是否需要确认覆盖
            val hasLocal = withContext(Dispatchers.IO) {
                localStorageManager.hasLocalContent()
            }

            when (
                val decision = decideUsbCopyDecision(
                    hasMediaContent = hasMediaContent,
                    requiredBytes = requiredSpace,
                    availableBytes = availableSpace,
                    hasLocalContent = hasLocal,
                    // USB hot-plug should always remind before overwriting existing local cache.
                    // Even if a USB config tries to disable overwrite confirmation, keep the
                    // prompt so the operator can choose overwrite vs keep-local.
                    showOverwriteConfirm = hasLocal || settings.value.showOverwriteConfirm
                )
            ) {
                UsbCopyDecision.NoMediaContent -> {
                    val folderName = settings.value.usbScanFolderName
                    showToast("U盘已连接，未找到 /$folderName 目录", MessageType.WARNING)
                }

                is UsbCopyDecision.InsufficientSpace -> {
                    val required = FileUtils.formatFileSize(decision.requiredBytes)
                    val available = FileUtils.formatFileSize(decision.availableBytes)
                    showToast("存储空间不足！需要 $required，可用 $available", MessageType.ERROR)
                    enterUsbErrorState("存储空间不足")
                }

                UsbCopyDecision.RequireOverwriteConfirmation -> {
                    showToast(StringResources.usbConnected, MessageType.SUCCESS)
                    pendingUsbPath = path
                    _showOverwriteDialog.value = true
                }

                UsbCopyDecision.CopyImmediately -> {
                    showToast(StringResources.usbConnected, MessageType.SUCCESS)
                    copyFromUsb(path, settings.value.usbScanFolderName)
                }
            }
        }
    }
    
    /**
     * U盘断开
     */
    fun onUsbDisconnected() {
        log("U盘已断开")
        resetUsbRetryBackoff()
        _usbState.value = UsbRuntimeState.Disconnected
        showToast(StringResources.usbDisconnected, MessageType.INFO)
        // 拔盘后重新加载本地缓存：U盘内容不会被清（下次插入还要用），
        // 但当前播放列表必须回到本地，否则界面停在已拔掉的U盘内容上。
        // 这也是「U盘 > 本地」优先级的回落：有U盘时以U盘为准，拔掉即用本地。
        loadLocalContent()
    }

    fun syncUsbScanning(path: File) {
        _usbState.value = UsbRuntimeState.Scanning(path)
    }

    fun syncUsbConnected(path: File, hasMediaContent: Boolean, hasConfig: Boolean) {
        resetUsbRetryBackoff()
        _usbState.value = UsbRuntimeState.Connected(path, hasMediaContent, hasConfig)
    }

    fun syncUsbDisconnected() {
        resetUsbRetryBackoff()
        _usbState.value = UsbRuntimeState.Disconnected
    }

    fun syncUsbState(state: UsbRuntimeState) {
        // 收到非错误态说明外部已恢复，取消重试并清零退避
        if (state !is UsbRuntimeState.Error) {
            resetUsbRetryBackoff()
        }
        _usbState.value = state
    }

    /**
     * 进入错误态并安排自动重试。
     *
     * 此前错误态没有任何出口 —— 一旦「存储空间不足」就会永久停住，
     * 即使操作者当场清出了空间，应用也不会自己恢复。
     *
     * **退避状态必须跨重试保留**：重试会触发 rescan → 若仍失败会再次进入本函数。
     * 若每次进来都把 delay 重置成初始值，就变成「永远 5 秒一次」，
     * 指数退避完全失效（这是本函数第一版的缺陷）。
     * 因此用 [usbRetryAttempt] 记录本轮错误已经重试过多少次，
     * 仅在真正恢复（[resetUsbRetryBackoff]）时清零。
     *
     * 达到上限后**不放弃**，而是退化为固定的最长间隔继续重试：
     * 空间不足这类问题要等人工处理后才能恢复，一旦清理完成，
     * 下一轮重试就能自愈 —— 停止重试等于让设备永久卡死。
     */
    private fun enterUsbErrorState(message: String) {
        _usbState.value = UsbRuntimeState.Error(message)

        val delayMs = usbRetryDelayMs(usbRetryAttempt)

        usbRetryJob?.cancel()
        usbRetryJob = viewModelScope.launch {
            delay(delayMs)
            val rescan = onRequestUsbRescan
            if (rescan == null) {
                log("未绑定重扫通道，停止自动重试")
                return@launch
            }
            usbRetryAttempt++
            log("U盘错误态自动重试第 $usbRetryAttempt 次（等待 ${delayMs}ms，原因：$message）")
            rescan()
        }
    }

    /** 恢复到正常状态时清空退避计数，让下一次故障重新从最短间隔开始 */
    private fun resetUsbRetryBackoff() {
        usbRetryAttempt = 0
        usbRetryJob?.cancel()
        usbRetryJob = null
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
                _usbState.value = UsbRuntimeState.Connected(usbPath, true, FeatureManager.hasUsbConfig())
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
    fun setVolume(playerIndex: Int, volume: Float): Boolean {
        // 索引不在当前布局中时是空操作，返回 false 让远控如实回报
        if (!isPlayerInCurrentLayout(playerIndex)) return false
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(volume = volume.coerceIn(0f, 1f))
                } else config
            }
        }
        return true
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
        PlayerStatusBroadcaster.sendError(
            getApplication(),
            PLAYBACK_ERROR_CODE,
            "player=$playerIndex,error=$errorMessage"
        )
    }

    /**
     * 清除播放器的错误状态（播放恢复正常时由播放器组件回调）。
     *
     * ERROR 状态没有其他出口：`togglePlayPause` 对 ERROR 原样保留，
     * `updatePlayerCurrentIndex` 只改索引。若不提供这个恢复入口，
     * 一次瞬时错误会让该格永久卡在报错，即使播放器已经能正常播放。
     */
    fun clearPlayerError(playerIndex: Int) {
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex && config.state == PlayerState.ERROR) {
                    log("播放器 $playerIndex 恢复正常")
                    config.copy(
                        state = if (config.mediaItems.isNotEmpty()) {
                            PlayerState.PLAYING
                        } else {
                            PlayerState.IDLE
                        }
                    )
                } else config
            }
        }
    }

    fun updatePlayerCurrentIndex(playerIndex: Int, currentIndex: Int) {
        _playerConfigs.update { configs ->
            // 「同画面」模式下 P2–P4 是 P1 的镜像，播的必须是**同一个文件**。
            // 若只更新 P1，其余格的 currentIndex 会停住，而各格又按自己的播放完成
            // 时刻推进索引 —— 时间一长四格就停在不同视频上。
            // 因此这里把 P1 的索引广播给所有格子。
            val mirrorAll = settings.value.layoutMode.mirrorsPrimaryPlayer
            configs.mapIndexed { index, config ->
                val shouldUpdate = if (mirrorAll) true else index == playerIndex
                if (shouldUpdate && config.mediaItems.isNotEmpty()) {
                    val safeIndex = currentIndex.coerceIn(0, config.mediaItems.lastIndex)
                    config.copy(currentIndex = safeIndex)
                } else config
            }
        }
    }

    fun onPlayerPlaybackCompleted(playerIndex: Int, mediaPath: String?, nextIndex: Int?) {
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index != playerIndex) {
                    config
                } else if (nextIndex != null && config.mediaItems.isNotEmpty()) {
                    val safeIndex = nextIndex.coerceIn(0, config.mediaItems.lastIndex)
                    config.copy(currentIndex = safeIndex, state = PlayerState.PLAYING)
                } else {
                    config.copy(state = PlayerState.IDLE)
                }
            }
        }
        PlayerStatusBroadcaster.sendPlaybackCompleted(getApplication(), playerIndex, mediaPath)
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

        configSettings.audioFallbackSilent?.let { fallback ->
            newSettings = newSettings.copy(audioFallbackSilent = fallback)
            log("  音频不支持时静音播放: $fallback")
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
    
    // ==================== 远程控制接口实现 ====================
    
    override fun playAll(): Boolean {
        var playCount = 0
        _playerConfigs.update { configs ->
            configs.map { config ->
                if (config.mediaItems.isNotEmpty()) {
                    playCount++
                    config.copy(state = PlayerState.PLAYING)
                } else config
            }
        }
        log("远程控制: 播放全部 ($playCount 个)")
        if (playCount > 0) {
            showToast("播放 $playCount 个", MessageType.SUCCESS)
        } else {
            showToast("无可播放内容", MessageType.WARNING)
        }
        // 一路可播内容都没有时返回 false，让 handler 回报错误而不是虚假成功
        return playCount > 0
    }

    override fun pauseAll(): Boolean {
        var pausedCount = 0
        _playerConfigs.update { configs ->
            configs.map { config ->
                if (config.state == PlayerState.PLAYING) {
                    pausedCount++
                    config.copy(state = PlayerState.PAUSED)
                } else config
            }
        }
        log("远程控制: 暂停全部 ($pausedCount 个)")
        if (pausedCount == 0) {
            // 当前没有正在播放的路 → 命令无实际效果
            return false
        }
        showToast("已暂停", MessageType.INFO)
        return true
    }

    override fun stopAll(): Boolean {
        if (settings.value.layoutMode.visiblePlayerCount <= 0) return false
        _playerConfigs.update { configs ->
            configs.map { config ->
                config.copy(state = PlayerState.IDLE, currentIndex = 0)
            }
        }
        log("远程控制: 停止全部")
        return true
    }
    
    /**
     * 播放指定路。
     * @return 是否真正生效。无媒体的路无法播放，返回 false 让调用方回报错误，
     *         而不是广播一个虚假的 playing 状态。
     */
    override fun play(playerIndex: Int): Boolean {
        if (!isPlayerInCurrentLayout(playerIndex)) {
            log("远程控制: 播放播放器 $playerIndex 失败（该路不在当前布局中）")
            return false
        }
        val target = _playerConfigs.value.getOrNull(playerIndex)
        if (target == null || target.mediaItems.isEmpty()) {
            log("远程控制: 播放播放器 $playerIndex 失败（无媒体或索引无效）")
            return false
        }
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) config.copy(state = PlayerState.PLAYING) else config
            }
        }
        log("远程控制: 播放播放器 $playerIndex")
        return true
    }

    override fun pause(playerIndex: Int): Boolean {
        if (!isPlayerInCurrentLayout(playerIndex)) {
            log("远程控制: 暂停播放器 $playerIndex 失败（该路不在当前布局中）")
            return false
        }
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) config.copy(state = PlayerState.PAUSED) else config
            }
        }
        log("远程控制: 暂停播放器 $playerIndex")
        return true
    }

    override fun stop(playerIndex: Int): Boolean {
        if (!isPlayerInCurrentLayout(playerIndex)) {
            log("远程控制: 停止播放器 $playerIndex 失败（该路不在当前布局中）")
            return false
        }
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(state = PlayerState.IDLE, currentIndex = 0)
                } else config
            }
        }
        log("远程控制: 停止播放器 $playerIndex")
        return true
    }

    /**
     * 该路是否在当前布局中可见。
     *
     * `_playerConfigs` 恒为 4 项且不随布局收缩（隐藏的格子仍需保留 ExoPlayer
     * 实例与播放进度，见 QuadPlayerLayout 的说明），因此**不能**用
     * `configs.getOrNull(index) != null` 判断索引是否有效——
     * 单路布局下对 P2/P3/P4 发 `play` 会在只检查「有无媒体」时被误判为成功，
     * 并向信发系统广播一个并不存在的 `playing` 状态。
     */
    private fun isPlayerInCurrentLayout(playerIndex: Int): Boolean {
        if (playerIndex < 0) return false
        return playerIndex < settings.value.layoutMode.visiblePlayerCount
    }

    override fun setLayout(layoutMode: LayoutMode) {
        safeLaunch {
            val newSettings = settings.value.copy(layoutMode = layoutMode)
            updateSettings(newSettings)
            log("远程控制: 设置布局 $layoutMode")
        }
    }
    
    override fun setMedia(playerIndex: Int, mediaItems: List<MediaItem>) {
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
        log("远程控制: 设置播放器 $playerIndex 媒体 (${mediaItems.size} 个文件)")
    }
    
    override fun clearMedia(playerIndex: Int) {
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(mediaItems = emptyList(), state = PlayerState.IDLE, currentIndex = 0)
                } else config
            }
        }
        log("远程控制: 清空播放器 $playerIndex")
    }
    
    override fun clearAllMedia() {
        _playerConfigs.update { configs ->
            configs.map { config ->
                config.copy(mediaItems = emptyList(), state = PlayerState.IDLE, currentIndex = 0)
            }
        }
        log("远程控制: 清空所有播放器")
    }
    
    override fun setInterval(seconds: Int) {
        safeLaunch {
            val newSettings = settings.value.copy(imageIntervalSeconds = seconds.coerceIn(1, 60))
            updateSettings(newSettings)
            log("远程控制: 设置轮播间隔 ${seconds}秒")
        }
    }
    
    override fun setVolume(playerIndex: Int, volume: Int): Boolean {
        if (!isPlayerInCurrentLayout(playerIndex)) {
            log("远程控制: 设置播放器 $playerIndex 音量失败（该路不在当前布局中）")
            return false
        }
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(volume = volume / 100f)
                } else config
            }
        }
        log("远程控制: 设置播放器 $playerIndex 音量 $volume")
        return true
    }

    override fun setMute(playerIndex: Int, mute: Boolean): Boolean {
        if (!isPlayerInCurrentLayout(playerIndex)) {
            log("远程控制: 设置播放器 $playerIndex 静音失败（该路不在当前布局中）")
            return false
        }
        _playerConfigs.update { configs ->
            configs.mapIndexed { index, config ->
                if (index == playerIndex) {
                    config.copy(isMuted = mute)
                } else config
            }
        }
        log("远程控制: 设置播放器 $playerIndex 静音 $mute")
        return true
    }
    
    override fun setLoopMode(loopMode: LoopMode) {
        safeLaunch {
            val newSettings = settings.value.copy(loopMode = loopMode)
            updateSettings(newSettings)
            log("远程控制: 设置循环模式 $loopMode")
        }
    }
    
    override fun getStatus(): PlayerCommandHandler.StatusData {
        val currentSettings = settings.value
        val configs = _playerConfigs.value
        
        val players = configs.map { config ->
            val currentMedia = config.mediaItems.getOrNull(config.currentIndex) ?: config.mediaItems.firstOrNull()
            PlayerStatusInfo(
                index = config.index,
                state = when (config.state) {
                    PlayerState.PLAYING -> "playing"
                    PlayerState.PAUSED -> "paused"
                    PlayerState.IDLE -> "idle"
                    PlayerState.LOADING -> "loading"
                    PlayerState.ERROR -> "error"
                },
                mediaType = currentMedia?.type?.name?.lowercase(),
                mediaPath = currentMedia?.path,
                mediaCount = config.mediaItems.size,
                currentIndex = config.currentIndex,
                volume = (config.volume * 100).toInt(),
                muted = config.isMuted
            )
        }
        
        return PlayerCommandHandler.StatusData(
            layoutMode = currentSettings.layoutMode.name,
            intervalSeconds = currentSettings.imageIntervalSeconds,
            loopMode = currentSettings.loopMode.name,
            players = players
        )
    }
}
