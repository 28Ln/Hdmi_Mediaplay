package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbConfigTest {

    @Test
    fun `feature overrides only replace provided values`() {
        val base = FeatureFlags.releaseDefaults()
        val merged = UsbConfigFeatures(
            showSettingsButton = true,
            allowManualFileSelect = true,
            showImageSplitTool = true,
            showLanguageSetting = true,
            showHdmiControl = true
        ).applyTo(base)

        assertTrue(merged.showSettingsButton)
        assertTrue(merged.allowManualFileSelect)
        assertTrue(merged.showImageSplitTool)
        assertTrue(merged.showLanguageSetting)
        assertTrue(merged.showHdmiControl)
        assertEquals(base.showBottomControlBar, merged.showBottomControlBar)
        assertFalse(base.showSettingsButton)
    }

    @Test
    fun `validation helpers reject invalid values and record warnings`() {
        val warnings = mutableListOf<String>()

        val invalidLayout = UsbConfig.validateEnumValue(
            "not_real",
            LayoutMode.entries.map { it.name }.toSet(),
            warnings,
            "settings",
            "layoutMode"
        )
        val invalidLanguage = UsbConfig.validateAliasValue(
            "jp",
            setOf("zh", "chinese", "en", "english"),
            warnings,
            "settings",
            "language"
        )
        val invalidColor = UsbConfig.validateHexColor("blue", warnings, "settings", "backgroundColor")
        val invalidVolume = UsbConfig.validateIntRange(120, 0, 100, warnings, "settings", "defaultVolume")
        val invalidInterval = UsbConfig.validateIntRange(0, 1, 60, warnings, "settings", "imageIntervalSeconds")
        val blankFolder = UsbConfig.validateNonBlankString("   ", warnings, "settings", "usbScanFolderName")

        assertNull(invalidLayout)
        assertNull(invalidLanguage)
        assertNull(invalidColor)
        assertNull(invalidVolume)
        assertNull(invalidInterval)
        assertNull(blankFolder)
        assertTrue(warnings.any { it.contains("Invalid enum value for settings.layoutMode") })
        assertTrue(warnings.any { it.contains("Invalid value for settings.language") })
        assertTrue(warnings.any { it.contains("Invalid color for settings.backgroundColor") })
        assertTrue(warnings.any { it.contains("Out of range for settings.defaultVolume") })
        assertTrue(warnings.any { it.contains("Out of range for settings.imageIntervalSeconds") })
        assertTrue(warnings.any { it.contains("Blank value is not allowed for settings.usbScanFolderName") })
    }
}
