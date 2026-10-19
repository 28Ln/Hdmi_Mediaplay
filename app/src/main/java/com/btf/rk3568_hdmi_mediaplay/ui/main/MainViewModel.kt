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
import com.btf.rk3568_hdmi_mediaplay.data.repository.UsbConfigOverrideStore
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixController
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixModeMapping
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixResult
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixState
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiResolution
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
import java.util.Locale

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

    // ==================== HDMI 拼接板（I2C） ====================

    /**
     * 拼接板控制器。
     *
     * 进程内单例：UI（设置页状态卡）与 ViewModel 必须共用**同一把写锁**，
     * 否则「mode 先写、mirror 后写」的顺序在两边并发时会被打破。
     */
    internal val hdmiMatrix: HdmiMatrixController = HdmiMatrixController.getInstance()

    /** 只读状态；Compose 直接 collect，重组时不发起任何 sysfs 读。 */
    val hdmiState: StateFlow<HdmiMatrixState> = hdmiMatrix.state

    /**
     * 当前正在执行的硬件布局任务。
     *
     * 布局切换可能连续发生（用户快速点模式 / 远控连续下发），
     * 必须取消被取代的任务，否则旧布局会晚到并覆盖新布局。
     * 控制器内部还持锁串行化，所以取消不会留下半写的中间态。
     */
    private var hardwareLayoutJob: Job? = null

    /** 最近一次已提交给硬件的布局，避免重复下发同一布局。 */
    @Volatile
    private var lastAppliedHardwareLayout: LayoutMode? = null

    /**
     * 是否处于「U 盘错误态（空间不足）」。
     *
     * **不能只看 `_usbState.value is Error`**：服务的轮询/重试会先 emit
     * `Scanning` / `Connected`，把状态覆盖掉，而空间决策（要读磁盘、统计目录大小）
     * 往往几秒后才把状态重新置回 Error。若用状态值判断，退避计数会在每次重试后
     * 被清零 → **指数退避永远退化为固定 5 秒**（2026-10-19 实测缺陷）。
     *
     * 这个标志只由「明确的恢复信号」清除：
     * - `onUsbConnected` 的决策不再是 InsufficientSpace；
     * - 拔盘（`syncUsbDisconnected` / 服务报 Disconnected）。
     */
    private var usbErrorActive: Boolean = false

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

        // 启动时如果**没有** U 盘配置，说明上次可能是「带着 U 盘配置关机、
        // 再空机开机」—— 状态里仍是 U 盘下发的那套值，需要回滚。
        // 有 U 盘时由 onUsbConnected → applyUsbConfigSettings 重新下发，无需回滚。
        if (!FeatureManager.hasUsbConfig()) {
            revertUsbConfigOverrides()
        }

        // 发送应用启动广播
        PlayerStatusBroadcaster.sendAppStarted(getApplication())

        // 启动时恢复拼接墙硬件模式（I2C）。必须异步：写 MCU 可能耗时数秒。
        restoreHardwareLayoutOnStartup()
    }

    override fun onCleared() {
        super.onCleared()
        // 清除远程控制回调
        PlayerCommandHandler.setCallback(null)
        // 发送应用停止广播
        PlayerStatusBroadcaster.sendAppStopping(getApplication())
        hardwareLayoutJob?.cancel()
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
                    // 清掉可能残留的覆盖确认框：上一次插入留下的 pending 状态
                    // 若不清理，新盘插入时仍会弹出一个指向旧路径的对话框。
                    dismissPendingOverwrite()
                    // 走到这里说明「盘在、但目录结构不对」，不是错误态；
                    // 顺带把上一个错误态的退避清零。
                    usbErrorActive = false
                    resetUsbRetryBackoff()
                    val folderName = settings.value.usbScanFolderName
                    showToast("U盘已连接，未找到 /$folderName 目录", MessageType.WARNING)
                }

                is UsbCopyDecision.InsufficientSpace -> {
                    // 同上。实测（2026-10-19 API30 模拟器）：先出现
                    // 「存储空间不足」红色提示，同时**旧对话框仍盖在画面上** ——
                    // 因为 showOverwriteDialog 是独立 StateFlow，决策改为
                    // InsufficientSpace 时没有任何代码把它复位。
                    dismissPendingOverwrite()
                    val required = FileUtils.formatFileSize(decision.requiredBytes)
                    val available = FileUtils.formatFileSize(decision.availableBytes)
                    showToast("存储空间不足！需要 $required，可用 $available", MessageType.ERROR)
                    enterUsbErrorState("存储空间不足")
                }

                UsbCopyDecision.RequireOverwriteConfirmation -> {
                    // 空间已够、可以继续操作：这是「错误已解除」的可靠信号
                    usbErrorActive = false
                    resetUsbRetryBackoff()
                    showToast(StringResources.usbConnected, MessageType.SUCCESS)
                    pendingUsbPath = path
                    _showOverwriteDialog.value = true
                }

                UsbCopyDecision.CopyImmediately -> {
                    usbErrorActive = false
                    resetUsbRetryBackoff()
                    dismissPendingOverwrite()
                    showToast(StringResources.usbConnected, MessageType.SUCCESS)
                    copyFromUsb(path, settings.value.usbScanFolderName)
                }
            }
        }
    }

    /**
     * 关闭覆盖确认框并丢弃待处理的 U 盘路径。
     *
     * 只在「本次决策不是 RequireOverwriteConfirmation」时调用 —— 否则会把
     * 刚弹出的确认框立刻关掉。
     */
    private fun dismissPendingOverwrite() {
        if (_showOverwriteDialog.value || pendingUsbPath != null) {
            _showOverwriteDialog.value = false
            pendingUsbPath = null
        }
    }
    
    /**
     * U盘断开
     */
    fun onUsbDisconnected() {
        log("U盘已断开")
        resetUsbRetryBackoff()
        usbErrorActive = false
        // U 盘拔掉后，把本次由它下发的设置回滚（运维手动改过的键不动）
        revertUsbConfigOverrides()
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
        // 错误态下 Service 的 Connected 只表示「盘还在」，不代表空间问题已解决。
        if (!usbErrorActive) resetUsbRetryBackoff()
        _usbState.value = UsbRuntimeState.Connected(path, hasMediaContent, hasConfig)
    }

    fun syncUsbDisconnected() {
        resetUsbRetryBackoff()
        _usbState.value = UsbRuntimeState.Disconnected
    }

    fun syncUsbState(state: UsbRuntimeState) {
        when {
            // 拔盘是明确的"错误已不适用"信号
            state is UsbRuntimeState.Disconnected -> {
                usbErrorActive = false
                resetUsbRetryBackoff()
            }
            // 错误态期间的 Scanning/Connected 由服务自动发出（重试的副产物），
            // 不能据此清零退避 —— 见 enterUsbErrorState 的注释。
            !usbErrorActive -> resetUsbRetryBackoff()
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
        usbErrorActive = true
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

            // ## 这里**不能**再根据 _usbState 判断"是否已恢复"并清零计数
            //    （2026-10-19 实测缺陷）
            //
            // 旧实现在 rescan 后 delay(3s)，若状态不是 Error 就把 attempt 清零。
            // 但 rescan 是异步的：服务先 emit `Connected`（把状态从 Error 覆盖掉），
            // 之后才回调 onUsbConnected 做空间决策 —— 而后者要读磁盘、算目录大小，
            // 往往**超过 3 秒**才把状态重新置回 Error。
            // 结果 3 秒检查总是看到 Connected → 每次清零 → 退避永远是 5 秒。
            //
            // 实测证据：API30 模拟器上 9GB 稀疏文件触发空间不足后，
            // `Manual scan triggered` 间隔恒为 ~5.3s（5s 退避 + 开销），从不增长。
            //
            // 现在计数只由**明确的恢复信号**清零：
            // - `onUsbConnected` 的决策不再是 InsufficientSpace（空间已够）；
            // - 拔盘；
            // - 连接状态被外部重置。
            // 这些都比"3 秒后看一眼状态"更精确。
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
        // 基线 = 调用方生成 newSettings 时看到的状态。
        // 传给 Repository 后，落盘时只应用「基线→目标」的增量，
        // 从而不会覆盖同时发生的另一条写入（U 盘下发 vs 用户操作）。
        val baseline = settings.value
        val previousLayout = baseline.layoutMode
        val previousLanguage = baseline.language
        safeLaunch {
            // 更新存储管理器设置
            localStorageManager.updateStorageSettings(newSettings.storageLocation, newSettings.customStoragePath)

            withContext(Dispatchers.IO) {
                settingsRepository.updateSettings(newSettings, previous = baseline)
            }
        }
        // StringResources 是全局单例：设置页切换语言时会自行更新，
        // 但 U 盘配置下发的 language 不经过设置页，需要在这里同步，
        // 否则后续错误提示会用旧语言。
        if (newSettings.language != previousLanguage) {
            StringResources.setLanguage(newSettings.language)
        }
        // 布局变化才下发硬件模式（其他设置与拼接板无关）。
        // 放在 safeLaunch 之外：硬件下发失败不应让设置保存显示为"操作失败"。
        if (newSettings.layoutMode != previousLayout) {
            applyLayoutToHardware(newSettings.layoutMode)
        }
    }

    // ==================== HDMI 拼接板：布局下发 ====================

    /**
     * 把布局映射成硬件模式并下发（设置页 / U 盘配置 / 远控 set_layout 的共同入口）。
     *
     * **绝不能在主线程执行**：一次写可能阻塞几百毫秒到秒级。
     *
     * 行为约定：
     * - 软件布局（SINGLE / 竖屏 / 同画面 / PIP）没有硬件模式 → 零写入，
     *   UI 保留软件布局，**不弹错误**（这不是故障）；
     * - 同一布局重复设置 → 跳过（幂等，避免无谓写 MCU）；
     * - 失败按 [HdmiMatrixErrorKind] 显示文案，并保留最后一次读回的诊断信息。
     */
    fun applyLayoutToHardware(layoutMode: LayoutMode, showErrors: Boolean = true) {
        val hardwareMode = HdmiMatrixModeMapping.hardwareModeFor(layoutMode)
        if (hardwareMode == null) {
            log("布局 $layoutMode 为纯软件布局，跳过硬件模式下发")
            lastAppliedHardwareLayout = layoutMode
            return
        }
        if (lastAppliedHardwareLayout == layoutMode) {
            log("布局 $layoutMode 的硬件模式未变化，跳过重复下发")
            return
        }

        // 取消被取代的下发，保证最终生效的是最新布局
        hardwareLayoutJob?.cancel()
        hardwareLayoutJob = viewModelScope.launch(Dispatchers.IO) {
            log("下发硬件布局: $layoutMode -> mode=$hardwareMode")
            when (val result = hdmiMatrix.applyLayout(layoutMode)) {
                is HdmiMatrixResult.Success -> {
                    lastAppliedHardwareLayout = layoutMode
                    log("硬件布局下发成功: mode=$hardwareMode")
                }

                is HdmiMatrixResult.Failure -> {
                    val error = result.error
                    // NoHardwareMode 在上面已经短路，这里只可能是真实错误
                    Log.w(TAG, "硬件布局下发失败: kind=${error.kind}, errno=${error.errno}, node=${error.node}")
                    if (showErrors) {
                        withContext(Dispatchers.Main) {
                            showToast(
                                StringResources.hdmiMatrixErrorDetail(error),
                                MessageType.ERROR
                            )
                        }
                    }
                    // 失败不写 lastApplied：下次进入设置/重试时会再尝试
                }
            }
        }
    }

    /**
     * 底部栏「切换模式」：轮换到下一个**客户可见**的拼接墙布局。
     *
     * 旧实现是脉冲 GPIO 按键，让硬件自己 +1；新固件没有按键节点，
     * 改为在软件侧推进布局并走 [applyLayoutToHardware]。
     * 这样也避免了「硬件 +1 走到 App 不展示的 1X4/4X1」的问题。
     */
    fun cycleWallLayout() {
        val modes = LayoutMode.wallModes
        if (modes.isEmpty()) return
        val current = settings.value.layoutMode
        val index = modes.indexOf(current)
        val next = if (index < 0) modes.first() else modes[(index + 1) % modes.size]
        log("底部栏切换模式: $current -> $next")
        updateSettings(settings.value.copy(layoutMode = next))
    }

    /**
     * 底部栏「切换分辨率」：按 720p → 1080p → 2160p30 → 720p 轮换。
     *
     * 分辨率不是持久化设置（MCU 自己保存），因此直接下发并重读状态。
     */
    fun cycleResolution() {
        viewModelScope.launch(Dispatchers.IO) {
            val applied = hdmiMatrix.readApplied()
            val current = (applied as? HdmiMatrixResult.Success)?.value?.resolution
            val next = when (current) {
                HdmiResolution.P720 -> HdmiResolution.P1080
                HdmiResolution.P1080 -> HdmiResolution.P2160_30
                HdmiResolution.P2160_30 -> HdmiResolution.P720
                null -> HdmiResolution.P1080
            }
            when (val result = hdmiMatrix.setResolution(next)) {
                is HdmiMatrixResult.Success -> {
                    log("分辨率切换为 ${next.name}")
                    withContext(Dispatchers.Main) {
                        showToast(
                            StringResources.hdmiMatrixResolution + ": " + resolutionLabel(next),
                            MessageType.SUCCESS
                        )
                    }
                }

                is HdmiMatrixResult.Failure -> withContext(Dispatchers.Main) {
                    showToast(StringResources.hdmiMatrixErrorDetail(result.error), MessageType.ERROR)
                }
            }
        }
    }

    private fun resolutionLabel(resolution: HdmiResolution): String = when (resolution) {
        HdmiResolution.P720 -> "720p"
        HdmiResolution.P1080 -> "1080p"
        HdmiResolution.P2160_30 -> "2160p30"
    }

    // ==================== HDMI 拼接板：设置页操作 ====================

    /** 刷新只读状态面板（设置页进入/点刷新）。 */
    fun refreshHdmiMatrix() {
        viewModelScope.launch {
            hdmiMatrix.probe()
            hdmiMatrix.readApplied()
        }
    }

    /** 电源开关；关机会让后续写入返回 EPERM，UI 需据此提示。 */
    fun setHdmiPower(on: Boolean) {
        viewModelScope.launch {
            handleHdmiWriteResult(hdmiMatrix.setPower(on), "power=$on")
        }
    }

    fun setHdmiResolution(resolution: HdmiResolution) {
        viewModelScope.launch {
            handleHdmiWriteResult(hdmiMatrix.setResolution(resolution), "resolution=${resolution.name}")
        }
    }

    fun setHdmiMute(mute: Boolean) {
        viewModelScope.launch {
            handleHdmiWriteResult(hdmiMatrix.setMute(mute), "mute=$mute")
        }
    }

    fun setHdmiRotate(quarterTurns: Int) {
        viewModelScope.launch {
            handleHdmiWriteResult(hdmiMatrix.setRotate(quarterTurns), "rotate=$quarterTurns")
        }
    }

    /**
     * 滑杆拖动帧：只更新内存预览，**零 IO**。
     * 返回 false 表示值越界，UI 可据此不回填滑块。
     */
    fun previewHdmiFusion(horizontal: Int, vertical: Int): Boolean =
        hdmiMatrix.stageFusion(horizontal, vertical).isSuccess

    /** 滑杆松手：提交一次（内部含 300ms flash 防抖）。 */
    fun commitHdmiFusion() {
        viewModelScope.launch {
            handleHdmiWriteResult(hdmiMatrix.commitFusion(), "fusion")
        }
    }

    private suspend fun handleHdmiWriteResult(result: HdmiMatrixResult<Unit>, what: String) {
        when (result) {
            is HdmiMatrixResult.Success -> {
                log("HDMI 写入成功: $what")
                // 写成功后再读一次状态，让卡片显示 MCU 的真实反馈
                hdmiMatrix.probe()
                hdmiMatrix.readApplied()
            }

            is HdmiMatrixResult.Failure -> {
                Log.w(TAG, "HDMI 写入失败: $what, kind=${result.error.kind}")
                withContext(Dispatchers.Main) {
                    showToast(
                        StringResources.hdmiMatrixErrorDetail(result.error),
                        MessageType.ERROR
                    )
                }
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
                val appLang = when (lang.lowercase(Locale.ROOT)) {
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

        configSettings.bootAutoStart?.let { boot ->
            newSettings = newSettings.copy(bootAutoStart = boot)
            log("  开机自启: $boot")
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

        // 如果有变化，更新设置，并记录「哪些键被 U 盘改过、原值是什么」。
        //
        // 没有这份快照，拔盘后这些值会**永久留驻**（客户在 app 内看不到来源，
        // 也无法区分是自己设的还是 U 盘下发的），只能整机重置设置才能回到出厂状态。
        if (newSettings != currentSettings) {
            val entries = UsbConfigOverrideStore.SUPPORTED_KEYS.mapNotNull { key ->
                val before = UsbConfigOverrideStore.readKey(currentSettings, key)
                val after = UsbConfigOverrideStore.readKey(newSettings, key)
                if (before != null && after != null && before != after) {
                    UsbConfigOverrideStore.Entry(key, original = before, applied = after)
                } else null
            }
            safeLaunch {
                UsbConfigOverrideStore.saveSnapshot(settingsRepository.preferenceStore, entries)
            }
            updateSettings(newSettings)
            showToast("已应用U盘配置", MessageType.SUCCESS)
        }
    }

    /**
     * 拔盘/启动时无 U 盘：回滚上一次 U 盘下发的设置。
     *
     * 只回滚「当前值仍等于 U 盘写入值」的键 —— 运维手动改过的不动。
     */
    private fun revertUsbConfigOverrides() {
        safeLaunch {
            val entries = UsbConfigOverrideStore.loadSnapshot(settingsRepository.preferenceStore)
            if (entries.isEmpty()) return@safeLaunch
            val (reverted, keys) = UsbConfigOverrideStore.revert(settings.value, entries)
            if (keys.isNotEmpty()) {
                log("U盘配置回滚 ${keys.size} 项: $keys")
                updateSettings(reverted)
            }
            UsbConfigOverrideStore.saveSnapshot(settingsRepository.preferenceStore, emptyList())
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

    /**
     * 启动时等待拼接板就绪，并把当前布局下发一次。
     *
     * 不用 `settings.value`：init 阶段 DataStore 往往还没读出持久化值，
     * 那时拿到的是默认布局，会把用户设置覆盖掉。改为等第一次真实设置到达后
     * 由 [onSettingsLoadedForHardware] 触发。
     */
    private fun restoreHardwareLayoutOnStartup() {
        viewModelScope.launch {
            // settings 是 Eagerly 的 StateFlow：第一个值恒为默认值，
            // 第二个值才是 DataStore 读出的真实值。drop(1) 等它。
            val loaded = settings.drop(1).first()
            val online = hdmiMatrix.ensureOnline(timeoutMs = 20_000L)
            if (online is HdmiMatrixResult.Failure) {
                Log.i(TAG, "拼接板未就绪，跳过启动下发: kind=${online.error.kind}")
                return@launch
            }
            applyLayoutToHardware(loaded.layoutMode, showErrors = false)
        }
    }

    /**
     * 设置页布局选择：先保存设置，再下发硬件。
     *
     * 顺序不能反：保存是本地 DataStore（快），硬件写可能秒级。
     * 先保存保证即使硬件失败，重启后 UI 仍是用户选择的布局。
     */
    fun selectLayoutMode(layoutMode: LayoutMode) {
        if (settings.value.layoutMode == layoutMode) return
        updateSettings(settings.value.copy(layoutMode = layoutMode))
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
                mediaType = currentMedia?.type?.name?.lowercase(Locale.ROOT),
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
