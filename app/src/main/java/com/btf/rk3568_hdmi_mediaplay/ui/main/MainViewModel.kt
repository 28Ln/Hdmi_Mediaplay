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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainViewModel"
    }
    
    // 协程异常处理器
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine exception: ${throwable.message}")
        throwable.printStackTrace()
        showToast("操作失败: ${throwable.message}", MessageType.ERROR)
    }
    
    // 安全的协程作用域
    private val safeScope = viewModelScope + exceptionHandler
    
    private val settingsRepository: SettingsRepository
    private val localStorageManager: LocalStorageManager
    
    init {
        settingsRepository = SettingsRepository(application)
        localStorageManager = LocalStorageManager(application)
        
        // 启动时加载本地内容
        loadLocalContent()
    }
    
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
    
    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
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
     * 显示 Toast 消息
     */
    fun showToast(message: String, type: MessageType = MessageType.INFO) {
        _toastMessage.value = ToastData(message, type)
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
    
    /**
     * 加载本地缓存内容
     */
    fun loadLocalContent() {
        safeScope.launch {
            try {
                log("开始加载本地内容...")
                val allMedia = localStorageManager.getAllLocalMediaFiles()
                
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
                    showToast("已加载本地缓存内容", MessageType.SUCCESS)
                } else {
                    log("本地无缓存内容")
                    showToast("本地无缓存内容，请插入U盘", MessageType.INFO)
                }
            } catch (e: Exception) {
                log("加载本地内容失败: ${e.message}")
                showToast("加载失败: ${e.message}", MessageType.ERROR)
                _errorMessage.value = "加载失败: ${e.message}"
            }
        }
    }
    
    /**
     * U盘连接
     */
    fun onUsbConnected(path: File, hasMediaContent: Boolean) {
        safeScope.launch {
            try {
                log("U盘已连接: ${path.absolutePath}, 有媒体内容: $hasMediaContent")
                _usbState.value = UsbState.Connected(path, hasMediaContent)
                
                if (!hasMediaContent) {
                    val folderName = settings.value.usbScanFolderName
                    showToast("U盘已连接，但未找到 /$folderName 目录", MessageType.WARNING)
                    return@launch
                }
                
                showToast("检测到U盘媒体内容", MessageType.INFO)
                
                // 检查存储空间
                val requiredSpace = calculateRequiredSpace(path)
                val availableSpace = getAvailableSpace()
                
                if (requiredSpace > availableSpace) {
                    val required = FileUtils.formatFileSize(requiredSpace)
                    val available = FileUtils.formatFileSize(availableSpace)
                    showToast("存储空间不足！需要 $required，可用 $available", MessageType.ERROR)
                    _usbState.value = UsbState.Error("存储空间不足")
                    return@launch
                }
                
                // 检查是否需要确认覆盖
                if (settings.value.showOverwriteConfirm && localStorageManager.hasLocalContent()) {
                    pendingUsbPath = path
                    _showOverwriteDialog.value = true
                } else {
                    // 自动覆盖
                    copyFromUsb(path, settings.value.usbScanFolderName)
                }
            } catch (e: Exception) {
                log("处理U盘连接失败: ${e.message}")
                showToast("处理U盘失败: ${e.message}", MessageType.ERROR)
                _usbState.value = UsbState.Error(e.message ?: "未知错误")
            }
        }
    }
    
    /**
     * U盘断开
     */
    fun onUsbDisconnected() {
        log("U盘已断开")
        _usbState.value = UsbState.Disconnected
        showToast("U盘已断开，切换到本地内容", MessageType.INFO)
        // 切换回本地内容
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
        showToast("已取消，继续播放本地内容", MessageType.INFO)
    }
    
    /**
     * 从U盘拷贝内容
     */
    private fun copyFromUsb(usbPath: File, folderName: String) {
        safeScope.launch {
            try {
                log("开始从U盘拷贝: ${usbPath.absolutePath}/$folderName")
                showToast("开始拷贝U盘内容...", MessageType.INFO)
                
                // 显示拷贝进度
                _copyProgress.value = CopyProgress(0, 0f)
                
                val success = localStorageManager.copyAllFromUsb(usbPath, folderName) { playerIndex, progress ->
                    _copyProgress.value = CopyProgress(playerIndex, progress)
                }
                
                if (success) {
                    _copyProgress.value = CopyProgress(3, 1f, isComplete = true)
                    log("拷贝完成")
                    showToast("拷贝完成！", MessageType.SUCCESS)
                } else {
                    _copyProgress.value = CopyProgress(0, 0f, error = "拷贝失败")
                    log("拷贝失败")
                    showToast("拷贝失败，请检查U盘", MessageType.ERROR)
                }
                
                // 延迟后隐藏进度
                delay(1500)
                _copyProgress.value = null
                
                if (success && settings.value.autoPlayAfterCopy) {
                    loadLocalContent()
                }
            } catch (e: Exception) {
                log("拷贝异常: ${e.message}")
                _copyProgress.value = CopyProgress(0, 0f, error = e.message)
                showToast("拷贝出错: ${e.message}", MessageType.ERROR)
                
                delay(2000)
                _copyProgress.value = null
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
                                showToast("播放器 ${index + 1} 无内容", MessageType.WARNING)
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
                    val newMuted = !config.isMuted
                    showToast(
                        "播放器 ${index + 1} ${if (newMuted) "已静音" else "已取消静音"}",
                        MessageType.INFO
                    )
                    config.copy(isMuted = newMuted)
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
                    if (mediaItems.isEmpty()) {
                        showToast("未选择任何文件", MessageType.WARNING)
                    } else {
                        showToast("已为播放器 ${index + 1} 设置 ${mediaItems.size} 个文件", MessageType.SUCCESS)
                    }
                    config.copy(
                        mediaItems = mediaItems,
                        currentIndex = 0,
                        state = if (mediaItems.isNotEmpty()) PlayerState.PLAYING else PlayerState.IDLE
                    )
                } else config
            }
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
            showToast("已播放 $playCount 个播放器", MessageType.SUCCESS)
        } else {
            showToast("没有可播放的内容", MessageType.WARNING)
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
        showToast("已暂停全部播放器", MessageType.INFO)
    }
    
    /**
     * 更新设置
     */
    fun updateSettings(newSettings: AppSettings) {
        safeScope.launch {
            try {
                settingsRepository.updateSettings(newSettings)
                // 不显示保存成功提示，避免频繁弹窗
            } catch (e: Exception) {
                showToast("保存设置失败: ${e.message}", MessageType.ERROR)
            }
        }
    }
    
    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        safeScope.launch {
            try {
                val sizeBefore = localStorageManager.getCacheSizeMB()
                localStorageManager.clearAllCache()
                _playerConfigs.update { configs ->
                    configs.map { it.copy(mediaItems = emptyList(), state = PlayerState.IDLE) }
                }
                showToast("已清除 ${sizeBefore}MB 缓存", MessageType.SUCCESS)
            } catch (e: Exception) {
                showToast("清除缓存失败: ${e.message}", MessageType.ERROR)
            }
        }
    }
    
    /**
     * 手动扫描U盘
     */
    fun scanUsb() {
        safeScope.launch {
            try {
                showToast("正在扫描U盘...", MessageType.INFO)
                
                val context = getApplication<Application>()
                val usbPaths = UsbUtils.getMountedUsbPaths(context)
                
                if (usbPaths.isEmpty()) {
                    showToast("未检测到U盘", MessageType.WARNING)
                    _usbState.value = UsbState.Disconnected
                    return@launch
                }
                
                val usbPath = usbPaths.first()
                val folderName = settings.value.usbScanFolderName
                val hasMedia = UsbUtils.hasValidMediaStructure(usbPath, folderName)
                
                if (!hasMedia) {
                    showToast("U盘中未找到 /$folderName/player1~4 目录", MessageType.WARNING)
                }
                
                onUsbConnected(usbPath, hasMedia)
            } catch (e: Exception) {
                log("扫描U盘失败: ${e.message}")
                showToast("扫描U盘失败: ${e.message}", MessageType.ERROR)
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
        showToast("播放器 ${playerIndex + 1} 出错: $errorMessage", MessageType.ERROR)
    }
    
    /**
     * 计算U盘内容所需空间
     */
    private fun calculateRequiredSpace(usbPath: File): Long {
        val folderName = settings.value.usbScanFolderName
        val mediaDir = File(usbPath, folderName)
        return FileUtils.getDirectorySizeMB(mediaDir) * 1024 * 1024
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
        if (settings.value.enableDebugLog) {
            Log.d(TAG, message)
        }
    }
}
