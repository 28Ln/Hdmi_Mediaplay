package com.btf.rk3568_hdmi_mediaplay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.remote.PlayerBroadcastContract
import com.btf.rk3568_hdmi_mediaplay.remote.PlayerCommandHandler

/**
 * 播放器远程控制命令接收器
 * 
 * 接收来自信发系统的控制命令广播
 * Action: com.btf.player.action.COMMAND
 * Extra: command_json (JSON字符串)
 */
class PlayerCommandReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PlayerCommandReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlayerBroadcastContract.ACTION_COMMAND) {
            return
        }
        
        val commandJson = intent.getStringExtra(PlayerBroadcastContract.EXTRA_COMMAND_JSON)
        if (commandJson.isNullOrBlank()) {
            Log.w(TAG, "收到空命令")
            return
        }
        
        Log.i(TAG, "收到远程命令，action=${intent.action}, length=${commandJson.length}")
        
        // 交给命令处理器处理
        PlayerCommandHandler.handleCommand(context, commandJson)
    }
}
