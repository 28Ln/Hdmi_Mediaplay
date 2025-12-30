package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

object UsbUtils {
    
    private const val TAG = "UsbUtils"
    
    // RK3568 U盘挂载路径
    private val USB_MOUNT_PATHS = listOf(
        "/mnt/media_rw",
        "/storage"
    )
    
    // 排除的目录名
    private val EXCLUDED_NAMES = setOf(
        "emulated", "self", "sdcard", "sdcard0", "sdcard1"
    )
    
    /**
     * 获取所有已挂载的U盘路径
     */
    fun getMountedUsbPaths(context: Context): List<File> {
        val usbPaths = mutableListOf<File>()
        
        // 扫描挂载路径
        for (basePath in USB_MOUNT_PATHS) {
            try {
                val baseDir = File(basePath)
                if (baseDir.exists() && baseDir.isDirectory) {
                    baseDir.listFiles()?.forEach { subDir ->
                        try {
                            if (subDir.isDirectory && 
                                subDir.canRead() && 
                                subDir.name !in EXCLUDED_NAMES &&
                                !subDir.name.startsWith(".") &&
                                !subDir.absolutePath.contains("/emulated/")) {
                                
                                // 检查是否有内容（排除空目录）
                                val hasContent = subDir.listFiles()?.isNotEmpty() == true
                                if (hasContent) {
                                    Log.d(TAG, "Found USB: ${subDir.absolutePath}")
                                    usbPaths.add(subDir)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error checking ${subDir.absolutePath}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning $basePath: ${e.message}")
            }
        }
        
        Log.i(TAG, "Total USB paths found: ${usbPaths.size}")
        return usbPaths
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
            Log.d(TAG, "Checking media dir: ${mediaDir.absolutePath}")
            
            if (!mediaDir.exists() || !mediaDir.isDirectory) {
                Log.d(TAG, "Media dir not found: ${mediaDir.absolutePath}")
                return false
            }
            
            // 检查每个播放器目录
            var totalFiles = 0
            FileUtils.PLAYER_FOLDERS.forEachIndexed { index, playerFolder ->
                val playerDir = File(mediaDir, playerFolder)
                if (playerDir.exists() && playerDir.isDirectory) {
                    val files = playerDir.listFiles()?.filter { 
                        it.isFile && it.length() > 0 && MediaTypeUtils.isSupportedMedia(it.name) 
                    } ?: emptyList()
                    if (files.isNotEmpty()) {
                        Log.d(TAG, "Player ${index + 1} ($playerFolder): ${files.size} files")
                        totalFiles += files.size
                    }
                }
            }
            
            Log.i(TAG, "Total media files found: $totalFiles")
            totalFiles > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking media structure: ${e.message}")
            false
        }
    }
}
