package com.btf.rk3568_hdmi_mediaplay.data.local

import android.content.Context
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地存储管理器
 */
class LocalStorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalStorageManager"
    }
    
    private var storageLocation: StorageLocation = StorageLocation.SDCARD
    private var customPath: String = ""
    
    fun updateStorageSettings(location: StorageLocation, path: String = "") {
        storageLocation = location
        customPath = path
        Log.d(TAG, "Storage settings updated: $location, path=$path")
    }
    
    /**
     * 获取指定播放器的本地媒体文件
     */
    fun getLocalMediaFiles(playerIndex: Int): List<MediaItem> {
        return try {
            val localDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
            Log.d(TAG, "Getting files for player $playerIndex from: ${localDir.absolutePath}")
            val files = FileUtils.scanMediaFiles(localDir, MediaSource.LOCAL)
            Log.d(TAG, "Player $playerIndex: ${files.size} files")
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error getting files for player $playerIndex: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * 获取所有播放器的本地媒体文件
     */
    fun getAllLocalMediaFiles(): Map<Int, List<MediaItem>> {
        val result = mutableMapOf<Int, List<MediaItem>>()
        for (i in 0..3) {
            result[i] = getLocalMediaFiles(i)
        }
        val total = result.values.sumOf { it.size }
        Log.i(TAG, "Total local files: $total")
        return result
    }
    
    /**
     * 从U盘拷贝媒体文件到本地
     */
    suspend fun copyFromUsb(
        usbPath: File,
        folderName: String,
        playerIndex: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val usbPlayerDir = UsbUtils.getUsbPlayerDir(usbPath, folderName, playerIndex)
            val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
            
            Log.i(TAG, "Copying player $playerIndex:")
            Log.i(TAG, "  From: ${usbPlayerDir.absolutePath}")
            Log.i(TAG, "  To: ${localPlayerDir.absolutePath}")
            
            if (!usbPlayerDir.exists() || !usbPlayerDir.isDirectory) {
                Log.w(TAG, "USB player dir not found: ${usbPlayerDir.absolutePath}")
                return@withContext false
            }
            
            // 检查U盘目录是否有文件
            val usbFiles = usbPlayerDir.listFiles()?.filter { 
                it.isFile && it.length() > 0 
            } ?: emptyList()
            
            if (usbFiles.isEmpty()) {
                Log.w(TAG, "No files in USB player $playerIndex")
                return@withContext false
            }
            
            Log.d(TAG, "Found ${usbFiles.size} files in USB player $playerIndex")
            
            // 清空本地目录
            FileUtils.clearDirectory(localPlayerDir)
            
            // 确保目录存在
            if (!localPlayerDir.exists()) {
                localPlayerDir.mkdirs()
            }
            
            // 拷贝文件
            val success = FileUtils.copyDirectory(usbPlayerDir, localPlayerDir) { current, total, progress ->
                val overallProgress = ((current - 1) + progress) / total
                onProgress?.invoke(overallProgress)
            }
            
            // 验证结果
            val copiedFiles = localPlayerDir.listFiles()?.filter { it.isFile } ?: emptyList()
            Log.i(TAG, "Player $playerIndex copy result: ${copiedFiles.size} files")
            
            copiedFiles.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying player $playerIndex: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 从U盘拷贝所有播放器的媒体文件
     */
    suspend fun copyAllFromUsb(
        usbPath: File,
        folderName: String,
        onProgress: ((Int, Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false
        
        Log.i(TAG, "Starting copy from USB: ${usbPath.absolutePath}/$folderName")
        
        for (playerIndex in 0..3) {
            try {
                val result = copyFromUsb(usbPath, folderName, playerIndex) { progress ->
                    onProgress?.invoke(playerIndex, progress)
                }
                if (result) {
                    anySuccess = true
                    Log.i(TAG, "Player $playerIndex: copy success")
                } else {
                    Log.w(TAG, "Player $playerIndex: no files or copy failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying player $playerIndex: ${e.message}")
            }
        }
        
        Log.i(TAG, "Copy all completed, anySuccess=$anySuccess")
        anySuccess
    }
    
    fun clearPlayerCache(playerIndex: Int): Boolean {
        return try {
            val localDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
            FileUtils.clearDirectory(localDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing player $playerIndex cache: ${e.message}")
            false
        }
    }
    
    fun clearAllCache(): Boolean {
        return try {
            val mediaDir = FileUtils.getLocalMediaDir(context, storageLocation, customPath)
            FileUtils.clearDirectory(mediaDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all cache: ${e.message}")
            false
        }
    }
    
    fun getCacheSizeMB(): Long {
        return try {
            val mediaDir = FileUtils.getLocalMediaDir(context, storageLocation, customPath)
            FileUtils.getDirectorySizeMB(mediaDir)
        } catch (e: Exception) {
            0L
        }
    }
    
    fun hasLocalContent(): Boolean {
        return try {
            (0..3).any { getLocalMediaFiles(it).isNotEmpty() }
        } catch (e: Exception) {
            false
        }
    }
    
    fun getCurrentStoragePath(): String {
        return try {
            FileUtils.getLocalMediaDir(context, storageLocation, customPath).absolutePath
        } catch (e: Exception) {
            ""
        }
    }
    
    fun isStorageAvailable(): Boolean {
        return try {
            val dir = FileUtils.getLocalMediaDir(context, storageLocation, customPath)
            FileUtils.isDirectoryWritable(dir)
        } catch (e: Exception) {
            false
        }
    }
    
    fun getAvailableSpaceMB(): Long {
        return try {
            val dir = FileUtils.getLocalMediaDir(context, storageLocation, customPath)
            FileUtils.getAvailableSpaceMB(dir)
        } catch (e: Exception) {
            0L
        }
    }
}
