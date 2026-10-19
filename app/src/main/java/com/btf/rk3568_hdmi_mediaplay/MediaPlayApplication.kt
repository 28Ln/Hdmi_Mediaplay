package com.btf.rk3568_hdmi_mediaplay

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.btf.rk3568_hdmi_mediaplay.ui.customerUiMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 应用程序入口
 * 负责全局初始化、异常处理、资源管理
 */
class MediaPlayApplication : Application(), ImageLoaderFactory {
    
    companion object {
        private const val TAG = "MediaPlayApp"
        
        @Volatile
        private var instance: MediaPlayApplication? = null
        
        fun getInstance(): MediaPlayApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
    
    // 全局协程作用域，带异常处理
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + 
            Dispatchers.Default + 
            globalExceptionHandler
        )
    }
    
    // 全局异常处理器
    private val globalExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception", throwable)
        handleException(throwable)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 设置全局未捕获异常处理
        setupUncaughtExceptionHandler()
        
        // 初始化功能管理器。
        // 用 customerUiMode 而非裸 IS_DEBUG_BUILD，使「客户版」与「开发版」的判定
        // 与 UI 层（ui/UiMode.kt）完全一致——包括 FORCE_CUSTOMER_UI_PREVIEW 预览开关。
        // 否则 Preview 开启时，UI 按客户版渲染、但默认功能矩阵仍是 Debug 的，两者不一致。
        FeatureManager.init(!customerUiMode)

        Log.i(TAG, "Application initialized, isDebug=${BuildConfig.IS_DEBUG_BUILD}, buildType=${if (BuildConfig.IS_DEBUG_BUILD) "DEBUG" else "RELEASE"}, customerUiMode=$customerUiMode")
    }
    
    /**
     * 配置 Coil 图片加载器 - 优化内存和磁盘缓存
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10) // 只使用10%可用内存 (4路播放需要更多内存给视频)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50MB 磁盘缓存
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }
    
    /**
     * 设置全局未捕获异常处理器
     */
    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            
            // 记录崩溃日志
            saveCrashLog(throwable)
            
            // 调用默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    /**
     * 处理异常
     */
    private fun handleException(throwable: Throwable) {
        when (throwable) {
            is OutOfMemoryError -> {
                Log.e(TAG, "OutOfMemoryError - clearing caches")
                clearCaches()
            }
            is SecurityException -> {
                Log.e(TAG, "SecurityException - permission issue")
            }
            else -> {
                Log.e(TAG, "Unhandled exception: ${throwable.message}")
            }
        }
    }
    
    /**
     * 清理缓存
     */
    /**
     * 释放内存压力下的缓存。
     *
     * ## 为什么不能直接删目录（2026-10-20 修复）
     *
     * 旧实现 `cacheDir/image_cache` 直接 `deleteRecursively()` —— 而那个目录
     * 正是 [newImageLoader] 配置给 Coil 的 `DiskCache` 正在使用的目录。
     * 在磁盘缓存**未关闭**的情况下把目录（含 journal）删掉，会导致：
     * - Coil 后续读写命中缺失的 journal，所有图片请求失败；
     * - 直到进程重启才恢复。
     *
     * 正确做法是通过 Coil 自己的 API 清理：它会先关闭/重建 journal，
     * 保证缓存仍可用。内存缓存同理（`memoryCache?.clear()`）。
     */
    private fun clearCaches() {
        try {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear memory cache: ${e.message}")
        }
        try {
            coil.Coil.imageLoader(this).diskCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear disk cache: ${e.message}")
        }
    }
    
    /**
     * 保存崩溃日志
     */
    private fun saveCrashLog(throwable: Throwable) {
        try {
            val crashDir = File(filesDir, "crash_logs")
            if (!crashDir.exists()) crashDir.mkdirs()
            
            val logFile = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
            logFile.writeText(buildString {
                appendLine("Time: ${java.util.Date()}")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine("Stack trace:")
                appendLine(throwable.stackTraceToString())
            })
            
            // 只保留最近10个崩溃日志
            crashDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(10)
                ?.forEach { it.delete() }
                
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory warning")
        clearCaches()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            Log.w(TAG, "Trim memory level: $level")
            clearCaches()
        }
    }
}
