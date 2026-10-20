# HDMI 拼接矩阵接口 —— 调用流程与参数说明

> 适用代码：`hardware/` 包（2026-10-19/20 新增的 I2C 直控实现）
> 协议依据：固件方 `I2C_SLAVE_PROTOCOL(1).md`（含 2026-10-19 修订版）
> 本文只讲 **App 侧怎么调**；寄存器/errno 的原始定义见 `docs/hdmi-i2c-control.md`

---

## 一、总览

```
┌─────────────────────────────────────────────────────────────┐
│  UI（设置页 / 底部栏）        MainViewModel                   │
│        │                        │                            │
│        └────────────┬───────────┘                            │
│                     ▼                                        │
│         HdmiMatrixController（单例，唯一写锁）                 │
│                     │                                        │
│         ┌───────────┴───────────┐                            │
│         ▼                       ▼                            │
│  HdmiMatrixNodeIo        HdmiMatrixModeMapping               │
│  （sysfs 读写 + errno）    （布局 ↔ 硬件模式，纯函数）          │
│         │                                                    │
│         ▼                                                    │
│  /sys/bus/i2c/devices/4-0030/{mode,resolution,...}           │
└─────────────────────────────────────────────────────────────┘
```

**三个入口，同一个控制器实例**：

| 入口 | 触发时机 | 调用 |
|---|---|---|
| 设置页 | 用户点布局卡片 / 下拉 | `applyLayout` / `setResolution` / `setRotate` / `setMute` |
| 底部栏 | 「切换模式」「切换分辨率」 | `cycleWallLayout()` → `applyLayout`；`cycleResolution()` → `setResolution` |
| 远控 | `set_layout` | `MainViewModel.setLayout` → `applyLayout` |
| 启动 | 应用冷启动 | `ensureOnline` → `applyLayout`（恢复上次布局） |

> 所有调用都在 `Dispatchers.IO`；控制器内部还有一把 `Mutex` 串行化**所有**读写。
> 原因是「写 mode 会清空 mirror」，mode 与 mirror 之间不能被插队。

---

## 二、获取控制器

```kotlin
val controller = HdmiMatrixController.getInstance()
```

- **进程内单例**：UI 与 ViewModel 必须共用同一实例，否则 `Mutex` 形同虚设
- 状态用 `StateFlow` 暴露，Compose 直接 collect，**重组时不发起任何 sysfs 读写**：

```kotlin
val state: StateFlow<HdmiMatrixState>   // availability / lastSnapshot / lastError / pendingFusion
```

---

## 三、启动流程（**顺序不能变**）

```kotlin
// MainViewModel.restoreHardwareLayoutOnStartup()
val loaded = settings.drop(1).first()      // 1. 等 DataStore 真实值（不是默认值）
val online = hdmiMatrix.ensureOnline(timeoutMs = 20_000L)  // 2. 等 MCU 就绪
if (online is HdmiMatrixResult.Failure) return              // 3. 未就绪就跳过，不报错
applyLayoutToHardware(loaded.layoutMode, showErrors = false) // 4. 下发上次布局
```

### 为什么 `settings.drop(1)`

`settings` 是 `Eagerly` 的 `StateFlow`：**第一个值恒为默认值**，第二个才是 DataStore 读出的真实值。
用第一个值会把用户设置覆盖成默认布局。

### `ensureOnline` 的行为

```kotlin
suspend fun ensureOnline(
    timeoutMs: Long = 20_000L,   // 总等待上限
    pollMs: Long = 250L          // 轮询间隔
): HdmiMatrixResult<Unit>
```

| 读到的情况 | 处理 |
|---|---|
| `version == 2` | 成功，`availability = ONLINE` |
| `ENOENT`（节点不存在） | **继续轮询**（驱动可能还没 probe 完） |
| `ENODEV`（节点在但无 ACK） | **立即失败**，`availability = NO_DEVICE`（硬件不在，重试无意义） |
| `EACCES` | 立即失败（权限不会自愈，由 Io 层做 500ms×3 重试） |
| 超时 | 返回 `NotAvailable` |

> `ENOENT` 与 `ENODEV` **必须分开**：前者是驱动/DTS 问题，后者是线缆/供电问题。
> 固件 2026-10-19 修订把「总线不通」从 `EIO` 改成了 `ENODEV`。

---

## 四、核心 API

所有写操作返回 `HdmiMatrixResult<T>`：

```kotlin
sealed class HdmiMatrixResult<out T> {
    data class Success<T>(val value: T, val snapshot: HdmiMatrixSnapshot?) 
    data class Failure(val error: HdmiMatrixError, val snapshot: HdmiMatrixSnapshot?)
    val isSuccess: Boolean
}
```

### 4.1 布局（**最常用**）

```kotlin
suspend fun applyLayout(
    layoutMode: LayoutMode,
    mirrorMask: Int = 0
): HdmiMatrixResult<Unit>
```

内部顺序（**固定，不可调换**）：

```
读当前 mode
  → 若变化：写 mode
  → 写 mirror（mode 变更时**强制重写**，因为固件会清空镜像）
```

**参数**

| 参数 | 取值 | 说明 |
|---|---|---|
| `layoutMode` | `LayoutMode` 枚举 | 由 `HdmiMatrixModeMapping` 映射成硬件值 |
| `mirrorMask` | `0..15`（TX3 板为 `0..7`） | bit0..bit3 = TX1..TX4 |

**返回值**

| 情况 | 结果 |
|---|---|
| 软件布局（`SINGLE` / 竖屏 / `GRID_2X2_SAME` / `PIP`） | `Failure(NoHardwareMode)`，**不发任何 IO** |
| `mirrorMask` 越界 | `Failure(InvalidValue)`，零 IO |
| mirror 但当前模式低于门槛 | `Failure(UnsupportedInMode)`，零 IO |
| 写成功 | `Success` |

> **预校验全部在 IO 之前完成** —— 越界值不会被写进 sysfs。

### 4.2 布局 ↔ 硬件模式映射（**有个坑**）

`HdmiMatrixModeMapping.hardwareModeFor(layoutMode)`：

| LayoutMode | 硬件 `mode` | 备注 |
|---|---|---|
| `GRID_1X2` | 1 | |
| `GRID_2X1` | **2** | ⚠️ **不是** `wallModeNumber` 的 5 |
| `GRID_1X3` | 3 | |
| `GRID_3X1` | 4 | |
| `GRID_2X2` | 5 | |
| `ROW_1X4` | 6 | 只在远控/裁剪里出现 |
| `COLUMN_4X1` | 7 | 同上 |
| `SINGLE` / `GRID_1X2_PORTRAIT` / `GRID_1X3_PORTRAIT` / `GRID_2X2_SAME` / `PIP` | `null` | 纯软件布局，**不下发硬件** |

> **禁止读取 `LayoutMode.wallModeNumber`**。它是拼接板示意图编号：
> `GRID_2X1` 示意图是 5，硬件是 2。用错**不会报错**，MCU 照单全收，画面切分与用户选择不符。

反向：`layoutModeFor(hardwareMode)`。
`0`（1X1）刻意不映射 —— App 不提供该布局，读到时应显示「无对应布局」而不是强行套用。

### 4.3 直接设模式（诊断用）

```kotlin
suspend fun setMode(hardwareMode: Int): HdmiMatrixResult<Unit>
```

范围 `0..7`。**副作用**：写 mode 会清空 mirror。需要镜像请用 `applyLayout`。

### 4.4 镜像

```kotlin
suspend fun setMirror(mirrorMask: Int): HdmiMatrixResult<Unit>
```

- 范围：`0..15`，但受板型约束（`HardwareConfig.HDMI_BOARD_VARIANT`，默认 TX4）
- **门槛**：TX3 需 `mode >= 3`，TX4 需 `mode >= 5`
- 本函数**不会**替调用方改 mode（静默改布局是最难排查的问题）

UI 预检用纯函数，避免用户点了才收到错误：

```kotlin
HdmiMatrixModeMapping.supportsMirror(layoutMode, variant)  // false = 该布局不支持镜像
```

### 4.5 分辨率

```kotlin
enum class HdmiResolution(val sysfsValue: Int) {
    P720(0), P1080(1), P2160_30(2)
}

suspend fun setResolution(resolution: HdmiResolution): HdmiMatrixResult<Unit>
```

用枚举而不是裸 `Int`，避免把 0/1/2 写错位。

### 4.6 旋转 / 静音 / 电源

```kotlin
suspend fun setRotate(quarterTurns: Int): HdmiMatrixResult<Unit>   // 0..3 = 0°/90°/180°/270°
suspend fun setMute(mute: Boolean): HdmiMatrixResult<Unit>
suspend fun setPower(on: Boolean): HdmiMatrixResult<Unit>
```

`setPower(true)` 会把 MCU 重启，写入可能超时 —— 这是**正常现象**，之后应轮询 `version`。

### 4.7 融合带（**flash 磨损，必须走 stage/commit**）

```kotlin
fun stageFusion(horizontal: Int, vertical: Int): HdmiMatrixResult<FusionStage>  // 纯内存
suspend fun commitFusion(): HdmiMatrixResult<Unit>                             // 真正写 flash
```

**为什么分两步**：每次成功写 `hfusion`/`vfusion` 都会写 MCU flash。滑杆逐帧下发会在几分钟内写坏。

```kotlin
// 滑杆拖动：只 stage
onValueChange = { v -> controller.stageFusion(v, v) }

// 松手：commit
onValueChangeFinished = { scope.launch(Dispatchers.IO) { controller.commitFusion() } }
```

`commitFusion` 保证：
- 距上次 flash **≥ 300ms**（`HardwareConfig.FUSION_FLASH_DEBOUNCE_MS`）
- 等待期间若有新 stage，取**最新值**重新计时（一次松手最多一次 flash）
- 等待**不持锁**（不会卡住其他设置写入）

一次性写入（开机恢复 / U 盘配置 / 远控）用 `setFusion(h, v)`，它同样受 300ms 节流。

### 4.8 批量

```kotlin
suspend fun applySettings(settings: HdmiMatrixSettings): HdmiMatrixResult<Unit>
```

```kotlin
data class HdmiMatrixSettings(
    val power: Boolean? = null,
    val mode: Int? = null,
    val mirror: Int? = null,
    val resolution: HdmiResolution? = null,
    val rotate: Int? = null,
    val hfusion: Int? = null,
    val vfusion: Int? = null,
    val mute: Boolean? = null
)
```

**执行顺序固定**：`power → mode → mirror → resolution → rotate → fusion → mute`

- 全部字段可空，`null` 表示不动
- **先整体预校验**，任一字段非法则**一次 IO 都不做**
- 中途失败**立即停止**，已完成的步骤保留（不再回滚）

### 4.9 读状态

```kotlin
suspend fun probe(): HdmiMatrixResult<HdmiMatrixSnapshot>        // 完整快照（含 status 位域）
suspend fun readSnapshot(): HdmiMatrixResult<HdmiMatrixSnapshot> // 同上
suspend fun readApplied(): HdmiMatrixResult<HdmiMatrixApplied>   // 只读「已生效」值
suspend fun isOnline(): Boolean                                   // 廉价存活探测
```

```kotlin
data class HdmiMatrixApplied(
    val mode: Int?, val resolution: HdmiResolution?, val mirror: Int?,
    val rotate: Int?, val hfusion: Int?, val vfusion: Int?,
    val mute: Boolean?, val power: Boolean?
)

data class HdmiMatrixSnapshot(
    val online: Boolean, val version: Int?, val status: HdmiMatrixStatus,
    val lastResult: Int?, val cmdCount: Long?, val rxState: Int?,
    val applied: HdmiMatrixApplied?, val readAtElapsedMs: Long
)
```

`HdmiMatrixStatus` 位域：`busy` / `power` / `error` / `overflow`。

> 读节点返回的是**已生效**值；命令还没执行完时读到的仍是旧值。
> 写入返回成功即表示已执行完。

---

## 五、错误处理

### 5.1 错误类型

```kotlin
sealed class HdmiMatrixError {
    abstract val kind: HdmiMatrixErrorKind
    abstract val errno: Int?
    abstract val node: HdmiMatrixNode?
    open val retryable: Boolean
}
```

| 类型 | errno | 含义 | 重试？ |
|---|---|---|---|
| `NotAvailable` | `ENOENT` 2 | 驱动未加载 / DTS 未配（**软件**问题） | ❌ |
| `NoDevice` | `ENODEV` 19 | 节点在但从机无 ACK（**硬件**问题） | ❌ |
| `PermissionDenied` | `EACCES` 13 | 权限未放行（开机早期） | ✅ 500ms×3 |
| `DeviceStandby` | `EPERM` 1 | 待机中，只接受 `power`/`key` | ❌ 先 `setPower(true)` |
| `InvalidValue` | `EINVAL`/`ERANGE` | 值越界 | ❌ 改值 |
| `UnsupportedInMode` | `EOPNOTSUPP` 95 | 当前模式不支持（如非拼接模式设 fusion） | ❌ 先切模式 |
| `Timeout` | `ETIMEDOUT` 110 | 2 秒未完成 | ✅ 重试 1 次 |
| `IoFailure` | `EIO` 5 | 握手总线错误 | ✅ 重试 1 次 |
| `Busy` | — | status 的 BUSY 位仍置位 | — |
| `NoHardwareMode` | — | 布局没有硬件模式 | — 跳过硬件 |
| `Unknown` | 其他 | 未分类 | ❌ |

### 5.2 UI 文案

统一走 `StringResources.hdmiMatrixErrorDetail(error)`，它按 `kind` 取文案；
不要在调用点自己拼字符串。

### 5.3 重试策略（在 `HdmiMatrixNodeIo` / `doWriteVerified` 内）

```
写失败
 ├─ EACCES 且 attempt < 3  → delay(500ms) 重试
 ├─ EIO / ETIMEDOUT 且已重试 < 1 → 立即重试一次
 ├─ EPERM（且允许上电恢复）→ 写 power=1，再重试原命令
 └─ 其他 → 直接返回结构化错误
```

---

## 六、幂等与强制写（**容易踩**）

写操作默认**幂等**：先回读，值相同就不写。

但有一个**必须强制写**的例外：

```kotlin
// doApplyLayout：写 mode 之后，mirror 必须显式重写
doWriteVerified(MIRROR, mirrorMask, forceWrite = modeChanged)
```

原因：固件规定「写 mode 会清除所有镜像」。此时回读到 `mirror = 0` **不代表目标已达成**，
它是写 mode 之前的状态。跳过写会导致镜像静默丢失。

---

## 七、常量（`HardwareConfig`）

| 常量 | 值 | 用途 |
|---|---|---|
| `I2C_BASE_PATH` | `/sys/bus/i2c/devices/4-0030` | 节点基址（固定，可硬编码） |
| `FUSION_FLASH_DEBOUNCE_MS` | `300L` | 两次 fusion flash 的最小间隔 |
| `EACCES_RETRY_DELAY_MS` | `500L` | 权限重试间隔 |
| `EACCES_MAX_ATTEMPTS` | `3` | 权限重试次数 |
| `MATRIX_OP_TIMEOUT_MS` | `1500L` | 单次读写超时 |
| `POWER_RECOVERY_TIMEOUT_MS` | `3000L` | 上电恢复等待 |
| `HDMI_BOARD_VARIANT` | `TX4` | 板型（影响 mirror 门槛），未确认时取更严格的一侧 |
| `LEGACY_GPIO_FALLBACK` | `true` | 旧 ROM 回退开关（新固件可关） |

---

## 八、完整调用示例

### 8.1 设置页切换布局

```kotlin
// MainViewModel.applyLayoutToHardware(layoutMode, showErrors)
val hardwareMode = HdmiMatrixModeMapping.hardwareModeFor(layoutMode)
if (hardwareMode == null) {
    log("布局 $layoutMode 为纯软件布局，跳过硬件下发")
    return                                  // 不报错，不是故障
}
if (lastAppliedHardwareLayout == layoutMode) {
    log("硬件模式未变化，跳过重复下发")
    return
}

hardwareLayoutJob?.cancel()                 // 取消被取代的下发
hardwareLayoutJob = viewModelScope.launch(Dispatchers.IO) {
    when (val result = hdmiMatrix.applyLayout(layoutMode)) {
        is HdmiMatrixResult.Success -> lastAppliedHardwareLayout = layoutMode
        is HdmiMatrixResult.Failure -> {
            // 失败不写 lastApplied，下次会重试
            if (showErrors) withContext(Dispatchers.Main) {
                showToast(StringResources.hdmiMatrixErrorDetail(result.error), MessageType.ERROR)
            }
        }
    }
}
```

### 8.2 底部栏「切换模式」

```kotlin
// 不再脉冲 GPIO；改为在软件侧轮换到下一个**客户可见**的拼接墙布局
// 这样也不会切到 App 不展示的 1X4/4X1
fun cycleWallLayout() {
    val modes = LayoutMode.wallModes
    val next = modes[(modes.indexOf(settings.value.layoutMode) + 1) % modes.size]
    updateSettings(settings.value.copy(layoutMode = next))   // 触发 applyLayoutToHardware
}
```

### 8.3 底部栏「切换分辨率」

```kotlin
fun cycleResolution() = viewModelScope.launch(Dispatchers.IO) {
    val current = (hdmiMatrix.readApplied() as? HdmiMatrixResult.Success)?.value?.resolution
    val next = when (current) {
        P720 -> P1080; P1080 -> P2160_30; P2160_30 -> P720; null -> P1080
    }
    hdmiMatrix.setResolution(next)
}
```

分辨率**不持久化**（MCU 自己保存），所以每次先读后写。

### 8.4 远控 set_layout

```kotlin
override fun setLayout(layoutMode: LayoutMode) {
    updateSettings(settings.value.copy(layoutMode = layoutMode))
    // updateSettings 内部检测到 layoutMode 变化 → applyLayoutToHardware
}
```

### 8.5 融合带滑杆

```kotlin
Slider(
    value = localValue,
    onValueChange = { localValue = it; controller.stageFusion(it, it) },   // 只内存
    onValueChangeFinished = { scope.launch(Dispatchers.IO) { controller.commitFusion() } }
)
```

---

## 九、当前实现状态（**重要**）

| 项 | 状态 |
|---|---|
| 代码完整度 | ✅ 全部 API 已实现，173 个单测覆盖映射/错误分类/防抖/顺序 |
| **真机可用性** | ❌ **固件尚未提供那 14 个节点** |
| 真机实测 | `/sys/bus/i2c/devices/4-0030/` 只有 `modalias` / `name` / `of_node` / `power` / `subsystem` / `uevent` |
| App 表现 | 按设计降级：`availability = MISSING` → 显示「驱动节点缺失」并隐藏控制入口，**不崩溃** |
| 阻塞 | 需固件方按修订 ABI（`power_state` 命名 + 节点恒存在）重新出包 |

### 固件就绪后的验证步骤

```bash
# 1. 在线判定
adb shell cat /sys/bus/i2c/devices/4-0030/version          # 期望 2

# 2. 控制链路
adb shell 'echo 6 > /sys/bus/i2c/devices/4-0030/mode'
adb shell cat /sys/bus/i2c/devices/4-0030/mode             # 期望 6
adb shell cat /sys/bus/i2c/devices/4-0030/result           # 期望 0

# 3. App 侧
# 设置页 → HDMI 拼接板卡片应显示「在线 · v2」
# 切布局 → 日志出现「下发硬件布局: GRID_2X1 -> mode=2」
```

---

## 十、常见误用

| 误用 | 后果 | 正确做法 |
|---|---|---|
| 用 `wallModeNumber` 当硬件 mode | 画面切分错误且**无报错** | 用 `HdmiMatrixModeMapping.hardwareModeFor` |
| 滑杆 `onValueChange` 里直接写 flash | 几分钟写坏 MCU flash | `stageFusion` + `onValueChangeFinished` → `commitFusion` |
| 在主线程调 API | UI 卡顿数百 ms 到数秒 | 一律 `Dispatchers.IO` |
| 自己 new 一个 Controller | 多把锁 → mode/mirror 顺序被打乱 | `getInstance()` |
| 把 `ENOENT` 和 `ENODEV` 混为一谈 | 排查方向错（软件 vs 硬件） | 分别处理，见 §5.1 |
| 给 `SINGLE`/竖屏布局强行下发硬件模式 | 画面与预期不符 | `hardwareModeFor` 返回 `null` 时跳过 |
| 写 mode 后不回写 mirror | 镜像静默丢失 | `applyLayout` 已处理；自己写要 `forceWrite = true` |
