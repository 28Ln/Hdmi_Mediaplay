# 鼠标拖动无法滚动 —— 根因与修法

## 现象

设备接 USB 鼠标、**无触摸屏**。本应用的设置页与图片裁剪页：

- 鼠标按住拖动 → **完全不滚动**
- 鼠标滚轮 → 行为不稳定

同一台设备上的系统设置等原生页面：鼠标拖动滚动正常。

## 根因

**Compose 的 `scrollable`（`verticalScroll` 的底层）只处理「触摸源」的拖动手势。**

鼠标源（`PointerType.Mouse`）的按住拖动被直接忽略。原生 `android.widget.ScrollView`
支持鼠标拖动，因此系统应用正常、Compose 页面不正常。

### 为什么排查耗时很久

`adb shell input swipe` 注入的是**触摸源**事件，走的是 `scrollable`
能够处理的那条分支 —— 所以每次用它验证都显示「能滚」，掩盖了真实问题。
**必须用注入真实鼠标事件（`BTN_LEFT` + `REL_Y`）才能复现。**

复现命令：
```bash
# 按下
adb shell sendevent /dev/input/eventN 1 272 1 && sendevent /dev/input/eventN 0 0 0
# 拖动（重复若干次，每次位移若干像素）
adb shell sendevent /dev/input/eventN 2 1 -3 && sendevent /dev/input/eventN 0 0 0
# 抬起
adb shell sendevent /dev/input/eventN 1 272 0 && sendevent /dev/input/eventN 0 0 0
```

## 修法

`ui/components/MouseDragScroll.kt` 提供 `Modifier.mouseDragScroll(state)`：

```kotlin
.pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)   // ①
            val change = event.changes.firstOrNull() ?: continue
            if (change.type != PointerType.Mouse) continue            // 只接管鼠标
            when (event.type) {
                Press -> { dragging = true; lastY = change.position.y; change.consume() }
                Move  -> if (dragging) {
                    state.dispatchRawDelta(-(change.position.y - lastY))
                    lastY = change.position.y
                    change.consume()                                   // ②
                }
                Release, Exit -> dragging = false
            }
        }
    }
}
```

两个要点缺一不可：

| 要点 | 作用 | 缺失后果 |
|---|---|---|
| ① `PointerEventPass.Initial` | 由外向内派发，本处理器**先于**内建 `scrollable` 收到事件 | 用 `Main`（内层先收）时内建先滚一次 |
| ② `change.consume()` | 内建收不到被消费的事件，不再二次滚动 | 不消费则两边同时滚 → **慢速拖动抖动** |

**抖动的成因**：`Main` pass + 不消费 = 内建与本处理各滚一次，慢速拖动时两者拉扯可见；
快速拖动位移大，盖过了差异所以「看不出来」。

## 应用位置

- `ui/settings/SettingsScreen.kt`
- `ui/split/ImageSplitScreen.kt`

滚轮与触摸不受影响：滚轮走 `Scroll` 事件分支、触摸走 `scrollable` 的触摸分支，
本处理器遇到非 Mouse 类型直接跳过。

## 验证

最小复现页（20 个按钮，无业务代码）注入真实鼠标拖动：
- 修复前：页面纹丝不动
- 修复后：慢速拖动 40 步 × 1px → 精确移动 40px，无抖动

## 附带修复

设置页曾因滚动容器被套入一层 `Box(Modifier.fillMaxSize())`
（为放置悬浮按钮引入）导致滚动区高度被挤出可视范围。
已恢复为「滚动 Column 是外层 Column 的直接子项」。
排查时可用 `uiautomator dump` 检查滚动容器的 bounds：
健康状态为 `[0,64][1024,552]`（顶栏之下、铺满剩余空间）。
