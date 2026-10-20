# I2C-4 / 0x30 现场诊断记录（2026-10-21）

设备：RK3568 `776134475d08a030`，Android 11

## 结论先行

**0x30 这颗从机在物理层全程无应答。软件侧（App、驱动、sysfs、权限、DT）
均已排除。** 需要硬件排查。

## 关键证据

### 1. 总线控制器与引脚都正常

```
pin 138 (gpio4-10): fe5d0000.i2c → function i2c4 group i2c4m0-xfer
pin 139 (gpio4-11): fe5d0000.i2c → function i2c4 group i2c4m0-xfer
i2c@fe5d0000 clock-frequency = 0x000186A0 = 100000 Hz
```

pinmux 正确、100kHz 标准速率。

### 2. 同一颗 SoC 上其它 I2C 从机全部正常

| 总线 | 从机 | 状态 |
|---|---|---|
| i2c-0 | tcs452x, rk809 | ✅ |
| i2c-1 | lt86204, hym8563 | ✅ 日志持续 `38 b0 ff 00` |
| i2c-3 | gt9xx | ✅ |
| **i2c-4** | **hdmi-matrix-ctl (0x30)** | ❌ **无应答** |

SoC 的 I2C 控制器、时钟、驱动框架均正常。

### 3. 绕开驱动直连仍然超时

解绑 `hdmi-matrix-ctl` 后 sysfs 节点消失，改用 `i2cget -y 4 0x30 0x00`：

```
i2cget: i2c_read_byte: Connection timed out
```

**驱动不参与，仍然超时** → 排除驱动。

### 4. 与 App 无关

停掉 App 后观察 15 秒，内核超时计数从 442 涨到 457（**+15，每秒一次**）。
超时来自驱动内部定时探测，不是 App 请求。

### 5. 从开机第一条日志起就没通

```
[2.775723] rk3x-i2c fe5d0000.i2c: timeout, ipd: 0x00, state: 1
[6.962439] hdmi-matrix-ctl 4-0030: bound at 0x30, slave not answering (check wiring/IO level)
```

- 第 2.7 秒就开始超时（**早于驱动绑定**）
- 第 6.96 秒驱动明确报告 "slave not answering"
- 连续采样 t=36s → 571s（**535 秒、50+ 次**），**零次成功**
- 100 次超时全部是 `ipd: 0x00`（此前的另一台是 `0x80`）

### 6. 驱动已正确绑定

```
/sys/bus/i2c/devices/4-0030/driver -> .../hdmi-matrix-ctl
/sys/bus/i2c/devices/4-0030/name = hdmi-matrix-ctl
```

14 个 ABI 节点全部存在（共 21 项）。

## 需要硬件方排查

按可能性排序：

1. **供电** — 拼接板 MCU 是否上电。`ipd: 0x00` 表示总线电平检测读不到任何东西，
   强烈指向 SDA/SCL 未被拉高，常见原因是从机无 VDD。
2. **上拉电阻** — SDA/SCL 是否有 4.7kΩ 上拉到 3.3V。
3. **接线** — 0x30 是否真的接在 i2c-4 的 SDA(gpio4-10)/SCL(gpio4-11) 上。
4. **地址跳线** — MCU 的 7 位地址是否真的是 0x30。
5. **上电时序** — 若 MCU 启动慢于 SoC，需要驱动侧加延迟重试。
   （但本次实测开机 571 秒仍无一次成功，时序解释不了）

## 待确认

用户反馈「重启后能好一阵再变坏」，但本次实测软件重启后
从 t=2.7s 到 t=571s **零次成功**。若「好一阵」确实存在，需要：

- 记录**断电重启**（而非 `reboot`）后的逐秒表现
- 确认 «好一阵» 期间 `cat version` 的真实返回值
- 抓取故障发生**瞬间**的 dmesg（此前只看到已持续超时的状态）
