package com.btf.rk3568_hdmi_mediaplay.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.MainActivity
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import org.json.JSONObject
import java.io.File

/**
 * 播放器命令处理器
 * 
 * 解析并执行来自信发系统的 JSON 命令
 */
object PlayerCommandHandler {
    
    private const val TAG = "PlayerCommandHandler"
    
    // 错误码
    object ErrorCode {
        const val INVALID_PLAYER_INDEX = 1001
        const val INVALID_LAYOUT_MODE = 1002
        const val MEDIA_NOT_FOUND = 1003
        const val MEDIA_NOT_READABLE = 1004
        const val UNSUPPORTED_FORMAT = 1005
        const val MEDIA_LOAD_FAILED = 1006
        const val JSON_PARSE_ERROR = 2001
        const val UNKNOWN_ACTION = 2002
    }
    
    // ViewModel 引用，由 MainActivity 设置
    private var viewModelCallback: ViewModelCallback? = null
    
    interface ViewModelCallback {
        fun playAll()
        fun pauseAll()
        fun stopAll()
        fun play(playerIndex: Int)
        fun pause(playerIndex: Int)
        fun stop(playerIndex: Int)
        fun setLayout(layoutMode: LayoutMode)
        fun setMedia(playerIndex: Int, mediaItems: List<MediaItem>)
        fun clearMedia(playerIndex: Int)
        fun clearAllMedia()
        fun setInterval(seconds: Int)
        fun setVolume(playerIndex: Int, volume: Int)
        fun setMute(playerIndex: Int, mute: Boolean)
        fun setLoopMode(loopMode: LoopMode)
        fun getStatus(): StatusData
    }
    
    data class StatusData(
        val layoutMode: String,
        val intervalSeconds: Int,
        val loopMode: String,
        val players: List<PlayerStatusInfo>
    )
    
    /**
     * 设置 ViewModel 回调
     */
    fun setCallback(callback: ViewModelCallback?) {
        viewModelCallback = callback
        Log.d(TAG, "ViewModel callback ${if (callback != null) "已设置" else "已清除"}")
    }
    
    /**
     * 处理命令
     */
    fun handleCommand(context: Context, commandJson: String) {
        handleCommandInternal(context, commandJson)
    }

    private fun handleCommandInternal(context: Context, commandJson: String): Boolean {
        try {
            val json = JSONObject(commandJson)
            val action = json.optString("action", "")
            
            if (action.isBlank()) {
                sendError(context, ErrorCode.JSON_PARSE_ERROR, "缺少 action 字段")
                return false
            }
            
            Log.d(TAG, "处理命令: $action")
            
            return when (action) {
                // 播放控制
                "play_all" -> handlePlayAll(context)
                "pause_all" -> handlePauseAll(context)
                "stop_all" -> handleStopAll(context)
                "play" -> handlePlay(context, json)
                "pause" -> handlePause(context, json)
                "stop" -> handleStop(context, json)
                
                // 布局控制
                "set_layout" -> handleSetLayout(context, json)
                
                // 媒体设置
                "set_media" -> handleSetMedia(context, json)
                "set_media_dir" -> handleSetMediaDir(context, json)
                "clear" -> handleClear(context, json)
                "clear_all" -> handleClearAll(context)
                
                // 参数设置
                "set_interval" -> handleSetInterval(context, json)
                "set_volume" -> handleSetVolume(context, json)
                "set_mute" -> handleSetMute(context, json)
                "set_loop_mode" -> handleSetLoopMode(context, json)
                
                // 应用控制
                "show" -> handleShow(context)
                "hide" -> handleHide(context)
                "get_status" -> handleGetStatus(context)
                
                // 批量命令
                "batch" -> handleBatch(context, json)
                
                else -> {
                    sendError(context, ErrorCode.UNKNOWN_ACTION, "未知命令: $action")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "命令解析失败", e)
            sendError(context, ErrorCode.JSON_PARSE_ERROR, "JSON解析失败: ${e.message}")
            return false
        }
    }
    
    // ==================== 播放控制 ====================
    
    private fun handlePlayAll(context: Context): Boolean {
        val callback = requireCallback(context) ?: return false
        callback.playAll()
        PlayerStatusBroadcaster.sendCommandSuccess(context, "play_all")
        return true
    }
    
    private fun handlePauseAll(context: Context): Boolean {
        val callback = requireCallback(context) ?: return false
        callback.pauseAll()
        PlayerStatusBroadcaster.sendCommandSuccess(context, "pause_all")
        return true
    }
    
    private fun handleStopAll(context: Context): Boolean {
        val callback = requireCallback(context) ?: return false
        callback.stopAll()
        PlayerStatusBroadcaster.sendCommandSuccess(context, "stop_all")
        return true
    }
    
    private fun handlePlay(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        callback.play(playerIndex)
        PlayerStatusBroadcaster.sendPlaybackChanged(context, playerIndex, "playing")
        return true
    }
    
    private fun handlePause(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        callback.pause(playerIndex)
        PlayerStatusBroadcaster.sendPlaybackChanged(context, playerIndex, "paused")
        return true
    }
    
    private fun handleStop(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        callback.stop(playerIndex)
        PlayerStatusBroadcaster.sendPlaybackChanged(context, playerIndex, "stopped")
        return true
    }
    
    // ==================== 布局控制 ====================
    
    private fun handleSetLayout(context: Context, json: JSONObject): Boolean {
        val layoutModeStr = json.optString("layout_mode", "")
        
        val layoutMode = parseLayoutMode(layoutModeStr)
        if (layoutMode == null) {
            sendError(context, ErrorCode.INVALID_LAYOUT_MODE, "无效的布局模式: $layoutModeStr")
            return false
        }
        val callback = requireCallback(context) ?: return false
        
        callback.setLayout(layoutMode)
        PlayerStatusBroadcaster.sendLayoutChanged(context, layoutMode.name)
        return true
    }
    
    // ==================== 媒体设置 ====================
    
    private fun handleSetMedia(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        val mediaItems = mutableListOf<MediaItem>()
        
        // 单个文件
        val mediaPath = json.optString("media_path", "")
        if (mediaPath.isNotBlank()) {
            val item = createMediaItem(mediaPath)
            if (item != null) {
                mediaItems.add(item)
            } else {
                sendError(context, ErrorCode.MEDIA_NOT_FOUND, "文件不存在: $mediaPath")
                return false
            }
        }
        
        // 多个文件
        val mediaPaths = json.optJSONArray("media_paths")
        if (mediaPaths != null) {
            for (i in 0 until mediaPaths.length()) {
                val path = mediaPaths.optString(i, "")
                if (path.isNotBlank()) {
                    val item = createMediaItem(path)
                    if (item != null) {
                        mediaItems.add(item)
                    } else {
                        Log.w(TAG, "文件不存在，跳过: $path")
                    }
                }
            }
        }
        
        if (mediaItems.isEmpty()) {
            sendError(context, ErrorCode.MEDIA_NOT_FOUND, "没有有效的媒体文件")
            return false
        }
        
        callback.setMedia(playerIndex, mediaItems)
        PlayerStatusBroadcaster.sendMediaLoaded(
            context, 
            playerIndex, 
            mediaItems.firstOrNull()?.path,
            mediaItems.size
        )
        return true
    }
    
    private fun handleSetMediaDir(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        val dirPath = json.optString("directory_path", "")
        if (dirPath.isBlank()) {
            sendError(context, ErrorCode.MEDIA_NOT_FOUND, "目录路径为空")
            return false
        }
        
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            sendError(context, ErrorCode.MEDIA_NOT_FOUND, "目录不存在: $dirPath")
            return false
        }
        
        val mediaItems = scanDirectory(dir)
        if (mediaItems.isEmpty()) {
            sendError(context, ErrorCode.MEDIA_NOT_FOUND, "目录中没有媒体文件")
            return false
        }
        
        callback.setMedia(playerIndex, mediaItems)
        PlayerStatusBroadcaster.sendMediaLoaded(
            context,
            playerIndex,
            mediaItems.firstOrNull()?.path,
            mediaItems.size
        )
        return true
    }
    
    private fun handleClear(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        callback.clearMedia(playerIndex)
        PlayerStatusBroadcaster.sendCommandSuccess(context, "clear", "已清空播放器 $playerIndex")
        return true
    }
    
    private fun handleClearAll(context: Context): Boolean {
        val callback = requireCallback(context) ?: return false
        callback.clearAllMedia()
        PlayerStatusBroadcaster.sendCommandSuccess(context, "clear_all", "已清空所有播放器")
        return true
    }
    
    // ==================== 参数设置 ====================
    
    private fun handleSetInterval(context: Context, json: JSONObject): Boolean {
        val callback = requireCallback(context) ?: return false
        val interval = json.optInt("interval", 5)
        callback.setInterval(interval.coerceIn(1, 60))
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_interval", "间隔设置为 ${interval}秒")
        return true
    }
    
    private fun handleSetVolume(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        val volume = json.optInt("volume", 100)
        callback.setVolume(playerIndex, volume.coerceIn(0, 100))
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_volume")
        return true
    }
    
    private fun handleSetMute(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false
        
        val mute = json.optBoolean("mute", false)
        callback.setMute(playerIndex, mute)
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_mute")
        return true
    }
    
    private fun handleSetLoopMode(context: Context, json: JSONObject): Boolean {
        val callback = requireCallback(context) ?: return false
        val loopModeStr = json.optString("loop_mode", "ALL")
        val loopMode = try {
            LoopMode.valueOf(loopModeStr.uppercase())
        } catch (e: Exception) {
            LoopMode.LIST
        }
        
        callback.setLoopMode(loopMode)
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_loop_mode")
        return true
    }
    
    // ==================== 应用控制 ====================
    
    private fun handleShow(context: Context): Boolean {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
            PlayerStatusBroadcaster.sendCommandSuccess(context, "show")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "显示应用失败", e)
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "显示应用失败: ${e.message}")
            return false
        }
    }
    
    private fun handleHide(context: Context): Boolean {
        try {
            // 发送广播让 Activity 自己处理后台
            val intent = Intent(PlayerBroadcastContract.ACTION_MOVE_TO_BACK).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            PlayerStatusBroadcaster.sendCommandSuccess(context, "hide")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "隐藏应用失败", e)
            return false
        }
    }
    
    private fun handleGetStatus(context: Context): Boolean {
        val callback = viewModelCallback
        if (callback == null) {
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "播放器未就绪")
            return false
        }
        
        val status = callback.getStatus()
        PlayerStatusBroadcaster.sendStatusResponse(
            context,
            status.layoutMode,
            status.intervalSeconds,
            status.loopMode,
            status.players
        )
        return true
    }
    
    // ==================== 批量命令 ====================
    
    private fun handleBatch(context: Context, json: JSONObject): Boolean {
        val commands = json.optJSONArray("commands")
        if (commands == null || commands.length() == 0) {
            sendError(context, ErrorCode.JSON_PARSE_ERROR, "批量命令为空")
            return false
        }
        
        var successCount = 0
        for (i in 0 until commands.length()) {
            val cmd = commands.optJSONObject(i)
            if (cmd != null) {
                try {
                    if (handleCommandInternal(context, cmd.toString())) {
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "批量命令第 $i 个执行失败", e)
                }
            }
        }
        
        PlayerStatusBroadcaster.sendCommandSuccess(context, "batch", "执行了 $successCount 个命令")
        return successCount > 0
    }
    
    // ==================== 辅助方法 ====================
    
    private fun validatePlayerIndex(context: Context, playerIndex: Int): Boolean {
        if (playerIndex < 0 || playerIndex > 3) {
            sendError(context, ErrorCode.INVALID_PLAYER_INDEX, "无效的播放器索引: $playerIndex (应为0-3)")
            return false
        }
        return true
    }

    private fun requireCallback(context: Context): ViewModelCallback? {
        return viewModelCallback ?: run {
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "播放器未就绪")
            null
        }
    }
    
    private fun sendError(context: Context, code: Int, message: String) {
        Log.e(TAG, "错误 [$code]: $message")
        PlayerStatusBroadcaster.sendError(context, code, message)
    }
    
    private fun parseLayoutMode(str: String): LayoutMode? {
        return when (str.uppercase()) {
            "SINGLE" -> LayoutMode.SINGLE
            "DUAL_HORIZONTAL", "GRID_1X2" -> LayoutMode.GRID_1X2
            "DUAL_VERTICAL", "GRID_2X1" -> LayoutMode.GRID_2X1
            "QUAD", "GRID_2X2" -> LayoutMode.GRID_2X2
            "SPLIT_1X3", "GRID_1X3" -> LayoutMode.GRID_1X3
            "SPLIT_3X1", "GRID_3X1" -> LayoutMode.GRID_3X1
            "SPLIT_1X4", "ROW_1X4" -> LayoutMode.ROW_1X4
            "SPLIT_4X1", "COLUMN_4X1" -> LayoutMode.COLUMN_4X1
            "PIP" -> LayoutMode.PIP
            else -> try {
                LayoutMode.valueOf(str.uppercase())
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun createMediaItem(path: String): MediaItem? {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            return null
        }
        
        val extension = file.extension.lowercase()
        val type = when (extension) {
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp" -> MediaType.VIDEO
            "jpg", "jpeg", "png", "bmp", "gif", "webp" -> MediaType.IMAGE
            else -> return null
        }
        
        return MediaItem(
            path = path,
            name = file.name,
            type = type,
            source = MediaSource.MANUAL,
            size = file.length()
        )
    }
    
    private fun scanDirectory(dir: File): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val supportedExtensions = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
            "jpg", "jpeg", "png", "bmp", "gif", "webp"
        )
        
        dir.listFiles()?.filter { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.sortedBy { it.name }?.forEach { file ->
            createMediaItem(file.absolutePath)?.let { items.add(it) }
        }
        
        return items
    }
}
