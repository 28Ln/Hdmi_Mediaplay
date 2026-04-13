package com.btf.rk3568_hdmi_mediaplay.ui.main

import org.junit.Assert.assertEquals
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
}
