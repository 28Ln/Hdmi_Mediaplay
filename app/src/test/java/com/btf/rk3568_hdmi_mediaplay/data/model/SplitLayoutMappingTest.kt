package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 裁剪布局 → 播放器布局 的映射守卫。
 *
 * 裁剪把一张图切成 N 块写入 player1..N，必须同时把播放器切到对应分屏，
 * 否则用户看不到拼接效果（2×2 裁完但当前是单屏 → 只显示一格），
 * 会以为功能没生效。这个映射错了不会报错、只会「看起来没反应」，
 * 因此在此显式约束。
 */
class SplitLayoutMappingTest {

    @Test
    fun `each split layout maps to a layout mode with matching rows and columns`() {
        SplitLayout.entries.forEach { split ->
            val mode = split.matchingLayoutMode
            assertNotNull("${split.name} 缺少匹配的播放布局", mode)
            assertEquals(
                "${split.name} 的行列数与 ${mode!!.name} 不一致",
                split.rows to split.cols,
                mode.rows to mode.columns
            )
        }
    }

    @Test
    fun `split 2x2 maps to independent grid not same-content mode`() {
        // 关键：不能映射到 GRID_2X2_SAME —— 那个模式四格播同一路，
        // 裁剪出的 4 块会显示成一样，拼接效果消失
        assertEquals(LayoutMode.GRID_2X2, SplitLayout.SPLIT_2X2.matchingLayoutMode)
    }

    @Test
    fun `mapping covers every split layout`() {
        val unmapped = SplitLayout.entries.filter { it.matchingLayoutMode == null }
        assertEquals("以下裁剪布局未映射到播放模式: $unmapped", emptyList<SplitLayout>(), unmapped)
    }

    @Test
    fun `layout mode rows and columns match the wall grid semantics`() {
        // 抽样确认行列语义没被写反
        assertEquals(LayoutMode.GRID_1X2, SplitLayout.SPLIT_1X2.matchingLayoutMode)  // 1行2列
        assertEquals(LayoutMode.GRID_2X1, SplitLayout.SPLIT_2X1.matchingLayoutMode)  // 2行1列
        assertEquals(LayoutMode.GRID_1X3, SplitLayout.SPLIT_1X3.matchingLayoutMode)  // 1行3列
        assertEquals(LayoutMode.GRID_3X1, SplitLayout.SPLIT_3X1.matchingLayoutMode)  // 3行1列
    }
}
