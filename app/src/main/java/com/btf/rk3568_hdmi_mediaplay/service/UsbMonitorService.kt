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
import android.os.IBinder
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.MainActivity
import com.btf.rk3568_hdmi_mediaplay.R
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.receiver.UsbBroadcastReceiver
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * U盘监听服务
 * 后台监听U盘的插入和拔出，并扫描媒体内容
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
    
    private val _usbState = MutableStateFlow<UsbState>(UsbState.Disconnected)
    val usbState: StateFlow<UsbState> = _usbState
    
    private var scanJob: Job? = null
    private var usbReceiver: UsbBroadcastReceiver? = null
    private var settingsRepository: SettingsRepository? = null
    
    // U盘状态
    sealed class UsbState {
        object Disconnected : UsbState()
        data class Connected(val path: File, val hasMediaContent: Boolean) : UsbState()
        data class Scanning(val path: File) : UsbState()
    }
    
    // 事件回调
    var onUsbConnected: ((File, Boolean) -> Unit)? = null
    var onUsbDisconnected: (() -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): UsbMonitorService = this@UsbMonitorService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保服务在前台运行
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
        
        try {
            // 初始化协程作用域
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // 初始化设置仓库
            settingsRepository = SettingsRepository(this)
            
            // 创建通知渠道并启动前台服务
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            
            // 注册广播接收器
            registerUsbReceiver()
            
            // 启动周期性扫描
            startPeriodicScan()
            
            Log.i(TAG, "Service created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create service: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            scanJob?.cancel()
            serviceScope?.cancel()
            unregisterUsbReceiver()
            Log.i(TAG, "Service destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying service: ${e.message}")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "U盘监听服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "监听U盘插入和拔出"
                    setShowBadge(false)
                }
                
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel: ${e.message}")
            }
        }
    }
    
    private fun createNotification(): Notification {
        // 创建点击通知时打开应用的 Intent
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
                .setContentTitle("媒体播放器")
                .setContentText("正在监听U盘...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("媒体播放器")
                .setContentText("正在监听U盘...")
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
            
            // Android 13+ 需要指定导出标志
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter)
            }
            
            UsbBroadcastReceiver.onUsbMounted = { path ->
                Log.i(TAG, "USB mounted callback: $path")
                checkUsbContent(File(path))
            }
            
            UsbBroadcastReceiver.onUsbUnmounted = { _ ->
                Log.i(TAG, "USB unmounted callback")
                _usbState.value = UsbState.Disconnected
                try {
                    onUsbDisconnected?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onUsbDisconnected callback: ${e.message}")
                }
            }
            
            Log.i(TAG, "USB receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB receiver: ${e.message}")
        }
    }
    
    private fun unregisterUsbReceiver() {
        try {
            usbReceiver?.let {
                unregisterReceiver(it)
            }
            UsbBroadcastReceiver.onUsbMounted = null
            UsbBroadcastReceiver.onUsbUnmounted = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }
    
    /**
     * 启动周期性扫描
     */
    private fun startPeriodicScan() {
        scanJob?.cancel()
        scanJob = serviceScope?.launch {
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
    
    /**
     * 扫描U盘
     */
    private fun scanForUsb() {
        try {
            val usbPaths = UsbUtils.getMountedUsbPaths(this)
            
            if (usbPaths.isEmpty()) {
                if (_usbState.value !is UsbState.Disconnected) {
                    _usbState.value = UsbState.Disconnected
                    try {
                        onUsbDisconnected?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onUsbDisconnected: ${e.message}")
                    }
                }
            } else {
                val usbPath = usbPaths.first()
                val currentState = _usbState.value
                
                if (currentState is UsbState.Disconnected || 
                    (currentState is UsbState.Connected && currentState.path != usbPath)) {
                    checkUsbContent(usbPath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB: ${e.message}")
        }
    }
    
    /**
     * 检查U盘内容
     */
    private fun checkUsbContent(usbPath: File) {
        _usbState.value = UsbState.Scanning(usbPath)
        
        serviceScope?.launch {
            try {
                // 从设置中获取目录名
                val folderName = try {
                    settingsRepository?.settingsFlow?.first()?.usbScanFolderName ?: "media"
                } catch (e: Exception) {
                    "media"
                }
                
                val hasMediaContent = UsbUtils.hasValidMediaStructure(usbPath, folderName)
                
                _usbState.value = UsbState.Connected(usbPath, hasMediaContent)
                
                try {
                    onUsbConnected?.invoke(usbPath, hasMediaContent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onUsbConnected callback: ${e.message}")
                }
                
                Log.i(TAG, "USB content check: path=$usbPath, hasMedia=$hasMediaContent")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking USB content: ${e.message}")
                _usbState.value = UsbState.Disconnected
            }
        }
    }
    
    /**
     * 手动触发扫描
     */
    fun triggerScan() {
        serviceScope?.launch {
            scanForUsb()
        }
    }
}
