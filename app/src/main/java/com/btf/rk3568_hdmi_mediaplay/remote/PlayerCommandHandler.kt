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
import com.btf.rk3568_hdmi_mediaplay.util.AppForegroundState
import org.json.JSONObject
import java.io.File
import java.util.Locale

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

    /** batch 命令最大嵌套深度。超过即拒绝，避免深层嵌套递归耗尽栈 */
    private const val MAX_BATCH_DEPTH = 3
    
    // ViewModel 引用，由 MainActivity 设置
    private var viewModelCallback: ViewModelCallback? = null
    
    interface ViewModelCallback {
        /** @return 是否真正生效（一个可播放的路都没有时返回 false） */
        fun playAll(): Boolean
        /** @return 是否真正生效（当前没有处于播放中的路时返回 false） */
        fun pauseAll(): Boolean
        /** @return 是否真正生效（当前布局内没有路时返回 false） */
        fun stopAll(): Boolean
        /** @return 是否真正生效（无媒体的路返回 false） */
        fun play(playerIndex: Int): Boolean
        fun pause(playerIndex: Int): Boolean
        fun stop(playerIndex: Int): Boolean
        fun setLayout(layoutMode: LayoutMode)
        fun setMedia(playerIndex: Int, mediaItems: List<MediaItem>)
        fun clearMedia(playerIndex: Int)
        fun clearAllMedia()
        fun setInterval(seconds: Int)
        /** @return 是否真正生效（索引不在当前布局中返回 false） */
        fun setVolume(playerIndex: Int, volume: Int): Boolean
        fun setMute(playerIndex: Int, mute: Boolean): Boolean
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

    private fun handleCommandInternal(
        context: Context,
        commandJson: String,
        depth: Int = 0
    ): Boolean {
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
                "batch" -> handleBatch(context, json, depth)
                
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
        // 依据实际是否生效回报：一路可播内容都没有时，报成功会误导接入方
        if (!callback.playAll()) {
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "play_all 未生效：没有任何可播放的内容")
            return false
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "play_all")
        return true
    }

    private fun handlePauseAll(context: Context): Boolean {
        val callback = requireCallback(context) ?: return false
        if (!callback.pauseAll()) {
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "pause_all 未生效：当前没有正在播放的路")
            return false
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "pause_all")
        return true
    }

    private fun handleStopAll(context: Context): Boolean {
        val callback = requireCallback(context) ?: return false
        if (!callback.stopAll()) {
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "stop_all 未生效：当前布局内没有播放器")
            return false
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "stop_all")
        return true
    }
    
    private fun handlePlay(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false

        // 依据实际是否生效回报，而不是无条件广播状态。
        // 此前 SINGLE 布局下发 play(player_index=3) 会报成功并广播 playing，
        // 但 ViewModel 那边是无媒体/越界的空操作，接入方被误导。
        val ok = callback.play(playerIndex)
        if (!ok) {
            sendError(
                context,
                ErrorCode.INVALID_PLAYER_INDEX,
                "播放未生效：第 $playerIndex 路无媒体或不在当前布局中"
            )
            return false
        }
        PlayerStatusBroadcaster.sendPlaybackChanged(context, playerIndex, "playing")
        return true
    }
    
    private fun handlePause(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false

        // 依据实际是否生效回报，而不是无条件广播状态。
        // 此前 SINGLE 布局下发 play(player_index=3) 会报成功并广播 playing，
        // 但 ViewModel 那边是无媒体/越界的空操作，接入方被误导。
        val ok = callback.pause(playerIndex)
        if (!ok) {
            sendError(
                context,
                ErrorCode.INVALID_PLAYER_INDEX,
                "暂停未生效：第 $playerIndex 路无媒体或不在当前布局中"
            )
            return false
        }
        PlayerStatusBroadcaster.sendPlaybackChanged(context, playerIndex, "paused")
        return true
    }
    
    private fun handleStop(context: Context, json: JSONObject): Boolean {
        val playerIndex = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, playerIndex)) return false
        val callback = requireCallback(context) ?: return false

        // 依据实际是否生效回报，而不是无条件广播状态。
        // 此前 SINGLE 布局下发 play(player_index=3) 会报成功并广播 playing，
        // 但 ViewModel 那边是无媒体/越界的空操作，接入方被误导。
        val ok = callback.stop(playerIndex)
        if (!ok) {
            sendError(
                context,
                ErrorCode.INVALID_PLAYER_INDEX,
                "停止未生效：第 $playerIndex 路无媒体或不在当前布局中"
            )
            return false
        }
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
            when (val result = createMediaItemResult(mediaPath)) {
                is MediaResult.Ok -> mediaItems.add(result.item)
                else -> {
                    val (code, msg) = describeFailure(mediaPath, result)
                    sendError(context, code, msg)
                    return false
                }
            }
        }
        
        // 多个文件
        val mediaPaths = json.optJSONArray("media_paths")
        if (mediaPaths != null) {
            for (i in 0 until mediaPaths.length()) {
                val path = mediaPaths.optString(i, "")
                if (path.isNotBlank()) {
                    when (val result = createMediaItemResult(path)) {
                        is MediaResult.Ok -> mediaItems.add(result.item)
                        else -> {
                            val (code, msg) = describeFailure(path, result)
                            Log.w(TAG, "跳过（$code）: $msg")
                        }
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
        val requested = json.optInt("interval", 5)
        val applied = requested.coerceIn(1, 60)
        callback.setInterval(applied)
        // 回报实际生效值：此前写的是原始请求值，导致 set_interval 999 时
        // 回执显示 999 而实际是 60，调用方无从察觉
        val note = if (applied != requested) {
            "间隔设置为 ${applied}秒（请求 $requested 已限制到 1-60）"
        } else {
            "间隔设置为 ${applied}秒"
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_interval", note)
        return true
    }
    
    private fun handleSetVolume(context: Context, json: JSONObject): Boolean {
        val playerIndex = requirePlayerIndex(context, json) ?: return false
        val callback = requireCallback(context) ?: return false
        
        val volume = json.optInt("volume", 100)
        // 依据实际是否生效回报：索引不在当前布局中时 setVolume 是空操作
        if (!callback.setVolume(playerIndex, volume.coerceIn(0, 100))) {
            sendError(context, ErrorCode.INVALID_PLAYER_INDEX, "set_volume 未生效：该路不在当前布局中")
            return false
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_volume")
        return true
    }

    private fun handleSetMute(context: Context, json: JSONObject): Boolean {
        val playerIndex = requirePlayerIndex(context, json) ?: return false
        val callback = requireCallback(context) ?: return false

        val mute = json.optBoolean("mute", false)
        if (!callback.setMute(playerIndex, mute)) {
            sendError(context, ErrorCode.INVALID_PLAYER_INDEX, "set_mute 未生效：该路不在当前布局中")
            return false
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "set_mute")
        return true
    }
    
    private fun handleSetLoopMode(context: Context, json: JSONObject): Boolean {
        val callback = requireCallback(context) ?: return false

        // 缺省用枚举中真实存在的值。此前写 "ALL"（枚举无此值）→ valueOf 抛异常
        // 被 catch 成 LIST，非法输入也因此静默降级、调用方收到「成功」。
        val loopModeStr = json.optString("loop_mode", LoopMode.LIST.name)
        val loopMode = try {
            LoopMode.valueOf(loopModeStr.uppercase(Locale.ROOT))
        } catch (e: Exception) {
            sendError(
                context,
                ErrorCode.UNKNOWN_ACTION,
                "无效的 loop_mode: $loopModeStr（可选 ${LoopMode.entries.joinToString("/")}）"
            )
            return false
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
        // 前置判定：只有 Activity 处于前台，MOVE_TO_BACK 才有接收方。
        // 此前发完广播即报成功——Activity 不在前台时无人接收，
        // app 实际没退后台，调用方却收到 command_success。
        //
        // 不采用「广播回执 + latch 等待」：命令处理运行在 onReceive 内的主线程，
        // 该上下文 registerReceiver 会抛 ReceiverCallNotAllowedException
        // （onReceive 拿到的是 ReceiverRestrictedContext）；即便注册成功，
        // 阻塞等待也会与主线程回调互锁、必然超时。
        if (!AppForegroundState.isForeground) {
            sendError(
                context,
                ErrorCode.MEDIA_LOAD_FAILED,
                "hide 未生效：应用当前不在前台"
            )
            return false
        }

        return try {
            context.sendBroadcast(
                Intent(PlayerBroadcastContract.ACTION_MOVE_TO_BACK)
                    .setPackage(context.packageName)
            )
            // 广播为异步投递：此处只保证「已发出」，Activity 收到后会执行 moveTaskToBack。
            // 副作用是否最终落地由 Activity 侧完成，调用方无需等待。
            PlayerStatusBroadcaster.sendCommandSuccess(context, "hide")
            true
        } catch (e: Exception) {
            Log.e(TAG, "隐藏应用失败", e)
            sendError(context, ErrorCode.MEDIA_LOAD_FAILED, "隐藏应用失败: ${e.message}")
            false
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
    
    private fun handleBatch(context: Context, json: JSONObject, depth: Int): Boolean {
        // 深度上限：batch 嵌套 batch 会递归下去，此前没有限制，
        // 深层嵌套会耗尽栈。StackOverflowError 是 Error 不是 Exception，
        // 外层 catch(Exception) 兜不住 → 主线程直接崩进程。
        if (depth >= MAX_BATCH_DEPTH) {
            sendError(
                context,
                ErrorCode.UNKNOWN_ACTION,
                "batch 嵌套超过 $MAX_BATCH_DEPTH 层，已拒绝"
            )
            return false
        }

        val commands = json.optJSONArray("commands")
        if (commands == null || commands.length() == 0) {
            sendError(context, ErrorCode.JSON_PARSE_ERROR, "批量命令为空")
            return false
        }

        var successCount = 0
        var failureCount = 0
        for (i in 0 until commands.length()) {
            val cmd = commands.optJSONObject(i)
            if (cmd == null) {
                failureCount++
                continue
            }
            try {
                if (handleCommandInternal(context, cmd.toString(), depth + 1)) {
                    successCount++
                } else {
                    failureCount++
                }
            } catch (e: Throwable) {
                // 捕获 Throwable 而非 Exception：StackOverflowError / OutOfMemoryError
                // 等 Error 不属于 Exception，会穿透到主线程导致崩溃。
                // 此处降级为一次失败回报即可。
                Log.e(TAG, "批量命令第 $i 个执行失败", e)
                failureCount++
            }
        }

        val detail = if (failureCount > 0) {
            "执行了 $successCount 个命令，失败 $failureCount 个"
        } else {
            "执行了 $successCount 个命令"
        }
        // 有子命令失败时不能回报 command_success——此前 detail 只报成功数，
        // 部分失败也会被当作整批成功。
        if (failureCount > 0) {
            sendError(context, ErrorCode.UNKNOWN_ACTION, "批量命令部分失败：$detail")
            return false
        }
        PlayerStatusBroadcaster.sendCommandSuccess(context, "batch", detail)
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

    /**
     * 读取并校验 `player_index`，**区分「字段缺失」与「值非法」**。
     *
     * 两者排查方向不同：缺失是调用方用法错误（该带没带），
     * 越界是值域错误（带错了）。此前一律报「无效的播放器索引: -1」，
     * 现场会去怀疑索引算错，而实际是根本没读这个字段。
     */
    private fun requirePlayerIndex(context: Context, json: JSONObject): Int? {
        if (!json.has("player_index")) {
            sendError(
                context,
                ErrorCode.INVALID_PLAYER_INDEX,
                "缺少 player_index 字段（应为0-3）"
            )
            return null
        }
        val index = json.optInt("player_index", -1)
        if (!validatePlayerIndex(context, index)) return null
        return index
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
        return when (str.uppercase(Locale.ROOT)) {
            "SINGLE" -> LayoutMode.SINGLE
            "WALL_MODE_1", "LANDSCAPE_1X2", "DUAL_HORIZONTAL", "GRID_1X2" -> LayoutMode.GRID_1X2
            "WALL_MODE_2", "LANDSCAPE_1X3", "SPLIT_1X3", "GRID_1X3" -> LayoutMode.GRID_1X3
            "WALL_MODE_3", "PORTRAIT_1X2", "GRID_1X2_PORTRAIT" -> LayoutMode.GRID_1X2_PORTRAIT
            "WALL_MODE_4", "PORTRAIT_1X3", "GRID_1X3_PORTRAIT" -> LayoutMode.GRID_1X3_PORTRAIT
            "WALL_MODE_5", "LANDSCAPE_2X1", "DUAL_VERTICAL", "GRID_2X1" -> LayoutMode.GRID_2X1
            "WALL_MODE_6", "LANDSCAPE_3X1", "SPLIT_3X1", "GRID_3X1" -> LayoutMode.GRID_3X1
            "WALL_MODE_7", "LANDSCAPE_2X2", "QUAD", "GRID_2X2" -> LayoutMode.GRID_2X2
            "WALL_MODE_8", "SAME_2X2", "MIRROR_2X2", "QUAD_SAME", "GRID_2X2_SAME" -> LayoutMode.GRID_2X2_SAME
            "SPLIT_1X4", "ROW_1X4" -> LayoutMode.ROW_1X4
            "SPLIT_4X1", "COLUMN_4X1" -> LayoutMode.COLUMN_4X1
            "PIP" -> LayoutMode.PIP
            else -> try {
                LayoutMode.valueOf(str.uppercase(Locale.ROOT))
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * 创建媒体的结果。
     *
     * 区分失败原因是为了让接入方能对症处理（文件不存在 / 无读权限 / 格式不支持），
     * 而不是一律收到「文件不存在」。
     */
    private sealed interface MediaResult {
        data class Ok(val item: MediaItem) : MediaResult
        object NotExist : MediaResult
        object NotReadable : MediaResult
        object Unsupported : MediaResult
    }

    private fun createMediaItemResult(path: String): MediaResult {
        val file = File(path)
        if (!file.exists()) return MediaResult.NotExist
        if (!file.canRead()) return MediaResult.NotReadable

        val extension = file.extension.lowercase(Locale.ROOT)
        val type = when (extension) {
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp" -> MediaType.VIDEO
            "jpg", "jpeg", "png", "bmp", "gif", "webp" -> MediaType.IMAGE
            else -> return MediaResult.Unsupported
        }

        return MediaResult.Ok(
            MediaItem(
                path = path,
                name = file.name,
                type = type,
                source = MediaSource.MANUAL,
                size = file.length()
            )
        )
    }

    /** 兼容包装：仅需成败时使用。 */
    private fun createMediaItem(path: String): MediaItem? =
        (createMediaItemResult(path) as? MediaResult.Ok)?.item

    /** 把失败原因映射到对应的错误码与提示。 */
    private fun describeFailure(path: String, result: MediaResult): Pair<Int, String> = when (result) {
        is MediaResult.Ok -> ErrorCode.MEDIA_LOAD_FAILED to "未知错误"
        MediaResult.NotExist -> ErrorCode.MEDIA_NOT_FOUND to "文件不存在: $path"
        MediaResult.NotReadable -> ErrorCode.MEDIA_NOT_READABLE to "文件不可读（权限不足）: $path"
        MediaResult.Unsupported -> ErrorCode.UNSUPPORTED_FORMAT to "不支持的格式: $path"
    }
    
    private fun scanDirectory(dir: File): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val supportedExtensions = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
            "jpg", "jpeg", "png", "bmp", "gif", "webp"
        )
        
        dir.listFiles()?.filter { file ->
            file.isFile && file.extension.lowercase(Locale.ROOT) in supportedExtensions
        }?.sortedBy { it.name }?.forEach { file ->
            createMediaItem(file.absolutePath)?.let { items.add(it) }
        }
        
        return items
    }
}
