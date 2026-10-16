# Device Verification Guide

## Purpose

本文件用于指导真机验证，覆盖代码与 CI 无法完全证明的链路：

- USB 插拔
- 复制回滚
- 播放完成 / 播放异常
- 远控权限联调
- 布局模式与拼接板示意图一致性
- 滚动输入（鼠标滚轮 / 方向键）
- Release 行为验收

## Test Environment

- RK3568 目标设备
- Android 11+
- 至少 1 个包含 `btf_config.json` 与媒体目录的 U 盘
- 至少 1 个无媒体或错误结构的 U 盘
- 具备发送广播命令的联调端
- 现场使用的鼠标 / 空鼠 / 遥控器（用于滚动输入验证）
- 拼接板原始示意图：`docs/hdmi-wall-mode-diagram.jpg`（转正版 `-rotated.jpg`）

## Verification Checklist

### 1. Startup

- 冷启动进入主界面
- 退出到后台再恢复
- 开机自启动开关开启后重启设备验证

### 2. USB Insert / Remove

- 插入有效 U 盘
- 插入无媒体 U 盘
- 插入坏配置 / 非法配置 U 盘
- 复制过程中拔出 U 盘
- 复制完成后再次插入新内容

### 3. Copy Rollback

- 本地已有旧缓存
- 插入无效 U 盘或制造复制失败
- 验证旧缓存仍可恢复播放

### 4. Playback Lifecycle

- 视频正常播完
- 多视频列表切换
- 图片轮播切换
- 人工制造错误文件，验证错误状态与提示

### 5. Remote Control

- 受信发送方发命令成功
- 非授权发送方命令被拒绝
- `get_status` 返回当前媒体与索引

### 6. Release Feature Matrix

逐项验证：

- 设置按钮
- 图片裁剪入口
- 本地扫描入口
- HDMI 控制入口
- 帮助提示
- 底部控制栏

### 7. Orientation / Portrait Wall

- 设置页选择模式 3（竖屏 1×2）→ 画面旋转为竖屏且左右分屏跟随
- 选择模式 4（竖屏 1×3）→ 竖屏三分
- 切回模式 1/2 → 恢复横屏
- 旋转过程中视频播放不中断、无黑屏残留（SurfaceView 恢复）
- 旋转后状态栏/导航栏不弹出（沉浸式全屏保持）
- U 盘配置 `features.allowOrientationAutoSwitch: false` → 竖屏模式按横屏渲染
- ROM 不支持旋转时静默降级（保持横屏），Logcat 输出 orientation 请求日志

### 8. Same-content 2×2 / HDMI efull

- 设置页选择「2×2 同画面」→ 四个格子播放 P1 同一路内容，P2–P4 静音
- 切回模式 7 → 恢复四路独立内容
- 底部栏 / 设置页点「HDMI全屏」→ `getprop persist.sys.rotation.efull-1` 在 `true` / `false` 间切换
- 无权限时按钮显示设置失败，不崩溃

### 9. 布局模式与示意图一致性（1.1.0 新增）

对照 `docs/hdmi-wall-mode-diagram.jpg`（拼接板原始示意图）逐个确认：

- 模式 1 → 2 路左右并列
- 模式 2 → 3 路左右并列
- 模式 3 → 竖屏 + 2 路左右并列
- 模式 4 → 竖屏 + 3 路左右并列
- 模式 5 → 2 路上下分屏
- 模式 6 → 3 路上下分布
- 模式 7 → 4 路 2×2 方阵
- 「2×2 同画面」（软件模式）→ 四格同内容，仅 P1 出声
- 客户模式下 8 个模式按钮的「模式 N / 布局名 / 描述 / ✓当前」四行文字**均不被截断**

> 注：示意图上模式 5/6/7 的手写标注（如模式 5 写作「1×2」）采用「列×行」记法，
> 与 App 内「横屏 2×1」的「行×列」记法相反，但所指画面相同。
> 本版未改动客户可见的布局名文案，需确认后统一。

### 10. 滚动输入（1.1.2 实现，真机已测）

**问题**：设备用 USB 鼠标、**无触摸屏**。Compose 的 `scrollable`（`verticalScroll`
的底层）只处理**触摸源**拖动，鼠标源的按住拖动被直接忽略 ——
因此「鼠标按住拖动」在本应用里不滚动，而同设备上的系统设置（原生 `ScrollView`）可以。

**为什么此前排查耗时很久**：`adb shell input swipe` 注入的是**触摸源**事件，
走的是能工作的那条分支，所以用它验证总是显示「能滚」，掩盖了真实问题。

**复现**（必须注入真实鼠标事件）：
```
# 按下
adb shell sendevent /dev/input/eventN 1 272 1 && adb shell sendevent /dev/input/eventN 0 0 0
# 拖动（重复若干次）
adb shell sendevent /dev/input/eventN 2 1 -3 && adb shell sendevent /dev/input/eventN 0 0 0
# 抬起
adb shell sendevent /dev/input/eventN 1 272 0 && adb shell sendevent /dev/input/eventN 0 0 0
```

**实现**：`ui/components/MouseDragScroll.kt` 的 `Modifier.mouseDragScroll(state)`。
两个要点缺一不可：

| 要点 | 作用 | 缺失后果 |
|---|---|---|
| `PointerEventPass.Initial` | 由外向内派发，本处理器**先于**内建 `scrollable` 收到事件 | 用 `Main`（内层先收）时内建会先滚一次 |
| `change.consume()` | 内建收不到被消费的事件，不再二次滚动 | 不消费则两条路径同时滚 → **慢速拖动抖动** |

**验证**（真机 + 真实鼠标事件注入）：

| 输入 | 结果 |
|---|---|
| 鼠标按住拖动 | ✅ 慢速拖动 40 步 × 1px → 精确移动 40px，无抖动 |
| 触摸拖动 | ✅ 正常（走内建 scrollable） |
| 鼠标滚轮 | 走 Compose 内建 `Scroll` 分支 |

**应用位置**：设置页（`SettingsScreen.kt`）与图片裁剪页（`ImageSplitScreen.kt`）。

**设置页另有一处布局修复**：滚动容器此前被套入一层 `Box(Modifier.fillMaxSize())`
（为放置悬浮滚动按钮引入），导致滚动区高度被挤出可视范围。
排查方法：`uiautomator dump` 查看滚动容器 bounds，健康状态为 `[0,64][1024,552]`
（顶栏之下、铺满剩余空间）。

**兜底**：设置页右侧提供 ▲▼ 悬浮按钮，仅依赖点击，滚轮不可用时仍可浏览。

### 11. HDMI 硬件按键权限（1.1.0 发现，需 ROM 侧处理）

**实测结论：底部栏「切换模式」「切换分辨率」在目标设备上写入失败。**

```
HdmiGpioController: open failed: EACCES (Permission denied)
```

原因：`/sys/devices/platform/hdmi-control/hdmi_b2` 与 `hdmi_b3`
权限为 `-rw-r--r-- root root`，而 app 以普通应用身份（`u0_aXXX`）运行。
SELinux 为 `Permissive`，故纯属文件权限问题，**不是代码缺陷**。

- 代码侧的错误处理是正确的：捕获异常、记录日志、不崩溃
- 1.1.0 起主界面底部栏也会在失败时弹出提示
  （此前该处丢弃返回值，按钮"按了没反应"，无法区分失败与未生效）
- **要让功能可用，需 ROM 侧放开节点写权限，或将 app 置于系统权限下运行。**
  这是需要与设备/ROM 提供方确认的部署项。

验证：

```
adb shell ls -l /sys/devices/platform/hdmi-control/hdmi_b2
adb shell 'echo 1 > /sys/devices/platform/hdmi-control/hdmi_b2'   # 以 app 身份应可写
```

### 12. 视频编码兼容性（1.1.0 发现）

**实测结论：AC3 音频轨的 MP4 在当前设备上无法播放。**

```
VideoPlayerView: Player error: MediaCodecAudioRenderer error,
  format=Format(2, null, null, audio/ac3, ...)
Caused by: AudioSink$InitializationException: AudioTrack init failed
```

设备无 AC3 解码器（`createAudioTrack` 失败），整个播放器报错。
同目录下的 AAC 音频 MP4 播放正常。

- 界面表现符合预期：该格显示「❌ 播放出错 / 请检查文件格式或重新选择」
- 错误事件正确外发：`PlayerStatusBroadcaster: 发送状态: type=error`
- **投放到现场前需确认客户媒体文件的音频编码**；建议使用 AAC。

## Evidence Collection

每次真机验证至少留存：

1. 设备型号 / 固件版本
2. App 版本 / commit SHA
   - 1.1.0 起可在设置页「关于」直接读取版本号（`1.1.2`）与版本代码（`4`）
3. 测试日期
4. 测试步骤
5. 结果
6. 失败截图 / 日志 / 视频

配套模板：

- `docs/acceptance-evidence-template.md`
