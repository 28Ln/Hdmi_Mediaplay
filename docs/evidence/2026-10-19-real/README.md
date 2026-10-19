# 真机验证证据 — 2026-10-19（RK3568 / Android 11）

- 设备：`rk3568_r`（`776134475d08a030`），Android 11 / userdebug / 1024×600 @160
- 包：`app-release.apk` 1.1.3 (versionCode 5)，`CN=byteflyer`
- 方法：root + `mount --bind` 造假 U 盘（真机 `/mnt/media_rw` 同样需要 `chmod 755` 才能被 app 目录遍历）

## 已验证

| 项 | 结果 | 证据 |
|---|---|---|
| 四路独立播放 | ✅ 219/219/220/219（0.1s 计数） | `same-mode-4pane.png` |
| 2×2 同画面（软件模式） | ✅ 255/255/256/255，偏差 ≤0.1s | `same-mode-enabled.png` |
| U 盘插入识别 | ✅ `Total USB paths found: 1` | 日志 |
| 覆盖确认框 | ✅ 正常弹出 | `usb-overwrite-dialog.png` |
| 两阶段提交 | ✅ 四路 stage → 四路 commit | 日志 |
| 空间不足 | ✅ 进入错误态 | 日志 |
| 指数退避 | ✅ 5→10→20→40→60s，封顶后保持 60s | 日志 21:14~21:24 |
| 空间释放后自动恢复 | ✅ 重新弹确认框，不卡死 | `space-recovered.png` |

## 未验证

- HDMI I2C 控制：**固件尚未提供那 14 个节点**（`/sys/bus/i2c/devices/4-0030/` 下只有 modalias/name）。
  App 会显示「驱动节点缺失」并隐藏控制入口，不崩溃。
- AC3 / 硬解零拷贝 / 长时间温度：本轮未测。
