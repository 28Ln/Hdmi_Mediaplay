# 模拟器验证记录（Android 15 / API 35）

- **日期**：2026-10-18
- **环境**：Android Studio 模拟器 `Medium_Phone_API_35`（1080×2400 竖屏）
- **目的**：真机（RK3568 / Android 11）离线 13 小时期间，验证**纯应用层**行为
- **包**：`app-release.apk` 1.1.3 (5)

> ⚠️ **模拟器能验证什么、不能验证什么**
>
> | 能验证 | 不能验证 |
> |---|---|
> | 应用能否启动、有无崩溃 | HDMI 硬件控制节点（`/sys/.../hdmi_b*`） |
> | Compose 界面渲染是否正确 | 真实 U 盘挂载（`/mnt/media_rw`） |
> | 多路视频解码与播放 | 硬件解码器（`OMX.rk.video_decoder`） |
> | 依赖 Android 框架的 API 行为 | 设备热降频、AC3 直通 |
> | 权限请求流程 | 远控命令（需签名权限 / root） |
> | 后台服务与轮询逻辑 | 分辨率/横竖屏相关的布局细节 |

---

## 已验证项

| # | 项 | 方法 | 结果 |
|---|---|---|---|
| 1 | 安装 | `adb install` | ✅ API 35 上安装成功（targetSdk 34 兼容） |
| 2 | 启动 | 冷启动 + logcat | ✅ 无 `FATAL` / `AndroidRuntime` |
| 3 | 2×2 分格渲染 | 截图 | ✅ 四格均分，编号 P1–P4 正确 |
| 4 | 空状态提示 | 截图 | ✅ 「长按选择媒体文件 / 或插入U盘自动加载」 |
| 5 | `POST_NOTIFICATIONS` 运行时请求 | 首启弹窗 | ✅ **API 35 上正常弹出并授权** |
| 6 | 视频解码播放 | 推送 4 个 mp4 后重启 | ✅ **四格同时正常播放** |
| 7 | `PlaybackSync` 默认关闭 | logcat 计数 | ✅ **0 次调用**（无每秒转圈） |
| 8 | USB 空转抑制 | 12 秒稳态统计 | ✅ `Checking USB content` **0 次**（签名闸门前置生效） |
| 9 | 后台服务 | logcat | ✅ `Background started FGS`，`UsbMonitorService` 正常启动 |

### 关于第 8 项的意义

这是签名闸门优化（`bbd2e42`）的直接验证：
- `UsbUtils` 轮询 13 次（每 3 秒，符合预期）
- 但 `checkUsbContentAsync` 一次都没进入

说明「先算签名、命中即早退」的逻辑确实避免了无 U 盘时的无谓检查。

---

### 补充观察（2026-10-18 第二轮）

| # | 项 | 结果 | 说明 |
|---|---|---|---|
| 10 | 四格独立循环 | ✅ 观察到 | `GRID_2X2` 下四格各自循环到不同位置（左上姑娘/右上撑伞/左下唱歌/右下派对）。这是**该模式的预期行为**，不是 bug —— 只有 `GRID_2X2_SAME` 才要求四格同一文件 |
| 11 | 应用强制横屏 | ✅ 确认 | `dumpsys window` 显示 `ROTATION_90`，实际 bounds 为 `2400×1080`。**排查时注意：`wm size` 报的 1080×2400 是物理尺寸，应用内坐标系是旋转后的** |

关于第 10 项：这次观察印证了此前对客户反馈的理解 ——
「四格播不一样」在 `GRID_2X2` 下是正常的，
只有在 `GRID_2X2_SAME` 模式下才是缺陷。

---

## 未能验证（模拟器限制）

| 项 | 原因 |
|---|---|
| `hide` 是否真能退后台 | 需签名权限发广播；且本应用是 HOME 应用的假设在模拟器上不成立 |
| 远控命令（play/stop/set_layout 等） | 同上 |
| U 盘插拔刷新 | 模拟器无 `/mnt/media_rw` 挂载点，root 也不可用（production build） |
| 错误自动重试 | 依赖 U 盘链路 |
| SAME 四格同步 | 需通过设置页切换模式，而设置页入口在底部控制栏（见下表"设置页各项"） |
| 语言切换 | 同上 |
| 图片裁剪 | 同上 |
| 设置页各项 | **已尝试但失败**：`input tap` 无法可靠触发 Compose 的 `detectTapGestures`。底部触发区在横屏 `2400×1080` 下应为 y≈828~1080，多次点击均无响应。改用 `uiautomator dump` 也超时（Compose 语义树频繁刷新）。**建议真机上用真实鼠标/触摸验证，不要依赖 `input tap`** |
| 温度 / 硬解性能 | 模拟器走软件渲染 |

---

## 结论

**纯应用层行为正常。** 最关键的两条 —— 「四格能正常播放」和「同步功能默认关闭、不转圈」
—— 在模拟器上得到确认，这与之前所有静态推理一致。

**硬件相关行为仍需真机验证**，见 `docs/verification-1.1.3-checklist.md`。

---

## 复现方式

```bash
# 启动模拟器
"$ADB" -avd Medium_Phone_API_35 -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect

# 等待启动
until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 5; done

# 安装并授权
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell appops set com.btf.rk3568_hdmi_mediaplay MANAGE_EXTERNAL_STORAGE allow

# 推测试媒体（4 路各一个）
for i in 1 2 3 4; do
  "$ADB" shell mkdir -p /sdcard/RK3568MediaPlayer/player$i
  "$ADB" push <some-video.mp4> /sdcard/RK3568MediaPlayer/player$i/video.mp4
done

# 启动
"$ADB" shell am start -n com.btf.rk3568_hdmi_mediaplay/.MainActivity

# 验证无转圈 + 无空转
"$ADB" logcat -d | grep -c PlaybackSync          # 期望 0
"$ADB" logcat -d | grep -c "Checking USB content" # 期望 0
```
