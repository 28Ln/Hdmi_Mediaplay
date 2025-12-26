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
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            storageManager?.storageVolumes?.forEach { volume ->
                try {
                    if (volume.isRemovable) {
                        // 使用反射获取路径 (Android 11+)
                        val getPath = volume.javaClass.getMethod("getPath")
                        val path = getPath.invoke(volume) as? String
                        path?.let { 
                            val file = File(it)
                            if (file.exists() && file.canRead()) {
                                usbPaths.add(file)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 忽略单个卷的错误
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 方法2: 扫描常见挂载路径
        for (basePath in USB_MOUNT_PATHS) {
            try {
                val baseDir = File(basePath)
                if (baseDir.exists() && baseDir.isDirectory) {
                    baseDir.listFiles()?.forEach { subDir ->
                        try {
                            if (subDir.isDirectory && subDir.canRead() && !isInternalStorage(subDir)) {
                                if (!usbPaths.any { it.absolutePath == subDir.absolutePath }) {
                                    usbPaths.add(subDir)
                                }
                            }
                        } catch (e: Exception) {
                            // 忽略单个目录的错误
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略单个路径的错误
            }
        }
        
        return usbPaths
    }
    
    /**
     * 判断是否为内部存储
     */
    private fun isInternalStorage(file: File): Boolean {
        return try {
            val internalPath = Environment.getExternalStorageDirectory().absolutePath
            file.absolutePath.startsWith(internalPath) || 
                   file.name == "emulated" ||
                   file.name == "self"
        } catch (e: Exception) {
            false
        }
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
        val safeIndex = playerIndex.coerceIn(0, FileUtils.PLAYER_FOLDERS.size - 1)
        return File(getUsbMediaDir(usbPath, folderName), FileUtils.PLAYER_FOLDERS[safeIndex])
    }
    
    /**
     * 检查U盘是否包含有效的媒体目录结构
     */
    fun hasValidMediaStructure(usbPath: File, folderName: String): Boolean {
        return try {
            val mediaDir = getUsbMediaDir(usbPath, folderName)
            if (!mediaDir.exists() || !mediaDir.isDirectory) return false
            
            // 检查是否至少有一个播放器目录包含媒体文件
            FileUtils.PLAYER_FOLDERS.any { playerFolder ->
                try {
                    val playerDir = File(mediaDir, playerFolder)
                    playerDir.exists() && 
                    playerDir.isDirectory && 
                    playerDir.listFiles()?.any { MediaTypeUtils.isSupportedMedia(it.name) } == true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
        return try {
            UsbInfo(
                path = usbPath,
                name = usbPath.name,
                totalSpace = usbPath.totalSpace,
                freeSpace = usbPath.freeSpace,
                hasMediaContent = hasValidMediaStructure(usbPath, folderName)
            )
        } catch (e: Exception) {
            UsbInfo(
                path = usbPath,
                name = usbPath.name,
                totalSpace = 0,
                freeSpace = 0,
                hasMediaContent = false
            )
        }
    }
}
