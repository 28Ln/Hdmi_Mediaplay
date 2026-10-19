package com.btf.testcmd

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * 远控联调发送端（仅用于测试，不入交付）。
 * 与应用同签名，因此能通过 signature 权限。
 */
class MainActivity : Activity() {
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val json = intent?.getStringExtra("status_json") ?: return
            Log.i("TestCmd", "STATUS: $json")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 第四个参数 broadcastPermission = 发送方必须持有的权限。
        // 主应用是用 sendBroadcast(intent, RECEIVE_PLAYER_STATUS) 发的，
        // 即"接收方需持有该权限"——两个方向的语义不同，注册端应传 null。
        registerReceiver(
            statusReceiver,
            IntentFilter("com.btf.player.status.CALLBACK")
        )
        Log.i("TestCmd", "receiver registered")

        // 延迟 10 秒再发 hide —— 给测试者时间把主应用切回前台。
        // 这样 hide 才是在「主应用确实在前台」时被判定，而不是返回 1006。
        android.os.Handler(mainLooper).postDelayed({
            send("""{"action":"hide"}""")
        }, 10_000)
    }

    private fun send(json: String) {
        val i = Intent("com.btf.player.action.COMMAND")
            .setClassName(
                "com.btf.rk3568_hdmi_mediaplay",
                "com.btf.rk3568_hdmi_mediaplay.receiver.PlayerCommandReceiver"
            )
            .putExtra("command_json", json)
        // 注意：**不要**把 SEND_PLAYER_COMMAND 作为 receiverPermission 传入。
        // 那是「要求**接收方**持有该权限」的意思，而本应用自己并没有申请
        // 自己声明的权限 → BroadcastQueue 会以 Permission Denial 拒绝。
        // receiver 的 android:permission 已经由系统在投递时校验发送方。
        sendBroadcast(i)
        Log.i("TestCmd", "SENT: $json")
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
