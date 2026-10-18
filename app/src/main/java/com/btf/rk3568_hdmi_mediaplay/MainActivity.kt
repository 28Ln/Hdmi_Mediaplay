package com.btf.rk3568_hdmi_mediaplay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbRuntimeState
import com.btf.rk3568_hdmi_mediaplay.remote.PlayerBroadcastContract
import com.btf.rk3568_hdmi_mediaplay.service.UsbMonitorService
import com.btf.rk3568_hdmi_mediaplay.ui.components.SubScreenHost
import com.btf.rk3568_hdmi_mediaplay.ui.main.MainScreen
import com.btf.rk3568_hdmi_mediaplay.ui.main.MainViewModel
import com.btf.rk3568_hdmi_mediaplay.ui.settings.SettingsScreen
import com.btf.rk3568_hdmi_mediaplay.ui.split.ImageSplitScreen
import com.btf.rk3568_hdmi_mediaplay.ui.split.ImageSplitViewModel
import com.btf.rk3568_hdmi_mediaplay.ui.theme.Rk3568_hdmi_mediaplayTheme
import com.btf.rk3568_hdmi_mediaplay.util.AppForegroundState
import com.btf.rk3568_hdmi_mediaplay.util.FilePickerHelper
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import java.io.File

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var usbMonitorService: UsbMonitorService? = null
    private var serviceBound = false

    /**
     * 是否已发起过 bindService。
     * 与 [serviceBound]（回调已到达）区分：onDestroy 必须依据本标志解绑，
     * 否则在绑定完成前销毁会泄漏 ServiceConnection。
     */
    private var bindRequested = false
    private var localStorageManager: LocalStorageManager? = null
    private var pendingManualUsbScan = false
    
    // 保存 ViewModel 引用，用于服务回调
    private var mainViewModelRef: MainViewModel? = null
    
    // 内部广播接收器 - 处理 hide 命令
    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PlayerBroadcastContract.ACTION_MOVE_TO_BACK) {
                Log.d(TAG, "收到 MOVE_TO_BACK 广播")
                moveTaskToBack(true)
                // 注：此处曾发 ACTION_MOVE_TO_BACK_ACK 回执，供 hide 等待确认。
                // 但该回执机制无法工作（详见 PlayerCommandHandler.handleHide 注释），
                // 现由 AppForegroundState 在「发送前」判定，故不再发回执。
            }
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Service connected")
            try {
                val binder = service as? UsbMonitorService.LocalBinder
                usbMonitorService = binder?.getService()
                serviceBound = true
                
                // 立即设置回调
                setupServiceCallbacks()

                if (pendingManualUsbScan) {
                    usbMonitorService?.triggerScan()
                    pendingManualUsbScan = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Service connection error: ${e.message}")
                e.printStackTrace()
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Service disconnected")
            usbMonitorService = null
            serviceBound = false
        }
    }
    
    private fun setupServiceCallbacks() {
        Log.i(TAG, "Setting up service callbacks, viewModel=${mainViewModelRef != null}")
        usbMonitorService?.let { service ->
            // ## 为什么只在首次绑定时安装回调（2026-10-19 实测缺陷）
            //
            // `UsbMonitorService.onUsbConnected` 的 setter 有**回放副作用**：
            // 每次给回调赋值，只要 lastDetectedUsb != null 就会把它再投递一次
            // （UsbMonitorService.kt 的 setter）。
            //
            // 而本方法会在每次 onResume 被调用。实测（API30 模拟器）：
            // 覆盖确认框弹出时按 Home 再切回，确认框会**重新弹出** ——
            // 因为回放重新触发了一遍「算空间 → 决策 → 弹框」的完整流程。
            //
            // 不能靠"按路径去重"来挡：U 盘内容变更是**同一条回调路径**，
            // 按路径去重会把真实的重新扫描也挡掉（实测：加上去重后，
            // 往 U 盘加了 9GB 文件却不再触发检查）。
            //
            // 正确做法：回调**只安装一次**；onResume 只做无副作用的状态同步
            // （getCurrentUsbState），不再重新赋值回调。
            if (!callbacksInstalled) {
                service.onUsbStateChanged = { state ->
                    mainViewModelRef?.syncUsbState(state)
                }
                service.onUsbConnected = { path: File, hasMedia: Boolean ->
                    Log.i(TAG, "USB connected callback received: $path, hasMedia=$hasMedia")
                    mainViewModelRef?.onUsbConnected(path, hasMedia, service.getCurrentConfig() != null)
                }
                service.onUsbDisconnected = {
                    Log.i(TAG, "USB disconnected callback received")
                    mainViewModelRef?.onUsbDisconnected()
                }
                callbacksInstalled = true
            }

            // 错误态自动重试需要一条从 ViewModel 回到服务的通道：
            // 错误由 ViewModel 判定（如空间不足），扫描能力在服务里。
            // 每次重新指向当前 ViewModel（Activity 重建后旧 VM 可能已销毁）。
            mainViewModelRef?.onRequestUsbRescan = { service.triggerScan() }

            when (val currentState = service.getCurrentUsbState()) {
                is UsbRuntimeState.Connected -> {
                    mainViewModelRef?.syncUsbConnected(
                        currentState.path,
                        currentState.hasMediaContent,
                        currentState.hasConfig
                    )
                }
                is UsbRuntimeState.Scanning -> {
                    mainViewModelRef?.syncUsbScanning(currentState.path)
                }
                is UsbRuntimeState.Disconnected -> {
                    mainViewModelRef?.syncUsbDisconnected()
                }
                is UsbRuntimeState.Error -> {
                    // 此前把 Error 降级成 Disconnected，导致「存储空间不足」
                    // 在界面上表现为「U盘未连接」，掩盖了真实原因。
                    mainViewModelRef?.syncUsbState(currentState)
                }
            }
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startUsbMonitorService()
        } else {
            showToast("部分权限被拒绝，U盘功能可能受限")
        }
    }

    /**
     * Android 13+ 通知权限（POST_NOTIFICATIONS）。
     *
     * 与 [permissionLauncher] 分开注册：通知权限是「可选增强」，
     * 被拒绝时绝不能让前台服务停摆（U盘监听必须继续运行），
     * 因此这里的结果只用于告知用户通知不可见，不参与任何启动判断。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "POST_NOTIFICATIONS granted")
        } else {
            Log.w(
                TAG,
                "POST_NOTIFICATIONS denied: foreground notification stays hidden, USB monitor keeps running"
            )
            // 降级提示：服务照常，用户只是看不到常驻通知
            showToast(StringResources.notificationDenied)
        }
    }

    /**
     * API 33+ 请求通知权限。
     *
     * 只有通知可见性受影响，所以任何失败路径都必须静默吞掉：
     * 调用方 [requestPermissions] 无论授权与否都会继续启动 U 盘监听服务。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Log.d(TAG, "POST_NOTIFICATIONS already granted")
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (e: Exception) {
            // 部分定制 ROM 可能未提供该权限，请求失败不得影响 U 盘监听启动
            Log.e(TAG, "requestNotificationPermissionIfNeeded failed", e)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 注册内部广播接收器
        try {
            val filter = IntentFilter(PlayerBroadcastContract.ACTION_MOVE_TO_BACK)
            ContextCompat.registerReceiver(
                this,
                internalReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "注册内部广播接收器失败", e)
        }
        
        try {
            localStorageManager = LocalStorageManager(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        requestPermissions()
        
        setContent {
            Rk3568_hdmi_mediaplayTheme {
                LaunchedEffect(Unit) {
                    setupFullscreen()
                }
                
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
                val mainViewModel: MainViewModel = viewModel()
                val settings by mainViewModel.settings.collectAsState()
                
                // 功能开关
                val featureFlags by FeatureManager.featureFlags.collectAsState()
                
                // 保存 ViewModel 引用
                LaunchedEffect(mainViewModel) {
                    mainViewModelRef = mainViewModel
                    Log.i(TAG, "ViewModel reference saved")
                    // 如果服务已绑定，重新设置回调
                    if (serviceBound) {
                        setupServiceCallbacks()
                    }
                }
                
                var selectingPlayerIndex by rememberSaveable { mutableIntStateOf(-1) }
                
                // 图片裁剪用的文件选择器
                val splitImageViewModel: ImageSplitViewModel = viewModel()
                val splitImagePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let { splitImageViewModel.selectImage(it) }
                }
                
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris: List<Uri> ->
                    Log.d(TAG, "File picker result: ${uris.size} uris, selectingPlayerIndex=$selectingPlayerIndex")
                    if (uris.isNotEmpty() && selectingPlayerIndex >= 0) {
                        val mediaItems = FilePickerHelper.createMediaItemsFromUris(
                            this@MainActivity, 
                            uris
                        )
                        Log.d(TAG, "Created ${mediaItems.size} media items")
                        if (mediaItems.isNotEmpty()) {
                            mainViewModel.setMediaFilesWithDuplicateCheck(selectingPlayerIndex, mediaItems)
                        } else {
                            showToast("无法加载所选文件")
                        }
                    } else {
                        Log.d(TAG, "No uris or invalid player index")
                    }
                    selectingPlayerIndex = -1
                }
                
                var cacheSizeMB by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    try {
                        cacheSizeMB = localStorageManager?.getCacheSizeMB() ?: 0L
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // 竖屏墙模式（模式3/4）自动请求系统旋转，使画面与面板朝向一致。
                // 触发源：设置页按钮 / U盘配置 layoutMode 覆盖 / 远控 set_layout，
                // 全部汇聚到 settingsFlow 后在此统一响应。
                // 开关关闭或切回横屏模式时强制回横屏，避免方向残留。
                LaunchedEffect(settings.layoutMode, featureFlags.allowOrientationAutoSwitch) {
                    val target = if (featureFlags.allowOrientationAutoSwitch && settings.layoutMode.isPortraitWall) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    // 相等守卫：USB 拔插/flag 翻转会重启本 effect，方向未变时避免冗余 WMS 请求
                    if (this@MainActivity.requestedOrientation != target) {
                        try {
                            this@MainActivity.requestedOrientation = target
                            Log.i(TAG, "Requested orientation: $target (mode=${settings.layoutMode})")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to request orientation $target", e)
                        }
                    }
                }

                // 同 MainScreen：在组合期同步设置，避免先用旧语言渲染一帧
                StringResources.setLanguage(settings.language)
                
                // ## 为什么 MainScreen 始终留在组合树里（2026-10-19 修复）
                //
                // 旧实现用 `when (currentScreen)` 互斥切换：进入设置页会**离开组合**，
                // QuadPlayerLayout → MediaPlayerView → VideoPlayerView 的
                // DisposableEffect.onDispose 随即 stop()+release() 掉四个 ExoPlayer。
                // 后果：打开设置的那一刻整墙黑屏，返回后每路视频从 0 重新开始
                // （ViewModel 只保存了 currentIndex，不保存播放位置）。
                //
                // 现在改为「主界面常驻 + 子页面覆盖」：SubScreenHost 不透明且
                // 消费所有指针事件，因此下层播放器既不会重建、也不会被误触。
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = mainViewModel,
                        onNavigateToSettings = { currentScreen = Screen.Settings },
                        onNavigateToImageSplit = { currentScreen = Screen.ImageSplit },
                        onScanUsb = { requestUsbScan() },
                        onSelectFile = { playerIndex ->
                            selectingPlayerIndex = playerIndex
                            try {
                                // Some RK/embedded file pickers do not reliably map video
                                // extensions to video/* MIME types, which makes videos appear
                                // disabled. Open all files and let FilePickerHelper filter the
                                // selected items to supported image/video formats.
                                filePickerLauncher.launch(arrayOf("*/*"))
                            } catch (e: Exception) {
                                e.printStackTrace()
                                showToast("无法打开文件选择器")
                            }
                        }
                    )

                    if (currentScreen != Screen.Main) {
                        SubScreenHost {
                            when (currentScreen) {
                                Screen.Settings -> {
                                    SettingsScreen(
                                        settings = settings,
                                        onSettingsChange = { newSettings -> mainViewModel.updateSettings(newSettings) },
                                        onClearCache = {
                                            mainViewModel.clearAllCache()
                                            cacheSizeMB = 0
                                        },
                                        onResetSettings = {
                                            mainViewModel.updateSettings(AppSettings())
                                        },
                                        onBack = { currentScreen = Screen.Main },
                                        onScanUsb = { requestUsbScan() },
                                        onImageSplit = { currentScreen = Screen.ImageSplit },
                                        cacheSizeMB = cacheSizeMB,
                                        featureFlags = featureFlags
                                    )
                                }

                                Screen.ImageSplit -> {
                                    ImageSplitScreen(
                                        viewModel = splitImageViewModel,
                                        onBack = {
                                            splitImageViewModel.clear()
                                            currentScreen = Screen.Main
                                        },
                                        onSelectImage = {
                                            try {
                                                splitImagePickerLauncher.launch(arrayOf("image/*"))
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                showToast("无法打开文件选择器")
                                            }
                                        },
                                        onApplyComplete = {
                                            showToast("裁剪完成，已应用到播放器")
                                            splitImageViewModel.clear()
                                            mainViewModel.loadLocalContent()
                                            currentScreen = Screen.Main
                                        }
                                    )
                                }

                                Screen.Main -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        // 供远控 hide 判定「是否真的能退到后台」；见 AppForegroundState
        AppForegroundState.onActivityStarted()
        Log.i(TAG, "onStart, serviceBound=$serviceBound, bindRequested=$bindRequested")
        // 依据 bindRequested 判断，避免回调未到达时重复 bind
        if (!bindRequested) {
            try {
                Intent(this, UsbMonitorService::class.java).also { intent ->
                    bindRequested = true
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                }
            } catch (e: Exception) {
                bindRequested = false
                Log.e(TAG, "bindService in onStart failed", e)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        AppForegroundState.onActivityStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        
        // 注销内部广播接收器
        try {
            unregisterReceiver(internalReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销内部广播接收器失败", e)
        }
        
        // 解绑必须依据「是否发起过绑定」，而不是「回调是否已到达」。
        // 若 Activity 在 bindService 与 onServiceConnected 之间被销毁，
        // serviceBound 仍为 false，只判断它会漏掉 unbind → Activity has leaked ServiceConnection。
        if (bindRequested) {
            try {
                usbMonitorService?.onUsbStateChanged = null
                usbMonitorService?.onUsbConnected = null
                usbMonitorService?.onUsbDisconnected = null
                callbacksInstalled = false
                mainViewModelRef?.onRequestUsbRescan = null
                unbindService(serviceConnection)
            } catch (e: Exception) {
                // 未真正绑定时 unbindService 会抛 IllegalArgumentException，视为已解绑
                Log.d(TAG, "unbindService: ${e.message}")
            }
            bindRequested = false
            serviceBound = false
        }
        mainViewModelRef = null
    }
    
    override fun onResume() {
        super.onResume()
        setupFullscreen()
        if (serviceBound && mainViewModelRef != null) {
            setupServiceCallbacks()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 路径下 Activity 不重建、onResume 不重跑，
        // 部分 RK ROM 旋转后沉浸式标志复位，需重新施加全屏
        setupFullscreen()
    }
    
    private var lastBackPressTime = 0L

    /**
     * 服务回调是否已安装。
     *
     * **不能**在 onResume 里重复安装：`UsbMonitorService.onUsbConnected` 的 setter
     * 带回放副作用，重复赋值会重放最近一次 U 盘事件（实测会让覆盖确认框重弹）。
     * 仅在解绑（onStop）时复位，下次绑定时重新安装一次。
     */
    private var callbacksInstalled = false
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            lastBackPressTime = currentTime
            showToast("再按一次退出应用")
        }
    }
    
    private fun setupFullscreen() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }
    
    private fun requestPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    showToast("请授予文件管理权限以读取U盘")
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    // 通知权限在「即将启动前台服务」时才请求：此时不会与
                    // 跳转系统设置页的 startActivity 抢焦点，且语义上正好对应
                    // 「这个服务需要一条常驻通知」。被拒绝也不影响服务运行。
                    requestNotificationPermissionIfNeeded()
                    startUsbMonitorService()
                }
            } else {
                val permissions = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }

                if (permissions.isNotEmpty()) {
                    permissionLauncher.launch(permissions.toTypedArray())
                } else {
                    requestNotificationPermissionIfNeeded()
                    startUsbMonitorService()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun startUsbMonitorService() {
        try {
            Intent(this, UsbMonitorService::class.java).also { intent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 绑定 U 盘监听服务。
     *
     * 统一在此设置 [bindRequested]，确保 onDestroy 一定会解绑
     * （即便回调还没到达就销毁了 Activity）。
     */
    private fun requestUsbScan() {
        if (serviceBound) {
            usbMonitorService?.triggerScan()
            return
        }
        // 已发起过绑定、只是回调未到：避免重复 bind
        if (bindRequested) return
        pendingManualUsbScan = true
        startUsbMonitorService()
        try {
            Intent(this, UsbMonitorService::class.java).also { intent ->
                bindRequested = true
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) {
            bindRequested = false
            Log.e(TAG, "bindService failed", e)
        }
    }
    
    private fun showToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    sealed class Screen {
        object Main : Screen()
        object Settings : Screen()
        object ImageSplit : Screen()
    }
}
