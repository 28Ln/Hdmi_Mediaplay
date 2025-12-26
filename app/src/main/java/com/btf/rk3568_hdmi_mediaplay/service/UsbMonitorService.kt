package com.btf.rk3568_hdmi_mediaplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.R
import com.btf.rk3568_hdmi_mediaplay.receiver.UsbBroadcastReceiver
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        private const val SCAN_INTERVAL_MS = 3000L  // 扫描间隔
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _usbState = MutableStateFlow<UsbState>(UsbState.Disconnected)
    val usbState: StateFlow<UsbState> = _usbState
    
    private var scanJob: Job? = null
    private var usbReceiver: UsbBroadcastReceiver? = null
    
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
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        registerUsbReceiver()
        startPeriodicScan()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        serviceScope.cancel()
        unregisterUsbReceiver()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "U盘监听服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "监听U盘插入和拔出"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("媒体播放器")
                .setContentText("正在监听U盘...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("媒体播放器")
                .setContentText("正在监听U盘...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()
        }
    }
    
    private fun registerUsbReceiver() {
        usbReceiver = UsbBroadcastReceiver()
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        
        registerReceiver(usbReceiver, filter)
        
        UsbBroadcastReceiver.onUsbMounted = { path ->
            Log.i(TAG, "USB mounted callback: $path")
            checkUsbContent(File(path))
        }
        
        UsbBroadcastReceiver.onUsbUnmounted = { _ ->
            Log.i(TAG, "USB unmounted callback")
            _usbState.value = UsbState.Disconnected
            onUsbDisconnected?.invoke()
        }
    }
    
    private fun unregisterUsbReceiver() {
        usbReceiver?.let {
            unregisterReceiver(it)
        }
        UsbBroadcastReceiver.onUsbMounted = null
        UsbBroadcastReceiver.onUsbUnmounted = null
    }
    
    /**
     * 启动周期性扫描
     * 用于检测U盘插入（某些设备可能不发送广播）
     */
    private fun startPeriodicScan() {
        scanJob = serviceScope.launch {
            while (isActive) {
                scanForUsb()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 扫描U盘
     */
    private fun scanForUsb() {
        val usbPaths = UsbUtils.getMountedUsbPaths(this)
        
        if (usbPaths.isEmpty()) {
            if (_usbState.value !is UsbState.Disconnected) {
                _usbState.value = UsbState.Disconnected
                onUsbDisconnected?.invoke()
            }
        } else {
            // 使用第一个检测到的U盘
            val usbPath = usbPaths.first()
            val currentState = _usbState.value
            
            if (currentState is UsbState.Disconnected || 
                (currentState is UsbState.Connected && currentState.path != usbPath)) {
                checkUsbContent(usbPath)
            }
        }
    }
    
    /**
     * 检查U盘内容
     */
    private fun checkUsbContent(usbPath: File, folderName: String = "media") {
        _usbState.value = UsbState.Scanning(usbPath)
        
        serviceScope.launch {
            val hasMediaContent = UsbUtils.hasValidMediaStructure(usbPath, folderName)
            
            _usbState.value = UsbState.Connected(usbPath, hasMediaContent)
            onUsbConnected?.invoke(usbPath, hasMediaContent)
            
            Log.i(TAG, "USB content check: path=$usbPath, hasMedia=$hasMediaContent")
        }
    }
    
    /**
     * 手动触发扫描
     */
    fun triggerScan() {
        scanForUsb()
    }
}
