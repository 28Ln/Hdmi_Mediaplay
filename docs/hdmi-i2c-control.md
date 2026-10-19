# HDMI 拼接矩阵控制（I2C 直控）

> **本文是该接口在 App 侧的权威参考**。协议原文见固件方提供的
> `I2C_SLAVE_PROTOCOL(1).md`。
>
> 生效固件：2026-10-19 起（`rk3568_r`，`lunch rk3568_r-userdebug`）。
> **旧节点 `/sys/devices/platform/hdmi-control/hdmi_b2`、`hdmi_b3` 已从固件移除**，
> 继续写会返回 `ENOENT`。

---

## 1. 为什么换成 I2C 直控

| 项 | 旧的 GPIO 脉冲 | 现在的 I2C 直控 |
|---|---|---|
| 控制方式 | 写电平模拟「按一下按键」 | 写寄存器，**直接设目标值** |
| 能力 | 只能「切到下一个」 | 可设任意目标值，**并回读当前值** |
| 回读 | 无 | `mode` / `resolution` / `status` 等 |
| 权限 | 节点属 root，需 ROM 放开或 su | init rc 已 `chmod 0666`，**不再需要 su** |
| 阻塞 | 毫秒级 | **可能数百 ms 到数秒** |

旧方案的两个根本问题因此消失：无法得知当前模式、无法直接跳到目标模式。

---

## 2. 节点

固定基址：`/sys/bus/i2c/devices/4-0030/`
（`4` = I2C 总线号，`0030` = 7-bit 从机地址 0x30；路径每次启动都不变）

### 2.1 可写

| 节点 | 取值 | 说明 |
|---|---|---|
| `mode` | 0=1X1 · 1=1X2 · 2=2X1 · 3=1X3 · 4=3X1 · 5=2X2 · 6=1X4 · 7=4X1 | 拼接模式 |
| `resolution` | 0=720p60 · 1=1080p60 · 2=2160p30 | 输出分辨率 |
| `mirror` | 0–15（bit0..bit3 = TX1..TX4） | 指定 TX 镜像 |
| `rotate` | 0=0° · 1=90° · 2=180° · 3=270° | 旋转 |
| `hfusion` / `vfusion` | 0–255 | 边缘融合（见 §4 的 flash 警告） |
| `mute` | 0/1 | 音频静音 |
| `power_state` | 0=待机 · 1=开机 | 电源（**不是 `power`**：与内核自带 `power/` 目录重名会导致整组节点创建失败） |
| `key` | 按键码 | 仅向后兼容，新代码不要用 |

### 2.2 只读

| 节点 | 说明 |
|---|---|
| `version` | 恒为 `2`。**在线判定就靠它** |
| `status` | bit0 BUSY · bit1 POWER · bit6 ERROR · bit7 OVERFLOW |
| `result` | 上一条命令的结果码，`0` = OK |
| `cmd_count` | 已执行命令计数（模 256，**跨电源周期会归零**） |
| `rx_state` | bit n = 1 表示 HDMI RX n 有稳定输入 |

---

## 3. 错误码与 App 侧处理

| errno | 含义 | 是否重试 |
|---|---|---|
| `EINVAL` (22) | 值越界 / 格式错 | ❌ 不要重试 |
| `ERANGE` (34) | 固件拒绝（BAD_VALUE） | ❌ 不要重试 |
| `EPERM` (1) | 待机中，只接受 `key`/`power_state` | ❌ 先写 `power_state=1` |
| `EOPNOTSUPP` (95) | 当前模式不支持（如非拼接模式设 fusion） | ❌ 先切模式 |
| `ETIMEDOUT` (110) | 2 秒未完成 | ✅ 重试 1 次 |
| `EIO` (5) | 握手过程中总线出错 | ✅ 重试 1 次 |
| `ENODEV` (19) | **从机不应答**（节点在，I2C 无 ACK） | ❌ 重试无意义，降级 UI |
| `ENOENT` (2) | 节点不存在（**驱动未加载 / DTS 未配**） | ❌ 这是**软件**问题 |
| `EACCES` (13) | 权限未放行（开机早期） | ✅ 500ms × 3 |

实现位置：`hardware/HdmiMatrixController.kt` 的 `errorFor()` —— 这是错误语义
在代码里的**唯一落点**，UI 只按 `HdmiMatrixErrorKind` 取文案。

---

## 4. 硬性约束（违反会出事故）

1. **禁止在 UI 主线程读写。** 单次典型 <100ms，最坏数秒。
   本项目的所有调用都在 `Dispatchers.IO`，且控制器内部用 `Mutex` 串行化。
2. **`hfusion` / `vfusion` 每次成功写都会写 MCU flash。**
   拖动滑杆时逐帧下发会在几分钟内写坏 flash。
   App 的做法：拖动只改内存（stage），**松手后才 commit**，且两次提交间隔 ≥300ms。
   相关常量：`HardwareConfig.FUSION_FLASH_DEBOUNCE_MS`。
3. **写 `mode` 会清除所有镜像。** 因此顺序必须是 **先 mode，再 mirror**。
   `HdmiMatrixController.applyLayout()` / `applySettings()` 已强制这个顺序，
   并且在 mode 变更后**强制重写 mirror**（此时回读值不可信）。
4. **镜像有模式门槛**：TX3 要求 mode ≥ 3，TX4 要求 mode ≥ 5。
   本项目默认按更严格的 TX4 处理（`HardwareConfig.HDMI_BOARD_VARIANT`）。
5. **重复写相同值安全**（固件返回 OK 且不动作），但控制器仍会先回读、
   值相同则跳过写，以减少无谓 IO。
6. **读节点返回「已生效」的值**；写入返回成功即表示已执行完。

---

## 5. App 布局 ↔ 硬件模式映射（**有个坑**）

`LayoutMode.wallModeNumber` 是**拼接板示意图编号**，与 `mode` 寄存器的取值**不同**。
典型陷阱：`GRID_2X1` 的示意图编号是 **5**，而硬件 `mode` 是 **2**。

映射表在 `hardware/HdmiMatrixModeMapping.kt`：

| LayoutMode | 硬件 `mode` |
|---|---|
| `SINGLE` | —（不写硬件） |
| `GRID_1X2` | 1 |
| `GRID_1X3` | 3 |
| `GRID_2X1` | **2** |
| `GRID_3X1` | 4 |
| `GRID_2X2` | 5 |
| `ROW_1X4` | 6 |
| `COLUMN_4X1` | 7 |
| `GRID_1X2_PORTRAIT` / `GRID_1X3_PORTRAIT` | —（软件旋转，不写硬件） |
| `GRID_2X2_SAME` / `PIP` | —（软件模式，不占用拼接板编号） |

> 反方向：硬件模式切了以后，App 通过读 `mode` 得知；但**不会**自动改
> `settings.layoutMode` —— 硬件切模式与 App 排布是两件事，静默互改是最难排查的一类问题。

---

## 5.5 ENOENT 与 ENODEV 必须分开处理（**现场最容易搞混**）

| | ENOENT (2) | ENODEV (19) |
|---|---|---|
| 含义 | 节点不存在 | 节点存在，从机无 ACK |
| 原因 | 驱动未加载 / DTS 未配置 | 矩阵未接、未上电、总线电平不对 |
| 处置 | 查固件、查 DTS、查 `dmesg` | 查线缆、查供电、查 pinmux |
| App 表现 | `MISSING` → 「驱动节点缺失」 | `NO_DEVICE` → 「拼接板无应答（检查连接与供电）」 |

> 2026-10-19 固件把「总线不通」的返回码从 `EIO` 改成了 `ENODEV`。
> 旧代码若按 `EIO` 判断「硬件没接」，会误判。

## 6. 启动检测

```kotlin
// 控制器内部（简化）
suspend fun ensureOnline(timeoutMs: Long = 20_000, pollMs: Long = 250): HdmiMatrixResult<Unit>
```

- 判定依据：`version == 2`
- 开机早期节点可能短暂不存在（驱动尚未 probe），因此轮询；
  但 `EACCES` 不轮询（权限问题不会自愈）
- 不在线时 UI **隐藏硬件控制入口**，而不是让按钮点了没反应

---

## 7. 现场自检

```bash
# 1) 驱动是否加载
dmesg | grep -i "hdmi-matrix-ctl"

# 2) 从机在线
cat /sys/bus/i2c/devices/4-0030/version        # 期望 2

# 3) 总线扫描
i2cdetect -y 4                                  # 期望 0x30

# 4) 控制链路
echo 6 > /sys/bus/i2c/devices/4-0030/mode
cat /sys/bus/i2c/devices/4-0030/mode            # 期望 6
cat /sys/bus/i2c/devices/4-0030/result          # 期望 0

# 5) 节点权限（应为 0666）
ls -l /sys/bus/i2c/devices/4-0030/mode
```

---

## 8. 回退窗口

`HardwareConfig.LEGACY_GPIO_FALLBACK`（默认 `false`）可在**旧 ROM** 上回退到
`hdmi_b2/b3` 脉冲。新固件全面部署后，以下文件可一并删除：

- `hardware/HdmiGpioController.kt`
- `hardware/HdmiMatrixLegacyFallback.kt`
- `HardwareConfig.MODE_KEY_PATH` / `RESOLUTION_KEY_PATH` / `SU_*`

---

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-19 | App 侧从 GPIO 脉冲迁移到 I2C 直控；新增本文件 |
| 2026-10-19 | ABI 修订：`power` → `power_state`；新增 `ENODEV` 语义并与 `ENOENT` 分开；节点恒定存在 |
