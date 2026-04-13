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

    private data class PlayerCopyResult(
        val playerIndex: Int,
        val sourceFileCount: Int,
        val copiedFileCount: Int,
        val success: Boolean,
        val error: String? = null
    )
    
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
    private suspend fun copyPlayerFromUsb(
        usbPath: File,
        folderName: String,
        playerIndex: Int,
        onProgress: ((Float) -> Unit)? = null
    ): PlayerCopyResult = withContext(Dispatchers.IO) {
        try {
            val usbPlayerDir = UsbUtils.getUsbPlayerDir(usbPath, folderName, playerIndex)
            val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
            val parentDir = localPlayerDir.parentFile
            
            Log.i(TAG, "Copying player $playerIndex:")
            Log.i(TAG, "  From: ${usbPlayerDir.absolutePath}")
            Log.i(TAG, "  To: ${localPlayerDir.absolutePath}")
            
            if (!usbPlayerDir.exists() || !usbPlayerDir.isDirectory) {
                Log.w(TAG, "USB player dir not found: ${usbPlayerDir.absolutePath}")
                return@withContext PlayerCopyResult(playerIndex, 0, 0, false, "source_dir_missing")
            }
            
            // 检查U盘目录是否有文件
            val usbFiles = usbPlayerDir.listFiles()?.filter { 
                it.isFile && it.length() > 0 && com.btf.rk3568_hdmi_mediaplay.util.MediaTypeUtils.isSupportedMedia(it.name)
            } ?: emptyList()
            
            if (usbFiles.isEmpty()) {
                Log.w(TAG, "No files in USB player $playerIndex")
                return@withContext PlayerCopyResult(playerIndex, 0, 0, false, "source_dir_empty")
            }
            
            Log.d(TAG, "Found ${usbFiles.size} files in USB player $playerIndex")
            if (parentDir == null) {
                return@withContext PlayerCopyResult(playerIndex, usbFiles.size, 0, false, "target_parent_missing")
            }

            val stagingDir = File(parentDir, ".${localPlayerDir.name}_staging")
            val backupDir = File(parentDir, ".${localPlayerDir.name}_backup")

            stagingDir.deleteRecursively()
            backupDir.deleteRecursively()
            stagingDir.mkdirs()

            // 先拷贝到 staging，避免直接破坏当前缓存
            val stagingCopied = FileUtils.copyDirectory(usbPlayerDir, stagingDir) { current, total, progress ->
                val overallProgress = ((current - 1) + progress) / total
                onProgress?.invoke(overallProgress)
            }

            val stagedFiles = stagingDir.listFiles()?.filter { file ->
                file.isFile && file.length() > 0 && com.btf.rk3568_hdmi_mediaplay.util.MediaTypeUtils.isSupportedMedia(file.name)
            } ?: emptyList()

            val sourceTotalBytes = usbFiles.sumOf { it.length() }
            val stagedTotalBytes = stagedFiles.sumOf { it.length() }

            if (!stagingCopied || stagedFiles.size != usbFiles.size || stagedTotalBytes != sourceTotalBytes) {
                Log.e(
                    TAG,
                    "Staging verification failed for player $playerIndex: stagingCopied=$stagingCopied, sourceCount=${usbFiles.size}, stagedCount=${stagedFiles.size}, sourceBytes=$sourceTotalBytes, stagedBytes=$stagedTotalBytes"
                )
                stagingDir.deleteRecursively()
                return@withContext PlayerCopyResult(playerIndex, usbFiles.size, stagedFiles.size, false, "staging_verification_failed")
            }

            val hadExistingContent = localPlayerDir.exists() && (localPlayerDir.listFiles()?.isNotEmpty() == true)
            var backupReady = false

            if (hadExistingContent) {
                backupReady = localPlayerDir.renameTo(backupDir)
                if (!backupReady) {
                    Log.e(TAG, "Failed to move existing player cache to backup: ${localPlayerDir.absolutePath}")
                    stagingDir.deleteRecursively()
                    return@withContext PlayerCopyResult(playerIndex, usbFiles.size, stagedFiles.size, false, "backup_prepare_failed")
                }
            } else if (localPlayerDir.exists()) {
                localPlayerDir.deleteRecursively()
            }

            val swapSuccess = if (stagingDir.renameTo(localPlayerDir)) {
                true
            } else {
                localPlayerDir.mkdirs()
                FileUtils.copyDirectory(stagingDir, localPlayerDir)
            }

            if (!swapSuccess) {
                Log.e(TAG, "Failed to promote staging dir for player $playerIndex")
                localPlayerDir.deleteRecursively()
                if (backupReady) {
                    backupDir.renameTo(localPlayerDir)
                }
                stagingDir.deleteRecursively()
                return@withContext PlayerCopyResult(playerIndex, usbFiles.size, stagedFiles.size, false, "promote_staging_failed")
            }

            val finalFiles = localPlayerDir.listFiles()?.filter { file ->
                file.isFile && file.length() > 0 && com.btf.rk3568_hdmi_mediaplay.util.MediaTypeUtils.isSupportedMedia(file.name)
            } ?: emptyList()
            val finalTotalBytes = finalFiles.sumOf { it.length() }

            if (finalFiles.size != usbFiles.size || finalTotalBytes != sourceTotalBytes) {
                Log.e(
                    TAG,
                    "Final verification failed for player $playerIndex: sourceCount=${usbFiles.size}, finalCount=${finalFiles.size}, sourceBytes=$sourceTotalBytes, finalBytes=$finalTotalBytes"
                )
                localPlayerDir.deleteRecursively()
                if (backupReady) {
                    backupDir.renameTo(localPlayerDir)
                }
                return@withContext PlayerCopyResult(playerIndex, usbFiles.size, finalFiles.size, false, "final_verification_failed")
            }

            backupDir.deleteRecursively()
            stagingDir.deleteRecursively()
            Log.i(TAG, "Player $playerIndex copy result: ${finalFiles.size} files")

            PlayerCopyResult(playerIndex, usbFiles.size, finalFiles.size, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying player $playerIndex: ${e.message}")
            e.printStackTrace()
            PlayerCopyResult(playerIndex, 0, 0, false, e.message)
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
        var hasSourceContent = false
        var anySuccess = false
        var failedCopies = 0
        
        Log.i(TAG, "Starting copy from USB: ${usbPath.absolutePath}/$folderName")
        
        for (playerIndex in 0..3) {
            try {
                val result = copyPlayerFromUsb(usbPath, folderName, playerIndex) { progress ->
                    onProgress?.invoke(playerIndex, progress)
                }
                if (result.sourceFileCount > 0) {
                    hasSourceContent = true
                }
                if (result.success) {
                    anySuccess = true
                    Log.i(TAG, "Player $playerIndex: copy success (${result.copiedFileCount}/${result.sourceFileCount})")
                } else {
                    if (result.sourceFileCount > 0) {
                        failedCopies++
                        Log.e(TAG, "Player $playerIndex: copy failed, error=${result.error}")
                    } else {
                        Log.w(TAG, "Player $playerIndex: no source content")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying player $playerIndex: ${e.message}")
                failedCopies++
            }
        }
        
        val success = hasSourceContent && failedCopies == 0 && anySuccess
        Log.i(TAG, "Copy all completed, hasSourceContent=$hasSourceContent, anySuccess=$anySuccess, failedCopies=$failedCopies, success=$success")
        success
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

    /**
     * 获取播放器基础目录（包含 player1~4 子目录的父目录）
     */
    fun getPlayerBaseDir(): File? {
        return try {
            FileUtils.getLocalMediaDir(context, storageLocation, customPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting player base dir: ${e.message}")
            null
        }
    }
}
