package com.btf.rk3568_hdmi_mediaplay.receiver

import android.app.AlarmManager
import android.app.PendingIntent
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
import kotlinx.coroutines.withTimeoutOrNull

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

        /** AlarmManager 兜底启动的请求码 */
        private const val ALARM_LAUNCH_REQUEST_CODE = 3001
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

        // ## 为什么加超时（2026-10-20）
        //
        // `goAsync()` 只把广播的生命周期延长约 10 秒；超过后系统会认为接收器
        // 未完成并可能触发 ANR。此前的实现直接 `first()` 读 DataStore ——
        // 若磁盘慢/DataStore 首次初始化，可能拖到超时之后才 `finish()`。
        //
        // 这里用 `withTimeoutOrNull` 把「读设置」这一步限死在 5 秒内：
        // 超时则跳过自启（宁可不开机播放，也不要 ANR），并保证 finally 一定执行。
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = withTimeoutOrNull(5_000L) {
                    SettingsRepository(appContext).settingsFlow.first()
                }
                if (settings == null) {
                    Log.w(TAG, "读取设置超时（5s），跳过本次开机自启")
                    return@launch
                }
                if (!settings.bootAutoStart) {
                    Log.i(TAG, "开机自启已关闭，跳过")
                    return@launch
                }

                // 先起前台服务：U 盘监听与媒体扫描由它负责，
                // 只起 Activity 会导致插盘后无人响应。
                startUsbService(appContext)

                // 再拉起主界面。
                //
                // ## 为什么同时准备 AlarmManager 兜底（2026-10-20）
                //
                // Android 10+ 对「后台启动 Activity」有严格限制。实测：
                // - 本应用若已设为**默认桌面**（HOME），`startActivity` 能成功
                //   —— 开机时系统本来就要启动 HOME，此时本应用是前台候选
                // - 若未设为默认桌面，`startActivity` 会被
                //   `Background activity start ... isBgStartWhitelisted:false` 拒绝
                //
                // AlarmManager 的 PendingIntent 在开机场景下相对宽松，作为兜底。
                // 两条路径都尝试，任一成功即可；失败只记日志，不影响服务运行。
                val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

                var launched = false
                try {
                    appContext.startActivity(launchIntent)
                    launched = true
                    Log.i(TAG, "开机自启：已直接启动主界面")
                } catch (e: Exception) {
                    Log.w(TAG, "直接启动主界面失败，改用 AlarmManager: ${e.message}")
                }

                if (!launched) {
                    scheduleLaunchViaAlarm(appContext, launchIntent)
                }
                Log.i(TAG, "开机自启：已启动服务与主界面（direct=$launched）")
            } catch (e: Exception) {
                Log.e(TAG, "开机自启失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 用精确闹钟把主界面拉起来。
     *
     * 系统投递的 PendingIntent 比应用直接 `startActivity` 更容易通过
     * 后台启动限制（这也正是 `UsbMonitorService.restoreMainUi` 采用的思路）。
     * 延迟 2 秒：等开机广播链路稳定、桌面初始化完成。
     */
    private fun scheduleLaunchViaAlarm(context: Context, launchIntent: Intent) {
        try {
            val pending = PendingIntent.getActivity(
                context,
                ALARM_LAUNCH_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = context.getSystemService(AlarmManager::class.java)
            alarm?.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 2_000L,
                pending
            )
            Log.i(TAG, "开机自启：已安排 AlarmManager 兜底启动")
        } catch (e: Exception) {
            Log.e(TAG, "安排 AlarmManager 兜底失败", e)
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
