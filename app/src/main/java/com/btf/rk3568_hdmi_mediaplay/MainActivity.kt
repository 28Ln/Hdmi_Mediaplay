package com.btf.rk3568_hdmi_mediaplay

import android.Manifest
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
import android.util.Log
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
import com.btf.rk3568_hdmi_mediaplay.ui.split.ImageSplitScreen
import com.btf.rk3568_hdmi_mediaplay.ui.split.ImageSplitViewModel
import com.btf.rk3568_hdmi_mediaplay.ui.theme.Rk3568_hdmi_mediaplayTheme
import com.btf.rk3568_hdmi_mediaplay.util.FilePickerHelper
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import java.io.File

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var usbMonitorService: UsbMonitorService? = null
    private var serviceBound = false
    private var localStorageManager: LocalStorageManager? = null
    
    // 保存 ViewModel 引用，用于服务回调
    private var mainViewModelRef: MainViewModel? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Service connected")
            try {
                val binder = service as? UsbMonitorService.LocalBinder
                usbMonitorService = binder?.getService()
                serviceBound = true
                
                // 立即设置回调
                setupServiceCallbacks()
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
            service.onUsbConnected = { path: File, hasMedia: Boolean ->
                Log.i(TAG, "USB connected callback received: $path, hasMedia=$hasMedia")
                mainViewModelRef?.onUsbConnected(path, hasMedia)
            }
            service.onUsbDisconnected = {
                Log.i(TAG, "USB disconnected callback received")
                mainViewModelRef?.onUsbDisconnected()
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                
                LaunchedEffect(settings.language) {
                    StringResources.setLanguage(settings.language)
                }
                
                when (currentScreen) {
                    Screen.Main -> {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                            onNavigateToImageSplit = { currentScreen = Screen.ImageSplit },
                            onSelectFile = { playerIndex ->
                                selectingPlayerIndex = playerIndex
                                try {
                                    filePickerLauncher.launch(arrayOf("video/*", "image/*"))
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
                            onSettingsChange = { newSettings -> mainViewModel.updateSettings(newSettings) },
                            onClearCache = { 
                                mainViewModel.clearAllCache()
                                cacheSizeMB = 0
                            },
                            onResetSettings = { 
                                mainViewModel.updateSettings(AppSettings())
                            },
                            onBack = { currentScreen = Screen.Main },
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
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart, serviceBound=$serviceBound")
        if (!serviceBound) {
            try {
                Intent(this, UsbMonitorService::class.java).also { intent ->
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        if (serviceBound) {
            try {
                usbMonitorService?.onUsbConnected = null
                usbMonitorService?.onUsbDisconnected = null
                unbindService(serviceConnection)
                serviceBound = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
