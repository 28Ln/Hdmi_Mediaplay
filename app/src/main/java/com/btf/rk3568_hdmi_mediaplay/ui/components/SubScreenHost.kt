package com.btf.rk3568_hdmi_mediaplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 子页面宿主：铺满全屏、不透明，并在 **Main pass** 吞掉所有未被内容消费的指针事件。
 *
 * ## 为什么需要
 *
 * 主界面（`MainScreen`）现在常驻组合树（见 `MainActivity` 的说明：避免进入设置页时
 * 销毁四个 ExoPlayer）。子页面以覆盖层形式显示，因此必须阻止事件漏到下层：
 * 否则点一下设置页空白处就会把下层视频暂停，底部触发区也会弹出控制栏。
 *
 * ## 为什么是 Main pass，而不是 Initial 或 Final
 *
 * - **不能是 Initial**：该 pass 由外向内派发，本宿主会先于自己的子内容收到事件，
 *   一消费就会把设置页自己的 Button/Switch/Slider 全部打死。
 * - **不能是 Final**：该 pass 由内向外派发，等到本宿主收到时，下层兄弟节点
 *   可能已经处理完了。
 * - **Main pass 的顺序正好**：同一子树内「子内容先处理、宿主后处理」，
 *   而兄弟节点按 z 轴自上而下派发 —— 本宿主消费后，下层 MainScreen 收不到。
 *
 * 因此只需在 Main pass 把剩余的 change 全部 consume。
 */
@Composable
fun SubScreenHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        content()
    }
}
