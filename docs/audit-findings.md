> **注意**：本文件是审计**过程记录**，其 P1 编号体系与 `audit-checklist.html` 不同，
> 且「修复进度」表已过时。
> **修复状态的唯一权威台账见 [delivery-ledger.md](delivery-ledger.md)。**

# 交付级审计任务清单（1.1.2 深度优化）

审计日期：2026-10-15
审计方式：20 个只读 agent 并发审计 + 本机静态核查 + RK3568 真机 GUI 实测
设备：RK3568 / Android 11 / 1024x600 @ density 160

## 修复进度

| 级别 | 总数 | 已修复 | 待修 |
|---|---|---|---|
| P0 | 4 | 4 | 0 |
| P1 | 25 | 6 | 19 |
| P2 | 16 | 0 | 16 |
| P3 | 6 | 0 | 6 |

已修复明细见文末「修复记录」。

---

## P0 — 阻断交付（必须清零）

### P0-0 布局切换销毁并重建 ExoPlayer（核心功能，与注释相反）
- **现象**：SINGLE → GRID_2X2 → SINGLE 来回切，2/3/4 路被反复 release + new；
  切回时视频**从 00:00 重播**并有一次黑屏。
- **根因**：`QuadPlayerLayout.kt:286-289` — `PlayerCell` 在不可见时提前 `return`，
  `MediaPlayerView` 离开组合树 → `VideoPlayerView.kt:249-258` 的 `DisposableEffect.onDispose`
  执行 `stop()+release()`。文件头注释（`:22-23`）却声明"布局切换不会重建"，**注释与实现相反**。
  `key("player_$i")` 只保证位置变化不重建，保不住"可见性变化"。
- **修法**：删掉 `PlayerCell` 的 early-return，始终发射 `MediaPlayerView`，
  由外层 `FlexiblePlayerLayout` 已支持的 `Constraints.fixed(1,1)` + `place(-10,-10)` 隐藏
  （该机制已存在，见 `QuadPlayerLayout.kt:116,127`）；`isVisible` 仅用于跳过事件上报。
- **验证**：切换布局时 logcat 不应再成对出现 `Creating new ExoPlayer for:` / `Player released`；
  切回后视频进度不回零。

### P0-1 客户版下 `btf_config.json` 的 features 全部失效
- **现象**：Release 包（客户版）中，U 盘 `btf_config.json` 的 `features` 开关**完全不起作用**。
  文档 `DEBUG_VS_RELEASE.md` 明确承诺"如何为客户开启更多功能：在U盘根目录放置 btf_config.json"。
- **根因**：
  - `ui/main/MainScreen.kt:54` `val featureFlags = if (customerUiMode) FeatureFlags.releaseDefaults() else runtimeFeatureFlags`
  - `ui/settings/SettingsScreen.kt:60` `val effectiveFeatureFlags = if (customerMode) FeatureFlags.releaseDefaults() else featureFlags`
  - Release 下 `customerUiMode` 恒 true（`ui/UiMode.kt`），无条件丢弃 `runtimeFeatureFlags`，
    而后者才是 `FeatureManager` 已叠加过 U 盘覆盖的结果。
  - `FeatureManager.applyUsbConfig` 逻辑本身正确（releaseDefaults 为底 + U 盘覆盖），
    UI 层的二次覆盖既冗余又有害。
- **修法**：
  1. `MediaPlayApplication.kt`：`FeatureManager.init(!customerUiMode)`，
     让 FeatureManager 的底值也尊重 `FORCE_CUSTOMER_UI_PREVIEW` 预览开关
  2. `MainScreen.kt:54` → `val featureFlags = runtimeFeatureFlags`
  3. `SettingsScreen.kt:60` → `val effectiveFeatureFlags = featureFlags`
- **验证**：Release 包 + U 盘配置 `{"features":{"showImageSplitTool":false}}` → 裁剪按钮消失。
  无 U 盘时 Release 行为不变（仍为精简矩阵）。
- **风险**：低。Release 无 U 盘配置时结果与现状一致。

### P0-2 ERROR 状态不可逆：播放器恢复后 UI 永久卡在报错
- **现象**：某路出现一次瞬时错误后，格子永久卡在 ERROR（或图片回退），
  点播放/暂停也出不来，只能重选文件。
- **根因**：`MainViewModel.kt:490-498` `setPlayerError` 置 `state = ERROR`，
  而 `togglePlayPause`（`:358`）的 `else -> config.state` 对 ERROR 原样保留，
  `updatePlayerCurrentIndex`（`:506-515`）只改 index 不动 state。
  唯一能清 ERROR 的是重新 `setMediaFiles`（`:408`）和播放完成（`:524/:526`）。
  同时 `VideoPlayerView.kt:191-199` 在 `STATE_READY` 时把自己的 `hasError=false`，
  形成**两套互不同步的状态机**。
- **修法**：`VideoPlayerView` 在 `STATE_READY` 时通过新增回调（如 `onRecovered`）
  通知 ViewModel 把 state 复位为 PLAYING；或在 ViewModel 里把 ERROR 改为可被
  BUFFERING/READY 清除的独立字段。
- **验证**：用无效路径播放 → 报错 → 换回有效文件，`PlayerIndexBadge` 应由红转绿。

### P0-3 `exoPlayer == null` 错误路径不上报，ViewModel 一直以为在播
- **现象**：播放器初始化失败时格子显示"播放器初始化失败"，
  但 `PlayerIndexBadge` 仍是绿色 PLAYING，远控查询也回 "playing"。
- **根因**：`VideoPlayerView.kt:95-98` 只渲染 `ErrorPlaceholder` 后 `return`，
  **没有** `onError?.invoke(...)`。对比 `:66-71`（文件不存在）是有 `LaunchedEffect { onError?.invoke(...) }` 的，
  唯独这条漏了。
- **修法**：在 `:95-98` return 前补 `LaunchedEffect(mediaPath) { onError?.invoke(...) }`。
- **验证**：人为让 `createLowMemoryExoPlayer` 抛异常，badge 应变红且发出 `sendError`。

---

## P1 — 影响交付质量

### P1-0 越界/不存在的 U 盘路径可让播放器读任意文件并回显路径（安全）
- 见 P1-15（状态载荷泄漏路径）与 P2-12；signature 权限下风险可接受，但需在契约中明示信任模型。

### P1-17 拷贝并发与中断丢数据（合并 P1-2/P1-3 与存储审计结论）
- 两次拷贝可并发：`MainViewModel.kt:305-339` 无 Job/无单飞，
  `LocalStorageManager.kt:110-111` 每次进入都删 staging/backup → 互相删毁。
- 发布窗口不原子：`LocalStorageManager.kt:136-165` backup rename 与 promote 之间被杀，
  数据只在 `.playerN_backup`，且**下次拷贝会先删掉这个备份**（`:111`）。
- 源端文件减少时旧缓存永不清理（`LocalStorageManager.kt:97-100` 提前返回）；
  `ImageSplitter` 写入的 `split_<ts>.jpg` 无任何清理路径 → 缓存无限增长。
- 修法：Mutex 单飞 + 持 Job 可取消 + 启动期备份回收 + `finally` 兜底 + 发布成功后才删旧备份。

### P1-18 开机自启不可靠
- `BootReceiver.kt:24-36` 依赖"后台启动 Activity"豁免；
  `MainActivity.kt:455-488` 权限已授予分支**不调用** `startUsbMonitorService()` 直接 return。
  首次授权路径导致 U 盘监听服务从未前前台启动。
- 修法：`BootReceiver` 直接 `startForegroundService(UsbMonitorService)`；
  `onResume` 补检测并启动服务。

### P1-19 服务内接收器在 Android 13 以下以隐式导出注册
- `UsbMonitorService.kt:213-217` 有 SDK 分支；应统一 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`。

### P1-20 图片裁剪：全量解码无降采样，4K/8K 必 OOM
- `ImageSplitter.kt:68-71` `decodeStream` 全尺寸；`:196` 先 `createBitmap` 再 `:204 createScaledBitmap`
  （同时持有两张全尺寸图）；`:156` 只 catch `Exception`，**抓不到 `OutOfMemoryError`**。
- 修法：`inJustDecodeBounds` 算 `inSampleSize`；避免中间图；
  `catch (t: Throwable)` 显式处理 OOM；`finally` recycle。

### P1-21 图片裁剪：每块无条件拉伸到 1920×1080，非 16:9 源图变形
- `ImageSplitModels.kt:83-89` 只等分，`ImageSplitter.kt:199-204` 无条件 scale 到 1920×1080。
  4:3 照片跑 2×2 横向拉伸 1.33 倍。与 `ImageSplitModels.kt:58-61` 注释声称的"确保输出全屏无黑边"矛盾。

### P1-22 图片裁剪：结果不落布局，四路画面与裁剪网格不匹配
- `ImageSplitViewModel.kt:190-205` 只写文件 + `loadLocalContent()`，
  从不设 `layoutMode`/`videoScaleMode`。2×2 裁剪后若当前是 SINGLE，只有一格显示。

### P1-23 图片用 `Size.ORIGINAL` 全分辨率解码
- `ImageDisplayView.kt:181` 显式关闭 Coil 降采样，6000×4000 ≈ 96MB/格；
  FADE 过渡期新旧两份同时驻留 ×4 格。注释"让 Coil 自动优化"与实际相反。

### P1-24 索引空间混用：视频索引与媒体总索引交叉污染
- `MediaPlayerView.kt:53-64` 用 `videoItems` 索引，
  而 `MainViewModel.kt:510,523` 用 `config.mediaItems` 索引同一字段 → 混合内容时切错视频。

### P1-25 轨道选择无上限，4 路 4K 有 OOM/解码器耗尽风险
- `VideoPlayerView.kt:331` `DefaultTrackSelector(context)` 无 `setMaxVideoSize/Bitrate`；
  `:326` `setTargetBufferBytes(C.LENGTH_UNSET)` 让缓冲随分辨率放大。


### P1-1 `startForeground` 失败被吞 → 启动 ANR/被杀
- `service/UsbMonitorService.kt:107-115,117-136` 把 `startForeground` 包在 catch 里只记日志，
  随后 `return START_STICKY`。Android 11+ 会抛 `RemoteServiceException` 并杀掉进程，
  日志上看像"随机崩溃"。
- 次生路径：`createNotificationChannel()` 自己吞异常（`:162-164`），
  随后 `Notification.Builder(context, CHANNEL_ID)` 抛 `IllegalArgumentException`。
- 修法：失败时 `stopSelf(); return START_NOT_STICKY`；建通知前确保 channel 存在。

### P1-2 拔盘不取消拷贝；失败路径可能丢缓存
- `MainViewModel.kt:260-264` 拔盘只改状态，**不取消拷贝 Job**；
  `MainViewModel.kt:305-339` 无 Job 引用、无单飞保护。
- `LocalStorageManager.kt:189-193` catch 既不恢复 backup 也不删 staging →
  若异常落在 backup rename（`:140`）与 promote（`:150`）之间，
  **live 目录被删、真数据孤立在 `.playerN_backup`**。
- 修法：持 Job 并在 `onUsbDisconnected` cancel；`try/catch/finally` 兜底恢复 backup 删 staging；
  Mutex 单飞。

### P1-3 拷贝可能并发执行（广播双投递）
- `AndroidManifest.xml:76-88` 静态 receiver（exported）**与**
  `UsbMonitorService.kt:201-238` 动态注册，都指向同一套 companion 逻辑 →
  一次插盘触发两次 `copyFromUsb`，互相删对方的 staging。
- 修法：去掉 manifest 的 `<receiver>`（动态注册已足够），或加去抖。

### P1-4 ServiceConnection 泄漏
- `MainActivity.kt:287,326,370` 调 `bindService`，但 `serviceBound=true` 只在
  `onServiceConnected`（`:79`）设置。Activity 在该窗口销毁 → `onDestroy`（`:389`）跳过 unbind →
  `Activity has leaked ServiceConnection`。
- 修法：onDestroy 无条件 unbind（catch IllegalArgumentException 当 no-op）；
  `onServiceDisconnected` 也清回调。

### P1-5 客户版底部栏文案写死中文，不随语言切换
- `MainScreen.kt:403,404,407` `"切换模式"`/`"切换分辨率"`/`"全屏开"`/`"全屏关"` 为字面量。
  `SettingsScreen.kt:507,524` 已有对应英文，只是 MainScreen 没用上。
- 修法：`StringResources` 增 `switchMode`/`switchResolution`/`hdmiFullscreenOn/Off`。

### P1-6 设置页与对话框大量硬编码中文（共 171 处 UI 层中文）
- `SettingsScreen.kt` 维护操作/HDMI控制/模式编号/关于 等整块内联 `if (isEn)`；
  `OverwriteDialog.kt`、`PlayerMenuDialog.kt`、`ToastMessage.kt` 同样。
- 修法：全部下沉 `StringResources`；`scanUsb`、`hdmiEfullOn/Off` 已有键可复用。

### P1-7 模式按钮文字可能被硬裁
- `SettingsScreen.kt:660,665` 固定 `height(82.dp)` + `contentPadding` 上下各 6dp → 可用 70dp；
  选中态需 4 行（17+13+11×2+11sp）≈ 86-90dp，超出即被 clip。
  两处 Text 无 `overflow`（默认 Clip）。
- 修法：`heightIn(min=)`；补 `TextOverflow.Ellipsis`；英文描述过长需缩短。

### P1-8 U 盘配置写入非 customerModes 的模式时，拼接区无任何高亮
- `SettingsScreen.kt:649` 只遍历 `LayoutMode.customerModes`（9 项），
  但 USB 配置可写入全部 12 个枚举值（`UsbConfig.kt:205,249` 只校验 `entries`），
  `MainViewModel.kt:587-591` 无过滤直接 copy。
  写入 `SINGLE`/`PIP` 时 → 8 个按钮全不高亮，用户看不到当前模式。
- 修法：USB 应用时用 `customerModes` 白名单校验并忽略非法值；或 UI 插入只读"当前:xxx"项。

### P1-9 切换语言后滚动焦点丢失，滚轮/方向键失效
- `SettingsScreen.kt:64` `LaunchedEffect(Unit)` 在 `key(lang)`（`:73`）**之外**；
  切语言时 `key` 重建子树，新 `focusable()` 节点没人 requestFocus，
  而 `LaunchedEffect(Unit)` 不会重跑。
- 修法：改为 `LaunchedEffect(lang)`，或移入 `key(lang)` 内。

### P1-10 `set_loop_mode` 非法值静默降级；`set_interval` 回报未 clamp 的值
- `PlayerCommandHandler.kt:339-351` 缺省值写 `"ALL"`（枚举无此值）→
  `valueOf` 失败 catch 成 LIST，仍无条件报成功。
- `:309-315` 生效值 clamp 到 60，回报文案用原始 999。
- 修法：缺省改 `LIST`；非法值 `sendError` + `return false`；回报用 clamped 值。

### P1-11 播放器级命令对不存在索引仍报成功并广播虚假状态
- `PlayerCommandHandler.kt:433-439` 只校验 `0..3` 常量区间，不知实际路数；
  `:158-186` 无论是否生效都发 `playback_changed "playing"`。
  SINGLE 布局下 `play(player_index=3)` 报 playing，实际无事发生。
- 修法：`ViewModelCallback` 方法返回 Boolean，handler 据此报成败。

### P1-12 错误码塌缩：1004/1005 永不触发
- `PlayerCommandHandler.kt:475-495` `createMediaItem` 把"不存在/不可读/格式不支持"
  一律折叠为 null → 调用方只能拿到 `MEDIA_NOT_FOUND(1003)`。
  `MEDIA_NOT_READABLE(1004)`、`UNSUPPORTED_FORMAT(1005)` 全仓零引用。
- `media_paths` 部分失败仅 `Log.w` 跳过，调用方收到"成功 + 更少文件"。

### P1-13 `hide`/`show` 无投递确认即报成功
- `PlayerCommandHandler.kt:371-384` `handleHide` 只发广播就报成功；
  Activity 不在前台时无人接收，app 未退后台，但调用方收到 `command_success`。
- `:355-369` `handleShow` 同理（后台启动 Activity 受限）。

### P1-14 11 种状态事件全部未文档化
- 实发 11 种 `type`（playback_changed / playback_completed / layout_changed /
  media_loaded / error / status_response / app_started / app_stopping /
  command_success / ...），`docs/remote-command-contract.md:10-16` 全文零个事件类型，
  文档自认 "JSON schema 待补"。验收方无法写接收端。
- `state` 取值域不一致：stop 回执用 `"stopped"`，但 `get_status` 把同一 IDLE 映射为 `"idle"`。

### P1-15 状态载荷泄漏本地绝对路径
- `PlayerStatusBroadcaster.kt:57,80,114` 外发 `media_path` 原样绝对路径（含 U 盘挂载点）；
  `PlayerCommandHandler.kt:221,270` 错误消息内嵌路径。

### P1-16 样式无单一来源：同屏 4 种按钮高度、3 套字号
- `SettingsScreen.kt:327`（Material 默认 ~40dp）、`:409,418`（60dp）、
  `:446`（64/52dp）、`:660`（82/68dp）；
  三个手写 `Surface`（`:382,553,566`）绕过 `SettingsSection` 且字号不随 customerMode。
- 修法：抽 `SettingsDimens` 由 customerMode 一次性解析。

---

## P2 — 体验与可维护性

- **P2-1** `showDebugInfo` 是僵尸标志：FeatureFlags/UsbConfig/文档/示例都有，`app/src/main` **零处读取**。与真实生效的 `enableDebugLog` 语义重叠。
- **P2-2** `batch` 无深度限制，递归可致 `StackOverflowError`（catch Exception 抓不到 Error）→ 主线程崩溃；部分失败仍报成功。
- **P2-3** 主线程阻塞 I/O：`PlayerCommandReceiver.onReceive` 在主线程，`handleSetMediaDir` 同步 `scanDirectory` 大目录有 ANR 风险。
- **P2-4** `POST_NOTIFICATIONS` 未声明：Android 13+ 前台服务通知不可见（minSdk 30 / targetSdk 34）。
- **P2-5** `Connected` 状态同路径不重扫；`Error` 状态无退出路径（ViewModel 置错后服务不会纠正）。
- **P2-6** 无心跳/周期状态；`interval_seconds` 命名易被误读为"状态推送周期"（实为图片轮播间隔）。
- **P2-7** 底部触发区 Box（`MainScreen.kt:106-117`）吞掉底部 96dp 内所有点击，含播放器点击。
- **P2-8** 15s 自动隐藏与 ✕ 存在计时竞态：✕ 后重新唤出，仍按旧计时结束（表现为"刚弹出就消失"）。
- **P2-9** `FORCE_CUSTOMER_UI_PREVIEW` 语义分裂：同时被当功能开关（`:145`）、
  字号开关、以及被刻意绕开（`:773` 注释自认二者不等价）。
- **P2-10** 图标按钮无 `contentDescription`，TalkBack 读 emoji。
- **P2-11** 对话框字号不参与 customerMode 体系（写死 14sp），与页面 18sp 断裂。
- **P2-12** `WALL_MODE_8` 是臆造别名（拼接板只有 1~7）；`parseLayoutMode` 用 `uppercase()` 受 locale 影响（tr-TR 下 "pip" 解析失败）。
- **P2-13** `status_json` 无 `schema_version`；状态契约零测试。
- **P2-14** `MainViewModel` 职责过载（settings/players/USB/copy/toast），可测性差。
- **P2-15** `StringResources.currentLanguage` 是普通 `var` 非 `mutableStateOf`，与 `key(lang)` 双套刷新机制并存。
- **P2-16** 未使用 import / 死参数（`MainScreen.kt:19` FeatureManager import 未用；`BottomControlBar.onHide` 默认值 `{}` 是死代码；`HdmiEfullUiState.message` 设置了但无 UI 展示）。

---

## P3 — 打磨

- **P3-1** `gap = 2` 是像素非 dp，与 `PlayerCell` 的 `1.dp` 单位不一致（160dpi 下恰好等价，换密度屏会露缝）。
- **P3-2** `QuadPlayerLayout` 与 `ImageSplitModels` 的 `SplitLayout` 定义了两套并行的行列模型。
- **P3-3** `res/values/strings.xml` 疑似被 `StringResources` 完全遮蔽（待确认是否死资源）。
- **P3-4** release `minifyEnabled = false`，`proguard-rules.pro` 为死配置；APK ~25MB。
- **P3-5** `MainScreen.kt:132` 用 `Spacer(1.dp)` 占位实现"右侧对齐"，语义隐晦。
- **P3-6** `TASK_LIST.md` 的 `showDebugInfo` 表格、`btf_config_sample.json` 与代码存在漂移。

---

## 已确认 clean 的项（避免重复审计）

- 配置三处漂移：`AppSettings` 26 个数据字段**全部**已持久化（读写对称），无遗漏
- 状态广播权限：`sendBroadcast(intent, RECEIVE_PLAYER_STATUS)` 双参重载正确，signature 级生效
- 命令接收器权限：`exported=true` + signature 权限，第三方无法发送命令
- `org.json` 构造：11 处 `put` 全部有 null 保护，无 `put(key,null)` 崩溃路径
- 布局几何：12 个 LayoutMode 的 `calculatePlacements` 边缘数学正确（余数补给最后一格，无缝隙）
- 滚动 modifier 链顺序正确（`verticalScroll` → `scrollInputHandler` → `focusRequester` → `focusable`）
- 音频降级：真机实测生效（AC3 → 静音播视频）
- 2×2 同画面：真机实测四格同步
- 仓库敏感文件：`app.jks`/`signing.properties`/`local.properties`/release APK 均未被跟踪

---

## 真机实测记录（本轮）

| 项 | 结果 |
|---|---|
| 内存占用（4 路播放） | PSS 126,833 KB；Java Heap 48,880 KB；Native 33,772 KB — 正常 |
| 音频降级 | P2 由黑屏报错 → 正常播放视频（无声） |
| 2×2 同画面 | 四格同内容，正确 |
| 8 个模式按钮 | 完整显示，描述无截断（1024x600 实测） |
| 底部栏居中 | 已修复（`fillMaxWidth` + `SpaceEvenly`） |
| 设置页顺序 | 布局模式已为第一项 |
| HDMI 硬件按键 | 写入 `EACCES`（ROM 权限，非代码缺陷） |

---

## 修复记录（按轮次）

### 轮次 1 — P0 清零

| ID | 修复内容 | 文件 |
|---|---|---|
| P0-0 | 布局切换不再销毁重建 ExoPlayer：`PlayerCell` 去掉 early-return，隐藏格改为「暂停+静音」但保留实例 | `ui/main/QuadPlayerLayout.kt` |
| P0-1 | 客户版 U 盘 features 生效：移除 UI 层对 `releaseDefaults()` 的二次覆盖；`FeatureManager.init(!customerUiMode)` | `ui/main/MainScreen.kt`、`ui/settings/SettingsScreen.kt`、`MediaPlayApplication.kt` |
| P0-2 | ERROR 状态可恢复：新增 `onRecovered` 回调链（VideoPlayerView → MediaPlayerView → QuadPlayerLayout → MainViewModel.clearPlayerError） | 四处 |
| P0-3 | 播放器初始化失败上报：`exoPlayer == null` 分支补 `onError`（此前静默，角标仍显示 PLAYING） | `ui/player/VideoPlayerView.kt` |
| P1(安全) | 路径穿越：`usbScanFolderName` 拒绝 `..`/`. `/` 等；`getUsbMediaDir` 增加 canonicalPath 越界兜底 | `data/model/UsbConfig.kt`、`util/UsbUtils.kt` |
| P1-1 | `startForeground` 失败不再吞掉：改 `stopSelf()` + `START_NOT_STICKY`，避免启动 ANR/被杀 | `service/UsbMonitorService.kt` |
| P1-4 | ServiceConnection 泄漏：新增 `bindRequested` 标志，`onDestroy` 依据它解绑 | `MainActivity.kt` |
| P1(重复) | 抽出 `requestUsbScan()` 消除 `onScanUsb` 两处逐字重复 | `MainActivity.kt` |
| P1-5 | 客户版底部栏文案 i18n：`切换模式`/`切换分辨率`/`全屏开|关` 改走 StringResources；修复英文拼接缺空格 | `ui/main/MainScreen.kt`、`util/StringResources.kt` |
| P1-6 | 维护操作区块 i18n（英语客户此前看到全中文） | `ui/settings/SettingsScreen.kt` |
| P1 | MainScreen 语言切换不刷新：`HelpTip` 加 `lang` 参数穿透 Compose 跳过优化 | `ui/main/MainScreen.kt` |

新增测试：`usbScanFolderName` 路径穿越拒绝（2 例）、audioFallbackSilent 解析（2 例）。

### 待办（下一轮）

- P1-17 拷贝并发/中断丢数据（Mutex + 可取消 Job + 启动期备份回收）
- P1-18 开机自启不可靠（BootReceiver 用 goAsync + 直接启动服务）
- P1-20~22 图片裁剪 OOM/变形/不落布局
- P1-23 图片 `Size.ORIGINAL` 全分辨率解码
- P1-24 视频索引与媒体索引混用
- P1-25 轨道选择无上限
- P1-10~13 远控契约（错误码/返回值/文档）
- P2/P3 各项

### 需人工决策（不擅自改）

- `showPlayerIndex` 双模型 AND 语义：USB 配置无法强制开启编号。改 OR 会改变「用户关掉仍显示」的行为 → 需产品确认
- 客户版底部栏是否要显示「扫描U盘/裁剪」按钮（当前客户版无这两个，但 `releaseDefaults().showScanUsbButton=true`）→ 需产品确认
- 拼接板示意图模式 5/6/7 的「列×行」vs App「行×列」记法 → 需客户确认

### 轮次 2 — P1 关键项

| ID | 修复内容 | 文件 |
|---|---|---|
| P1-17a | 拷贝不再「进入即删 backup」：先回收上次中断遗留的备份，再清理 staging | `data/local/LocalStorageManager.kt` |
| P1-17b | 拷贝异常时还原 backup + 清理 staging（此前异常路径两者都不管，旧缓存消失、真数据孤立） | 同上 |
| P1-17c | `copyMutex` 串行化整批拷贝（广播双投递会并发拷贝、互相删目录） | 同上 |
| P1-20 | 图片解码按需降采样（`inJustDecodeBounds` 算 `inSampleSize`），并捕获 `OutOfMemoryError` | `util/ImageSplitter.kt` |
| P1-23 | 移除 `Size.ORIGINAL`（注释称"让 Coil 自动优化"，实际关闭降采样，6000x4000 ≈ 96MB/格） | `ui/player/ImageDisplayView.kt` |

新增测试：`usbScanFolderName` 路径穿越拒绝（2 例）。

### 仍需处理（下一轮）

- P1-21 图片裁剪非 16:9 源图变形（每块无条件拉伸到 1920x1080）
- P1-22 裁剪结果不落布局（四路画面与裁剪网格不匹配）
- P1-24 视频索引与媒体总索引混用
- P1-25 轨道选择无上限（4 路 4K 风险）
- P1-10~13 远控契约（`set_loop_mode` 静默降级、错误码塌缩、返回值不校验）
- P1-14/15 状态事件文档缺失、载荷泄漏绝对路径
- P1-16 样式无单一来源（4 种按钮高度、3 套字号）
- P1-18 开机自启不可靠
- P2/P3 各项

### 轮次 3 — 设置页无法滚动（根因：页面结构被破坏）

**现象**：设置页鼠标滚轮、鼠标拖动、触摸滑动全部表现为"滚不动"；
但同项目的图片裁剪页正常，系统其它 app 正常。

**根因**：`SettingsScreen` 的滚动容器被塞进了一层多余的 `Box(Modifier.fillMaxSize())`：

```
原始(HEAD，正常):  Column(fillMaxSize) → TopAppBar
                                      → Column(fillMaxSize.verticalScroll)   ✓

被改坏后:          Column(fillMaxSize) → TopAppBar
                                      → Box(fillMaxSize)                     ✗
                                          → Column(fillMaxSize.verticalScroll)
```

在 `Column` 中，子项 `fillMaxSize()` 在**只有一个可伸缩子项**时按剩余空间测量；
但一旦中间插入一层 `Box(fillMaxSize)`，该 Box 会索要整个父容器高度，
它与 TopAppBar 的高度之和超出屏幕，滚动区被挤出可视范围 —— 表现为完全无法滚动。

**引入时机**：为放置"右侧浮动滚动按钮"时套了这层 Box。

**修法**：滚动 Column 恢复为外层 Column 的**直接子项**（与 HEAD 一致）；
浮动按钮改由最外层 `Box` 承载，不再插在中间破坏测量链。

**为什么之前几轮排查方向错了**：
在事件层（滚轮 delta、指针类型、鼠标源拖动）反复试验，而真正的问题是**布局测量**。
`adb input swipe` 注入的滑动之所以"能滚"，是因为它注入的是触摸源，
会走 `scrollable` 的另一条分支 —— 而该分支恰好对错误高度更宽容，掩盖了问题。

**教训**：页面"滚不动"应先查**布局测量**（父容器高度分配），再查事件处理。
