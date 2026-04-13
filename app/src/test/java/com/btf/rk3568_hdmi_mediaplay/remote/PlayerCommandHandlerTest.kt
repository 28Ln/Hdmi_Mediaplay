package com.btf.rk3568_hdmi_mediaplay.remote

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlayerCommandHandlerTest {

    @Test
    fun `parseLayoutMode supports documented aliases`() {
        assertEquals(LayoutMode.GRID_2X2, invokeParseLayoutMode("QUAD"))
        assertEquals(LayoutMode.GRID_1X2, invokeParseLayoutMode("DUAL_HORIZONTAL"))
        assertEquals(LayoutMode.GRID_2X1, invokeParseLayoutMode("DUAL_VERTICAL"))
        assertEquals(LayoutMode.PIP, invokeParseLayoutMode("PIP"))
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
}
