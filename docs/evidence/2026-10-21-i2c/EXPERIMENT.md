# I2C 对照实验：判定 App 是否为元凶

## 背景

已确证的规律：
- **断电重启** → I2C 正常约 **203 秒** → 永久失效
- **`reboot`**（不断外设电）→ 从头就坏，`ipd: 0x00`

未解问题：**App 的写操作是不是导致失效的原因？**

失效前 1.35 秒确实观察到 App 写 `mode`：
```
[201.794356] avc: denied { write } ... name="mode" ... app=com.btf.rk3568_hdmi_mediaplay
[203.148934] rk3x-i2c fe5d0000.i2c: timeout, ipd: 0x91, state: 1
```
但因果未验证 —— 之前尝试对照时用了 `reboot`，根本没进入可用窗口。

## 实验设计

### 步骤

1. **断开电源**，等 10 秒
2. **上电**
3. **立即**在 PC 上执行（不要打开 App）：

```bash
adb push scripts/i2c_probe.sh /data/local/tmp/
adb shell "su root sh /data/local/tmp/i2c_probe.sh"
```

脚本会每 2 秒读一次 `version`，连续 3 次失败才判定失效，
并记录失效时刻的 `ipd` 分布与最近的 timeout 日志。

### 关键约束

- **全程不启动 App**（`pm disable-user com.btf.rk3568_hdmi_mediaplay` 可确保）
- 必须是真的断电，不是 `reboot`

## 判据

| 结果 | 结论 | 下一步 |
|---|---|---|
| I2C 保持正常 **>> 200 秒**（如 10 分钟以上） | **App 的写操作是元凶** | 排查 `applyLayoutToHardware` 的写入时机；可能需要加写保护或延迟 |
| 仍在约 **200 秒**后失效 | **拼接板 MCU 自身问题** | 查看门狗、供电稳定性、温漂；App 层无需改动 |

## 辅助实验（可选）

如果第一个实验显示 App 是元凶，可进一步定位到具体操作：

### A. 只读不写

断电重启后启动 App，但**不进入设置页、不切换布局**，
观察是否仍能维持超过 200 秒。

- 能 → 是 `mode` 写入导致
- 不能 → 是 `ensureOnline` 的读轮询导致（20 秒内 80 次读）

### B. 禁用启动时的布局恢复

临时改 `restoreHardwareLayoutOnStartup()` 直接 return，
观察是否延长可用时间。

## 已知的软件恢复手段（全部无效）

| 手段 | 结果 |
|---|---|
| 驱动 unbind → rebind | `slave not answering` |
| `delete_device` → `new_device` | 新设备实例化成功，probe 仍失败 |
| `i2cget` 直连 | `EBUSY` |
| `reboot` | 不断外设电，从头就坏 |
| **断电重启** | ✅ 唯一有效 |
