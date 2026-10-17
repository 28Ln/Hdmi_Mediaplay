package com.btf.rk3568_hdmi_mediaplay.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelDecisionTest {

    @Test
    fun `returns no-media when usb has no valid media`() {
        val decision = MainViewModel.decideUsbCopyDecision(
            hasMediaContent = false,
            requiredBytes = 1024,
            availableBytes = 2048,
            hasLocalContent = true,
            showOverwriteConfirm = true
        )

        assertEquals(MainViewModel.UsbCopyDecision.NoMediaContent, decision)
    }

    @Test
    fun `returns insufficient-space when required exceeds available`() {
        val decision = MainViewModel.decideUsbCopyDecision(
            hasMediaContent = true,
            requiredBytes = 4096,
            availableBytes = 1024,
            hasLocalContent = false,
            showOverwriteConfirm = false
        )

        assertEquals(
            MainViewModel.UsbCopyDecision.InsufficientSpace(
                requiredBytes = 4096,
                availableBytes = 1024
            ),
            decision
        )
    }

    @Test
    fun `returns overwrite confirmation when local cache exists and confirm is enabled`() {
        val decision = MainViewModel.decideUsbCopyDecision(
            hasMediaContent = true,
            requiredBytes = 1024,
            availableBytes = 4096,
            hasLocalContent = true,
            showOverwriteConfirm = true
        )

        assertEquals(MainViewModel.UsbCopyDecision.RequireOverwriteConfirmation, decision)
    }

    @Test
    fun `returns copy immediately when space is enough and no overwrite prompt needed`() {
        val decision = MainViewModel.decideUsbCopyDecision(
            hasMediaContent = true,
            requiredBytes = 1024,
            availableBytes = 4096,
            hasLocalContent = false,
            showOverwriteConfirm = true
        )

        assertEquals(MainViewModel.UsbCopyDecision.CopyImmediately, decision)
    }

    /**
     * 回归防线：错误重试必须是**指数退避**。
     *
     * 第一版实现的缺陷：重试触发 rescan → 仍失败 → 再次进入 enterUsbErrorState，
     * 而该函数每次都把 delay 重置成初始值，于是永远 5 秒重试一次，
     * 指数退避完全失效（空间不足时会把日志刷满、白白唤醒 CPU）。
     * 现在退避计数跨重试保留，本测试锁死递增语义。
     */
    @Test
    fun `usb retry delay grows exponentially and caps out`() {
        val d0 = MainViewModel.usbRetryDelayMs(0)
        val d1 = MainViewModel.usbRetryDelayMs(1)
        val d2 = MainViewModel.usbRetryDelayMs(2)
        val d3 = MainViewModel.usbRetryDelayMs(3)

        assertEquals(5_000L, d0)
        assertEquals(10_000L, d1)
        assertEquals(20_000L, d2)
        assertEquals(40_000L, d3)

        // 递增，不能恒等于初始值（那正是曾经的缺陷）
        assertTrue("退避必须递增", d1 > d0 && d2 > d1 && d3 > d2)

        // 上限封顶，避免退避到几乎不重试
        assertEquals(60_000L, MainViewModel.usbRetryDelayMs(10))
        assertEquals(60_000L, MainViewModel.usbRetryDelayMs(100))
    }
}
