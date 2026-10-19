package com.btf.rk3568_hdmi_mediaplay.data.local

import android.content.Context
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation
import com.btf.rk3568_hdmi_mediaplay.util.FileUtils
import com.btf.rk3568_hdmi_mediaplay.util.UsbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地存储管理器
 */
class LocalStorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalStorageManager"
    }

    /**
     * 整批拷贝的结果。
     *
     * 此前只返回 Boolean，失败时调用方只能显示笼统的「拷贝失败」，
     * 具体原因（哪个格子、哪一步失败）只写在日志里 —— 现场无从判断。
     */
    data class BatchCopyResult(
        val success: Boolean,
        /** 失败时给出可展示的原因；成功时为 null */
        val reason: String? = null
    )

    private data class PlayerCopyResult(
        val playerIndex: Int,
        val sourceFileCount: Int,
        val copiedFileCount: Int,
        val success: Boolean,
        val error: String? = null
    )
    
    private var storageLocation: StorageLocation = StorageLocation.SDCARD
    private var customPath: String = ""

    /**
     * 串行化整批拷贝。USB 插盘广播可能被投递两次（Manifest 静态 + 服务内动态注册），
     * 并发拷贝会互相删除对方的 staging/backup 目录，破坏事务语义。
     */
    private val copyMutex = Mutex()
    
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
        onProgress: ((Float) -> Unit)? = null,
        /**
         * true = 只把数据准备好放进 staging，**不**改名替换本地目录。
         *
         * 用于「先全部暂存、确认四路都成功后再统一提交」的两阶段拷贝：
         * 逐路提交会在第 3 路失败时留下「1-2 路是新内容、3-4 路是旧内容」
         * 的混合状态，且旧内容已被删除，无法回滚。
         */
        stageOnly: Boolean = false
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

            // 先回收上次中断遗留的备份：若上次拷贝在「旧目录已改名、新数据未就位」之间被杀，
            // 真实数据只在 backup 里。此时应先把它还原，而不是删掉（旧实现会直接删）。
            if (!localPlayerDir.exists() && backupDir.exists()) {
                Log.w(TAG, "Recovering orphaned backup for player $playerIndex")
                if (!backupDir.renameTo(localPlayerDir)) {
                    // 唯一一份数据此刻只存在于 backup 中，还原失败就不能继续往下走：
                    // 下面的 backupDir.deleteRecursively() 会把它删掉，造成数据丢失。
                    Log.e(TAG, "Failed to recover backup for player $playerIndex, aborting copy")
                    stagingDir.deleteRecursively()
                    return@withContext PlayerCopyResult(
                        playerIndex, usbFiles.size, 0, false, "backup_recovery_failed"
                    )
                }
            }

            stagingDir.deleteRecursively()

            // 走到这里 backupDir 若仍存在，说明 localPlayerDir 也有数据
            // （上次拷贝在 promote 成功之后、清理之前被中断），是陈旧备份，必须删除。
            //
            // 注意：此处**不能**加任何 exists 条件——曾误写成 `if (!backupDir.exists())`，
            // 使删除恒为空操作，于是 backup 残留；下一次拷贝时第 158 行的
            // `localPlayerDir.renameTo(backupDir)` 因目标已存在而失败（renameTo 无法
            // 覆盖已存在的目录），返回 backup_prepare_failed，**此后每次 U 盘拷贝都失败**，
            // 且必须手工删除隐藏目录才能恢复。
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

            if (stageOnly) {
                // 数据已在 staging 且校验通过；提交（改名替换）由 copyAllFromUsbInternal
                // 在四路**全部**暂存成功后统一执行。
                Log.i(TAG, "Player $playerIndex staged ${stagedFiles.size} files (commit deferred)")
                return@withContext PlayerCopyResult(
                    playerIndex, usbFiles.size, stagedFiles.size, true
                )
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
            // 异常（含拔盘、IO 失败、协程取消）时必须做两件事：
            //   1) 清理半成品 staging，避免残留占空间并被误扫
            //   2) 若旧缓存已被改名到 backup 而新数据未就位，把它还原回去
            // 否则会「旧缓存消失、真数据孤立在隐藏的 backup 里」，现场表现像媒体丢失。
            Log.e(TAG, "Error copying player $playerIndex: ${e.message}", e)
            try {
                val dir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
                val parent = dir.parentFile
                if (parent != null) {
                    File(parent, ".${dir.name}_staging").deleteRecursively()
                    val backup = File(parent, ".${dir.name}_backup")
                    if (!dir.exists() && backup.exists()) {
                        Log.w(TAG, "Restoring backup for player $playerIndex after failure")
                        backup.renameTo(dir)
                    }
                }
            } catch (cleanupError: Exception) {
                Log.e(TAG, "Cleanup after copy failure also failed", cleanupError)
            }
            PlayerCopyResult(playerIndex, 0, 0, false, e.message)
        }
    }
    
    /**
     * 从U盘拷贝所有播放器的媒体文件
     *
     * 用 [copyMutex] 串行化：插盘广播可能被投递两次（静态 + 动态注册），
     * 并发拷贝会互相删对方的 staging/backup，导致拷贝失败甚至丢失旧缓存。
     * 第二个调用会排队等待，而不是并行执行。
     */
    suspend fun copyAllFromUsb(
        usbPath: File,
        folderName: String,
        onProgress: ((Int, Float) -> Unit)? = null
    ): Boolean = copyAllFromUsbDetailed(usbPath, folderName, onProgress).success

    /** 同 [copyAllFromUsb]，但失败时带可展示的原因。 */
    suspend fun copyAllFromUsbDetailed(
        usbPath: File,
        folderName: String,
        onProgress: ((Int, Float) -> Unit)? = null
    ): BatchCopyResult = copyMutex.withLock {
        copyAllFromUsbInternal(usbPath, folderName, onProgress)
    }

    private suspend fun copyAllFromUsbInternal(
        usbPath: File,
        folderName: String,
        onProgress: ((Int, Float) -> Unit)? = null
    ): BatchCopyResult = withContext(Dispatchers.IO) {
        var hasSourceContent = false
        var failedCopies = 0
        val stagedPlayers = mutableListOf<Int>()
        // 第一处失败的具体描述（哪个格子、哪一步），用于展示给用户
        var firstFailure: String? = null

        Log.i(TAG, "Starting copy from USB: ${usbPath.absolutePath}/$folderName")

        // ===== 阶段一：全部暂存，不改动任何现有缓存 =====
        //
        // 旧实现是「每路暂存成功就立刻改名替换」。风险：第 3 路失败时，
        // 1-2 路已是新内容、3-4 路仍是旧内容，而 1-2 路的旧内容已被删除 ——
        // 墙上出现四路混合代次，且无法回滚。
        //
        // 现在先只暂存；四路都成功后才统一提交。任何一路失败就整体放弃，
        // 删除全部 staging，现有缓存保持原样（等价于「从没插过 U 盘」）。
        for (playerIndex in 0..3) {
            try {
                val result = copyPlayerFromUsb(
                    usbPath, folderName, playerIndex,
                    onProgress = { progress -> onProgress?.invoke(playerIndex, progress) },
                    stageOnly = true
                )
                if (result.sourceFileCount > 0) {
                    hasSourceContent = true
                }
                if (result.success) {
                    stagedPlayers += playerIndex
                    Log.i(TAG, "Player $playerIndex: staged (${result.copiedFileCount}/${result.sourceFileCount})")
                } else {
                    if (result.sourceFileCount > 0) {
                        failedCopies++
                        if (firstFailure == null) {
                            firstFailure = "P${playerIndex + 1} ${result.error ?: "暂存失败"}"
                        }
                        Log.e(TAG, "Player $playerIndex: stage failed, error=${result.error}")
                    } else {
                        Log.w(TAG, "Player $playerIndex: no source content")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error staging player $playerIndex: ${e.message}")
                failedCopies++
                if (firstFailure == null) firstFailure = "P${playerIndex + 1} ${e.message ?: "异常"}"
            }
        }

        // ===== 校验：有任何失败就整体放弃 =====
        if (!hasSourceContent || failedCopies > 0 || stagedPlayers.isEmpty()) {
            Log.e(
                TAG,
                "Aborting copy: hasSourceContent=$hasSourceContent, failedCopies=$failedCopies. " +
                    "现有缓存未改动。"
            )
            discardStaged(stagedPlayers)
            return@withContext BatchCopyResult(
                success = false,
                reason = when {
                    !hasSourceContent -> "U盘中没有可复制的媒体"
                    firstFailure != null -> "复制失败（$firstFailure）"
                    else -> "复制失败"
                }
            )
        }

        // ===== 阶段二：统一提交 =====
        val committed = mutableListOf<Int>()
        for (playerIndex in stagedPlayers) {
            if (commitStaged(playerIndex)) {
                committed += playerIndex
            } else {
                Log.e(TAG, "Commit failed for player $playerIndex, rolling back ${committed.size} players")
                rollbackCommitted(committed)
                discardStaged(stagedPlayers.filter { it !in committed })
                return@withContext BatchCopyResult(
                    success = false,
                    reason = "写入本地失败（P${playerIndex + 1}）"
                )
            }
        }

        Log.i(TAG, "Copy all completed: committed ${committed.size} players")
        BatchCopyResult(success = true)
    }

    private fun stagingDirFor(playerIndex: Int): File {
        val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
        return File(localPlayerDir.parentFile, ".${localPlayerDir.name}_staging")
    }

    private fun backupDirFor(playerIndex: Int): File {
        val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
        return File(localPlayerDir.parentFile, ".${localPlayerDir.name}_backup")
    }

    /** 删除尚未提交的 staging */
    private fun discardStaged(playerIndices: List<Int>) {
        playerIndices.forEach { idx ->
            try {
                stagingDirFor(idx).deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "discard staging for player $idx failed: ${e.message}")
            }
        }
    }

    /**
     * 把某一路的 staging 提交为正式缓存：
     * 旧目录改名到 backup → staging 改名到正式 → 删 backup。
     */
    private fun commitStaged(playerIndex: Int): Boolean {
        val localPlayerDir = FileUtils.getPlayerLocalDir(context, playerIndex, storageLocation, customPath)
        val staging = stagingDirFor(playerIndex)
        val backup = backupDirFor(playerIndex)

        if (!staging.exists()) {
            Log.e(TAG, "commit: staging missing for player $playerIndex")
            return false
        }
        backup.deleteRecursively()

        val hadExisting = localPlayerDir.exists()
        if (hadExisting && !localPlayerDir.renameTo(backup)) {
            Log.e(TAG, "commit: backup prepare failed for player $playerIndex")
            return false
        }
        if (!staging.renameTo(localPlayerDir)) {
            if (hadExisting) {
                localPlayerDir.deleteRecursively()
                backup.renameTo(localPlayerDir)
            }
            Log.e(TAG, "commit: promote failed for player $playerIndex")
            return false
        }
        backup.deleteRecursively()
        Log.i(TAG, "Committed player $playerIndex")
        return true
    }

    /** 把已提交的格子恢复为提交前的旧缓存 */
    private fun rollbackCommitted(playerIndices: List<Int>) {
        playerIndices.forEach { idx ->
            try {
                val localPlayerDir = FileUtils.getPlayerLocalDir(context, idx, storageLocation, customPath)
                val backup = backupDirFor(idx)
                if (backup.exists()) {
                    localPlayerDir.deleteRecursively()
                    if (backup.renameTo(localPlayerDir)) {
                        Log.i(TAG, "Rolled back player $idx")
                    } else {
                        Log.e(TAG, "Rollback failed for player $idx — backup kept at ${backup.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rollback error for player $idx: ${e.message}")
            }
        }
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
