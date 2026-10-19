package com.btf.rk3568_hdmi_mediaplay.hardware

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局 <-> 硬件模式映射的回归防线。
 *
 * 这里锁死的是**曾经发生过的缺陷**：`GRID_2X1` 的示意图编号是 5，
 * 而硬件模式是 2。若有人图省事直接复用 `wallModeNumber`，
 * 2×1 会被切成 2×2，且硬件不会报错 —— 只有这组测试能发现。
 */
class HdmiMatrixModeMappingTest {

    @Test
    fun `GRID_2X1 maps to hardware mode 2 not diagram number 5`() {
        assertEquals(2, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_2X1))
        // 明确对比：示意图编号是 5，硬件值必须不同
        assertEquals(5, LayoutMode.GRID_2X1.wallModeNumber)
        assertTrue(
            "GRID_2X1 的硬件值绝不能等于 wallModeNumber",
            HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_2X1) !=
                LayoutMode.GRID_2X1.wallModeNumber
        )
    }

    @Test
    fun `every hardware mode 0 to 7 is covered`() {
        val hardwareValues = HdmiMatrixModeMapping.layoutMapping.values.filterNotNull().toSet()
        assertEquals(
            setOf(1, 2, 3, 4, 5, 6, 7),
            hardwareValues
        )
        // 0 = 1X1：App 不请求，因此映射表里不应出现
        assertFalse(0 in hardwareValues)
    }

    @Test
    fun `hardware mapping matches the documented ABI table`() {
        assertEquals(1, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_1X2))
        assertEquals(2, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_2X1))
        assertEquals(3, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_1X3))
        assertEquals(4, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_3X1))
        assertEquals(5, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.GRID_2X2))
        assertEquals(6, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.ROW_1X4))
        assertEquals(7, HdmiMatrixModeMapping.hardwareModeFor(LayoutMode.COLUMN_4X1))
    }

    @Test
    fun `software-only layouts have no hardware mode`() {
        listOf(
            LayoutMode.SINGLE,
            LayoutMode.GRID_1X2_PORTRAIT,
            LayoutMode.GRID_1X3_PORTRAIT,
            LayoutMode.GRID_2X2_SAME,
            LayoutMode.PIP
        ).forEach { layout ->
            assertNull("$layout 应为纯软件布局", HdmiMatrixModeMapping.hardwareModeFor(layout))
        }
    }

    @Test
    fun `every LayoutMode has an explicit mapping entry`() {
        // 新增枚举值时本测试会失败，强制作者决定「有无硬件模式」
        assertEquals(
            LayoutMode.entries.toSet(),
            HdmiMatrixModeMapping.layoutMapping.keys
        )
    }

    @Test
    fun `inverse mapping returns null for 1X1 and out-of-range`() {
        assertNull(HdmiMatrixModeMapping.layoutModeFor(0))
        assertNull(HdmiMatrixModeMapping.layoutModeFor(-1))
        assertNull(HdmiMatrixModeMapping.layoutModeFor(8))
        assertEquals(LayoutMode.GRID_1X2, HdmiMatrixModeMapping.layoutModeFor(1))
        assertEquals(LayoutMode.GRID_2X1, HdmiMatrixModeMapping.layoutModeFor(2))
        assertEquals(LayoutMode.GRID_2X2, HdmiMatrixModeMapping.layoutModeFor(5))
        assertEquals(LayoutMode.COLUMN_4X1, HdmiMatrixModeMapping.layoutModeFor(7))
    }

    @Test
    fun `inverse mapping round-trips every mapped layout`() {
        HdmiMatrixModeMapping.layoutMapping.forEach { (layout, hardware) ->
            if (hardware != null) {
                assertEquals(layout, HdmiMatrixModeMapping.layoutModeFor(hardware))
            }
        }
    }

    @Test
    fun `mirror threshold differs per board variant`() {
        // TX3：mode >= 3 才允许镜像
        assertFalse(HdmiMatrixModeMapping.mirrorAllowedForMode(2, HdmiBoardVariant.TX3))
        assertTrue(HdmiMatrixModeMapping.mirrorAllowedForMode(3, HdmiBoardVariant.TX3))
        // TX4：门槛更高，mode >= 5
        assertFalse(HdmiMatrixModeMapping.mirrorAllowedForMode(4, HdmiBoardVariant.TX4))
        assertTrue(HdmiMatrixModeMapping.mirrorAllowedForMode(5, HdmiBoardVariant.TX4))
    }

    @Test
    fun `supportsMirror is false for software layouts and low modes`() {
        // GRID_1X2 -> mode 1，任何板型都不支持镜像
        assertFalse(HdmiMatrixModeMapping.supportsMirror(LayoutMode.GRID_1X2))
        // GRID_2X1 -> mode 2
        assertFalse(HdmiMatrixModeMapping.supportsMirror(LayoutMode.GRID_2X1))
        // 软件布局
        assertFalse(HdmiMatrixModeMapping.supportsMirror(LayoutMode.SINGLE))
        assertFalse(HdmiMatrixModeMapping.supportsMirror(LayoutMode.PIP))
        // TX4 下 GRID_2X2 -> mode 5
        assertTrue(HdmiMatrixModeMapping.supportsMirror(LayoutMode.GRID_2X2))
        // 同一布局在 TX3 下更宽松：GRID_1X3 -> mode 3
        assertTrue(HdmiMatrixModeMapping.supportsMirror(LayoutMode.GRID_1X3, HdmiBoardVariant.TX3))
        assertFalse(HdmiMatrixModeMapping.supportsMirror(LayoutMode.GRID_1X3, HdmiBoardVariant.TX4))
    }

    @Test
    fun `mirror mask is bounded by board variant`() {
        // TX3 只有 3 路，0x0F 越界
        assertEquals(0..0x07, HdmiMatrixModeMapping.mirrorMaskError(0x0F, HdmiBoardVariant.TX3))
        assertNull(HdmiMatrixModeMapping.mirrorMaskError(0x07, HdmiBoardVariant.TX3))
        // TX4 允许 0x0F
        assertNull(HdmiMatrixModeMapping.mirrorMaskError(0x0F, HdmiBoardVariant.TX4))
        assertEquals(0..0x0F, HdmiMatrixModeMapping.mirrorMaskError(0x10, HdmiBoardVariant.TX4))
        assertEquals(0..0x0F, HdmiMatrixModeMapping.mirrorMaskError(-1, HdmiBoardVariant.TX4))
    }

    @Test
    fun `node ranges match the ABI`() {
        assertEquals(0..7, HdmiMatrixNode.MODE.allowedRange)
        assertEquals(0..2, HdmiMatrixNode.RESOLUTION.allowedRange)
        assertEquals(0..15, HdmiMatrixNode.MIRROR.allowedRange)
        assertEquals(0..3, HdmiMatrixNode.ROTATE.allowedRange)
        assertEquals(0..255, HdmiMatrixNode.HFUSION.allowedRange)
        assertEquals(0..255, HdmiMatrixNode.VFUSION.allowedRange)
        assertEquals(0..1, HdmiMatrixNode.MUTE.allowedRange)
        assertEquals(0..1, HdmiMatrixNode.POWER.allowedRange)
    }

    @Test
    fun `read-only nodes are marked read-only`() {
        listOf(
            HdmiMatrixNode.VERSION,
            HdmiMatrixNode.STATUS,
            HdmiMatrixNode.RESULT,
            HdmiMatrixNode.CMD_COUNT,
            HdmiMatrixNode.RX_STATE
        ).forEach { assertFalse("$it 必须只读", it.writable) }

        listOf(
            HdmiMatrixNode.MODE,
            HdmiMatrixNode.RESOLUTION,
            HdmiMatrixNode.MIRROR,
            HdmiMatrixNode.ROTATE,
            HdmiMatrixNode.HFUSION,
            HdmiMatrixNode.VFUSION,
            HdmiMatrixNode.MUTE,
            HdmiMatrixNode.POWER
        ).forEach { assertTrue("$it 必须可写", it.writable) }
    }

    @Test
    fun `node paths are relative to the I2C base`() {
        HdmiMatrixNode.entries.forEach { node ->
            assertEquals(
                "${HardwareConfig.I2C_BASE_PATH}/${node.relativePath}",
                node.absolutePath
            )
        }
        assertEquals("/sys/bus/i2c/devices/4-0030", HardwareConfig.I2C_BASE_PATH)
    }

    @Test
    fun `resolution enum values match sysfs contract`() {
        assertEquals(0, HdmiResolution.P720.sysfsValue)
        assertEquals(1, HdmiResolution.P1080.sysfsValue)
        assertEquals(2, HdmiResolution.P2160_30.sysfsValue)
        assertEquals(HdmiResolution.P1080, HdmiResolution.fromSysfsValue(1))
        assertNull(HdmiResolution.fromSysfsValue(3))
    }
}
