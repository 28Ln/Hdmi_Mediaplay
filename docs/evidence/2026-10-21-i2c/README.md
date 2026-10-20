# I2C-4 / 0x30 故障规律研究（2026-10-21 深夜）

设备：RK3568 `776134475d08a030`，Android 11
目标：搞清楚「I2C 一开始正常、运行一段时间后失效、必须重启」这个规律。

## 结论

**本机不存在「先好后坏」的窗口。** 从上电第 2.7 秒起持续故障，
10 分钟内每 30 秒探测一次，**零次成功**。软硬件因素已逐项排除，
故障位于 i2c-4 的**电气层**（SDA/SCL 未被拉高）。

## 一、对照实验（最关键）

同一时刻对两条总线做相同操作：

| 总线 / 地址 | 结果 | 耗时 | 判读 |
|---|---|---|---|
| i2c-1 / 0x38（LT86204，已绑驱动） | `EBUSY` | 286ms | 总线正常 |
| i2c-1 / 0x30（该地址无设备） | `No such device or address` | **265ms** | **总线正常，无设备快速 NACK** |
| i2c-4 / 0x30 | `Connection timed out` | **1206ms** | 总线无信号 |
| i2c-4 / 0x2f | `Connection timed out` | 同 | 整条总线无信号 |
| i2c-4 / 0x31 | `Connection timed out` | 同 | 整条总线无信号 |

**关键点**：正常总线在「地址无设备」时 265ms 内返回 NACK。
i2c-4 连这个都做不到 —— **不是 0x30 单颗从机的问题，是整条总线没信号**。

同时刻 i2c-1 日志持续正常：
```
[776.563640] LT86204: 38 b2 ff 00
[776.564243] LT86204: No change needed for TX channels, RX1=0
```

## 二、已排除的软件因素

| 项 | 证据 |
|---|---|
| **驱动** | 解绑 `hdmi-matrix-ctl` 后用 `i2cget` 直连，仍 `Connection timed out` |
| **App** | 停掉 App 15 秒，内核超时计数仍 +15（每秒一次），来自驱动内部探测 |
| **pinmux** | `pin 138/139 → function i2c4 group i2c4m0-xfer`，配置正确 |
| **时钟** | `clk_i2c4` enable=1 @24MHz，`pclk_i2c4` enable=1 @99MHz |
| **总线速率** | DT `clock-frequency = 0x000186A0` = 100kHz 标准值 |
| **DT 节点** | `pinctrl-0` / `clocks` / `interrupts` / `reg` 齐全 |
| **runtime PM** | `unsupported` / `auto`，未挂起 |
| **节点创建** | 14 个 ABI 节点全部存在（共 21 项），驱动绑定正确 |

## 三、故障特征

- **693 次超时，全部 `ipd: 0x00, state: 1`，零变化**
- 间隔精确 **1.013 秒**（驱动内部定时探测）
- 首次超时在上电第 **2.775 秒**，早于驱动绑定（6.962s）
- 驱动在第 6.962 秒明确报告：
  `hdmi-matrix-ctl 4-0030: bound at 0x30, slave not answering (check wiring/IO level)`

`ipd` 是 I2C IP 的引脚方向寄存器回读值。`0x00` 表示输出方向位全零 ——
引脚未能驱动 SDA/SCL。此前另一台样机报 `ipd: 0x80`（部分引脚有动作），
本机连这个都没有。

## 四、超时时长不稳定

10 分钟监测中单次 `i2cget` 耗时：6.5s / 9.0s / 10.4s / 12.5s / 10.5s /
10.6s / 15.1s / 10.7s / 15.7s / 10.6s / 14.9s / 12.1s / 11.7s / 10.3s / 14.2s

波动 6.4~15.7 秒，且部分请求被外层 timeout 杀掉（无返回）。
对照 i2c-1 的稳定 265ms，说明 i2c-4 的时序本身异常。

## 五、需要硬件方排查

按可能性排序：

1. **上拉电阻** — SDA/SCL 是否有 4.7kΩ 上拉到 3.3V。
   `ipd: 0x00` + 整条总线无 NACK，最指向这里。
2. **接线** — i2c4m0 是 gpio4-10(SDA)/gpio4-11(SCL)，
   确认拼接板确实接在这两个脚上，且没有接错到其它管脚组。
3. **供电** — 拼接板 MCU 是否上电。
4. **地址跳线** — 但注意：即使地址错，总线也应快速 NACK 而非超时，
   所以地址不是主要原因。
5. **总线负载/电容** — 线缆过长、上拉过弱会导致上升沿过缓。

## 六、关于「先好后坏」

用户描述「重启后能好一阵再变坏」，但本次实测未能复现：

- 软件重启（`reboot`）后：t=2.7s → 1350s，**零次成功**
- 解绑驱动后立即裸访问：仍超时
- 10 分钟内每 30 秒探测：全部失败

若「好一阵」确实存在，需进一步确认：
- 是否为**断电重启**（而非 `reboot`，后者外设不断电）
- 是否为**另一台板子**
- 「好」的时候 `cat version` 的实际返回值

## 七、可复现的验证命令

```bash
# 1. 对照：正常总线无设备的耗时（应 <300ms）
adb shell su root i2cget -y 1 0x30 0x00

# 2. 故障总线（应超时）
adb shell su root i2cget -y 4 0x30 0x00

# 3. 看驱动报告
adb shell su root sh -c 'dmesg | grep -E "rk3x-i2c|hdmi-matrix" | tail -20'

# 4. 确认 pinmux
adb shell su root sh -c 'cat /sys/kernel/debug/pinctrl/*/pinmux-pins | grep "pin 13[89]"'
```

## 八、SELinux 部署风险（2026-10-21 新增，**重要**）

本机 `getenforce` = **Permissive**，因此 sysfs 访问被放行。但审计日志显示
应用**确实被拒绝**，只是宽容模式没拦：

```
avc: denied { open } for comm="i2c-sysfs-reade"
  path=".../4-0030/version" scontext=u:r:untrusted_app:s0 permissive=1
```

实测对比（临时 `setenforce 1` 后立刻恢复）：

| SELinux | 结果 | 耗时 |
|---|---|---|
| Permissive | `ENODEV`（等 I2C 超时） | 25.5s |
| **Enforcing** | **`Permission denied`** | **289ms** |

**如果量产 ROM 设为 Enforcing，App 将完全无法访问 I2C 节点**，
表现为「权限拒绝」而不是「总线故障」，排查方向完全不同。

需要在 ROM 侧补 SELinux 策略（`untrusted_app` 允许读写该 sysfs，
或改用 `sysfs_i2c` 标签 + 相应 allow 规则），或保持 Permissive。

注：本次开机 36 条 avc denied 涉及 `/dev/i2c-4`、`/mnt/media_rw`、
`/system/xbin/su` 等，说明这类放行是**普遍依赖**，不止 I2C 一处。

已确认 SELinux 状态**不会随运行时间变化**（开机即 permissive，
日志中无任何自动切换记录），因此它**不是**「先好后坏」的原因。

## 九、驱动 unbind 不会因 reboot 自动恢复（操作注意）

测试中执行过 `echo 4-0030 > .../unbind`，之后 `reboot` 重启，
驱动**仍是未绑定状态**（sysfs 只剩 5 个条目，日志显示 `removed`）。
需要手动重新 bind：

```bash
echo 4-0030 > /sys/bus/i2c/drivers/hdmi-matrix-ctl/bind
```

做故障复现时若发现节点数量异常少，先确认这一点，否则会误判为
「驱动未加载」。
