package com.btf.rk3568_hdmi_mediaplay

import android.app.Application
import android.os.StrictMode
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.util.DebugLogger
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
        
        // 开发模式下启用严格模式检测
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
        
        Log.i(TAG, "Application initialized")
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
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
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
     * 启用严格模式（仅调试）
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
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
    private fun clearCaches() {
        try {
            // 清理图片缓存
            cacheDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name == "image_cache") {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing caches", e)
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
