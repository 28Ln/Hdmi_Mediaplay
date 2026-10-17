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
import androidx.core.content.ContextCompat
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.MainActivity
import com.btf.rk3568_hdmi_mediaplay.R
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbConfig
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbRuntimeState
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.receiver.UsbBroadcastReceiver
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
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

        /**
         * 计算U盘内容签名（供实例方法与单测共用）。
         *
         * 见实例方法 [contentSignature] 的说明。做成 companion 函数是为了能在
         * JVM 单测里直接验证「内容不变签名不变 / 内容一变签名就变」这条核心约束
         * —— 该约束一旦写错，表现是每 3 秒弹一次覆盖确认框，插件无法使用。
         */
        internal fun contentSignatureForTest(usbPath: File, folderName: String): String {
            return buildContentSignature(usbPath, folderName)
        }

        private fun buildContentSignature(usbPath: File, folderName: String): String {
            return try {
                val sb = StringBuilder(usbPath.absolutePath)
                val mediaDir = UsbUtils.getUsbMediaDir(usbPath, folderName)
                if (mediaDir.exists() && mediaDir.isDirectory) {
                    FileUtils.PLAYER_FOLDERS.forEach { playerFolder ->
                        val dir = File(mediaDir, playerFolder)
                        if (dir.isDirectory) {
                            // 目录自身 mtime：FAT32 文件时间戳精度仅 2 秒，
                            // 2 秒内替换同名同大小文件时逐文件签名可能不变。
                            // 增删文件一定会改变目录 mtime，作为补充判据。
                            sb.append('|').append(playerFolder).append(":dir@")
                                .append(dir.lastModified())
                            dir.listFiles()
                                ?.filter { it.isFile }
                                ?.sortedBy { it.name }
                                ?.forEach { f ->
                                    sb.append('|').append(playerFolder)
                                        .append('/').append(f.name)
                                        .append(':').append(f.length())
                                        .append('@').append(f.lastModified())
                                }
                        }
                    }
                }
                // 配置文件变化也要触发重扫（客户可能只改 btf_config.json 不动媒体）
                val configFile = File(usbPath, UsbConfig.CONFIG_FILE_NAME)
                if (configFile.exists()) {
                    sb.append("|cfg:").append(configFile.length())
                        .append('@').append(configFile.lastModified())
                }
                sb.toString()
            } catch (e: Exception) {
                Log.w(TAG, "contentSignature failed: ${e.message}")
                // 返回固定标记而非每次不同的值：若每次不同，"内容未变"永远不成立，
                // 每 3 秒都会通知上层 → 反复弹覆盖框，比漏一次更新更糟。
                "unavailable"
            }
        }
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

    /**
     * 上一次已处理过的U盘内容签名（路径 + 媒体文件清单 + 配置文件 mtime）。
     *
     * **为什么需要**：轮询每 3 秒跑一次。若「已连接也重扫」且每次都回调
     * [onUsbConnected]，上层会每 3 秒弹一次覆盖确认框 —— 插件无法使用。
     * 用签名做闸门：只有U盘内容**真的变了**才重新通知。
     *
     * 覆盖两种「内容变化」场景：
     * 1. 拔掉后重新插入**同一个**U盘（路径相同，但期间可能改过内容）
     * 2. U盘一直插着，用户往里拷了新文件
     */
    @Volatile
    private var lastContentSignature: String? = null
    
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

    @Volatile
    var onUsbStateChanged: ((UsbRuntimeState) -> Unit)? = null
    
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
        // startForegroundService 之后必须在 5 秒内调用 startForeground，
        // 否则系统抛 RemoteServiceException 并杀掉进程——日志上表现为"随机崩溃"。
        // 因此这里失败时必须主动 stopSelf，不能只记日志后继续运行。
        val started = try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed, stopping service", e)
            false
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
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
            
            // 统一用 ContextCompat：在 Android 13 以下也能明确声明「不导出」，
            // 避免该接收器被外部应用触发（USB 挂载广播本身受系统保护，
            // 但显式声明可消除歧义、也便于安全审计）
            ContextCompat.registerReceiver(
                this,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            
            UsbBroadcastReceiver.onUsbMounted = { path ->
                Log.i(TAG, "Broadcast: USB mounted at $path")
                checkUsbContent(File(path))
            }
            
            UsbBroadcastReceiver.onUsbUnmounted = { path ->
                Log.i(TAG, "Broadcast: USB unmounted from $path")
                lastDetectedUsb = null
                lastLoadedConfig = null
                // 清空内容签名：这样「拔了再插同一个盘」会被判定为内容变化，
                // 从而正常触发一次刷新（否则重插同盘不会重新拷贝）
                lastContentSignature = null
                emitUsbState(UsbRuntimeState.Disconnected)
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
                    emitUsbState(UsbRuntimeState.Disconnected)
                    // 清除U盘配置
                    FeatureManager.applyUsbConfig(null)
                    notifyUsbDisconnected()
                }
            } else {
                val usbPath = usbPaths.first()
                val currentState = _usbState.value

                // 需要重新检查的情形：
                // - 尚未连接 / 连接的是另一个U盘
                // - **已连接同一路径**：U盘内容可能已变（用户往里拷了文件），
                //   或拔插后重新挂载到同一路径
                // - **Error 态**：给出一条自动恢复路径，否则永远卡在错误态
                //
                // 注意：这里"重扫"只做媒体/配置的**探测**，是否回调上层
                // 由 [checkUsbContentAsync] 里的内容签名决定，不会每 3 秒打扰用户。
                val needCheck = when (currentState) {
                    is UsbRuntimeState.Disconnected -> true
                    is UsbRuntimeState.Error -> true
                    is UsbRuntimeState.Connected ->
                        currentState.path.absolutePath != usbPath.absolutePath ||
                            contentSignature(usbPath, settingsRepository?.settingsFlow?.first()?.usbScanFolderName ?: "media") !=
                            lastContentSignature
                    is UsbRuntimeState.Scanning -> false
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
    
    /**
     * 计算U盘内容签名。
     *
     * 包含：路径、每个 playerN 目录的 mtime、目录内每个文件的「名字+大小+mtime」，
     * 以及配置文件的 mtime。内容有任何变化，签名就不同。
     *
     * 只读目录元数据、不读文件内容，开销与一次 `listFiles` 同级。
     */
    private fun contentSignature(usbPath: File, folderName: String): String =
        contentSignatureForTest(usbPath, folderName)

    private suspend fun checkUsbContentAsync(usbPath: File) {
        Log.i(TAG, "Checking USB content: ${usbPath.absolutePath}")
        emitUsbState(UsbRuntimeState.Scanning(usbPath))

        try {
            val folderName = try {
                settingsRepository?.settingsFlow?.first()?.usbScanFolderName ?: "media"
            } catch (e: Exception) {
                "media"
            }

            // 内容未变 → 只刷新状态、**不回调上层**，
            // 避免每 3 秒弹一次覆盖确认框。
            // 注意：拔盘时会把 lastContentSignature 置空，因此「拔了再插同一个盘」
            // 签名必然不同 → 会正常触发刷新。
            val signature = contentSignature(usbPath, folderName)
            val unchanged = signature == lastContentSignature

            val hasMediaContent = withContext(Dispatchers.IO) {
                UsbUtils.hasValidMediaStructure(usbPath, folderName)
            }

            // 加载U盘配置文件
            val usbConfig = withContext(Dispatchers.IO) {
                UsbConfigLoader.loadConfig(usbPath)
            }

            val hasConfig = usbConfig != null

            Log.i(TAG, "USB check result: path=${usbPath.absolutePath}, hasMedia=$hasMediaContent, hasConfig=$hasConfig, unchanged=$unchanged")

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
            lastContentSignature = signature
            emitUsbState(UsbRuntimeState.Connected(usbPath, hasMediaContent, hasConfig))

            // 通知回调：仅在内容变化时触发，避免轮询重复打扰
            if (!unchanged) {
                notifyUsbConnected(usbPath, hasMediaContent)
            } else {
                Log.i(TAG, "U盘内容未变化，跳过重复通知")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking USB content: ${e.message}")
            emitUsbState(UsbRuntimeState.Disconnected)
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
                emitUsbState(UsbRuntimeState.Disconnected)
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

    private fun emitUsbState(state: UsbRuntimeState) {
        _usbState.value = state
        onUsbStateChanged?.let { callback ->
            mainHandler.post {
                try {
                    callback.invoke(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onUsbStateChanged callback: ${e.message}")
                }
            }
        }
    }
}
