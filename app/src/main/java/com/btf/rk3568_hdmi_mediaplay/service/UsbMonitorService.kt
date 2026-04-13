package com.btf.rk3568_hdmi_mediaplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.MainActivity
import com.btf.rk3568_hdmi_mediaplay.R
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbConfig
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbRuntimeState
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.receiver.UsbBroadcastReceiver
import com.btf.rk3568_hdmi_mediaplay.util.UsbConfigLoader
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * U盘监听服务
 */
class UsbMonitorService : Service() {
    
    companion object {
        private const val TAG = "UsbMonitorService"
        private const val CHANNEL_ID = "usb_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SCAN_INTERVAL_MS = 3000L
    }
    
    private val binder = LocalBinder()
    private var serviceScope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val _usbState = MutableStateFlow<UsbRuntimeState>(UsbRuntimeState.Disconnected)
    val usbState: StateFlow<UsbRuntimeState> = _usbState
    
    private var scanJob: Job? = null
    private var usbReceiver: UsbBroadcastReceiver? = null
    private var settingsRepository: SettingsRepository? = null
    
    // 缓存最后检测到的U盘状态
    @Volatile
    private var lastDetectedUsb: Pair<File, Boolean>? = null
    
    // 缓存最后加载的U盘配置
    @Volatile
    private var lastLoadedConfig: UsbConfig? = null
    
    // 事件回调 - 使用 @Volatile 确保线程可见性
    @Volatile
    var onUsbConnected: ((File, Boolean) -> Unit)? = null
        set(value) {
            Log.i(TAG, "onUsbConnected callback ${if (value != null) "SET" else "CLEARED"}")
            field = value
            // 设置回调后，如果已经检测到U盘，立即通知
            if (value != null) {
                lastDetectedUsb?.let { (path, hasMedia) ->
                    Log.i(TAG, "Notifying cached USB state: $path, hasMedia=$hasMedia")
                    mainHandler.post {
                        try {
                            value.invoke(path, hasMedia)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in cached callback: ${e.message}")
                        }
                    }
                }
            }
        }
    
    @Volatile
    var onUsbDisconnected: (() -> Unit)? = null
    
    // U盘配置加载回调
    @Volatile
    var onConfigLoaded: ((UsbConfig?) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): UsbMonitorService = this@UsbMonitorService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Service bound")
        return binder
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbound")
        return super.onUnbind(intent)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
        }
        return START_STICKY
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        
        try {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            settingsRepository = SettingsRepository(this)
            
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            
            registerUsbReceiver()
            startPeriodicScan()
            
            Log.i(TAG, "Service created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create service: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        try {
            scanJob?.cancel()
            serviceScope?.cancel()
            unregisterUsbReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying service: ${e.message}")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "监听U盘插入和拔出"
                    setShowBadge(false)
                }
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel: ${e.message}")
            }
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = try {
            val intent = Intent(this, MainActivity::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, intent, flags)
        } catch (e: Exception) {
            null
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
    
    private fun registerUsbReceiver() {
        try {
            usbReceiver = UsbBroadcastReceiver()
            
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addDataScheme("file")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter)
            }
            
            UsbBroadcastReceiver.onUsbMounted = { path ->
                Log.i(TAG, "Broadcast: USB mounted at $path")
                checkUsbContent(File(path))
            }
            
            UsbBroadcastReceiver.onUsbUnmounted = { path ->
                Log.i(TAG, "Broadcast: USB unmounted from $path")
                lastDetectedUsb = null
                lastLoadedConfig = null
                _usbState.value = UsbRuntimeState.Disconnected
                // 清除U盘配置
                FeatureManager.applyUsbConfig(null)
                notifyUsbDisconnected()
            }
            
            Log.i(TAG, "USB receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB receiver: ${e.message}")
        }
    }
    
    private fun unregisterUsbReceiver() {
        try {
            usbReceiver?.let { unregisterReceiver(it) }
            UsbBroadcastReceiver.onUsbMounted = null
            UsbBroadcastReceiver.onUsbUnmounted = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }
    
    private fun startPeriodicScan() {
        scanJob?.cancel()
        scanJob = serviceScope?.launch {
            // 首次扫描延迟一下，等待服务完全启动
            delay(1000)
            while (isActive) {
                try {
                    scanForUsb()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic scan: ${e.message}")
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun scanForUsb() {
        try {
            val usbPaths = UsbUtils.getMountedUsbPaths(this@UsbMonitorService)
            
            if (usbPaths.isEmpty()) {
                if (_usbState.value !is UsbRuntimeState.Disconnected) {
                    Log.i(TAG, "No USB detected, setting disconnected")
                    lastDetectedUsb = null
                    lastLoadedConfig = null
                    _usbState.value = UsbRuntimeState.Disconnected
                    // 清除U盘配置
                    FeatureManager.applyUsbConfig(null)
                    notifyUsbDisconnected()
                }
            } else {
                val usbPath = usbPaths.first()
                val currentState = _usbState.value
                
                // 检查是否需要更新状态
                val needCheck = when {
                    currentState is UsbRuntimeState.Disconnected -> true
                    currentState is UsbRuntimeState.Connected && currentState.path.absolutePath != usbPath.absolutePath -> true
                    else -> false
                }
                
                if (needCheck) {
                    checkUsbContentAsync(usbPath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB: ${e.message}")
        }
    }
    
    private fun checkUsbContent(usbPath: File) {
        serviceScope?.launch {
            checkUsbContentAsync(usbPath)
        }
    }
    
    private suspend fun checkUsbContentAsync(usbPath: File) {
        Log.i(TAG, "Checking USB content: ${usbPath.absolutePath}")
        _usbState.value = UsbRuntimeState.Scanning(usbPath)
        
        try {
            val folderName = try {
                settingsRepository?.settingsFlow?.first()?.usbScanFolderName ?: "media"
            } catch (e: Exception) {
                "media"
            }
            
            val hasMediaContent = withContext(Dispatchers.IO) {
                UsbUtils.hasValidMediaStructure(usbPath, folderName)
            }
            
            // 加载U盘配置文件
            val usbConfig = withContext(Dispatchers.IO) {
                UsbConfigLoader.loadConfig(usbPath)
            }
            
            val hasConfig = usbConfig != null
            
            Log.i(TAG, "USB check result: path=${usbPath.absolutePath}, hasMedia=$hasMediaContent, hasConfig=$hasConfig")
            
            // 应用U盘配置到 FeatureManager
            if (usbConfig != null) {
                Log.i(TAG, "Applying USB config: version=${usbConfig.version}")
                FeatureManager.applyUsbConfig(usbConfig)
                lastLoadedConfig = usbConfig
                
                // 通知配置加载
                mainHandler.post {
                    onConfigLoaded?.invoke(usbConfig)
                }
            }
            
            // 更新状态
            lastDetectedUsb = Pair(usbPath, hasMediaContent)
            _usbState.value = UsbRuntimeState.Connected(usbPath, hasMediaContent, hasConfig)
            
            // 通知回调
            notifyUsbConnected(usbPath, hasMediaContent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking USB content: ${e.message}")
            _usbState.value = UsbRuntimeState.Disconnected
        }
    }
    
    private fun notifyUsbConnected(path: File, hasMedia: Boolean) {
        val callback = onUsbConnected
        Log.i(TAG, "notifyUsbConnected: callback is ${if (callback != null) "SET" else "NULL"}")
        
        if (callback != null) {
            mainHandler.post {
                try {
                    Log.i(TAG, "Invoking onUsbConnected on main thread")
                    callback.invoke(path, hasMedia)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onUsbConnected callback: ${e.message}")
                }
            }
        }
    }
    
    private fun notifyUsbDisconnected() {
        val callback = onUsbDisconnected
        if (callback != null) {
            mainHandler.post {
                try {
                    callback.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onUsbDisconnected callback: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 手动触发扫描
     */
    fun triggerScan() {
        Log.i(TAG, "Manual scan triggered")
        serviceScope?.launch {
            // 强制重新扫描
            val usbPaths = UsbUtils.getMountedUsbPaths(this@UsbMonitorService)
            if (usbPaths.isNotEmpty()) {
                checkUsbContentAsync(usbPaths.first())
            } else {
                lastDetectedUsb = null
                lastLoadedConfig = null
                _usbState.value = UsbRuntimeState.Disconnected
                FeatureManager.applyUsbConfig(null)
                notifyUsbDisconnected()
            }
        }
    }
    
    /**
     * 获取当前U盘状态
     */
    fun getCurrentUsbState(): UsbRuntimeState = _usbState.value
    
    /**
     * 获取当前加载的U盘配置
     */
    fun getCurrentConfig(): UsbConfig? = lastLoadedConfig
}
