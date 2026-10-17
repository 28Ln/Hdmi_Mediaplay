package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
        assertFalse(base.showLanguageSetting)
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

    @Test
    fun `orientation auto switch feature parses from json without unknown key warning`() {
        // 直接传 JSON 字符串（与 UsbConfigLoader.loadConfig 的生产路径一致）
        val config = UsbConfig.fromJson("""{"features":{"allowOrientationAutoSwitch":false}}""")

        assertNotNull(config)
        val merged = config!!.features!!.applyTo(FeatureFlags.releaseDefaults())
        assertFalse(merged.allowOrientationAutoSwitch)
        assertTrue(config.validationWarnings.isEmpty())
    }

    @Test
    fun `usb config accepts portrait wall modes`() {
        val warnings = mutableListOf<String>()

        val validatedMode = UsbConfig.validateEnumValue(
            LayoutMode.GRID_1X3_PORTRAIT.name,
            LayoutMode.entries.map { it.name }.toSet(),
            warnings,
            "settings",
            "layoutMode"
        )

        assertEquals(LayoutMode.GRID_1X3_PORTRAIT.name, validatedMode)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `usb config accepts same-content 2x2 mode`() {
        val warnings = mutableListOf<String>()

        val validatedMode = UsbConfig.validateEnumValue(
            LayoutMode.GRID_2X2_SAME.name,
            LayoutMode.entries.map { it.name }.toSet(),
            warnings,
            "settings",
            "layoutMode"
        )

        assertEquals(LayoutMode.GRID_2X2_SAME.name, validatedMode)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `audio fallback setting parses from json without unknown key warning`() {
        // 关闭音频降级：音频轨不受支持时维持整格报错，而非静音播视频
        val config = UsbConfig.fromJson("""{"settings":{"audioFallbackSilent":false}}""")

        assertNotNull(config)
        assertFalse(config!!.settings!!.audioFallbackSilent!!)
        assertTrue(config.validationWarnings.isEmpty())
    }

    @Test
    fun `audio fallback defaults to enabled when absent`() {
        val config = UsbConfig.fromJson("""{"settings":{"layoutMode":"GRID_2X2"}}""")

        assertNotNull(config)
        assertNull(config!!.settings!!.audioFallbackSilent)
        // 未在配置中指定时，AppSettings 的默认值为 true（静音降级）
        assertTrue(AppSettings().audioFallbackSilent)
        assertTrue(config.validationWarnings.isEmpty())
    }

    @Test
    fun `usbScanFolderName rejects path traversal values`() {
        // 该值会被 File(usbPath, folderName) 直接拼接；若放行会逃出 U 盘。
        listOf("../..", "..", ".", "a/b", "a\\b", "/sdcard").forEach { evil ->
            val warnings = mutableListOf<String>()
            val result = UsbConfig.validateFolderName(evil, warnings, "settings", "usbScanFolderName")
            assertNull("应拒绝路径穿越值: $evil", result)
            assertTrue("应记录告警: $evil", warnings.isNotEmpty())
        }
    }

    @Test
    fun `usbScanFolderName accepts a normal single directory name`() {
        val warnings = mutableListOf<String>()
        val result = UsbConfig.validateFolderName("btf_media", warnings, "settings", "usbScanFolderName")
        assertEquals("btf_media", result)
        assertTrue(warnings.isEmpty())
    }

    // 回归防线：枚举归一化必须用 Locale.ROOT。
    // 土耳其语环境下 "pip".uppercase() == "PİP"（带点大写 İ），与枚举值 "PIP" 不匹配，
    // 会让合法配置被静默判为非法并回退默认值 —— 属"能编译、能运行、行为错"的一类。
    @Test
    fun `enum normalization is immune to turkish locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val warnings = mutableListOf<String>()

            assertEquals("PIP", UsbConfig.validateEnumValue("pip", setOf("PIP", "FULL"), warnings, "s", "f"))
            assertEquals("pip", UsbConfig.validateAliasValue("PIP", setOf("pip", "full"), warnings, "s", "f"))

            assertTrue("Locale.ROOT 下不应产生告警: $warnings", warnings.isEmpty())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `config parsing under turkish locale still accepts valid enum values`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val config = UsbConfig.fromJson("""{"settings":{"layoutMode":"GRID_2X2"}}""")

            assertNotNull(config)
            assertTrue(
                "tr-TR 下合法枚举值不应产生告警: ${config!!.validationWarnings}",
                config.validationWarnings.isEmpty()
            )
        } finally {
            Locale.setDefault(original)
        }
    }
}
