package com.btf.rk3568_hdmi_mediaplay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.btf.rk3568_hdmi_mediaplay.MainActivity
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机广播接收器
 * 用于实现开机自启动功能
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            // 检查是否启用了开机自启动
            CoroutineScope(Dispatchers.IO).launch {
                val settingsRepository = SettingsRepository(context)
                val settings = settingsRepository.settingsFlow.first()
                
                if (settings.bootAutoStart) {
                    // 启动主Activity
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                }
            }
        }
    }
}
