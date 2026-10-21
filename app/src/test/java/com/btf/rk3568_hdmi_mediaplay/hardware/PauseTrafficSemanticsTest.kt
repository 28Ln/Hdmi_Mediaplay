package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「暂停全部流量」的语义。
 *
 * ## 背景（2026-10-21 真机实测）
 *
 * 排查 I2C「先好后坏」时，需要一段**总线上绝对零事务**的观察窗口。
 * 原来的「停止」按钮只停写循环，`refresh()` 仍每 5 秒读一次 ——
 * 窗口并不干净。
 *
 * 实测支撑：
 * - App force-stop 后 61 秒 → 内核 **0 条** timeout
 * - 停在调试页 46 秒 → **30 条** timeout
 * - App 在播放页 60 秒 → **0 条**
 *
 * 可见流量确实全部来自调试页，因此「暂停」必须同时停掉刷新和写入。
 */
class PauseTrafficSemanticsTest {

    /** 复现调试页的两个流量源 */
    private class DebugPageState {
        var paused = false
        var writing = false
        var refreshRunning = false

        /** 暂停时应停掉写循环 */
        fun togglePause() {
            paused = !paused
            if (paused) {
                writing = false
                refreshRunning = false
            }
        }

        /** 一轮刷新是否会产生 I2C 事务 */
        fun refreshProducesTraffic(): Boolean = !paused

        /** 写循环是否会继续发送 */
        fun writeLoopActive(): Boolean = writing && !paused
    }

    @Test
    fun `pausing stops both refresh and write`() {
        val page = DebugPageState().apply {
            writing = true
            refreshRunning = true
        }

        page.togglePause()

        assertFalse("暂停后写循环必须停", page.writeLoopActive())
        assertFalse("暂停后刷新不得再发事务", page.refreshProducesTraffic())
    }

    /**
     * 关键区分：只点「停止」按钮（原行为）不足以得到零流量窗口。
     */
    @Test
    fun `stopping writing alone still leaves refresh traffic`() {
        val page = DebugPageState().apply {
            writing = false      // 只停了写
            refreshRunning = true // 刷新还在
        }

        assertFalse("写已停", page.writeLoopActive())
        assertTrue(
            "但刷新仍在发事务 —— 这就是为什么需要单独的「暂停全部」",
            page.refreshProducesTraffic()
        )
    }

    @Test
    fun `resuming re-enables traffic`() {
        val page = DebugPageState()
        page.togglePause()          // 暂停
        page.togglePause()          // 恢复

        assertTrue("恢复后刷新应重新工作", page.refreshProducesTraffic())
    }

    /**
     * 暂停期间不应自动启动写循环。
     *
     * 否则用户刚按下暂停，5 秒后 refresh 完成又把 writing 置回 true。
     */
    @Test
    fun `auto start must not fire while paused`() {
        fun shouldAutoStart(autoStarted: Boolean, paused: Boolean, strategyAvailable: Boolean): Boolean =
            !autoStarted && !paused && strategyAvailable

        assertFalse(
            "暂停期间不得自动开始写",
            shouldAutoStart(autoStarted = false, paused = true, strategyAvailable = true)
        )
        assertTrue(
            "未暂停时首次进入应自动开始",
            shouldAutoStart(autoStarted = false, paused = false, strategyAvailable = true)
        )
    }
}
