package com.btf.rk3568_hdmi_mediaplay.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 让鼠标按住拖动也能滚动页面。
 *
 * 背景：Compose 的 `scrollable`（`verticalScroll` 内建）只处理**触摸源**的拖动；
 * 鼠标源（`PointerType.Mouse`）的按住拖动默认被忽略，
 * 所以「鼠标按住往下拖」在 Compose 页面里不会滚动，而系统原生页面可以。
 *
 * 实现要点（两条缺一不可）：
 * 1. 用 **Initial pass**：该 pass 由外向内派发，本处理器先于内建 scrollable 收到事件；
 * 2. **consume** 事件：内建 scrollable 收不到被消费的事件，因此不会二次滚动。
 *
 * 若用 Main pass（内层先收），内建会先处理一次、本处理器再叠加一次，
 * 两条路径同时滚动 → 慢速拖动时表现为抖动（快速拖动位移大，盖过差异，看不出来）。
 *
 * 滚轮与触摸不受影响：滚轮走 Scroll 事件、触摸走 scrollable 的触摸分支。
 */
fun Modifier.mouseDragScroll(state: ScrollState): Modifier = this.pointerInput(state) {
    awaitPointerEventScope {
        var lastY = 0f
        var dragging = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue

            // 只接管鼠标，触摸仍交给内建 scrollable
            if (change.type != PointerType.Mouse) {
                dragging = false
                continue
            }

            when (event.type) {
                PointerEventType.Press -> {
                    dragging = true
                    lastY = change.position.y
                    change.consume()
                }
                PointerEventType.Move -> {
                    if (dragging) {
                        val dy = change.position.y - lastY
                        if (dy != 0f) {
                            // 手指/鼠标往下拖 = 内容往下走 = scrollValue 减小
                            state.dispatchRawDelta(-dy)
                            lastY = change.position.y
                        }
                        change.consume()   // 关键：消费，避免内建重复处理导致抖动
                    }
                }
                PointerEventType.Release, PointerEventType.Exit -> {
                    dragging = false
                }
                else -> {}
            }
        }
    }
}
