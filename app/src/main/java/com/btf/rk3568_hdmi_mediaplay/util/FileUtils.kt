package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileUtils {
    
    // 播放器目录名
    val PLAYER_FOLDERS = listOf("player1", "player2", "player3", "player4")
    
    /**
     * 获取本地缓存根目录
     */
    fun getLocalMediaDir(context: Context): File {
        return try {
            val dir = File(context.filesDir, "media")
            if (!dir.exists()) dir.mkdirs()
            dir
        } catch (e: Exception) {
            e.printStackTrace()
            context.filesDir
        }
    }
    
    /**
     * 获取指定播放器的本地缓存目录
     */
    fun getPlayerLocalDir(context: Context, playerIndex: Int): File {
        return try {
            val safeIndex = playerIndex.coerceIn(0, PLAYER_FOLDERS.size - 1)
            val dir = File(getLocalMediaDir(context), PLAYER_FOLDERS[safeIndex])
            if (!dir.exists()) dir.mkdirs()
            dir
        } catch (e: Exception) {
            e.printStackTrace()
            getLocalMediaDir(context)
        }
    }
    
    /**
     * 扫描目录中的媒体文件
     */
    fun scanMediaFiles(directory: File, source: MediaSource): List<MediaItem> {
        return try {
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            
            directory.listFiles()
                ?.filter { 
                    try {
                        it.isFile && MediaTypeUtils.isSupportedMedia(it.name)
                    } catch (e: Exception) {
                        false
                    }
                }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    try {
                        MediaItem(
                            path = file.absolutePath,
                            name = file.name,
                            type = MediaTypeUtils.getMediaType(file),
                            source = source,
                            size = file.length(),
                            lastModified = file.lastModified()
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * 拷贝文件
     */
    suspend fun copyFile(
        source: File,
        destination: File,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!source.exists() || !source.isFile) return@withContext false
            
            destination.parentFile?.mkdirs()
            
            val totalSize = source.length()
            if (totalSize == 0L) return@withContext false
            
            var copiedSize = 0L
            
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedSize += bytesRead
                        try {
                            onProgress?.invoke(copiedSize.toFloat() / totalSize)
                        } catch (e: Exception) {
                            // 忽略进度回调错误
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 拷贝目录
     */
    suspend fun copyDirectory(
        sourceDir: File,
        destDir: File,
        onProgress: ((Int, Int, Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceDir.exists() || !sourceDir.isDirectory) return@withContext false
            
            destDir.mkdirs()
            
            val files = sourceDir.listFiles()
                ?.filter { 
                    try {
                        it.isFile && MediaTypeUtils.isSupportedMedia(it.name)
                    } catch (e: Exception) {
                        false
                    }
                }
                ?: return@withContext false
            
            if (files.isEmpty()) return@withContext true
            
            files.forEachIndexed { index, file ->
                try {
                    val destFile = File(destDir, file.name)
                    copyFile(file, destFile) { progress ->
                        try {
                            onProgress?.invoke(index + 1, files.size, progress)
                        } catch (e: Exception) {
                            // 忽略进度回调错误
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 清空目录
     */
    fun clearDirectory(directory: File): Boolean {
        return try {
            if (!directory.exists()) return true
            directory.listFiles()?.forEach { 
                try {
                    it.delete()
                } catch (e: Exception) {
                    // 忽略单个文件删除错误
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 获取目录大小 (MB)
     */
    fun getDirectorySizeMB(directory: File): Long {
        return try {
            if (!directory.exists()) return 0
            directory.walkTopDown()
                .filter { it.isFile }
                .map { 
                    try {
                        it.length()
                    } catch (e: Exception) {
                        0L
                    }
                }
                .sum() / (1024 * 1024)
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return try {
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            }
        } catch (e: Exception) {
            "$bytes B"
        }
    }
}
