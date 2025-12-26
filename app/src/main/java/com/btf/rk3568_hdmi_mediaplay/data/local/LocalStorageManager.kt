package com.btf.rk3568_hdmi_mediaplay.data.local

import android.content.Context
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地存储管理器
 * 负责管理本地缓存和U盘文件的拷贝
 */
class LocalStorageManager(private val context: Context) {
    
    /**
     * 获取指定播放器的本地媒体文件
     */
    fun getLocalMediaFiles(playerIndex: Int): List<MediaItem> {
        val localDir = FileUtils.getPlayerLocalDir(context, playerIndex)
        return FileUtils.scanMediaFiles(localDir, MediaSource.LOCAL)
    }
    
    /**
     * 获取所有播放器的本地媒体文件
     */
    fun getAllLocalMediaFiles(): Map<Int, List<MediaItem>> {
        return (0..3).associateWith { getLocalMediaFiles(it) }
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
        val usbPlayerDir = UsbUtils.getUsbPlayerDir(usbPath, folderName, playerIndex)
        val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex)
        
        if (!usbPlayerDir.exists() || !usbPlayerDir.isDirectory) {
            return@withContext false
        }
        
        // 清空本地目录
        FileUtils.clearDirectory(localPlayerDir)
        
        // 拷贝文件
        FileUtils.copyDirectory(usbPlayerDir, localPlayerDir) { current, total, progress ->
            val overallProgress = ((current - 1) + progress) / total
            onProgress?.invoke(overallProgress)
        }
    }
    
    /**
     * 从U盘拷贝所有播放器的媒体文件
     */
    suspend fun copyAllFromUsb(
        usbPath: File,
        folderName: String,
        onProgress: ((Int, Float) -> Unit)? = null  // (playerIndex, progress)
    ): Boolean = withContext(Dispatchers.IO) {
        var success = true
        
        for (playerIndex in 0..3) {
            val result = copyFromUsb(usbPath, folderName, playerIndex) { progress ->
                onProgress?.invoke(playerIndex, progress)
            }
            if (!result) {
                // 即使某个播放器目录不存在也继续
                // success = false
            }
        }
        
        success
    }
    
    /**
     * 清空指定播放器的本地缓存
     */
    fun clearPlayerCache(playerIndex: Int): Boolean {
        val localDir = FileUtils.getPlayerLocalDir(context, playerIndex)
        return FileUtils.clearDirectory(localDir)
    }
    
    /**
     * 清空所有本地缓存
     */
    fun clearAllCache(): Boolean {
        val mediaDir = FileUtils.getLocalMediaDir(context)
        return FileUtils.clearDirectory(mediaDir)
    }
    
    /**
     * 获取本地缓存大小 (MB)
     */
    fun getCacheSizeMB(): Long {
        val mediaDir = FileUtils.getLocalMediaDir(context)
        return FileUtils.getDirectorySizeMB(mediaDir)
    }
    
    /**
     * 检查本地是否有缓存内容
     */
    fun hasLocalContent(): Boolean {
        return (0..3).any { getLocalMediaFiles(it).isNotEmpty() }
    }
    
    /**
     * 比较U盘和本地内容是否相同
     */
    fun compareWithUsb(usbPath: File, folderName: String, playerIndex: Int): ContentComparison {
        val usbPlayerDir = UsbUtils.getUsbPlayerDir(usbPath, folderName, playerIndex)
        val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex)
        
        val usbFiles = FileUtils.scanMediaFiles(usbPlayerDir, MediaSource.USB)
        val localFiles = FileUtils.scanMediaFiles(localPlayerDir, MediaSource.LOCAL)
        
        val usbFileNames = usbFiles.map { it.name }.toSet()
        val localFileNames = localFiles.map { it.name }.toSet()
        
        return ContentComparison(
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
    }
    
    data class ContentComparison(
        val newFiles: Set<String>,
        val deletedFiles: Set<String>,
        val modifiedFiles: Set<String>,
        val isSame: Boolean
    )
}
