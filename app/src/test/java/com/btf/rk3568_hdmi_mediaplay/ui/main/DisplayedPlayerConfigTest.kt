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

    /**
     * 回归防线：「同画面」模式下四格必须播**同一个文件**。
     *
     * 曾经的问题：P2–P4 各自按自己的播放完成时刻 `currentIndex++`，
     * 而更新索引时只写了 P1，导致四格的 currentIndex 渐渐错开 ——
     * 表现为「same 模式播着播着四格不是同一个视频了」。
     * 时间偏移可以用 seekTo 纠正，**播的文件不同无法纠正**。
     */
    @Test
    fun `same-content mode keeps every pane on the same media index`() {
        val multi = listOf(
            MediaItem("/media/a.mp4", "a.mp4", MediaType.VIDEO, MediaSource.LOCAL),
            MediaItem("/media/b.mp4", "b.mp4", MediaType.VIDEO, MediaSource.LOCAL),
            MediaItem("/media/c.mp4", "c.mp4", MediaType.VIDEO, MediaSource.LOCAL)
        )
        val shared = List(4) { PlayerConfig(index = it, mediaItems = multi, currentIndex = 1) }

        // 四格解析出来的「当前播放文件」必须是同一个
        val paths = (0..3).map { cell ->
            val cfg = displayedPlayerConfig(LayoutMode.GRID_2X2_SAME, shared, cell)
            cfg.mediaItems[cfg.currentIndex].path
        }
        assertEquals(listOf("/media/b.mp4", "/media/b.mp4", "/media/b.mp4", "/media/b.mp4"), paths)
    }

    @Test
    fun `same-content mode only pane 0 owns playback events`() {
        // 只有 P1 允许推进索引与上报事件，P2–P4 是纯镜像。
        // 这条约束由 QuadPlayerLayout 的 followsPrimary / reportEvents 保证，
        // 此处断言其依据：镜像格映射回同一个 source index。
        assertEquals(0, LayoutMode.GRID_2X2_SAME.sourcePlayerIndex(1))
        assertEquals(0, LayoutMode.GRID_2X2_SAME.sourcePlayerIndex(2))
        assertEquals(0, LayoutMode.GRID_2X2_SAME.sourcePlayerIndex(3))
    }
}
