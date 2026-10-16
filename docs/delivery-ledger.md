# 交付台账（单一事实来源）

> 本文件是本项目修复状态的**唯一权威台账**。
> `audit-findings.md` 与 `audit-checklist.html` 是审计过程的记录（编号体系不同、已过时），
> 状态以本文件为准。

- 版本基线：**1.1.2（versionCode 4）**，见 `gradle/libs.versions.toml`
- 台账更新日期：2026-10-16
- 核实方式：逐条 grep 代码中的实际实现，**不采信文档声称**

---

## 一、已修复（真机或代码核实）

| 编号 | 问题 | 证据（实际实现） |
|---|---|---|
| P0-01 | 布局切换销毁重建 ExoPlayer，视频回零 | `QuadPlayerLayout.kt` 隐藏格保留实例（`effectiveConfig` 改为暂停+静音） |
| P0-02 | 客户版 U 盘 `btf_config.json` features 全部失效 | `MainScreen.kt` 直读 `FeatureManager.featureFlags`，不再替换为 releaseDefaults |
| P0-03 | ERROR 状态不可逆，播放恢复后 UI 永久卡报错 | `MainViewModel.clearPlayerError` + 回调链 |
| P0-04 | 播放器初始化失败不上报，角标仍显示 PLAYING | `VideoPlayerView.kt` 该分支补 `onError?.invoke` |
| P0-05 | 路径穿越：`usbScanFolderName` 可越出 U 盘读任意目录 | `UsbConfig.validateFolderName` + `UsbUtils` canonicalPath 兜底 |
| P0-06 | `startForeground` 失败被吞 → 系统 5 秒后杀进程 | `UsbMonitorService.kt` 失败即 `stopSelf()` |
| P0-07 | 拷贝中断丢全部旧缓存 | `LocalStorageManager.kt` 回收孤儿备份 + 异常还原 |
| P0-08 | 拷贝并发互相删毁 staging | `LocalStorageManager.kt` `copyMutex.withLock` |
| P0-09 | ServiceConnection 泄漏 | `MainActivity.kt` `bindRequested` 标志 |
| P0-10 | 图片裁剪全量解码，4K/8K 必 OOM | `ImageSplitter.kt` `calculateInSampleSize` + 捕获 `OutOfMemoryError` |
| P0-11 | CI 无法在干净环境构建 | `gradle.properties` 已移除硬编码 `org.gradle.java.home` |
| P1-01 | 音频轨不受支持时整格黑屏 | `VideoPlayerView.isAudioTrackFailure` → 静音播视频 |
| P1-02 | 错误态遮盖同路可用图片 | `MediaPlayerView.kt` 优先级改为 视频 > 图片 > 报错 |
| P1-03 | 图片 `Size.ORIGINAL` 关闭降采样（96MB/格） | `ImageDisplayView.kt` 改为按格子上限 1920×1080 请求 |
| P1-04 | 客户版底部栏文案写死中文 | 接入 `StringResources.switchMode/switchResolution` |
| P1-05 | 设置页「维护操作」整块硬编码中文 | 接入 `StringResources.imageSplit/maintenance` |
| P1-06 | 布局模式 3 描述写错（上下分屏 → 实为左右并列） | `StringResources.getLayoutModeDescription` + 守卫测试 |
| P1-07 | Debug 包显示成 Release，验收误判 | 直读 `BuildConfig.IS_DEBUG_BUILD` |
| P1-08 | Debug 包被强制用 Release 功能矩阵 | `FeatureManager.init(!customerUiMode)` |
| P1-09 | 设置页完全无法滚动 | 滚动容器恢复为外层 Column 的直接子项 |
| P1-10 | 鼠标按住拖动无法滚动（设备无触摸屏） | `MouseDragScroll.kt`：Initial pass + consume |
| P1-11 | 「2×2 同画面」四格越播越不同步（**客户反馈**） | `PlaybackSync.kt`：P1 主时钟，5 秒检查 / 1.5 秒阈值 |
| P1-12 | HDMI 硬件按键因节点权限不可用 | `HdmiGpioController` 两级回退：直接写 → `su root sh -c` |
| P1-13 | 硬件常量散落硬编码 | `HardwareConfig.kt` 集中管理 |
| P1-14 | 版本号从未变过（1.0/1 自建仓未改） | 统一到 `libs.versions.toml` |
| P1-15 | 无变更记录文件，但发布流程要求 | 新增 `CHANGELOG.md`（含 1.1.0/1.1.1/1.1.2） |
| P1-16 | `.gitignore` 只写具体路径，改名即静默提交密钥 | 补 `*.jks`/`*.keystore`/`*.p12` 等通配 |

---

## 二、未修复（按影响排序）

### 影响交付

| 编号 | 问题 | 位置 | 说明 |
|---|---|---|---|
| P1-20 | 远控 11 种状态事件全部未文档化 | `docs/remote-command-contract.md` | 客户无法编写接收端，**对接前置条件** |
| P1-21 | 开机自启不可靠 | `receiver/BootReceiver.kt` | 无 `goAsync()`，`onReceive` 立即返回 |
| P1-22 | 图片裁剪非 16:9 源图变形 | `ImageSplitModels.kt` + `ImageSplitter.kt` | 每块无条件拉伸到 1920×1080 |
| P1-23 | 裁剪结果不落布局 | `ImageSplitViewModel.kt` | 2×2 裁完若当前是 SINGLE，只显示一格 |
| P1-24 | 状态载荷泄漏本地绝对路径 | `PlayerStatusBroadcaster.kt` | 含 U 盘挂载点 |
| P1-25 | 轨道选择无上限 | `VideoPlayerView.kt` | 无 `setMaxVideoSize`，4 路 4K 有风险 |
| P1-26 | `set_loop_mode` 静默降级 / `set_interval` 回报未 clamp | `PlayerCommandHandler.kt` | 缺省值 `"ALL"` 枚举中不存在 |
| P1-27 | 播放器级命令对无效索引仍报成功 | `PlayerCommandHandler.kt` | 广播虚假 `playing` 状态 |
| P1-28 | 错误码塌缩，1004/1005 永不触发 | `PlayerCommandHandler.kt` | 三类失败折叠为同一码 |
| P1-29 | `hide`/`show` 无投递确认即报成功 | `PlayerCommandHandler.kt` | Activity 不在前台时无人接收 |
| P1-30 | 服务内接收器 <13 隐式导出注册 | `UsbMonitorService.kt` | 未统一 `ContextCompat.registerReceiver` |
| P1-31 | 样式无单一来源（4 种按钮高度 / 3 套字号） | `SettingsScreen.kt` | 无 `SettingsDimens` |
| P1-32 | 模式按钮文字可能被硬裁 | `SettingsScreen.kt` | 固定 `height(82dp)`，无 `TextOverflow` |

### 体验与可维护性（P2）

- `showDebugInfo` 僵尸标志（声明/解析/文档齐全，代码零读取）
- `batch` 无深度限制，可致 `StackOverflowError` 崩溃主线程
- 主线程阻塞 I/O，万级文件目录扫描有 ANR 风险
- `POST_NOTIFICATIONS` 未声明 → Android 13+ 前台服务通知不可见
- `Connected` 状态不重扫；`Error` 状态无退出路径
- 底部触发区吞掉最后 96dp 内所有点击（含播放器点击）
- 15s 自动隐藏与 ✕ 计时竞态
- 图标按钮无 `contentDescription`
- `WALL_MODE_8` 是臆造别名；`uppercase()` 受系统语言影响
- `MainViewModel` 职责过载（900 行 / 8 类职责），无法单测
- 死代码：`MainViewModel.getCurrentStoragePath()`、`SingleImageView`、
  `onPlaybackEnded`/`onNextVideo` 回调链、`hdmiEfullOn/Off` 未使用

### 打磨（P3）

- `gap = 2` 用像素非 dp，换密度屏会露缝
- 布局与裁剪各有一套并行行列模型
- `res/values/strings.xml` 九成死资源
- release 未开启代码裁剪，APK 96% 是未处理 dex
- `TASK_LIST.md`、`btf_config_sample.json` 与代码漂移

---

## 三、阻塞项（需外部输入）

| 项 | 说明 | 需要谁 |
|---|---|---|
| **密钥泄漏** | 生产签名密钥 `app.jks` 可从公开仓库历史恢复，需轮换 + 清历史 | 需决策后执行 |
| **HDMI 节点权限** | `/sys/.../hdmi_b{2,3}` 属 root。App 已支持 su 回退，但**推荐 ROM 侧放开**（一行 `chmod 0666`），性能更好、不依赖 root | ROM 方 |
| **AC3 音频** | 设备无 AC3 直通，视频轨正常但无声。已降级为静音播视频 | 建议客户素材改用 AAC |
| **模式 5/6/7 记法** | 示意图「列×行」vs App「行×列」，图示相同 | 需客户确认 |

---

## 四、真机验证记录

设备：RK3568 / Android 11 / 1024×600 @ density 160

| 项 | 结果 |
|---|---|
| 2×2 四格分割 | ✅ 精确，P1–P4 编号正确 |
| 视频播放（AAC） | ✅ 正常有声 |
| 视频播放（AC3） | ✅ 音频降级生效，画面正常、无声 |
| 图片播放 | ✅ 正常 |
| 2×2 同画面 | ✅ 四格同内容，P2–P4 静音 |
| 模式按钮 | ✅ 8 个完整显示、描述无截断 |
| 模式 3 切换 | ✅ `Requested orientation: 1 (mode=GRID_1X2_PORTRAIT)` |
| 鼠标按住拖动滚动 | ✅ 慢速拖动跟手、无抖动（真实鼠标事件注入验证） |
| 播放错误 | ✅ 界面提示 + 远控 error 事件外发 |
| HDMI 按键（su 路径） | ✅ 内核日志出现标准按键脉冲 `1→0→1` |
| 版本号显示 | ✅ 设置页顶部 `v1.1.2 (4) · RELEASE` |

**未做**：USB 插拔/复制回滚回归、空间不足场景、远控权限联调。
