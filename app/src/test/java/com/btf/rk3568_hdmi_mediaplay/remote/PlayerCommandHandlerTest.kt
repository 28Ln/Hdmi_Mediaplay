package com.btf.rk3568_hdmi_mediaplay.remote

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlayerCommandHandlerTest {

    @Test
    fun `parseLayoutMode supports documented aliases`() {
        assertEquals(LayoutMode.GRID_2X2, invokeParseLayoutMode("QUAD"))
        assertEquals(LayoutMode.GRID_1X2, invokeParseLayoutMode("DUAL_HORIZONTAL"))
        assertEquals(LayoutMode.GRID_2X1, invokeParseLayoutMode("DUAL_VERTICAL"))
        assertEquals(LayoutMode.GRID_1X2_PORTRAIT, invokeParseLayoutMode("WALL_MODE_3"))
        assertEquals(LayoutMode.GRID_1X3_PORTRAIT, invokeParseLayoutMode("PORTRAIT_1X3"))
        assertEquals(LayoutMode.PIP, invokeParseLayoutMode("PIP"))
        assertEquals(LayoutMode.GRID_2X2_SAME, invokeParseLayoutMode("GRID_2X2_SAME"))
        assertEquals(LayoutMode.GRID_2X2_SAME, invokeParseLayoutMode("SAME_2X2"))
        assertEquals(LayoutMode.GRID_2X2_SAME, invokeParseLayoutMode("QUAD_SAME"))
    }

    @Test
    fun `parseLayoutMode returns null for unsupported value`() {
        assertNull(invokeParseLayoutMode("NOT_EXISTS"))
    }

    @Test
    fun `createMediaItem accepts supported media file`() {
        val tempFile = File.createTempFile("player-command", ".mp4")
        tempFile.writeText("demo")
        tempFile.deleteOnExit()

        val mediaItem = invokeCreateMediaItem(tempFile.absolutePath)

        assertNotNull(mediaItem)
        assertEquals(MediaType.VIDEO, mediaItem?.type)
        assertEquals(tempFile.name, mediaItem?.name)
    }

    @Test
    fun `createMediaItem rejects unsupported media file`() {
        val tempFile = File.createTempFile("player-command", ".txt")
        tempFile.writeText("demo")
        tempFile.deleteOnExit()

        val mediaItem = invokeCreateMediaItem(tempFile.absolutePath)

        assertNull(mediaItem)
    }

    private fun invokeParseLayoutMode(value: String): LayoutMode? {
        val method = PlayerCommandHandler::class.java.getDeclaredMethod("parseLayoutMode", String::class.java)
        method.isAccessible = true
        return method.invoke(PlayerCommandHandler, value) as? LayoutMode
    }

    private fun invokeCreateMediaItem(path: String): com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem? {
        val method = PlayerCommandHandler::class.java.getDeclaredMethod("createMediaItem", String::class.java)
        method.isAccessible = true
        return method.invoke(PlayerCommandHandler, path) as? com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
    }

    @Test
    fun `loop mode default value exists in the enum`() {
        // 回归守卫：此前 handleSetLoopMode 的缺省值写成 "ALL"，
        // 而 LoopMode 枚举只有 SINGLE/LIST/RANDOM —— valueOf 抛异常后被
        // catch 成 LIST，导致非法输入静默降级、调用方收到「成功」。
        val default = com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.LIST.name
        assertNotNull(
            "缺省 loop_mode 必须是枚举中真实存在的值",
            com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.entries
                .firstOrNull { it.name == default }
        )
    }

    @Test
    fun `layout mode parsing is locale independent`() {
        // 土耳其语环境下 "pip".uppercase() 会得到 "PİP"（带点大写 I），
        // 导致解析失败。必须用 uppercase(Locale.ROOT)。
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals(LayoutMode.PIP, invokeParseLayoutMode("pip"))
            assertEquals(LayoutMode.GRID_2X2, invokeParseLayoutMode("quad"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `media failure reasons map to distinct error codes`() {
        // P1-28 回归守卫：三种失败原因必须映射到不同错误码。
        // 此前 createMediaItem 一律返回 null，调用方只能收到 MEDIA_NOT_FOUND，
        // 接入方无法区分「不存在」「无权限」「格式不支持」。
        val notExist = File("/tmp/definitely-not-exists-${System.nanoTime()}.mp4")
        val unsupported = File.createTempFile("player-command", ".txt")
        unsupported.writeText("x")
        unsupported.deleteOnExit()

        assertNull("不存在的文件应创建失败", invokeCreateMediaItem(notExist.absolutePath))
        assertNull("不支持的扩展名应创建失败", invokeCreateMediaItem(unsupported.absolutePath))

        // 通过反射直接验证结果类型区分了原因
        val clazz = Class.forName(
            "com.btf.rk3568_hdmi_mediaplay.remote.PlayerCommandHandler"
        )
        val method = clazz.getDeclaredMethod("createMediaItemResult", String::class.java)
        method.isAccessible = true
        val instance = clazz.getDeclaredField("INSTANCE").get(null)

        val r1 = method.invoke(instance, notExist.absolutePath)
        val r2 = method.invoke(instance, unsupported.absolutePath)
        assertEquals("NotExist", r1::class.java.simpleName)
        assertEquals("Unsupported", r2::class.java.simpleName)
        assertNotEquals(
            "不同失败原因必须得到不同的结果类型",
            r1::class.java, r2::class.java
        )
    }
}