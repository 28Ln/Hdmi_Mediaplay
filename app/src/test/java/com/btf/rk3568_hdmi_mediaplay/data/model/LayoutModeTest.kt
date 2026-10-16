package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutModeTest {

    @Test
    fun `wall modes match diagram order and orientation`() {
        assertEquals(
            listOf(
                LayoutMode.GRID_1X2,
                LayoutMode.GRID_1X3,
                LayoutMode.GRID_1X2_PORTRAIT,
                LayoutMode.GRID_1X3_PORTRAIT,
                LayoutMode.GRID_2X1,
                LayoutMode.GRID_3X1,
                LayoutMode.GRID_2X2
            ),
            LayoutMode.wallModes
        )
        assertEquals(
            (1..7).toList(),
            LayoutMode.wallModes.mapNotNull { it.wallModeNumber }
        )
        assertEquals(PanelOrientation.PORTRAIT, LayoutMode.GRID_1X2_PORTRAIT.panelOrientation)
        assertEquals(PanelOrientation.PORTRAIT, LayoutMode.GRID_1X3_PORTRAIT.panelOrientation)
    }

    @Test
    fun `wall modes expose the expected player count`() {
        assertEquals(
            listOf(2, 3, 2, 3, 2, 3, 4),
            LayoutMode.wallModes.map { it.visiblePlayerCount }
        )
    }

    @Test
    fun `only portrait wall modes report isPortraitWall`() {
        assertTrue(LayoutMode.GRID_1X2_PORTRAIT.isPortraitWall)
        assertTrue(LayoutMode.GRID_1X3_PORTRAIT.isPortraitWall)
        assertFalse(LayoutMode.GRID_1X2.isPortraitWall)
        assertFalse(LayoutMode.GRID_2X2.isPortraitWall)
        assertEquals(
            listOf(false, false, true, true, false, false, false),
            LayoutMode.wallModes.map { it.isPortraitWall }
        )
    }

    @Test
    fun `same-content 2x2 is a software mode that mirrors player 1`() {
        assertEquals(listOf(LayoutMode.GRID_2X2_SAME), LayoutMode.softwareModes)
        assertTrue(LayoutMode.customerModes.contains(LayoutMode.GRID_2X2_SAME))
        assertFalse(LayoutMode.wallModes.contains(LayoutMode.GRID_2X2_SAME))
        assertEquals(4, LayoutMode.GRID_2X2_SAME.visiblePlayerCount)
        assertTrue(LayoutMode.GRID_2X2_SAME.mirrorsPrimaryPlayer)
        assertFalse(LayoutMode.GRID_2X2.mirrorsPrimaryPlayer)
        assertEquals(0, LayoutMode.GRID_2X2_SAME.sourcePlayerIndex(3))
        assertEquals(3, LayoutMode.GRID_2X2.sourcePlayerIndex(3))
    }
}
