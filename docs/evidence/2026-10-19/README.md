# 验证证据 — 2026-10-19（API 30 模拟器）

- 设备：Android Emulator `RK3568_API30`（Android 11 / userdebug / 1024×600 @160）
- 包：`app-release.apk` 1.1.3 (versionCode 5)，签名证书 `CN=byteflyer`（非 debug）
- 构建：`clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`
  → **BUILD SUCCESSFUL**，77 tests / 0 failures，lint 0 errors

## 截图

| 文件 | 内容 | 判定 |
|---|---|---|
| `release-4pane-distinct.png` | release 包，2×2，四格分别为 FILE 1/2/3/4 | ✅ 四路独立内容，无串格 |
| `usb-copy-4-distinct.png` | U 盘复制完成后四格 | ✅ 四个文件各就各位 |
| `same-mode-drift.png` | 软件模式（2×2 同画面） | ✅ 四格同一文件；⚠️ 时间差约 10s（已知限制） |
| `insufficient-space.png` | 9GB 稀疏文件触发空间不足 | ✅ 红色提示；修后无残留对话框 |
| `settings-page.png` | 客户版设置页（1024×600） | ✅ 无截断，布局正常 |

## 未能在此环境验证

- `hide` 是否真能退到后台（应用注册为 HOME；需真机）
- 远控 19 条命令（需签名权限；`am broadcast` 被拒）
- HDMI 节点 / 硬解 / AC3 / 温度
- 真实 USB 硬件插拔与 FAT32 时间戳精度

详见 `docs/audits/2026-10-19-audit-03.md` 第七节。
