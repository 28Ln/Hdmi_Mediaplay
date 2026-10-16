package com.btf.rk3568_hdmi_mediaplay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.MainActivity
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.service.UsbMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机广播接收器 —— 实现开机自启动。
 *
 * **必须用 goAsync()**：
 * `onReceive` 返回后系统即可回收该进程。若在其中直接 launch 协程而不
 * 通过 `goAsync()` 保活，读取设置与启动 Activity 可能来不及执行完，
 * 表现为「开机自启时灵时不灵」。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // 保活：告诉系统本接收器仍在工作，完成后必须调用 finish()
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).settingsFlow.first()
                if (!settings.bootAutoStart) {
                    Log.i(TAG, "开机自启已关闭，跳过")
                    return@launch
                }

                // 先起前台服务：U 盘监听与媒体扫描由它负责，
                // 只起 Activity 会导致插盘后无人响应。
                startUsbService(appContext)

                // 再拉起主界面
                val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                appContext.startActivity(launchIntent)
                Log.i(TAG, "开机自启：已启动服务与主界面")
            } catch (e: Exception) {
                Log.e(TAG, "开机自启失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startUsbService(context: Context) {
        try {
            val intent = Intent(context, UsbMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 对后台启动前台服务有限制；此处失败不阻断拉起界面
            Log.e(TAG, "启动 U 盘监听服务失败", e)
        }
    }
}
