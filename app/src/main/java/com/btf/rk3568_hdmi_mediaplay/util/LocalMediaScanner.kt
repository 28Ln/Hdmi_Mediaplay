package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 本地媒体扫描器
 * 优化: 限制扫描范围、超时保护、协程取消支持
 */
object LocalMediaScanner {
    
    private const val TAG = "LocalMediaScanner"
    
    // 只扫描这些常见媒体目录
    private val SCAN_DIRS = listOf(
        "DCIM",
        "Pictures", 
        "Movies",
        "Download",
        "Downloads",
        "Video",
        "Videos",
        "Media"
    )
    
    // 最大扫描文件数
    private const val MAX_FILES = 100
    
    // 扫描超时时间 (毫秒)
    private const val SCAN_TIMEOUT = 8000L
    
    // 最大扫描深度
    private const val MAX_DEPTH = 2
    
    /**
     * 扫描外部存储中的媒体文件
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun scanExternalStorage(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaItems = mutableListOf<MediaItem>()
        
        val result = withTimeoutOrNull(SCAN_TIMEOUT) {
            try {
                val externalStorage = Environment.getExternalStorageDirectory()
                if (externalStorage.exists() && externalStorage.canRead()) {
                    for (dirName in SCAN_DIRS) {
                        coroutineContext.ensureActive()
                        if (mediaItems.size >= MAX_FILES) break
                        
                        val targetDir = File(externalStorage, dirName)
                        if (targetDir.exists() && targetDir.canRead()) {
                            scanDirectoryFast(targetDir, mediaItems)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Scan error", e)
                false
            }
        }
        
        if (result == null) {
            Log.w(TAG, "Scan timeout, returning partial results")
        }
        
        Log.i(TAG, "Scan completed, found ${mediaItems.size} files")
        
        mediaItems.distinctBy { it.path }
            .sortedByDescending { it.lastModified }
            .take(MAX_FILES)
    }
    
    /**
     * 快速扫描目录
     */
    private suspend fun scanDirectoryFast(
        directory: File, 
        mediaItems: MutableList<MediaItem>,
        currentDepth: Int = 0
    ) {
        if (currentDepth > MAX_DEPTH || mediaItems.size >= MAX_FILES) return
        if (!directory.exists() || !directory.canRead()) return
        
        try {
            coroutineContext.ensureActive()
            
            val files = directory.listFiles() ?: return
            
            // 先处理文件
            for (file in files) {
                if (mediaItems.size >= MAX_FILES) return
                coroutineContext.ensureActive()
                
                try {
                    if (file.isFile && MediaTypeUtils.isSupportedMedia(file.name)) {
                        mediaItems.add(
                            MediaItem(
                                path = file.absolutePath,
                                name = file.name,
                                type = MediaTypeUtils.getMediaType(file),
                                source = MediaSource.LOCAL,
                                size = file.length(),
                                lastModified = file.lastModified()
                            )
                        )
                    }
                } catch (e: Exception) {
                    // 忽略单个文件错误
                }
            }
            
            // 再递归子目录
            for (file in files) {
                if (mediaItems.size >= MAX_FILES) return
                coroutineContext.ensureActive()
                
                if (file.isDirectory && !file.name.startsWith(".")) {
                    scanDirectoryFast(file, mediaItems, currentDepth + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning ${directory.absolutePath}", e)
        }
    }
}
