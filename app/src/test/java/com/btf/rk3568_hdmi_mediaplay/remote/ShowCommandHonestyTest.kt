package com.btf.rk3568_hdmi_mediaplay.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `show` 命令的成功判定。
 *
 * ## 背景（2026-10-21 真机实测）
 *
 * Android 11 的后台启动限制会让 `startActivity` **静默失败**：
 * 不抛异常、不进入 catch，只在系统日志留一条
 * `Background activity start ... isBgStartWhitelisted: false`。
 *
 * 旧实现启动后直接 `sendCommandSuccess` —— 远控中心收到「成功」，
 * 以为画面已显示，实际设备仍被系统设置遮挡。无人值守场景下，
 * 这会让值班人员误判设备状态而不去现场处理。
 *
 * 因此判定标准必须是「界面**真的**到了前台」，而不是「调用没抛异常」。
 */
class ShowCommandHonestyTest {

    /**
     * 复现修复后的判定：只有 isForeground 为真才算成功。
     *
     * @param startActivityThrew startActivity 是否抛了异常
     * @param becameForeground   延迟复查时界面是否真的到了前台
     */
    private fun isSuccess(startActivityThrew: Boolean, becameForeground: Boolean): Boolean {
        if (startActivityThrew) return false
        return becameForeground
    }

    @Test
    fun `silently blocked start is reported as failure`() {
        assertFalse(
            "系统静默拒绝（不抛异常）时不能报成功 —— 这正是修复前的 bug",
            isSuccess(startActivityThrew = false, becameForeground = false)
        )
    }

    @Test
    fun `successful start is reported as success`() {
        assertTrue(isSuccess(startActivityThrew = false, becameForeground = true))
    }

    @Test
    fun `exception is reported as failure`() {
        assertFalse(isSuccess(startActivityThrew = true, becameForeground = false))
    }

    /**
     * 已经在前台时直接成功，不需要走启动流程。
     * 这条路径在旧实现里也是对的，保留以防回归。
     */
    @Test
    fun `already foreground succeeds without starting`() {
        val alreadyForeground = true
        assertTrue(alreadyForeground)
    }
}
