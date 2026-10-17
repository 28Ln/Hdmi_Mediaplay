package com.btf.rk3568_hdmi_mediaplay.util

/**
 * 应用前台状态持有者。
 *
 * 由 [com.btf.rk3568_hdmi_mediaplay.MainActivity] 在 `onStart` / `onStop` 维护。
 *
 * **用途**：远控 `hide` 命令需要判断「应用是否真的能被退到后台」。
 * `MOVE_TO_BACK` 广播只有 Activity 存活时才有接收方；Activity 不在前台时
 * 广播无人接、`moveTaskToBack` 也就不会执行，此时若回报 `command_success`
 * 就是虚假成功。
 *
 * **为什么不用广播回执**：命令处理发生在 `onReceive` 内、运行在主线程。
 * 在该上下文里 `registerReceiver` 会抛 `ReceiverCallNotAllowedException`
 * （`onReceive` 拿到的是 `ReceiverRestrictedContext`），且阻塞等待回执会与
 * 主线程回调互锁，永远超时。因此改用本对象做主线程安全的同步判定。
 */
object AppForegroundState {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun onActivityStarted() {
        isForeground = true
    }

    fun onActivityStopped() {
        isForeground = false
    }
}
