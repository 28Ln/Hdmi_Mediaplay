package com.btf.rk3568_hdmi_mediaplay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.service.UsbMonitorService
import com.btf.rk3568_hdmi_mediaplay.ui.main.MainScreen
import com.btf.rk3568_hdmi_mediaplay.ui.main.MainViewModel
import com.btf.rk3568_hdmi_mediaplay.ui.settings.SettingsScreen
import com.btf.rk3568_hdmi_mediaplay.ui.theme.Rk3568_hdmi_mediaplayTheme

class MainActivity : ComponentActivity() {
    
    private var usbMonitorService: UsbMonitorService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UsbMonitorService.LocalBinder
            usbMonitorService = binder.getService()
            serviceBound = true
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            usbMonitorService = null
            serviceBound = false
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 权限请求结果处理
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startUsbMonitorService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置全屏
        setupFullscreen()
        
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 请求权限
        requestPermissions()
        
        setContent {
            Rk3568_hdmi_mediaplayTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
                val mainViewModel: MainViewModel = viewModel()
                val settings by mainViewModel.settings.collectAsState()
                
                // 设置U盘监听回调
                LaunchedEffect(serviceBound) {
                    usbMonitorService?.let { service ->
                        service.onUsbConnected = { path, hasMedia ->
                            mainViewModel.onUsbConnected(path, hasMedia)
                        }
                        service.onUsbDisconnected = {
                            mainViewModel.onUsbDisconnected()
                        }
                    }
                }
                
                when (currentScreen) {
                    Screen.Main -> {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToSettings = { currentScreen = Screen.Settings }
                        )
                    }
                    
                    Screen.Settings -> {
                        SettingsScreen(
                            settings = settings,
                            onSettingsChange = { mainViewModel.updateSettings(it) },
                            onClearCache = { mainViewModel.clearAllCache() },
                            onResetSettings = { 
                                mainViewModel.updateSettings(com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings())
                            },
                            onBack = { currentScreen = Screen.Main }
                        )
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        // 绑定U盘监听服务
        Intent(this, UsbMonitorService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        // 存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
        }
        
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startUsbMonitorService()
        }
    }
    
    private fun startUsbMonitorService() {
        Intent(this, UsbMonitorService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
    
    sealed class Screen {
        object Main : Screen()
        object Settings : Screen()
    }
}
