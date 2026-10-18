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
| U 盘插拔刷新 | **已实测确认无法验证**（见下）。模拟器无 `su`、`/mnt/media_rw` 拒绝访问、`/storage` 下无法 mkdir |
| 错误自动重试 | 依赖 U 盘链路，同上 |
| SAME 四格同步 | 需通过设置页切换模式，而设置页入口在底部控制栏（见下表"设置页各项"） |
| 语言切换 | 同上 |
| 图片裁剪 | 同上 |
| 设置页各项 | 未能进入。`input tap` 本身可用（已在系统设置中验证），但本应用的坐标系换算复杂，尚未点中底部触发区。详见下方「坐标系坑」 |
| 温度 / 硬解性能 | 模拟器走软件渲染 |

---

## 坐标系坑（重要，之前判断错了）

**`wm size` 报的是物理尺寸，截图是旋转后的尺寸，而 `input tap` 用物理坐标。**

本应用强制横屏（`dumpsys window` 显示 `ROTATION_90`）：

| 来源 | 坐标系 |
|---|---|
| `wm size` | `1080×2400`（物理） |
| `screencap` 截图 | `2400×1080`（旋转后） |
| `input tap` | `1080×2400`（物理） |

**换算**：截图点 `(sx, sy)` → 物理点 `(1080 - sy, sx)`

我最初按截图坐标直接传给 `input tap`，点了几次都落空，
一度误判为「`input tap` 无法触发 Compose 手势」——
**这个结论是错的**，已在系统设置页验证 `input tap` 正常工作。

### 但仍未点中底部触发区

尝试过的映射与结果：

| 映射 | 结果 |
|---|---|
| 截图坐标直接使用 `(1200, 950)` | ✗ 无响应 |
| `(1080-sy, sx)` | ✗ 无响应 |
| 物理四边遍历 `(540,2350)/(540,50)/(50,540)/(1030,540)` | 其中一个命中了播放器，触发暂停（说明坐标系在这一带附近） |
| 强制 `user_rotation=1` | ✗ 截图仍为 2400×1080 |

**结论**：`input tap` 可用，但本应用横屏下的坐标映射需要更多试错，
投入产出比低。**改用真实鼠标/触摸验证设置页**，或直接在横屏 AVD 上测。

> 这一项放弃继续尝试。已知它能验证的价值（四格播放、权限、轮询、无转圈）
> 都已经拿到；设置页交互留给真机。

**最终尝试记录（第 4 次）**：`dumpsys display` 显示物理 `rotation 0`，
而应用自己请求横屏（`mCurrentOrientation=1`）。按
`截图(sx,sy) → 物理(sy, 2400-sx)` 计算、点击 `(950, 1200)`，
命中的仍是播放器区域（出现暂停图标），不是底部 96dp 触发区。

**结论**：在这个 AVD 上，应用自旋转后的 `input tap` 映射无法靠推理确定。
不再尝试 —— 4 次试错的成本已超过收益，真机上用真实鼠标不存在此问题。

### 附带发现：软件解码很慢

模拟器走 `c2.goldfish.h264.decoder`（软件），4 路同时解码
需要约 **25 秒**才进入 `STATE_READY`。这期间四格显示黑屏 + 小白点（loading）。
真机上硬件解码不会有这个延迟。

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

---

## U 盘链路为何在模拟器上无法验证（已实测）

不要再次尝试，以下是实测结论：

| 检查项 | 结果 |
|---|---|
| `adb root` | `adbd cannot run as root in production builds` |
| `which su` | 不存在 |
| `ls /mnt/media_rw` | `Permission denied`（存在但不可访问） |
| `mkdir /mnt/media_rw/TESTUSB` | `Permission denied` |
| `mkdir /storage/FAKEUSB` | `Permission denied`（`/storage` 是 `drwx--x--- shell everybody`） |
| `run-as com.btf...` | `package not debuggable`（release 包） |
| 应用日志 | `Total USB paths found: 0`，始终为 0 |

`UsbUtils` 扫描 `/mnt/media_rw` 与 `/storage` 两处，
两处在模拟器上都既不可写、应用也读不到可识别目录。
**结论：U 盘插拔与错误重试只能在真机验证。**

---

## SAME 模式验证（2026-10-18 已突破）

### 关键发现：`wm size` 改成横屏后坐标问题消失

```bash
adb shell wm size 2400x1080
```

改为**横屏物理分辨率**后：
- `Override size: 2400x1080` 与截图尺寸一致
- `input tap` 坐标不再需要旋转换算
- 底部触发区、设置页按钮全部可点中

**之前 4 次坐标尝试失败的根本原因**：AVD 物理是 1080×2400 竖屏，
应用强制横屏后产生旋转，而 `input tap` 用物理坐标、截图用旋转后坐标，
两者不一致。直接改物理尺寸比换算坐标简单可靠。

### 验证结果

| # | 项 | 结果 |
|---|---|---|
| 12 | 进入设置页 | ✅ 底部栏 → 设置 |
| 13 | Debug 包显示完整设置页 | ✅ 8 个模式按钮（`FORCE_CUSTOMER_UI_PREVIEW=false` 生效） |
| 14 | 版本标识 | ✅ `v1.1.3 (5) · DEBUG`（黄色，构建类型正确） |
| 15 | 切换到「软件模式」 | ✅ DataStore 确认 `GRID_2X2_SAME`，界面显示「✓ 当前」 |
| 16 | **SAME 四格同文件** | ✅ **日志显示 4 次 `Media prepared` 全部是 `player1/video.mp4`** |
| 17 | **SAME 四格同画面** | ✅ 截图确认四格显示同一镜头 |

**第 16 项是本次最重要的验证** —— 它直接确认了
`displayedPlayerConfig` 的镜像修复有效：四格确实加载同一个文件。

### 未验证的部分

四格**时间同步**（是否完全对齐到同一帧）仍未验证 ——
`PlaybackSync` 默认关闭，四格各自缓冲会有毫秒级差异，
这与「播放不同文件」（已修复）是两个不同的问题。

### 底部控制栏按钮无法用 input 注入点击（工具限制，非应用缺陷）

想验证「语言切换」「裁剪」需先进设置页，但控制栏按钮点不动：

| 尝试 | 结果 |
|---|---|
| `input tap` 精确坐标 | ✗ 无响应 |
| `input swipe` 零距离（同点按下抬起） | **按钮出现按下高亮，但不执行** |
| 两条命令间隔 0.3s | ✗ 无响应 |

已确认 `CustomerControlButton` 是标准 `Button(onClick=...)`，无自定义手势。
按下高亮说明触摸**已被识别**，但 click 未触发 ——
`input` 注入的 press/release 时序不满足 Compose `Button` 的点击判定。

**结论**：控制栏交互只能真机验证。
设置页内部按钮（如「软件模式」）可用 `input tap` 正常点击，因为更大、层级更浅。

---

## SAME 模式为何在模拟器上仍无法验证（已实测）


换用 **Debug 包**（可 `run-as`）后重试，仍走不通：

| 路径 | 结果 |
|---|---|
| Debug 包 + `run-as` | ✅ 可访问应用私有目录 |
| 修改 DataStore 切 `layout_mode` | ✗ 启动时 DataStore 文件尚未创建（应用用默认值）；写入需 UI 操作触发 |
| DataStore 格式 | protobuf（`settings.preferences_pb`），不宜手写 |
| `am start` 带 Intent extra | ✗ 应用不读取任何 extra（只有 `ACTION_MOVE_TO_BACK` 广播） |
| 远控广播 `set_layout` | ✗ 需签名权限，debug 包同样被拒 |

**结论**：在本 AVD 上无法通过命令行把布局切到 `GRID_2X2_SAME`。
SAME 模式的四格同文件行为只能在真机上验证。
