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
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /**
         * 轮询时是否应进入内容检查（纯函数，便于测试）。
         *
         * 只有 `Scanning` 不重入；其余状态都进入检查，由签名闸门决定是否通知上层。
         * 该判定本身不做 IO —— 此前这里是按状态与路径分别判断，
         * 曾两次因漏掉某个分支导致功能静默失效（手动扫描无效、重试永远失败）。
         */
        internal fun shouldCheckOnPoll(state: UsbRuntimeState): Boolean =
            state !is UsbRuntimeState.Scanning

        /**
         * 调用方是否需要绕过签名闸门（纯函数，便于测试）。
         *
         * 语义是「新插入 / 明确重试」的路径必须绕过，否则会被"内容未变"吞掉。
         * 本项目的三次静默失效都源于漏判这一点，故用测试固化。
         */
        internal fun mustBypassSignatureGate(caller: TriggerSource): Boolean =
            when (caller) {
                TriggerSource.POLL -> false
                TriggerSource.MOUNT_BROADCAST -> true
                TriggerSource.MANUAL_OR_RETRY -> true
            }

        /** 触发内容检查的来源 */
        enum class TriggerSource {
            /** 每 3 秒的例行轮询 */
            POLL,
            /** 系统挂载广播 */
            MOUNT_BROADCAST,
            /** 「扫描U盘」按钮 / 错误态自动重试 */
            MANUAL_OR_RETRY
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

    /**
     * 串行化内容检查。
     *
     * [checkUsbContentAsync] 有**三个并发调用方**，都跑在
     * `Dispatchers.IO` 线程池上：
     * - 每 3 秒的轮询
     * - 挂载广播
     * - 手动扫描 / 错误重试
     *
     * 没有这把锁时，"读 lastContentSignature → 比较 → 写回"不是原子操作，
     * 两个线程可能同时判定"内容变了"，于是并发 emitUsbState 并两次
     * notifyUsbConnected —— 上层收到重复通知、重复弹覆盖框，
     * 而这正是内容签名闸门想要消除的现象。
     */
    private val checkMutex = Mutex()

    /**
     * 缓存代次。每次 [clearUsbCaches] 自增。
     *
     * 用于解决「检查进行中遇到拔盘」的竞态：
     * 检查是在持锁期间做多次 IO 的，若此间有广播触发了 clearUsbCaches()，
     * 检查结束时会把**过期的** signature 写回去，使已清空的缓存复活，
     * 导致下次重插同一个 U 盘时不刷新。
     *
     * 做法：进入检查时记下代次，写回前比对；若代次已变说明期间被清空过，
     * 就放弃写回（让下一次检查重新判断）。
     */
    @Volatile
    private var cacheGeneration: Long = 0L
    
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
                    StringResources.notificationChannelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = StringResources.notificationChannelDescription
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
                .setContentTitle(StringResources.notificationTitle)
                .setContentText(StringResources.notificationText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(StringResources.notificationTitle)
                .setContentText(StringResources.notificationText)
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
                // 挂载广播是"新设备已就绪"的权威信号，必须绕过签名闸门。
                //
                // 不能只依赖卸载广播清缓存：某些 ROM 在重新挂载同一分区时
                // 只发 MOUNTED、不发 UNMOUNTED；服务重启后也可能直接收到 MOUNTED。
                // 这些情况下签名可能与缓存值相同，闸门会把这次插入吞掉。
                if (mustBypassSignatureGate(TriggerSource.MOUNT_BROADCAST)) {
                    lastContentSignature = null
                }
                checkUsbContent(File(path))
            }
            
            UsbBroadcastReceiver.onUsbUnmounted = { path ->
                Log.i(TAG, "Broadcast: USB unmounted from $path")
                // 清空全部缓存（含内容签名）：这样「拔了再插同一个盘」
                // 会被判定为内容变化，从而正常触发一次刷新
                clearUsbCaches()
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
                    // 与广播路径保持一致，统一走 clearUsbCaches()
                    clearUsbCaches()
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
                // 是否进入「检查」流程。
                //
                // 这里只做**廉价**判断（状态与路径），不计算内容签名：
                // 签名是「是否通知上层」的闸门，统一由 [checkUsbContentAsync]
                // 计算一次。若两处都算，两次计算之间目录 mtime 可能已变，
                // 会得出不一致结论，反而造成多余的检查或漏检。
                //
                // Connected 同一路径时仍然每轮进入检查：这样 U 盘内容变化
                // （用户往里拷文件）能被发现；是否真正通知上层由签名决定，
                // 不会每 3 秒打扰用户。
                val needCheck = shouldCheckOnPoll(currentState)

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
     * 清空全部「已检测U盘」缓存。
     *
     * 抽成单一函数的原因：此前有三处独立重置（广播拔出、轮询未检测到、
     * 手动重扫），其中两处漏掉了 [lastContentSignature]，导致
     * 「拔了再插同一个盘」被判为内容未变而不刷新。
     * 以后新增重置路径时，只需调用本函数，不会再漏字段。
     */
    private fun clearUsbCaches() {
        cacheGeneration++
        lastDetectedUsb = null
        lastLoadedConfig = null
        lastContentSignature = null
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

    /**
     * 检查U盘内容。
     *
     * **内容签名闸门**：签名未变时直接早退、不通知上层，用于避免轮询每 3 秒
     * 重复打扰（弹覆盖确认框、重复拷贝）。
     *
     * ## 调用方须知：有三类调用必须绕过闸门
     *
     * 若调用方的语义是「这是一次新的插入/一次明确的重试」，而不是「例行巡检」，
     * 就必须在调用前把 `lastContentSignature = null`：
     *
     * | 调用方 | 是否绕过 | 原因 |
     * |---|---|---|
     * | `onUsbMounted` 挂载广播 | **是** | 新设备就绪的权威信号 |
     * | `triggerScan`（手动/重试） | **是** | 意图就是"再试一次"，被闸门挡住等于功能失效 |
     * | `scanForUsb` 轮询 | 否 | 例行巡检，靠闸门去重 |
     *
     * **新增调用方时请先判断属于哪一类** —— 本项目已两次因漏了这一步
     * 导致功能静默失效（手动扫描无效、错误自动重试永远恢复不了）。
     */
    private suspend fun checkUsbContentAsync(usbPath: File) = checkMutex.withLock {
        val startGeneration = cacheGeneration
        try {
            val folderName = try {
                settingsRepository?.settingsFlow?.first()?.usbScanFolderName ?: "media"
            } catch (e: Exception) {
                "media"
            }

            // 先算签名，命中「内容未变」直接返回 —— **连 Scanning 状态都不发**。
            //
            // 这里曾经是先 emit(Scanning) 再算签名，结果是轮询每 3 秒把状态
            // 打回 Scanning 一次：界面右上角的 U 盘指示器周期性闪烁、日志被刷屏。
            // 状态广播只应反映真实的状态迁移，不应反映"例行巡检"。
            //
            // 早退还能省掉下面 hasValidMediaStructure 与 loadConfig 两次 IO。
            //
            // 注意：拔盘/挂载广播/手动重扫都会把 lastContentSignature 置空，
            // 因此这些"需要真正检查"的路径不受此闸门影响。
            val signature = contentSignature(usbPath, folderName)
            if (signature == lastContentSignature) {
                Log.d(TAG, "U盘内容未变化，跳过检查")
                return
            }

            // 确认内容确有变化，此刻才对外表现为"正在扫描"
            Log.i(TAG, "Checking USB content: ${usbPath.absolutePath}")
            emitUsbState(UsbRuntimeState.Scanning(usbPath))

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

            // 更新状态。
            // 写回前确认期间没有被 clearUsbCaches()（如拔盘广播）打断，
            // 否则会把过期签名写回去、让已清空的缓存复活。
            if (cacheGeneration != startGeneration) {
                Log.i(TAG, "检查期间缓存被清空（代次 $startGeneration -> $cacheGeneration），放弃写回")
                return
            }
            lastDetectedUsb = Pair(usbPath, hasMediaContent)
            lastContentSignature = signature
            emitUsbState(UsbRuntimeState.Connected(usbPath, hasMediaContent, hasConfig))

            // 走到这里说明内容确实变了（未变的情况已在上面早退）
            notifyUsbConnected(usbPath, hasMediaContent)

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
    /**
     * 手动/自动重试触发的扫描。
     *
     * 与轮询扫描的关键区别：**必须绕过内容签名闸门**。
     * 调用方（手动扫描、错误态自动重试）的意图就是「再试一次」，
     * 若因内容未变而早退，重试就毫无意义 —— 尤其「空间不足」场景，
     * U 盘内容本来就没变，变的是本地可用空间。
     */
    fun triggerScan() {
        Log.i(TAG, "Manual scan triggered")
        serviceScope?.launch {
            val usbPaths = UsbUtils.getMountedUsbPaths(this@UsbMonitorService)
            if (usbPaths.isNotEmpty()) {
                // 只清签名、保留 lastDetectedUsb / lastLoadedConfig：
                // 目的是"强制再检查一次同一个盘"，而不是把它当成新插入的盘。
                // 若这里走 clearUsbCaches()，会把已检测状态也清掉，
                // 上层可能误判为新盘插入。
                if (mustBypassSignatureGate(TriggerSource.MANUAL_OR_RETRY)) {
                    lastContentSignature = null
                }
                checkUsbContentAsync(usbPaths.first())
            } else {
                clearUsbCaches()
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
