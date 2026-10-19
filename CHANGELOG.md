# Changelog

本项目版本号唯一来源为 `gradle/libs.versions.toml`（`appVersionName` / `appVersionCode`），
设置页「关于」读取 `BuildConfig.VERSION_NAME` / `VERSION_CODE` 展示。

---

## [未发布] - 2026-10-19

### 变更

- **HDMI 拼接矩阵控制改为 I2C 直控**
  旧实现向 `/sys/devices/platform/hdmi-control/hdmi_b2`、`hdmi_b3`
  写电平脉冲模拟按键，只能「切到下一个」且无法回读。新固件已移除这两个节点，
  改为 `/sys/bus/i2c/devices/4-0030/{mode,resolution,mirror,rotate,hfusion,vfusion,mute,power}`：
  可直接设置目标值、可回读当前值、权限 0666（不再需要 root/su）。
  写入可能阻塞数百毫秒到数秒，因此全部调用移出主线程并由 `Mutex` 串行化。
  详见 `docs/hdmi-i2c-control.md`。

## [1.1.3] - 2026-10-17

本次为**二次审计**后的修复批次。其中最严重的一项（U 盘拷贝）属于会**永久生效**
的故障，建议所有已部署设备升级。

### 修复

- **U 盘拷贝可能永久失败，且可能丢失缓存（严重）**
  `LocalStorageManager` 中陈旧备份清理被误加了 `if (!backupDir.exists())` 条件
  —— 该条件使清理**永远不执行**。一旦某次拷贝在「旧目录已改名、新数据已就位」
  之后、清理之前被中断（断电 / 进程被杀），隐藏目录 `.playerN_backup` 会残留；
  下次拷贝时 `renameTo` 无法覆盖已存在的目录，返回 `backup_prepare_failed`，
  **此后每次拷贝都失败**，必须手工删除该隐藏目录才能恢复。
  现已恢复无条件清理。
- **同一处还有一处数据丢失隐患**：孤儿备份还原失败时原代码只记日志就继续，
  紧接着的清理会删掉当时**唯一一份**数据。现改为还原失败即中止拷贝，绝不删除。
- **远控 `hide` 命令完全不可用**（比 1.1.2 更差）
  1.1.2 的实现是在 `BroadcastReceiver.onReceive` 里注册回执接收器并阻塞等待——
  该上下文是 `ReceiverRestrictedContext`，`registerReceiver` 必抛
  `ReceiverCallNotAllowedException`；且命令处理本身在主线程，回执也在主线程投递，
  阻塞等待必然超时互锁。更严重的是注册排在发送之前，异常被吞掉后
  **连「退到后台」都不再执行**。现改为发送前用 `AppForegroundState` 判定前台状态。
- **播放器级命令对无效索引仍报成功**
  `_playerConfigs` 恒为 4 项且不随布局收缩，因此「索引存在」不等于「该路可见」。
  单路布局下对 P2–P4 发 `play` 会广播一个并不存在的 `playing` 状态。
  现校验索引是否在当前布局内。同时 `play_all` / `pause_all` / `stop_all` /
  `set_volume` / `set_mute` 也改为按**实际是否生效**回报。
- **布局解析在土耳其语环境下失败**（1.1.2 只修了远控侧）
  `UsbConfig` 仍有三处裸 `uppercase()` / `lowercase()`，
  tr-TR 下 `"pip"` → `"PİP"`，合法配置被静默判为非法并回退默认值。
  已全部改用 `Locale.ROOT`，并新增在 tr-TR 下运行的回归测试。
- **切换语言后帮助提示不刷新**：`StringResources` 不是 Compose State，
  语言变化不会触发重组。现已把语言作为重组键。

### 改进

- **远控契约文档订正**：`status_response` 的 `players[]` 字段实际是 **`index`**
  （文档曾误写为 `player_index`，接入方按文档写会取不到值）；
  补齐 `media_type`、`timestamp`、错误码 `5001`；订正 `1004`/`1005` 的说明。
- **`batch` 命令加嵌套深度上限**（3 层），超出返回错误；
  子命令部分失败时不再报整体成功。同时把 `catch (Exception)` 扩到 `Throwable`，
  避免深层嵌套的 `StackOverflowError` 直接崩溃主线程。
- **Android 13+ 通知可见**：补 `POST_NOTIFICATIONS` 声明与运行时请求。
  用户拒绝不会影响前台服务运行，仅降级提示。
- **无障碍**：图标按钮补 `contentDescription`，纯装饰 emoji 对读屏静音。
- **资源清理**：`strings.xml` 41 项中 37 项为死资源，已删除；
  `colors.xml` 全部为模板残留，已清空；新增 `values-en` 英文资源。
- 删除死代码：`MainViewModel.getCurrentStoragePath()`、`SingleImageView`、
  `hdmiEfullOn/Off`、`showDebugInfo` 字段（U 盘配置中的同名键仍被接受，
  不会产生「未知键」告警）。

### 已知未完成

- `values-en` **不会**让通知跟随应用内语言设置 —— 需要接入
  `AppCompatDelegate.setApplicationLocales`，本轮未做。
- 底部触发区会吞掉最后 96dp 内的点击（含播放器点击）——属交互取舍，待产品确认。

---

## [1.1.2] - 2026-10-16

### 修复

- **「2×2 同画面」四格越播越不同步**（客户反馈）：
  该模式下四格是**四个独立的 ExoPlayer** 播同一个文件，彼此没有任何同步机制，
  起始就相差数百毫秒，且循环播放时误差逐次累积，因此「播着播着就不同步」。
  现新增 `ui/player/PlaybackSync.kt`：以 P1 为主时钟，每 5 秒检查一次，
  P2–P4 偏差超过 1.5 秒才 `seekTo` 对齐（阈值取大以避免频繁 seek 造成画面跳动）。
  仅在「同画面」模式启用，其它布局各播各的无需同步。
- **HDMI 硬件按键在节点受限时仍可用**：控制节点默认属 root，普通应用写入被拒（EACCES）。
  现支持两级回退：直接写 → `su root sh -c` 提权写。
  注意本设备的 su 为 `su [WHO [COMMAND...]]` 形式，**不支持** `su -c`。
- **CI 无法在干净环境构建**：`gradle.properties` 硬编码了本机 JDK 路径
  （`C:/Program Files/Android/Android Studio/jbr`），该路径在 GitHub runner 上不存在，
  会覆盖 `setup-java` 设置的 JDK 导致构建失败。已移除该硬编码。

### 改进

- **硬件配置外置**：新增 `hardware/HardwareConfig.kt`，把 sysfs 节点路径、电平极性、
  按按时长、su 命令前缀、属性名等全部集中管理，换板型只需改这一个文件。
  此前这些值散落在 `HdmiGpioController` / `HdmiSystemProperties` 中硬编码。
- **设置页顶部常驻显示版本号与构建类型**（`v1.1.2 (4) · RELEASE`），
  便于现场排查时确认装的是哪个包；此前需滚动到「关于」区块才能看到。
- **移除主页上滑栏的「HDMI 全屏」按钮**：属低频设置项，设置页仍可操作。
- **播放器编号默认不常显**：初始显示 3 秒后自动隐藏，触摸该格再显示 3 秒，
  避免左上角 `1/2/3/4` 遮挡画面。
- **设置页右侧增加 ▲▼ 滚动按钮**：不依赖滚轮，仅需点击。

---

## [1.1.1] - 2026-10-15

### 修复

- **设置页完全无法滚动**：滚动容器被套入一层 `Box(Modifier.fillMaxSize())`
  （为放置悬浮按钮引入），在 `Column` 中该 Box 会索要整个父容器高度，
  与已占位的 `TopAppBar` 之和超出屏幕，滚动区被挤出可视范围。
  现恢复为「滚动 Column 是外层 Column 的直接子项」。
- **鼠标按住拖动无法滚动**（设备无触摸屏，仅有鼠标）：
  新增 `ui/components/MouseDragScroll.kt`，用 `PointerEventPass.Initial` + `consume()`
  接管鼠标源拖动。两个要点缺一不可——用 `Main` pass 或不消费，内建 `scrollable`
  会与本处理同时滚动，表现为慢速拖动抖动。

### 说明

- 排查过程记录见 `docs/mouse-drag-scroll-fix.md`。
  关键教训：`adb shell input swipe` 注入的是**触摸源**事件，走的是能工作的分支，
  用它验证会一直显示「能滚」而掩盖问题；**必须注入真实鼠标事件**
  （`BTN_LEFT` 按下 + `REL_Y` 移动 + 抬起）才能复现。

---

## [1.1.0] - 2026-10-14

面向 RK3568 客户设备的交付版本。本版把此前未纳入版本管理的拼接墙能力正式纳入交付范围。

### 新增

- **2×2 同画面（软件模式）**：四个格子播放 P1 同一路内容，P2–P4 自动静音，
  且仅 P1 上报播放完成/错误事件，避免同一路内容的重复远控事件。
  该模式不占用 HDMI 拼接板的硬件编号，在设置页标记为「软件模式」。
- **拼接板模式 1~7**：设置页按 HDMI 拼接板示意图编号选择，与硬件「切换模式」按键一一对应：

  | 编号 | 布局 | 画面 |
  |---|---|---|
  | 1 | 1×2 横屏 | 2 路左右并列 |
  | 2 | 1×3 横屏 | 3 路左右并列 |
  | 3 | 1×2 竖屏 | 2 路左右并列（竖屏输出） |
  | 4 | 1×3 竖屏 | 3 路左右并列（竖屏输出） |
  | 5 | 2×1 横屏 | 2 路上下分屏 |
  | 6 | 3×1 横屏 | 3 路上下分布 |
  | 7 | 2×2 横屏 | 4 路 2×2 方阵 |

- **布局模式说明文字**：每个模式按钮下显示该模式的通俗描述与布局名。
- **HDMI 全屏（efull）开关**：读写 `persist.sys.rotation.efull-1`。
- **底部栏硬件按键**：切换模式、切换分辨率（写入对应的 sysfs 控制节点）。
- **竖屏模式自动旋转**：选择模式 3/4 时请求系统竖屏，切回横屏模式时恢复，
  可通过 `btf_config.json` 的 `features.allowOrientationAutoSwitch` 关闭。

### 修复

- **布局模式描述错误**：模式 3 的描述原为「2路上下分屏（竖屏）」，
  与实际行为（左右并列）及拼接板示意图不符，已修正为「2路左右并列（竖屏）」。
  同时把模式 1/2/4/6 的描述统一为「左右/上下」这类无歧义表述，
  避免「横向/纵向」在竖屏输出下产生歧义。
- **调试包构建类型显示错误**：设置页「关于」的构建类型原先经由 `customerUiMode` 推导，
  而该值此前被硬编码为恒真，导致 Debug 包显示为 "Release"（绿色）。
  现改为直读 `BuildConfig.IS_DEBUG_BUILD`，如实显示。
- **调试包设置项被隐藏**：同一原因导致 Debug 包被强制使用 Release 功能矩阵，
  语言/播放/视频/显示/高级等设置区块不可见。现 Debug 包恢复完整设置项。
- **音频轨不受支持时不再整格黑屏**（真机实测定位）：
  MP4 带 AC3 音轨的文件，设备 ROM 声明支持 AC3 直通（`format_supported=YES`）
  但音频 HAL 实际建不出输出流（`SpdifStreamOut::open() status=-61`
  → `createTrack -38` → `Cannot create AudioTrack`），
  导致整个播放器报错、该格黑屏——**视频轨其实是好的**。
  现识别此类失败后禁用音频轨重新 prepare，改为静音播放视频。
  可用 `settings.audioFallbackSilent`（U 盘配置）关闭，关闭后维持整格报错。
- **修复错误态遮盖同路可用内容**：`MediaPlayerView` 的分支顺序原为
  `state==ERROR` 优先于 `hasImages`，导致任何一次视频失败都会
  把整格替换为报错页，**即使该路还有完好的图片可显示**。
  现调整优先级为「视频 > 图片 > 错误提示」，只在确实无内容可显示时才报错。
- **修复 Debug 版底部控制栏按钮不居中**：该分支的 `Row` 缺少 `fillMaxWidth()`，
  且用的是 `Arrangement.spacedBy`，导致按钮按内容宽度收缩、挤在左侧。
  现改为 `fillMaxWidth()` + `Arrangement.SpaceEvenly`。

### 改进

- **设置页区块顺序调整**：核心设置提到最前，且「布局模式」为其中第一项
  （现场最常调整的就是拼接模式）；「HDMI 全屏」「HDMI 控制」移到核心设置之后。
  此前 HDMI 控制占据首屏顶部，挤压了核心设置的可见性。
- **版本号统一管理**：`versionCode` / `versionName` 移入 `gradle/libs.versions.toml`，
  不再散落在 `build.gradle.kts` 中。
- **修复页面无法滚动**（1.1.2 真机实测定位，实现见下节）：
  根因有两处——① 设置页滚动容器被套入一层多余的 `Box(fillMaxSize)`，
  高度被挤出可视范围；② Compose 的 `scrollable` 只处理**触摸源**拖动，
  鼠标源按住拖动被忽略（设备无触摸屏，仅有鼠标）。
  现分别修复：容器恢复为直接子项，并新增 `mouseDragScroll` 接管鼠标拖动。
- **硬件按键失败时给出提示**：主界面底部栏的「切换模式」「切换分辨率」
  此前丢弃 `HdmiGpioController.press()` 的返回值，失败时按钮"按了没反应"。
  现失败会弹出提示。（根因见「已知限制」）
- **新增布局文案守卫测试**：`LayoutModeDescriptionTest`（6 个用例）约束
  「模式描述与切分方向一致」，此前描述文案**完全没有测试覆盖**，
  这正是模式 3 描述错误长期未被发现的原因。
- **文档校正**：`DEBUG_VS_RELEASE.md`、`TASK_LIST.md` 的功能开关默认值表
  此前与 `FeatureFlags` 实际值不符（如 `showSettingsButton` /
  `allowManualFileSelect` 在 Release 实际为 `true`，文档标为 `false`），已按实际值修正。
- **归档拼接板示意图**：原图与转正版存入 `docs/`，
  作为模式 1~7 的权威依据（此前仅存在于仓库外）。
- **新增交付包校验脚本**：`tools/verify-release-apk.sh`，
  自动校验签名（非 debug 证书）、版本号、以及本版新代码是否确实进入产物。

### 真机验证结果（RK3568 / Android 11）

已实测通过：

- 2×2 布局四格分割精确，P1–P4 编号正确
- 视频播放（AAC 音轨）正常；图片播放正常
- **2×2 同画面**：四格播放 P1 同一路内容，行为正确
- 模式按钮 8 个全部完整显示、描述无截断，选中态高亮正确
- 模式 3 切换触发 `Requested orientation: 1 (mode=GRID_1X2_PORTRAIT)`
- 播放错误正确呈现（界面提示 + 远控 error 事件外发）
- 鼠标按住拖动滚动正常（真机注入真实鼠标事件验证）
- 设置页顶部常驻显示版本号与构建类型，构建类型如实

### 已知限制

- **HDMI 硬件按键在当前设备上不可用**：
  `/sys/devices/platform/hdmi-control/hdmi_b2`、`hdmi_b3` 权限为
  `-rw-r--r-- root root`，普通应用无法写入（实测 `EACCES`）。
  SELinux 为 Permissive，故纯属文件权限问题，非代码缺陷。
  **需 ROM 侧放开节点写权限，或将 app 置于系统权限下运行。**
- **AC3 音频轨无法解码**（视频轨正常，已降级处理）：
  设备无可用 AC3 直通输出，`SpdifStreamOut::open() status=-61`。
  本版已在播放器层降级为**静音播视频**，画面不受影响；
  但该文件的**声音无法播出**。投放前请确认客户媒体使用 AAC 等设备支持的音频编码，
  或由 ROM 侧补齐 AC3 直通支持。
- 拼接板示意图中模式 5/6/7 的手写标注采用「列×行」记法，
  与 App 内显示「横屏 2×1 / 3×1 / 2×2」的「行×列」记法相反（图示相同）。
  因涉及客户可见文案，本版未改动，待确认后统一。

---

## [1.0] - 初始基线

项目初始版本，无 git tag。

- 多窗口播放（1~4 路）、视频/图片播放
- 本地缓存恢复、U 盘检测与 `btf_config.json` 配置覆盖
- 媒体复制与空间/覆盖校验
- 远控广播命令与权限受控状态广播
- CI 基线（`testDebugUnitTest` / `lintDebug` / `lintRelease` / `assembleDebug` / `assembleRelease`）
