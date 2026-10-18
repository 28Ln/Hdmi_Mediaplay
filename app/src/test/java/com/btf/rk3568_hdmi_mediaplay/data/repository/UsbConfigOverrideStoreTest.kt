package com.btf.rk3568_hdmi_mediaplay.data.repository

import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U 盘配置覆盖 / 回滚的纯函数测试。
 *
 * 这个功能的核心风险不是"能不能写进去"，而是**回滚时误伤**：
 * 把运维手动改过的设置也一并还原，等于替客户做了决定。
 * 因此重点覆盖「只回滚仍等于 U 盘写入值的键」。
 */
class UsbConfigOverrideStoreTest {

    @Test
    fun `every supported key is readable`() {
        val s = AppSettings()
        UsbConfigOverrideStore.SUPPORTED_KEYS.forEach { key ->
            assertTrue("readKey($key) returned null", UsbConfigOverrideStore.readKey(s, key) != null)
        }
    }

    @Test
    fun `applyOverrides records only changed keys`() {
        val before = AppSettings(layoutMode = com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X2)
        val (after, entries) = UsbConfigOverrideStore.applyOverrides(
            before,
            mapOf(
                "layoutMode" to "GRID_1X2",
                "defaultVolume" to before.defaultVolume.toString()   // 值相同 → 不记录
            )
        )
        assertEquals(com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2, after.layoutMode)
        assertEquals(1, entries.size)
        assertEquals("layoutMode", entries[0].name)
        assertEquals("GRID_2X2", entries[0].original)
        assertEquals("GRID_1X2", entries[0].applied)
    }

    @Test
    fun `revert restores untouched keys`() {
        val original = AppSettings()
        val entries = listOf(
            UsbConfigOverrideStore.Entry("layoutMode", "GRID_2X2", "GRID_1X2"),
            UsbConfigOverrideStore.Entry("defaultVolume", "100", "0")
        )
        val overridden = original.copy(
            layoutMode = com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2,
            defaultVolume = 0
        )

        val (reverted, keys) = UsbConfigOverrideStore.revert(overridden, entries)

        assertEquals(original.layoutMode, reverted.layoutMode)
        assertEquals(100, reverted.defaultVolume)
        assertEquals(2, keys.size)
    }

    @Test
    fun `revert keeps values the operator changed manually`() {
        val entries = listOf(
            UsbConfigOverrideStore.Entry("defaultVolume", "100", "0")
        )
        // U 盘设了 0，运维后来手动改成 55 → 不应被回滚成 100
        val current = AppSettings(defaultVolume = 55)

        val (reverted, keys) = UsbConfigOverrideStore.revert(current, entries)

        assertEquals(55, reverted.defaultVolume)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `encode decode round trip`() {
        val entries = listOf(
            UsbConfigOverrideStore.Entry("layoutMode", "GRID_2X2", "GRID_1X2"),
            UsbConfigOverrideStore.Entry("usbScanFolderName", "media", "btf_media")
        )
        assertEquals(entries, UsbConfigOverrideStore.decode(UsbConfigOverrideStore.encode(entries)))
    }

    @Test
    fun `decode tolerates blank and malformed input`() {
        assertTrue(UsbConfigOverrideStore.decode(null).isEmpty())
        assertTrue(UsbConfigOverrideStore.decode("").isEmpty())
        assertTrue(UsbConfigOverrideStore.decode("garbage\nonly\ttwo").isEmpty())
    }

    @Test
    fun `writeKey ignores invalid values instead of throwing`() {
        val s = AppSettings()
        val result = UsbConfigOverrideStore.writeKey(s, "layoutMode", "NOT_A_MODE")
        assertEquals(s, result)
    }
}
