package com.btf.rk3568_hdmi_mediaplay.ui.main

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayedPlayerConfigTest {

    private val player1 = PlayerConfig(
        index = 0,
        mediaItems = listOf(
            MediaItem("/media/a.mp4", "a.mp4", MediaType.VIDEO, MediaSource.LOCAL)
        ),
        isMuted = false
    )
    private val player2 = PlayerConfig(index = 1, mediaItems = emptyList(), isMuted = false)
    private val configs = listOf(player1, player2, PlayerConfig(index = 2), PlayerConfig(index = 3))

    @Test
    fun `same-content mode clones player 1 media to all panes and mutes clones`() {
        val pane0 = displayedPlayerConfig(LayoutMode.GRID_2X2_SAME, configs, 0)
        val pane3 = displayedPlayerConfig(LayoutMode.GRID_2X2_SAME, configs, 3)

        assertEquals(player1.mediaItems, pane0.mediaItems)
        assertEquals(player1.mediaItems, pane3.mediaItems)
        assertFalse(pane0.isMuted)
        assertTrue(pane3.isMuted)
        assertEquals(3, pane3.index)
    }

    @Test
    fun `regular 2x2 keeps independent playlists`() {
        val pane1 = displayedPlayerConfig(LayoutMode.GRID_2X2, configs, 1)
        assertEquals(emptyList<MediaItem>(), pane1.mediaItems)
        assertEquals(1, pane1.index)
    }
}
