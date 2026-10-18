package com.btf.rk3568_hdmi_mediaplay.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/**
 * 让鼠标按住拖动也能滚动页面。
 *
 * 背景：Compose 的 `scrollable`（`verticalScroll` 内建）只处理**触摸源**的拖动；
 * 鼠标源（`PointerType.Mouse`）的按住拖动默认被忽略，
 * 所以「鼠标按住往下拖」在 Compose 页面里不会滚动，而系统原生页面可以。
 *
 * ## 实现要点
 *
 * 1. 用 **Initial pass**：该 pass 由外向内派发，本处理器先于内建 scrollable 收到事件；
 * 2. 只在**超过滑动阈值之后**才 `consume()` Move。
 *
 * ## 为什么 Press 绝不能 consume（2026-10-19 修复）
 *
 * Compose 的 `clickable` / `detectTapGestures` 内部调用
 * `awaitFirstDown(requireUnconsumed = true, pass = Main)`。
 * 父级若在 Initial pass 就把 Press 消费掉，子级在 Main pass 找不到
 * 一个「未被消费的 down」→ 按钮、开关、滑块**全部收不到点击**。
 * 旧实现无条件 `change.consume()` Press，正是这个隐患。
 *
 * 现在的策略：
 * - Press：只记录起点，**不消费**；
 * - Move：纵向位移超过 `touchSlop` 之前**不消费**（否则会破坏子级的手势识别，
 *   例如 Slider 的水平拖动）；超过之后才消费，接管滚动；
 * - Release/Exit：复位。
 *
 * 这样「按住拖动滚动」仍然只由本处理器驱动（超过阈值后消费，内建 scrollable
 * 收不到 Move，不会二次滚动 → 不抖动），而「单击」完整地留给子级控件。
 *
 * ## 注意
 *
 * 滚轮走 Compose 内建 `Scroll` 分支，触摸走内建 scrollable 的触摸分支，
 * 两者都不经过本处理器（仅接管 `PointerType.Mouse`）。
 */
@Composable
fun Modifier.mouseDragScroll(state: ScrollState): Modifier {
    // touchSlop 由平台密度决定（通常约 8dp），与内建手势阈值保持一致
    val slopPx = with(LocalViewConfiguration.current) { touchSlop }
    return this.pointerInput(state, slopPx) {
        awaitPointerEventScope {
            var startY = 0f
            var lastY = 0f
            var dragging = false
            var pastSlop = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue

                // 只接管鼠标，触摸仍交给内建 scrollable
                if (change.type != PointerType.Mouse) {
                    dragging = false
                    pastSlop = false
                    continue
                }

                when (event.type) {
                    PointerEventType.Press -> {
                        dragging = true
                        pastSlop = false
                        startY = change.position.y
                        lastY = startY
                        // 关键：不消费 Press，否则子级 Button/Switch/Slider 收不到点击
                    }

                    PointerEventType.Move -> {
                        if (dragging) {
                            val dy = change.position.y - lastY
                            if (!pastSlop && kotlin.math.abs(change.position.y - startY) > slopPx) {
                                pastSlop = true
                            }
                            if (pastSlop) {
                                // 手指/鼠标往下拖 = 内容往下走 = scrollValue 减小
                                if (dy != 0f) state.dispatchRawDelta(-dy)
                                lastY = change.position.y
                                // 只有真正进入拖动后才消费，避免内建 scrollable 二次滚动
                                change.consume()
                            }
                        }
                    }

                    PointerEventType.Release, PointerEventType.Exit -> {
                        dragging = false
                        pastSlop = false
                    }

                    else -> {}
                }
            }
        }
    }
}
