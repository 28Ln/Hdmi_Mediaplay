# 交付台账（单一事实来源）

> 本文件是本项目修复状态的**唯一权威台账**。
> `audit-findings.md` 与 `audit-checklist.html` 是审计过程的记录（编号体系不同、已过时），
> 状态以本文件为准。

- 版本基线：**1.1.3（versionCode 5）**，见 `gradle/libs.versions.toml`
- 台账更新日期：2026-10-19（增量审计 03，见 `docs/audits/2026-10-19-audit-03.md`）
- 核实方式：逐条 grep 代码中的实际实现，**不采信文档声称**

---

## 一、已修复（真机或代码核实）

> **2026-10-19 增量审计新增**（`docs/audits/2026-10-19-audit-03.md`，API 30 模拟器端到端实测）

| 编号 | 问题 | 证据（实际实现） |
|---|---|---|
| P0-12 | 进入设置/裁剪页会销毁四个 ExoPlayer，整墙黑屏且视频从头播 | `MainActivity` 改为主界面常驻 + `ui/components/SubScreenHost.kt` 覆盖层；实测进出设置计数器连续 |
| P0-13 | 空间不足时残留上一次的覆盖确认框 | `MainViewModel.dismissPendingOverwrite()`；三个非确认分支都调用 |
| P0-14 | 从后台返回时覆盖确认框重放 | `UsbMonitorService.replayPending`（回放只一次）+ `MainActivity.callbacksInstalled`（回调只装一次） |
| P0-15 | **错误自动重试的指数退避永远退化为 5 秒**（单测绿但端到端错误） | `MainViewModel.usbErrorActive`：只由明确恢复信号清零；实测 5→10→20→40→60s |
| P0-16 | U 盘拷贝逐路提交，部分失败会留下四路**混合代次**且无法回滚 | 改为两阶段：四路全部暂存成功才统一提交；任一失败则丢弃 staging、缓存原样。实测 player3 缺内容时保留旧缓存，其余三路正常提交 |
| P1-33 | `mouseDragScroll` 无条件消费鼠标 Press，与 `awaitFirstDown(requireUnconsumed=true)` 冲突 | `MouseDragScroll.kt`：Press 不消费，Move 超过 touchSlop 才消费；鼠标点击/拖动/触摸三种输入均回归通过 |

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
| P1-11 | 「2×2 同画面」四格越播越不同步（**客户反馈**） | **2026-10-19 实质解决**：起始对齐 + **速度纠偏**（`setPlaybackSpeed` 1.05×/0.95×，不重新缓冲），实测 10s → **≤0.1s**，收敛后 60s 内 0 次干预。周期同步随之恢复默认开启。详见 `docs/audits/2026-10-19-audit-03.md` §三 |
| P1-12 | HDMI 硬件按键因节点权限不可用 | `HdmiGpioController` 两级回退：直接写 → `su root sh -c` |
| P1-13 | 硬件常量散落硬编码 | `HardwareConfig.kt` 集中管理 |
| P1-14 | 版本号从未变过（1.0/1 自建仓未改） | 统一到 `libs.versions.toml` |
| P1-15 | 无变更记录文件，但发布流程要求 | 新增 `CHANGELOG.md`（含 1.1.0/1.1.1/1.1.2） |
| P1-16 | `.gitignore` 只写具体路径，改名即静默提交密钥 | 补 `*.jks`/`*.keystore`/`*.p12` 等通配 |
| P1-17 | 开机自启不可靠（信发场景核心诉求） | `BootReceiver.kt` 补 `goAsync()` 保活 + 同时启动前台服务 |
| P1-18 | 服务内接收器 <13 隐式导出注册 | `UsbMonitorService.kt` 统一 `ContextCompat.registerReceiver` |
| P1-19 | 裁剪结果不落布局，用户看不到拼接效果 | `SplitLayout.matchingLayoutMode` + 保存后自动切布局与 STRETCH |
| P1-20 | 轨道无上限，4 路 4K 有 OOM 风险 | `PlaybackConfig.kt` + `setMaxVideoSize/Bitrate/FrameRate` |
| P1-21 | 模式按钮文字可能被硬裁 | `heightIn(min=)` + `TextOverflow.Ellipsis` |
| P1-22 | 远控 11 种事件未文档化（**对接前置条件**） | `docs/remote-command-contract.md` 重写：9 种事件、19 命令、错误码、layout_mode 全取值与别名、接入示例 |
| P1-23 | `set_loop_mode` 缺省值 `"ALL"` 是枚举中不存在的值 | 改为 `LIST`；非法值返回错误而非静默降级 |
| P1-24 | `set_interval` 回报未 clamp 的原始值 | 回执显示实际生效值 |
| P1-25 | `hide` 发完广播即报成功，未确认是否生效 | **初版修法是错的**（回执方案在框架层面不可能成功）。现改为发送前用 `AppForegroundState` 判定前台，不在前台返回 `1006`。**2026-10-19 端到端实测：前台时 `moveTaskToBack` 真的生效，应用退到后台；不在前台时返回 1006** |
| P1-26 | 布局解析受系统语言影响（tr-TR 下 `pip` 失败） | **⚠️ 原判断有误**：Kotlin 无参 `lowercase()`/`uppercase()` 自 1.5 起即为 `Locale.ROOT` 语义，与 Java `toLowerCase()` 不同。实测 `"AVI".lowercase()` 在 tr-TR 下仍得到 `"avi"`。相关"修复"与 tr-TR 回归测试**无实际作用**（抓不到 bug），保留显式 `Locale.ROOT` 仅为表达意图 |
| P1-27 | 播放器级命令对无效索引仍报成功并广播虚假 `playing` | **初版只拦了「无媒体」，没拦「不在当前布局」**。现已补 `isPlayerInCurrentLayout` 校验；`*_all` 与 `set_volume`/`set_mute` 一并按实际生效返回 |
| P1-28 | 错误码塌缩，1004/1005 永不触发 | `MediaResult` 区分「不存在/不可读/格式不支持」→ 1003/1004/1005 |

---

## 二、未修复（按影响排序）

### 影响交付

| 编号 | 问题 | 位置 | 说明 |
|---|---|---|---|
| P1-24 | 状态载荷含本地绝对路径 | — | **待客户确认**：改动会变更对外契约，已在契约文档说明；接入方应用文件名匹配 |
| P1-31 | 样式无单一来源（4 种按钮高度 / 3 套字号） | `SettingsScreen.kt` | **已评估后决定不做**，理由见下 |

> **P1-31 的评估结论（2026-10-17）**
>
> 曾尝试抽出 `SettingsDimens` 集中管理，两次都因批量替换引入视觉回归而回退：
> 1. 维护操作的按钮高度被从固定 60dp 改成随构建类型变化
> 2. 独立函数内引用 `dim` 导致编译失败（该函数作用域内无此变量）
> 3. `13.sp else 10.sp` 与 `12.sp else 10.sp` 被映射到同一字段，
>    客户版字号从 13sp 静默变成 12sp —— 能编译通过但视觉已变
>
> 该文件约 900 行、43 处 `if (customerMode)` 三元表达式，**批量替换的风险高于收益**：
> - 收益：仅代码整洁，无功能改进
> - 风险：视觉静默变化，且不会导致编译失败，难以发现
>
> 若后续要做，应改为**逐处替换 + 每步截图比对**，而非批量脚本。

### 体验与可维护性（P2）

**已修复**（2026-10-18 逐项核实代码确认，非凭记忆）：

| 项 | 核实方式 |
|---|---|
| `showDebugInfo` 僵尸标志 | 字段已删；`FEATURE_KEYS` 保留以兼容老配置 |
| `batch` 无深度限制 | `MAX_BATCH_DEPTH = 3`（`PlayerCommandHandler.kt:39`） |
| `POST_NOTIFICATIONS` | manifest 已声明（1 处） |
| 图标按钮无 `contentDescription` | `MainScreen.kt` 有 3 处 |
| `uppercase()` locale | `UsbConfig.kt` 有 5 处 `Locale.ROOT`。**注：P1-26 原判为误判**，Kotlin 无参版本本就是 ROOT 语义 |
| 死代码 `SingleImageView` / `hdmiEfullOn/Off` | 全仓 grep 无引用 |
| `strings.xml` 死资源 | 41 项 → 1 项（仅 `app_name`） |
| `Connected` 不重扫 / `Error` 无出口 | 已实现重扫 + 错误自动重试（退避 5→60s） |
| U 盘拔插不刷新 | 已实现内容签名闸门 + 拔盘回落本地 |

**仍未修复（已诊断，需排期或外部输入）**：

| 项 | 现状 | 为什么没做 |
|---|---|---|
| 底部触发区吞掉最后 96dp 点击 | `MainScreen.kt:145` `onPress` 消费按下事件 | **产品取舍**：点底部唤起控制栏是现有交互，改成只响应滑动会破坏它 |
| 15s 自动隐藏与 ✕ 竞态 | `BOTTOM_BAR_VISIBLE_MS = 15000` | 与上条一起定 |
| 主线程阻塞 I/O | `MainViewModel` 有 9 处 `withContext(Dispatchers.IO)`，主要路径已覆盖 | 剩余风险在万级文件目录扫描，需先确认目标 U 盘规模 |
| `MainViewModel` 职责过载 | **约 1100 行**（见 `wc -l`；拆分会同时触及 U 盘/播放/远控三条链路） | 重构面大，且当前真机验证未完成，不宜同时动结构 |
| release 未开代码裁剪 | `isMinifyEnabled = false`（debug 与 release 都是） | 开启有反射崩溃风险，需真机验证后再评估 |
| `gap = 2` 像素非 dp | `QuadPlayerLayout.kt:159` | 已加注释说明是刻意为之；换密度屏才需改 |

> **已从本列表移除**：「`WALL_MODE_8` 是臆造别名」——
> 它是**有意保留的兼容别名**（非硬件编号，拼接板只有 1–7），
> `remote-command-contract.md:77` 已明确说明，属已文档化的设计而非缺陷。

### 打磨（P3）

| 项 | 现状 |
|---|---|
| `gap = 2` 用像素非 dp | 仍在（`QuadPlayerLayout.kt:159`），已加注释说明刻意为之 |
| 布局与裁剪各有一套并行行列模型 | 仍在，属架构重复 |
| ~~`res/values/strings.xml` 九成死资源~~ | ✅ 已清理：41 项 → 1 项 |
| release 未开启代码裁剪 | 仍在（`isMinifyEnabled = false`），需真机验证后再评估 |
| ~~`TASK_LIST.md` 与代码漂移~~ | ✅ 已修正（配置样例补全、默认值表纠正） |
| `btf_config_sample.json` 与代码漂移 | ✅ 已核对，双向完全一致（settings 15/15、features 25/26） |

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

> ## ⚠️ 1.1.3 的真机验证**尚未执行**
>
> 下表记录的是 **1.1.2 时期**的结果。1.1.3 的改动（U 盘链路 5 轮、
> 播放同步 4 轮、`hide`、P1-19、通知语言等）**只经过 JVM 单测，
> 从未在真机验证**。设备离线约 6 小时。
>
> 设备恢复后请按 **[`docs/verification-1.1.3-checklist.md`](verification-1.1.3-checklist.md)**
> 逐项执行 —— 该清单把判定标准写死了，避免"看起来正常"就放过。
> 其中 `hide` 与 U 盘错误重试是风险最高的两项。

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
