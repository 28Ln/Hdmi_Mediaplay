package com.btf.rk3568_hdmi_mediaplay

import com.btf.rk3568_hdmi_mediaplay.data.model.FeatureFlags
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbConfig
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbConfigFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureManagerTest {

    @Test
    fun `init uses debug defaults when build is debug`() {
        FeatureManager.init(true)

        assertEquals(FeatureFlags.debugDefaults(), FeatureManager.getCurrentFlags())
        assertTrue(FeatureManager.isDebug())
    }

    @Test
    fun `init uses release defaults when build is release`() {
        FeatureManager.init(false)

        val current = FeatureManager.getCurrentFlags()
        assertEquals(FeatureFlags.releaseDefaults(), current)
        assertFalse(current.showSettingsButton)
        assertFalse(current.allowManualFileSelect)
        assertFalse(FeatureManager.isDebug())
    }

    @Test
    fun `usb config overrides release defaults and clears back to base`() {
        FeatureManager.init(false)

        FeatureManager.applyUsbConfig(
            UsbConfig(
                features = UsbConfigFeatures(
                    showSettingsButton = true,
                    allowManualFileSelect = true
                )
            )
        )

        assertTrue(FeatureManager.getCurrentFlags().showSettingsButton)
        assertTrue(FeatureManager.getCurrentFlags().allowManualFileSelect)

        FeatureManager.applyUsbConfig(null)

        assertEquals(FeatureFlags.releaseDefaults(), FeatureManager.getCurrentFlags())
    }
}
