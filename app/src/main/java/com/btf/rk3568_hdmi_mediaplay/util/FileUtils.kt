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
        val dir = File(context.filesDir, "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    /**
     * 获取指定播放器的本地缓存目录
     */
    fun getPlayerLocalDir(context: Context, playerIndex: Int): File {
        val dir = File(getLocalMediaDir(context), PLAYER_FOLDERS[playerIndex])
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    /**
     * 扫描目录中的媒体文件
     */
    fun scanMediaFiles(directory: File, source: MediaSource): List<MediaItem> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        
        return directory.listFiles()
            ?.filter { it.isFile && MediaTypeUtils.isSupportedMedia(it.name) }
            ?.sortedBy { it.name }
            ?.map { file ->
                MediaItem(
                    path = file.absolutePath,
                    name = file.name,
                    type = MediaTypeUtils.getMediaType(file),
                    source = source,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            } ?: emptyList()
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
            destination.parentFile?.mkdirs()
            
            val totalSize = source.length()
            var copiedSize = 0L
            
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedSize += bytesRead
                        onProgress?.invoke(copiedSize.toFloat() / totalSize)
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
        onProgress: ((Int, Int, Float) -> Unit)? = null  // (currentFile, totalFiles, fileProgress)
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceDir.exists() || !sourceDir.isDirectory) return@withContext false
            
            destDir.mkdirs()
            
            val files = sourceDir.listFiles()
                ?.filter { it.isFile && MediaTypeUtils.isSupportedMedia(it.name) }
                ?: return@withContext false
            
            files.forEachIndexed { index, file ->
                val destFile = File(destDir, file.name)
                copyFile(file, destFile) { progress ->
                    onProgress?.invoke(index + 1, files.size, progress)
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
        if (!directory.exists()) return true
        return directory.listFiles()?.all { it.delete() } ?: true
    }
    
    /**
     * 获取目录大小 (MB)
     */
    fun getDirectorySizeMB(directory: File): Long {
        if (!directory.exists()) return 0
        return directory.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024 * 1024)
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
