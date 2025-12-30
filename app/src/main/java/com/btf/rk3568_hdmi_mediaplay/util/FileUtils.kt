package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileUtils {
    
    private const val TAG = "FileUtils"
    
    // 播放器目录名
    val PLAYER_FOLDERS = listOf("player1", "player2", "player3", "player4")
    
    // 默认存储目录名
    private const val DEFAULT_MEDIA_FOLDER = "RK3568MediaPlayer"
    
    /**
     * 获取本地缓存根目录
     */
    fun getLocalMediaDir(
        context: Context, 
        storageLocation: StorageLocation = StorageLocation.SDCARD,
        customPath: String = ""
    ): File {
        return try {
            val dir = when {
                customPath.isNotBlank() -> File(customPath)
                storageLocation == StorageLocation.SDCARD -> {
                    File(Environment.getExternalStorageDirectory(), DEFAULT_MEDIA_FOLDER)
                }
                else -> File(context.filesDir, "media")
            }
            
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.d(TAG, "Created dir ${dir.absolutePath}: $created")
            }
            dir
        } catch (e: Exception) {
            Log.e(TAG, "Error getting media dir: ${e.message}")
            val fallback = File(context.filesDir, "media")
            if (!fallback.exists()) fallback.mkdirs()
            fallback
        }
    }
    
    /**
     * 获取本地缓存根目录 (简化版)
     */
    fun getLocalMediaDir(context: Context): File {
        return getLocalMediaDir(context, StorageLocation.SDCARD, "")
    }
    
    /**
     * 获取指定播放器的本地缓存目录
     */
    fun getPlayerLocalDir(
        context: Context, 
        playerIndex: Int,
        storageLocation: StorageLocation = StorageLocation.SDCARD,
        customPath: String = ""
    ): File {
        return try {
            val safeIndex = playerIndex.coerceIn(0, PLAYER_FOLDERS.size - 1)
            val dir = File(getLocalMediaDir(context, storageLocation, customPath), PLAYER_FOLDERS[safeIndex])
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.d(TAG, "Created player dir ${dir.absolutePath}: $created")
            }
            dir
        } catch (e: Exception) {
            Log.e(TAG, "Error getting player dir: ${e.message}")
            getLocalMediaDir(context, storageLocation, customPath)
        }
    }
    
    /**
     * 获取指定播放器的本地缓存目录 (简化版)
     */
    fun getPlayerLocalDir(context: Context, playerIndex: Int): File {
        return getPlayerLocalDir(context, playerIndex, StorageLocation.SDCARD, "")
    }
    
    /**
     * 扫描目录中的媒体文件
     */
    fun scanMediaFiles(directory: File, source: MediaSource): List<MediaItem> {
        return try {
            if (!directory.exists() || !directory.isDirectory) {
                Log.d(TAG, "Directory not found: ${directory.absolutePath}")
                return emptyList()
            }
            
            val files = directory.listFiles()
                ?.filter { it.isFile && it.length() > 0 && MediaTypeUtils.isSupportedMedia(it.name) }
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
            
            Log.d(TAG, "Scanned ${directory.absolutePath}: ${files.size} files")
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning ${directory.absolutePath}: ${e.message}")
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
            if (!source.exists() || !source.isFile) {
                Log.w(TAG, "Source not found: ${source.absolutePath}")
                return@withContext false
            }
            
            // 确保目标目录存在
            destination.parentFile?.let { parent ->
                if (!parent.exists()) {
                    val created = parent.mkdirs()
                    Log.d(TAG, "Created parent dir ${parent.absolutePath}: $created")
                }
            }
            
            val totalSize = source.length()
            if (totalSize == 0L) {
                Log.w(TAG, "Source file is empty: ${source.absolutePath}")
                return@withContext false
            }
            
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
            
            Log.d(TAG, "Copied: ${source.name} -> ${destination.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying ${source.absolutePath}: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 拷贝目录中的媒体文件
     */
    suspend fun copyDirectory(
        sourceDir: File,
        destDir: File,
        onProgress: ((Int, Int, Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                Log.w(TAG, "Source dir not found: ${sourceDir.absolutePath}")
                return@withContext false
            }
            
            // 确保目标目录存在
            if (!destDir.exists()) {
                val created = destDir.mkdirs()
                Log.d(TAG, "Created dest dir ${destDir.absolutePath}: $created")
            }
            
            val files = sourceDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && MediaTypeUtils.isSupportedMedia(it.name) }
                ?: emptyList()
            
            if (files.isEmpty()) {
                Log.d(TAG, "No media files in ${sourceDir.absolutePath}")
                return@withContext true
            }
            
            Log.d(TAG, "Copying ${files.size} files from ${sourceDir.absolutePath}")
            
            var successCount = 0
            files.forEachIndexed { index, file ->
                val destFile = File(destDir, file.name)
                val success = copyFile(file, destFile) { progress ->
                    onProgress?.invoke(index + 1, files.size, progress)
                }
                if (success) successCount++
            }
            
            Log.i(TAG, "Copied $successCount/${files.size} files to ${destDir.absolutePath}")
            successCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error copying directory: ${e.message}")
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
                if (it.isDirectory) {
                    clearDirectory(it)
                    it.delete()
                } else {
                    it.delete()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing ${directory.absolutePath}: ${e.message}")
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
                .map { it.length() }
                .sum() / (1024 * 1024)
        } catch (e: Exception) {
            0
        }
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
    
    /**
     * 检查目录是否可写
     */
    fun isDirectoryWritable(directory: File): Boolean {
        return try {
            if (!directory.exists()) directory.mkdirs()
            directory.exists() && directory.isDirectory && directory.canWrite()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取可用存储空间 (MB)
     */
    fun getAvailableSpaceMB(directory: File): Long {
        return try {
            directory.freeSpace / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }
}
