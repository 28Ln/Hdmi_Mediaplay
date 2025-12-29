package com.btf.rk3568_hdmi_mediaplay

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.service.UsbMonitorService
import com.btf.rk3568_hdmi_mediaplay.ui.main.MainScreen
import com.btf.rk3568_hdmi_mediaplay.ui.main.MainViewModel
import com.btf.rk3568_hdmi_mediaplay.ui.settings.SettingsScreen
import com.btf.rk3568_hdmi_mediaplay.ui.theme.Rk3568_hdmi_mediaplayTheme
import com.btf.rk3568_hdmi_mediaplay.util.FilePickerHelper
import com.btf.rk3568_hdmi_mediaplay.util.StringResources

class MainActivity : ComponentActivity() {
    
    private var usbMonitorService: UsbMonitorService? = null
    private var serviceBound = false
    private var localStorageManager: LocalStorageManager? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as? UsbMonitorService.LocalBinder
                usbMonitorService = binder?.getService()
                serviceBound = true
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("服务连接失败")
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            usbMonitorService = null
            serviceBound = false
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startUsbMonitorService()
            showToast("权限已授予")
        } else {
            showToast("部分权限被拒绝，U盘功能可能受限")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化存储管理器
        try {
            localStorageManager = LocalStorageManager(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 保持屏幕常亮
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 请求权限
        requestPermissions()
        
        setContent {
            Rk3568_hdmi_mediaplayTheme {
                // 设置全屏
                LaunchedEffect(Unit) {
                    setupFullscreen()
                }
                
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
                val mainViewModel: MainViewModel = viewModel()
                val settings by mainViewModel.settings.collectAsState()
                
                // 当前选择文件的播放器索引 - 使用 rememberSaveable 保持状态
                var selectingPlayerIndex by rememberSaveable { mutableIntStateOf(-1) }
                
                // 文件选择器
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris: List<Uri> ->
                    if (uris.isNotEmpty() && selectingPlayerIndex >= 0) {
                        val mediaItems = FilePickerHelper.createMediaItemsFromUris(
                            this@MainActivity, 
                            uris
                        )
                        if (mediaItems.isNotEmpty()) {
                            // 检查重复文件并设置
                            mainViewModel.setMediaFilesWithDuplicateCheck(selectingPlayerIndex, mediaItems)
                        } else {
                            showToast("无法加载所选文件")
                        }
                    }
                    selectingPlayerIndex = -1
                }
                
                // 获取缓存大小
                var cacheSizeMB by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    try {
                        cacheSizeMB = localStorageManager?.getCacheSizeMB() ?: 0L
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                // 设置U盘监听回调
                LaunchedEffect(serviceBound) {
                    if (serviceBound) {
                        usbMonitorService?.let { service ->
                            service.onUsbConnected = { path, hasMedia ->
                                mainViewModel.onUsbConnected(path, hasMedia)
                            }
                            service.onUsbDisconnected = {
                                mainViewModel.onUsbDisconnected()
                            }
                        }
                    }
                }
                
                // 根据设置控制屏幕常亮
                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                
                // 初始化语言设置
                LaunchedEffect(settings.language) {
                    StringResources.setLanguage(settings.language)
                }
                
                when (currentScreen) {
                    Screen.Main -> {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                            onSelectFile = { playerIndex ->
                                selectingPlayerIndex = playerIndex
                                try {
                                    filePickerLauncher.launch(
                                        arrayOf("video/*", "image/*")
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    showToast("无法打开文件选择器")
                                }
                            }
                        )
                    }
                    
                    Screen.Settings -> {
                        SettingsScreen(
                            settings = settings,
                            onSettingsChange = { mainViewModel.updateSettings(it) },
                            onClearCache = { 
                                mainViewModel.clearAllCache()
                                cacheSizeMB = 0
                            },
                            onResetSettings = { 
                                mainViewModel.updateSettings(AppSettings())
                            },
                            onBack = { currentScreen = Screen.Main },
                            cacheSizeMB = cacheSizeMB
                        )
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        if (!serviceBound) {
            try {
                Intent(this, UsbMonitorService::class.java).also { intent ->
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("绑定服务失败")
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
                serviceBound = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        setupFullscreen()
    }
    
    // 返回键处理 - 双击退出
    private var lastBackPressTime = 0L
    
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
        val permissions = mutableListOf<String>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    showToast("请授予文件管理权限以读取U盘")
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        showToast("无法打开权限设置")
                    }
                } else {
                    startUsbMonitorService()
                }
            } else {
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
                    startUsbMonitorService()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("权限请求失败")
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
            showToast("启动服务失败")
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
    }
}
