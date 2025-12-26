package com.btf.rk3568_hdmi_mediaplay.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    private val localStorageManager = LocalStorageManager(application)
    
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
    
    // 待处理的U盘路径
    private var pendingUsbPath: File? = null
    
    sealed class UsbState {
        object Disconnected : UsbState()
        data class Connected(val path: File, val hasMediaContent: Boolean) : UsbState()
    }
    
    data class CopyProgress(
        val playerIndex: Int,
        val progress: Float,
        val isComplete: Boolean = false
    )
    
    init {
        // 启动时加载本地内容
        loadLocalContent()
    }
    
    /**
     * 加载本地缓存内容
     */
    fun loadLocalContent() {
        viewModelScope.launch {
            val allMedia = localStorageManager.getAllLocalMediaFiles()
            
            _playerConfigs.update { configs ->
                configs.mapIndexed { index, config ->
                    val mediaItems = allMedia[index] ?: emptyList()
                    config.copy(
                        mediaItems = mediaItems,
                        state = if (mediaItems.isNotEmpty() && settings.value.autoPlayOnStart) 
                            PlayerState.PLAYING else PlayerState.IDLE
                    )
                }
            }
        }
    }
    
    /**
     * U盘连接
     */
    fun onUsbConnected(path: File, hasMediaContent: Boolean) {
        _usbState.value = UsbState.Connected(path, hasMediaContent)
        
        if (hasMediaContent) {
            val folderName = settings.value.usbScanFolderName
            
            // 检查是否需要确认覆盖
            if (settings.value.showOverwriteConfirm && localStorageManager.hasLocalContent()) {
                pendingUsbPath = path
                _showOverwriteDialog.value = true
            } else {
                // 自动覆盖
                copyFromUsb(path, folderName)
            }
        }
    }
    
    /**
     * U盘断开
     */
    fun onUsbDisconnected() {
        _usbState.value = UsbState.Disconnected
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
        // 继续播放本地内容
    }
    
    /**
     * 从U盘拷贝内容
     */
    private fun copyFromUsb(usbPath: File, folderName: String) {
        viewModelScope.launch {
            // 显示拷贝进度
            _copyProgress.value = CopyProgress(0, 0f)
            
            val success = localStorageManager.copyAllFromUsb(usbPath, folderName) { playerIndex, progress ->
                _copyProgress.value = CopyProgress(playerIndex, progress)
            }
            
            _copyProgress.value = CopyProgress(3, 1f, isComplete = true)
            
            // 延迟后隐藏进度
            kotlinx.coroutines.delay(1000)
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
                    config.copy(
                        state = when (config.state) {
                            PlayerState.PLAYING -> PlayerState.PAUSED
                            PlayerState.PAUSED -> PlayerState.PLAYING
                            PlayerState.IDLE -> if (config.mediaItems.isNotEmpty()) 
                                PlayerState.PLAYING else PlayerState.IDLE
                            else -> config.state
                        }
                    )
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
    }
    
    /**
     * 播放全部
     */
    fun playAll() {
        _playerConfigs.update { configs ->
            configs.map { config ->
                if (config.mediaItems.isNotEmpty()) {
                    config.copy(state = PlayerState.PLAYING)
                } else config
            }
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
    }
    
    /**
     * 更新设置
     */
    fun updateSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(newSettings)
        }
    }
    
    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        viewModelScope.launch {
            localStorageManager.clearAllCache()
            _playerConfigs.update { configs ->
                configs.map { it.copy(mediaItems = emptyList(), state = PlayerState.IDLE) }
            }
        }
    }
    
    /**
     * 手动扫描U盘
     */
    fun scanUsb() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val usbPaths = UsbUtils.getMountedUsbPaths(context)
            
            if (usbPaths.isNotEmpty()) {
                val usbPath = usbPaths.first()
                val folderName = settings.value.usbScanFolderName
                val hasMedia = UsbUtils.hasValidMediaStructure(usbPath, folderName)
                onUsbConnected(usbPath, hasMedia)
            }
        }
    }
}
