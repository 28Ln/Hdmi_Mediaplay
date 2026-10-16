package com.btf.rk3568_hdmi_mediaplay.ui.settings

import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局模式文案与切分方向的一致性守卫。
 *
 * 背景：模式 3 的描述曾写成「2路上下分屏（竖屏）」，而实际是左右并列
 * （见 `QuadPlayerLayout.calculatePlacements`，`GRID_1X2_PORTRAIT` 与
 * `GRID_1X2` 走同一分支；拼接板示意图亦为左右并列）。
 * 文案错误不会被编译或既有测试发现，因此在此显式约束。
 *
 * 描述文案以「左右/上下」表述切分方向，不含「横向/纵向」——
 * 后者在竖屏输出下含义会被误读。
 */
class LayoutModeDescriptionTest {

    @After
    fun tearDown() {
        StringResources.setLanguage(AppLanguage.CHINESE)
    }

    /** 模式 1/2/3/4 按列切分，描述必须说「左右」 */
    @Test
    fun `column-split modes describe left-right`() {
        listOf(
            LayoutMode.GRID_1X2,
            LayoutMode.GRID_1X3,
            LayoutMode.GRID_1X2_PORTRAIT,
            LayoutMode.GRID_1X3_PORTRAIT
        ).forEach { mode ->
            val desc = StringResources.getLayoutModeDescription(mode)
            assertTrue(
                "${mode.name} 应按列切分，描述需含「左右」，实际：$desc",
                desc.contains("左右")
            )
            assertFalse(
                "${mode.name} 描述不应出现「上下」：$desc",
                desc.contains("上下")
            )
        }
    }

    /** 模式 5/6 按行切分，描述必须说「上下」 */
    @Test
    fun `row-split modes describe top-bottom`() {
        listOf(LayoutMode.GRID_2X1, LayoutMode.GRID_3X1).forEach { mode ->
            val desc = StringResources.getLayoutModeDescription(mode)
            assertTrue(
                "${mode.name} 应按行切分，描述需含「上下」，实际：$desc",
                desc.contains("上下")
            )
            assertFalse(
                "${mode.name} 描述不应出现「左右」：$desc",
                desc.contains("左右")
            )
        }
    }

    /** 竖屏模式需在描述中体现竖屏，避免与同切分方向的横屏模式混淆 */
    @Test
    fun `portrait modes mention portrait`() {
        listOf(LayoutMode.GRID_1X2_PORTRAIT, LayoutMode.GRID_1X3_PORTRAIT).forEach { mode ->
            val desc = StringResources.getLayoutModeDescription(mode)
            assertTrue(
                "${mode.name} 描述需标注竖屏：$desc",
                desc.contains("竖屏")
            )
        }
    }

    /**
     * 描述统一使用「左右/上下」，不使用「横向/纵向」表述切分方向。
     * 竖屏输出下「纵向并列」会被读成上下排列，而实际仍是左右切分。
     */
    @Test
    fun `descriptions avoid ambiguous horizontal-vertical wording`() {
        LayoutMode.wallModes.forEach { mode ->
            val desc = StringResources.getLayoutModeDescription(mode)
            assertFalse(
                "${mode.name} 描述应避免「横向/纵向」（竖屏下歧义）：$desc",
                desc.contains("横向") || desc.contains("纵向") || desc.contains("竖向")
            )
        }
    }

    /** 每个客户可见模式都必须有非空描述，避免按钮出现空行 */
    @Test
    fun `every customer mode has a non-empty description`() {
        LayoutMode.customerModes.forEach { mode ->
            assertTrue(
                "${mode.name} 缺少描述文案",
                StringResources.getLayoutModeDescription(mode).isNotBlank()
            )
            assertTrue(
                "${mode.name} 缺少布局名文案",
                StringResources.getLayoutModeText(mode).isNotBlank()
            )
        }
    }

    /** 英文描述同样不得为空，避免切英文后按钮只剩编号 */
    @Test
    fun `english descriptions are present for every customer mode`() {
        StringResources.setLanguage(AppLanguage.ENGLISH)
        LayoutMode.customerModes.forEach { mode ->
            assertTrue(
                "${mode.name} 缺少英文描述",
                StringResources.getLayoutModeDescription(mode).isNotBlank()
            )
            assertTrue(
                "${mode.name} 缺少英文布局名",
                StringResources.getLayoutModeText(mode).isNotBlank()
            )
        }
    }
}
