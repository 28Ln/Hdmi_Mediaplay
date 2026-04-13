package com.btf.rk3568_hdmi_mediaplay.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放器状态广播器
 * 
 * 向信发系统发送状态回调广播
 * Action: com.btf.player.status.CALLBACK
 * Extra: status_json (JSON字符串)
 */
object PlayerStatusBroadcaster {
    
    private const val TAG = "PlayerStatusBroadcaster"
    
    /**
     * 发送状态广播
     */
    private fun sendStatus(context: Context, json: JSONObject) {
        try {
            // 添加时间戳
            json.put("timestamp", System.currentTimeMillis())
            
            val intent = Intent(PlayerBroadcastContract.ACTION_STATUS_CALLBACK).apply {
                putExtra(PlayerBroadcastContract.EXTRA_STATUS_JSON, json.toString())
            }
            context.sendBroadcast(intent, PlayerBroadcastContract.PERMISSION_RECEIVE_PLAYER_STATUS)
            Log.d(TAG, "发送状态: type=${json.optString("type")}, payloadSize=${json.toString().length}")
        } catch (e: Exception) {
            Log.e(TAG, "发送状态失败", e)
        }
    }
    
    /**
     * 播放状态变化
     */
    fun sendPlaybackChanged(context: Context, playerIndex: Int, state: String) {
        val json = JSONObject().apply {
            put("type", "playback_changed")
            put("player_index", playerIndex)
            put("state", state)
        }
        sendStatus(context, json)
    }
    
    /**
     * 播放完成
     */
    fun sendPlaybackCompleted(context: Context, playerIndex: Int, mediaPath: String?) {
        val json = JSONObject().apply {
            put("type", "playback_completed")
            put("player_index", playerIndex)
            mediaPath?.let { put("media_path", it) }
        }
        sendStatus(context, json)
    }
    
    /**
     * 布局变化
     */
    fun sendLayoutChanged(context: Context, layoutMode: String) {
        val json = JSONObject().apply {
            put("type", "layout_changed")
            put("layout_mode", layoutMode)
        }
        sendStatus(context, json)
    }
    
    /**
     * 媒体加载完成
     */
    fun sendMediaLoaded(context: Context, playerIndex: Int, mediaPath: String?, mediaCount: Int = 1) {
        val json = JSONObject().apply {
            put("type", "media_loaded")
            put("player_index", playerIndex)
            mediaPath?.let { put("media_path", it) }
            put("media_count", mediaCount)
        }
        sendStatus(context, json)
    }
    
    /**
     * 错误
     */
    fun sendError(context: Context, errorCode: Int, errorMessage: String) {
        val json = JSONObject().apply {
            put("type", "error")
            put("error_code", errorCode)
            put("error_message", errorMessage)
        }
        sendStatus(context, json)
    }
    
    /**
     * 状态响应 (响应 get_status 命令)
     */
    fun sendStatusResponse(
        context: Context,
        layoutMode: String,
        intervalSeconds: Int,
        loopMode: String,
        players: List<PlayerStatusInfo>
    ) {
        val playersArray = JSONArray()
        players.forEach { player ->
            val playerJson = JSONObject().apply {
                put("index", player.index)
                put("state", player.state)
                player.mediaType?.let { put("media_type", it) }
                player.mediaPath?.let { put("media_path", it) }
                if (player.mediaCount > 1) {
                    put("media_count", player.mediaCount)
                    put("current_index", player.currentIndex)
                }
                put("volume", player.volume)
                put("muted", player.muted)
            }
            playersArray.put(playerJson)
        }
        
        val json = JSONObject().apply {
            put("type", "status_response")
            put("layout_mode", layoutMode)
            put("interval_seconds", intervalSeconds)
            put("loop_mode", loopMode)
            put("players", playersArray)
        }
        sendStatus(context, json)
    }
    
    /**
     * 应用启动
     */
    fun sendAppStarted(context: Context) {
        val json = JSONObject().apply {
            put("type", "app_started")
        }
        sendStatus(context, json)
    }
    
    /**
     * 应用停止
     */
    fun sendAppStopping(context: Context) {
        val json = JSONObject().apply {
            put("type", "app_stopping")
        }
        sendStatus(context, json)
    }
    
    /**
     * 命令执行成功
     */
    fun sendCommandSuccess(context: Context, action: String, message: String? = null) {
        val json = JSONObject().apply {
            put("type", "command_success")
            put("action", action)
            message?.let { put("message", it) }
        }
        sendStatus(context, json)
    }
}

/**
 * 播放器状态信息
 */
data class PlayerStatusInfo(
    val index: Int,
    val state: String,  // "playing", "paused", "stopped", "idle", "error"
    val mediaType: String? = null,  // "video", "image"
    val mediaPath: String? = null,
    val mediaCount: Int = 0,
    val currentIndex: Int = 0,
    val volume: Int = 100,
    val muted: Boolean = false
)
