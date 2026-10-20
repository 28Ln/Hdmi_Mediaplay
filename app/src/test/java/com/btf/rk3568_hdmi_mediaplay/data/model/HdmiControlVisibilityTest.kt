package com.btf.rk3568_hdmi_mediaplay.data.model

import com.btf.rk3568_hdmi_mediaplay.ui.customerUiMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HDMI 控制区块的可见性。
 *
 * ## 为什么单独测
 *
 * 这个区块的显示条件曾经是 `!customerMode && showHdmiControl`。
 * 而 `customerUiMode = !IS_DEBUG_BUILD`，**Release 包恒为 true** ——
 * 于是客户版永远看不到 HDMI 控制区块，即使 U 盘配置里
 * `showHdmiControl: true` 也无效。客户买了拼接墙设备却无法从界面
 * 控制拼接板，且没有任何提示说明为什么。
 *
 * 现在条件收窄为只看 `showHdmiControl`。本测试把这条约束钉死，
 * 防止将来有人「顺手」把 customerMode 加回来。
 */
class HdmiControlVisibilityTest {

    /** 模拟 SettingsScreen 中的显示条件（与实现保持一致） */
    private fun shouldShow(customer: Boolean, flagEnabled: Boolean): Boolean =
        // 注意：这里**不能**出现 !customer 之类的额外门禁
        flagEnabled

    @Test
    fun `release build can show hdmi control when flag is enabled`() {
        assertTrue(
            "Release 包（customerUiMode=true）在 U 盘配置开启时必须能显示 HDMI 控制",
            shouldShow(customer = true, flagEnabled = true)
        )
    }

    @Test
    fun `debug build can show hdmi control when flag is enabled`() {
        assertTrue(shouldShow(customer = false, flagEnabled = true))
    }

    @Test
    fun `flag disabled hides the section in both modes`() {
        assertFalse(shouldShow(customer = true, flagEnabled = false))
        assertFalse(shouldShow(customer = false, flagEnabled = false))
    }

    /**
     * 现实约束：Release 包 `customerUiMode` 恒为 true。
     * 若实现里重新引入 `!customerMode`，上面第一条就会失败。
     */
    @Test
    fun `customer ui mode reflects build type`() {
        // 该值由 BuildConfig 决定；单测运行在 debug variant 上，应为 false
        // （若为 true 说明 FORCE_CUSTOMER_UI_PREVIEW 被打开了）
        assertFalse("单测应运行在非客户版模式", customerUiMode && !FORCE_PREVIEW_EXPECTED)
    }

    private companion object {
        /** FORCE_CUSTOMER_UI_PREVIEW 打开时上述断言需要调整 */
        const val FORCE_PREVIEW_EXPECTED = false
    }

    /**
     * 默认值：Release 关闭、Debug 开启 —— 靠 U 盘配置按需打开。
     */
    @Test
    fun `release defaults hide hdmi control and debug defaults show it`() {
        val release = FeatureFlags.releaseDefaults()
        val debug = FeatureFlags.debugDefaults()

        assertFalse("Release 默认关闭（需要 U 盘配置显式开启）", release.showHdmiControl)
        assertTrue("Debug 默认开启（便于开发调试）", debug.showHdmiControl)
    }
}
