package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /**
     * 裁剪页只允许选择「用户之后还能在设置页切回」的布局。
     *
     * 1×4 / 4×1 虽然映射存在（上一条测试仍覆盖它们），但它们没有拼接板编号，
     * 不出现在 `LayoutMode.customerModes` 里。若在裁剪页可选，用户裁剪后会
     * 被切到设置页**看不到**的布局 —— 能进去、却切不回来。
     *
     * 这条测试锁死「可选布局必须是设置页可见模式的子集」这个不变式。
     */
    @Test
    fun `selectable split layouts are all reachable from the settings page`() {
        val customerModes = LayoutMode.customerModes.toSet()

        SplitLayout.selectableLayouts.forEach { split ->
            val mode = split.matchingLayoutMode
            assertNotNull("${split.name} 没有对应播放模式", mode)
            assertTrue(
                "${split.name} 会切到 ${mode!!.name}，但该模式不在设置页的模式列表中，" +
                    "用户裁剪后无法切回",
                mode in customerModes
            )
        }
    }

    /**
     * 推荐布局必须落在可选范围内，否则"默认选中项"会是界面上不存在的卡片。
     */
    @Test
    fun `recommended layout is always selectable`() {
        // 覆盖从超宽到超高的各种宽高比
        val cases = listOf(
            4000 to 600,   // 超宽 ≈6.7:1（曾经会推荐 1×4）
            2400 to 800,   // 3:1
            1600 to 800,   // 2:1
            1000 to 1000,  // 1:1
            800 to 1600,   // 1:2
            800 to 2400,   // 1:3
            600 to 4000    // 超高 ≈1:6.7（曾经会推荐 4×1）
        )

        cases.forEach { (w, h) ->
            val recommended = SplitLayout.recommendLayout(w, h)
            assertTrue(
                "推荐 ${recommended.name}（${w}x$h）不在可选列表 ${
                    SplitLayout.selectableLayouts.map { it.name }
                } 中",
                recommended in SplitLayout.selectableLayouts
            )
        }
    }
}
