package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object UsbUtils {
    
    // RK3568 常见的U盘挂载路径
    private val USB_MOUNT_PATHS = listOf(
        "/mnt/media_rw",
        "/mnt/usb_storage",
        "/storage",
        "/mnt/sdcard"
    )
    
    /**
     * 获取所有已挂载的U盘路径
     */
    fun getMountedUsbPaths(context: Context): List<File> {
        val usbPaths = mutableListOf<File>()
        
        // 方法1: 通过 StorageManager 获取
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumes = storageManager.storageVolumes
            
            for (volume in storageVolumes) {
                if (volume.isRemovable) {
                    // 使用反射获取路径 (Android 11+)
                    try {
                        val getPath = volume.javaClass.getMethod("getPath")
                        val path = getPath.invoke(volume) as? String
                        path?.let { 
                            val file = File(it)
                            if (file.exists() && file.canRead()) {
                                usbPaths.add(file)
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略反射错误
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 方法2: 扫描常见挂载路径
        for (basePath in USB_MOUNT_PATHS) {
            val baseDir = File(basePath)
            if (baseDir.exists() && baseDir.isDirectory) {
                baseDir.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory && subDir.canRead() && !isInternalStorage(subDir)) {
                        if (!usbPaths.any { it.absolutePath == subDir.absolutePath }) {
                            usbPaths.add(subDir)
                        }
                    }
                }
            }
        }
        
        return usbPaths
    }
    
    /**
     * 判断是否为内部存储
     */
    private fun isInternalStorage(file: File): Boolean {
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        return file.absolutePath.startsWith(internalPath) || 
               file.name == "emulated" ||
               file.name == "self"
    }
    
    /**
     * 获取U盘中的媒体扫描目录
     */
    fun getUsbMediaDir(usbPath: File, folderName: String): File {
        return File(usbPath, folderName)
    }
    
    /**
     * 获取U盘中指定播放器的目录
     */
    fun getUsbPlayerDir(usbPath: File, folderName: String, playerIndex: Int): File {
        return File(getUsbMediaDir(usbPath, folderName), FileUtils.PLAYER_FOLDERS[playerIndex])
    }
    
    /**
     * 检查U盘是否包含有效的媒体目录结构
     */
    fun hasValidMediaStructure(usbPath: File, folderName: String): Boolean {
        val mediaDir = getUsbMediaDir(usbPath, folderName)
        if (!mediaDir.exists() || !mediaDir.isDirectory) return false
        
        // 检查是否至少有一个播放器目录包含媒体文件
        return FileUtils.PLAYER_FOLDERS.any { playerFolder ->
            val playerDir = File(mediaDir, playerFolder)
            playerDir.exists() && 
            playerDir.isDirectory && 
            playerDir.listFiles()?.any { MediaTypeUtils.isSupportedMedia(it.name) } == true
        }
    }
    
    /**
     * 获取U盘信息
     */
    data class UsbInfo(
        val path: File,
        val name: String,
        val totalSpace: Long,
        val freeSpace: Long,
        val hasMediaContent: Boolean
    )
    
    fun getUsbInfo(usbPath: File, folderName: String): UsbInfo {
        return UsbInfo(
            path = usbPath,
            name = usbPath.name,
            totalSpace = usbPath.totalSpace,
            freeSpace = usbPath.freeSpace,
            hasMediaContent = hasValidMediaStructure(usbPath, folderName)
        )
    }
}
