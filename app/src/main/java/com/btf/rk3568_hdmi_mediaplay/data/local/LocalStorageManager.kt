package com.btf.rk3568_hdmi_mediaplay.data.local

import android.content.Context
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 本地存储管理器
 * 负责管理本地缓存和U盘文件的拷贝
 * 优化: 异常处理、协程取消支持、日志记录
 */
class LocalStorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalStorageManager"
    }
    
    /**
     * 获取指定播放器的本地媒体文件
     */
    fun getLocalMediaFiles(playerIndex: Int): List<MediaItem> {
        return try {
            val localDir = FileUtils.getPlayerLocalDir(context, playerIndex)
            FileUtils.scanMediaFiles(localDir, MediaSource.LOCAL)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local media files for player $playerIndex", e)
            emptyList()
        }
    }
    
    /**
     * 获取所有播放器的本地媒体文件
     */
    fun getAllLocalMediaFiles(): Map<Int, List<MediaItem>> {
        return try {
            (0..3).associateWith { getLocalMediaFiles(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all local media files", e)
            emptyMap()
        }
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
            val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex)
            
            if (!usbPlayerDir.exists() || !usbPlayerDir.isDirectory) {
                Log.w(TAG, "USB player dir not found: ${usbPlayerDir.absolutePath}")
                return@withContext false
            }
            
            // 检查协程是否被取消
            coroutineContext.ensureActive()
            
            // 清空本地目录
            if (!FileUtils.clearDirectory(localPlayerDir)) {
                Log.w(TAG, "Failed to clear local directory: ${localPlayerDir.absolutePath}")
            }
            
            // 拷贝文件
            FileUtils.copyDirectory(usbPlayerDir, localPlayerDir) { current, total, progress ->
                coroutineContext.ensureActive()
                val overallProgress = ((current - 1) + progress) / total
                onProgress?.invoke(overallProgress)
            }
            
            Log.i(TAG, "Copy completed for player $playerIndex")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy from USB for player $playerIndex", e)
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
        
        for (playerIndex in 0..3) {
            try {
                coroutineContext.ensureActive()
                
                val result = copyFromUsb(usbPath, folderName, playerIndex) { progress ->
                    onProgress?.invoke(playerIndex, progress)
                }
                
                if (result) {
                    anySuccess = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying player $playerIndex", e)
            }
        }
        
        anySuccess
    }
    
    /**
     * 清空指定播放器的本地缓存
     */
    fun clearPlayerCache(playerIndex: Int): Boolean {
        return try {
            val localDir = FileUtils.getPlayerLocalDir(context, playerIndex)
            FileUtils.clearDirectory(localDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear player cache for player $playerIndex", e)
            false
        }
    }
    
    /**
     * 清空所有本地缓存
     */
    fun clearAllCache(): Boolean {
        return try {
            val mediaDir = FileUtils.getLocalMediaDir(context)
            FileUtils.clearDirectory(mediaDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all cache", e)
            false
        }
    }
    
    /**
     * 获取本地缓存大小 (MB)
     */
    fun getCacheSizeMB(): Long {
        return try {
            val mediaDir = FileUtils.getLocalMediaDir(context)
            FileUtils.getDirectorySizeMB(mediaDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cache size", e)
            0L
        }
    }
    
    /**
     * 检查本地是否有缓存内容
     */
    fun hasLocalContent(): Boolean {
        return try {
            (0..3).any { getLocalMediaFiles(it).isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check local content", e)
            false
        }
    }
    
    /**
     * 比较U盘和本地内容是否相同
     */
    fun compareWithUsb(usbPath: File, folderName: String, playerIndex: Int): ContentComparison {
        return try {
            val usbPlayerDir = UsbUtils.getUsbPlayerDir(usbPath, folderName, playerIndex)
            val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex)
            
            val usbFiles = FileUtils.scanMediaFiles(usbPlayerDir, MediaSource.USB)
            val localFiles = FileUtils.scanMediaFiles(localPlayerDir, MediaSource.LOCAL)
            
            val usbFileNames = usbFiles.map { it.name }.toSet()
            val localFileNames = localFiles.map { it.name }.toSet()
            
            ContentComparison(
                newFiles = usbFileNames - localFileNames,
                deletedFiles = localFileNames - usbFileNames,
                modifiedFiles = usbFiles.filter { usbFile ->
                    localFiles.find { it.name == usbFile.name }?.let { localFile ->
                        usbFile.size != localFile.size || usbFile.lastModified > localFile.lastModified
                    } ?: false
                }.map { it.name }.toSet(),
                isSame = usbFileNames == localFileNames && 
                         usbFiles.all { usbFile ->
                             localFiles.find { it.name == usbFile.name }?.let { localFile ->
                                 usbFile.size == localFile.size
                             } ?: false
                         }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compare with USB", e)
            ContentComparison(
                newFiles = emptySet(),
                deletedFiles = emptySet(),
                modifiedFiles = emptySet(),
                isSame = false
            )
        }
    }
    
    data class ContentComparison(
        val newFiles: Set<String>,
        val deletedFiles: Set<String>,
        val modifiedFiles: Set<String>,
        val isSame: Boolean
    )
}
