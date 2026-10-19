package com.btf.rk3568_hdmi_mediaplay.data.repository

import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置写入的**丢失更新**回归测试（2026-10-19）。
 *
 * 场景：U 盘下发配置与用户在设置页操作几乎同时发生。两者各自基于
 * 「自己读到的快照」生成目标状态，若整份写回，后落盘的一方会覆盖另一方。
 *
 * 修复方式：落盘事务内重读磁盘值，只应用「基线→目标」的**字段增量**。
 * 这些测试固化 [SettingsRepository.applyDelta] 的语义。
 */
class SettingsDeltaTest {

    private val base = AppSettings(
        layoutMode = LayoutMode.GRID_2X2,
        defaultVolume = 50,
        imageIntervalSeconds = 5
    )

    @Test
    fun `only changed fields are applied over the latest disk state`() {
        // 磁盘已被另一条链路改成 volume=70、layout=1X2
        val disk = base.copy(defaultVolume = 70, layoutMode = LayoutMode.GRID_1X2)

        // 本调用方的基线是 base，只想把 interval 改成 9
        val desired = base.copy(imageIntervalSeconds = 9)

        val merged = SettingsRepository.applyDeltaForTest(disk, base, desired)

        assertEquals("本次改动的字段必须生效", 9, merged.imageIntervalSeconds)
        assertEquals("并发写入的音量不能被覆盖", 70, merged.defaultVolume)
        assertEquals("并发写入的布局不能被覆盖", LayoutMode.GRID_1X2, merged.layoutMode)
    }

    @Test
    fun `the exact lost-update scenario from the field`() {
        // T0：用户改音量 50 -> 70，基线 50
        val userBaseline = base
        val userDesired = base.copy(defaultVolume = 70)

        // T1：U 盘下发把布局改成 1X2，基线同样是 50
        val usbBaseline = base
        val usbDesired = base.copy(layoutMode = LayoutMode.GRID_1X2)

        // T2：用户先落盘（磁盘从 base 变成 volume=70）
        val afterUser = SettingsRepository.applyDeltaForTest(base, userBaseline, userDesired)
        assertEquals(70, afterUser.defaultVolume)

        // T3：U 盘配置后落盘 —— 不应把音量打回 50
        val afterUsb = SettingsRepository.applyDeltaForTest(afterUser, usbBaseline, usbDesired)
        assertEquals("U 盘下发不能覆盖用户同时改的音量", 70, afterUsb.defaultVolume)
        assertEquals("U 盘下发的布局必须生效", LayoutMode.GRID_1X2, afterUsb.layoutMode)
    }

    @Test
    fun `no-op delta keeps disk state untouched`() {
        val disk = base.copy(defaultVolume = 70)
        val merged = SettingsRepository.applyDeltaForTest(disk, base, base)
        assertEquals(disk, merged)
    }
}
