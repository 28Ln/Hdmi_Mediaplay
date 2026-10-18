# 今晚 8 小时执行计划（2026-10-19 夜）

> 依据：本文件是 2026-10-18/19 一次的**增量审计**结论。
> 审计方法：3 个只读子代理分别扫 UI / 架构 / 文档真值 + 本机逐条 grep 复核 + API 35 模拟器实测（1024×600@160）。
> 代码指纹：`c023c4b`（审计 02 之后 **48 个提交**，`INDEX.md` 里写的增量基准 `1cdb969` 已过期 49 个提交）。

---

## 一、结论先行（三个问题的直接回答）

### Q1：需要深度优化 UI 布局吗？

**不需要"深度重构"，需要一次"定向加固"。**

两个独立审计的结论一致：UI 结构对 1024×600 / density 160 是**成立**的（全部用 dp/sp，
1dp=1px；布局用自定义 `Layout` 计算，已刻意为不重建播放器设计）。
历史上四次静默视觉回归**全部来自批量替换**，而不是来自缺少设计系统 ——
所以 `P1-31`（抽 `SettingsDimens`）仍然**不做**。

但发现 **2 个可能让产品在目标硬件上不可用**的问题，必须在交付前处理：

| # | 问题 | 证据 |
|---|---|---|
| U1 | `mouseDragScroll` 在 `PointerEventPass.Initial` 里 **consume 了鼠标 Press**，而 Compose 1.6.6 的 `detectTapAndPress` 调用 `awaitFirstDown` 时 `flags=3` → 两个参数都走默认值：**`requireUnconsumed = true`、`pass = Main`**。父级在 Initial 已消费 → 子级跳过该 down → Button/Switch/Slider/下拉 **永远等不到下一次按下** | `MouseDragScroll.kt:42-46`；`foundation-android-1.6.6` 的 `TapGestureDetectorKt$detectTapAndPress$2$1`：`iconst_0`(参数占位) / `aconst_null`(pass) / `iconst_3`(flags) + `awaitFirstDown$default` |
| U2 | 进入设置页/裁剪页时 `when(currentScreen)` 直接换掉 MainScreen → 四个 `ExoPlayer` 在 `onDispose` 被 release → **整墙黑屏、退出设置后所有视频从 0 重新开始** | `MainActivity.kt:336-385`；`VideoPlayerView.kt:251,329-345` |

> ⚠️ **U1 的静态证据已经闭合，但仍未在真实鼠标上复现**。
>
> **为什么以前的验证抓不到它** —— 这是本轮最重要的方法论收获：
> 之前所有模拟器交互验证都用 `adb shell input tap`，它注入的是 **touchscreen 源**；
> 而 `MouseDragScroll` 只接管 `PointerType.Mouse`，触摸事件直接 `continue` 掉。
> 也就是说，**用 touch 源测永远走的是没被消费的那条分支**，
> 与真机（只有鼠标）走的分支完全不同。这与项目已有的教训一模一样
> （"用 `input swipe` 测鼠标拖动 → 走触摸源，永远显示正常"），只是换了个症状。
>
> 所以今晚阶段 1 的判定方式必须是：真实 `BTN_LEFT` 按下/抬起且**无位移**，
> 或直接接受静态证据并在修复后由真机回归。**不要先改代码再验证。**

### Q2：有没有 skills 能深度优化？

有，但要挑对，且**不要**用批量替换类操作：

| 可用 | 用途 | 备注 |
|---|---|---|
| `/design` | 架构/交互方案设计（不是视觉设计） | 适合 U2 的"保留播放器的导航"方案 |
| `/analyze` | 多维度代码分析 | 已在本次用子代理覆盖 |
| `/review`、`/code-review` | 改动后复审 | 今晚每个批次结束跑一次 |
| `/simplify` | 只做等价简化 | 对 `SettingsScreen` 慎用（P1-31 已否决） |
| `/test` | 补测试 | 用于 §三 T4 的纯函数测试 |
| `/troubleshoot` | 定位 ANR / 卡死 | 用于 U1 验证与模拟器问题 |

**不推荐**：`/improve` 的批量重构路径 —— 本项目 4 次静默回归均源于此。

### Q3：文档真值台账 + 还有哪些重要模块？

台账本身已经**漂移**（详见 §五）。真正还没做完的模块按交付影响排序：

1. **远控契约的可靠性语义**（无请求 ID、`batch` 部分成功、`hide` 有效性从未证实）
2. **USB/配置链路的"配置残留"**（拔盘后 `AppSettings` 不回滚，客户配置永久粘住）
3. **拷贝事务的跨播放器原子性**（逐格提交，部分失败留下混合代次）
4. **开机/kiosk 自愈**（无看门狗，`BootReceiver` 失败只记日志）
5. **现场可用性文档**（安装/升级/回滚/排障/FAQ 全部缺失）
6. **1.1.3 真机验收**（至今零证据）

---

## 二、今晚的硬约束（先读，避免白干）

| 约束 | 说明 |
|---|---|
| **真机离线** | 只剩 API 35 模拟器。凡涉及 U 盘、HDMI 节点、硬解、root 广播的项**今晚不可能验证**，只能写"待真机" |
| **模拟器不能证明性能/ANR** | 本次实测冷启动 3 次出现 `ANR ... failed to complete startup`（swiftshader + 4 路软解，CPU 52%、iowait 38%）。这是模拟器环境，**不能据此判断真机行为**，也不要"优化"它 |
| **`input tap` 打不中 Compose 按钮** | 本应用在 API 35 上 `input tap`（touchscreen 源）不触发 `onClick`。**可用的是**：`input keyevent KEYCODE_TAB` + `KEYCODE_DPAD_CENTER`（已验证能进设置页并选中模式） |
| **`wm size` 会重启 SystemUI** | 改尺寸后 `topResumedActivity` 会变成系统 Settings；必须 `am force-stop` 后重新 `am start` 并等 10s+ |
| **禁止批量替换** | 每个改动单独 Edit；每批结束截图比对 |

---

## 三、8 小时排程

总预算 480 min，含 40 min 机动。**任一 Gate 未过，不进入下一阶段。**

---

### 阶段 0（00:00–00:30，30 min）建立基线

- [ ] `git status` 必须干净；`git rev-parse --short HEAD` 记录指纹
- [ ] 跑完整基线（后台跑，约 6–10 min）：

```powershell
./gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain --no-daemon
```

- [ ] 记录：测试数（应为 **77**；文档写的 47 已过时）、lint error/warning 数、APK 时间戳
- [ ] 产物留档：`app/build/reports/` 复制到 `docs/evidence/2026-10-19/`

**Gate 0**：基线全绿。若有失败，先记录失败项，今晚只修基线失败。

---

### 阶段 1（00:30–01:30，60 min）U1：鼠标点击是否被吞 —— **只验证，不改**

这是今晚**最高优先级**：如果成立，客户设备（无触摸屏、只有鼠标/空鼠）的设置页几乎不可操作。

1. 在真机不存在的情况下，用 `sendevent` 向模拟器注入**真实鼠标**事件
   （模拟器 `getevent -pl` 没有鼠标设备 → 若无法注入，改用下面的"代码路径判定法"）：

```bash
# 判定法（离线可做，30 min）：
# 读 foundation 1.6.6 的 detectTapAndPress / detectTapGestures 字节码，确认
# (a) awaitFirstDown 的 requireUnconsumed 参数
# (b) awaitFirstDown 在哪个 PointerEventPass 上等 down
# 结论已部分取得：iconst_3 → requireUnconsumed=true；pass 为 Main
```

2. **关键判定**：`mouseDragScroll` 在 `Initial` pass 上 `change.consume()` Press。
   按 Compose 事件派发顺序：`Initial`(外→内) → `Main`(内→外) → `Final`(外→内)。
   **父级在 Initial 消费后，子级在 Main 里 `awaitFirstDown(requireUnconsumed=true)` 会跳过该 down。**
   → 推断：设置页内所有 Button/Switch/Slider/下拉**对鼠标点击完全失效**。

3. 交叉证据（真机 / 模拟器都要找）：
   - 用键盘 Tab 能进设置页并选中模式 → 说明 Compose 本身正常
   - 之前真机只验证过"鼠标按住拖动滚动"，**从未验证过"鼠标单击设置项"**

**Gate 1**：给出三选一结论，写入 `docs/audits/2026-10-19-audit-03.md` 草稿：
- `CONFIRMED`（代码 + 事件语义足以定案）→ 进入阶段 2
- `PLAUSIBLE`（需真机）→ 阶段 2 仍做，但标记"待真机确认"
- `REFUTED` → 跳到阶段 2 的 U2

> **建议直接按 CONFIRMED 处理**：`awaitFirstDown` 的两个参数默认值已在字节码里确认，
> 而 `Initial` pass 的消费顺序是 Compose 文档化的语义。等真机只会推迟修复。

---

### 阶段 2（01:30–03:00，90 min）修复 U1 + U2

#### U1 修复（约 30 min）

目标：**只在达到滑动阈值后才消费事件**，Press 一律不消费（这样 `detectTapAndPress`
的 `awaitFirstDown(requireUnconsumed=true)` 才能拿到 down，
同时 `waitForUpOrCancellation` 也不会因为被消费而判定为取消）。

```kotlin
// MouseDragScroll.kt 伪代码，实现时逐行改，不要整块替换
val slop = viewConfiguration.touchSlop      // 8dp 左右
Press  -> { dragging = true; start = pos; lastY = pos.y; /* 不 consume */ }
Move   -> if (dragging) {
             val dy = pos.y - lastY
             if (!pastSlop && abs(pos.y - start.y) > slop) pastSlop = true
             if (pastSlop) { state.dispatchRawDelta(-dy); change.consume() }
             lastY = pos.y
          }
Release/Exit -> dragging = false; pastSlop = false
```

**必须同时验证**（模拟器可做 keyboard 路径 + 真机鼠标）：
- 鼠标单击设置项 → 生效
- 鼠标按住拖动 → 仍滚动、不抖动（回归）
- 触摸拖动 → 仍滚动（另一条路径不受影响）

#### U2 修复（约 45 min）

目标：进入设置/裁剪页时**不销毁播放器**。

两个可选实现，**选 A，风险最小**：

| 方案 | 做法 | 风险 |
|---|---|---|
| **A（选它）** | MainScreen 保持组合，设置/裁剪用 `Box` 覆盖在上层（`zIndex`），隐藏时 `Modifier.alpha(0f)` + 禁止命中，或以 `AnimatedVisibility` 包住而不销毁 | 低；播放继续，返回时进度仍在 |
| B | 把 4 个播放器提升到 Activity 级 `movableContentOf` | 中；改动面大，容易碰 `key("player_$i")` 稳定性 |

> ⚠️ 注意：若设置页覆盖在播放器上，**播放器声音会继续**。需确认产品语义：
> 建议进入设置时 `pauseAll()`，返回主界面时恢复原播放状态（记录进入前的 playing 集合）。

**Gate 2**：
- [ ] 进入设置 → 主界面仍在播放（或按预期暂停）→ 返回后视频**从原位置继续**
- [ ] 截图前后对比（`screencap`），确认布局未变
- [ ] `testDebugUnitTest` 仍全绿

---

### 阶段 3（03:00–04:00，60 min）配置与状态真值（纯 JVM，可离线）

这 3 项都是"客户会真的遇到、但排查极贵"的问题，且都能用纯函数 + 单测完成。

#### T3-1 拔盘后 U 盘配置不回滚（30 min）

**问题**：`FeatureManager` 在拔盘时被重置（`UsbMonitorService.kt:383,430,646`），
但 `MainViewModel.applyUsbConfigSettings()`（`:710-831`）**没有逆操作** →
U 盘设过 `layoutMode=GRID_1X2` / `defaultVolume=0` / 自定义 `usbScanFolderName` 后拔盘重启，
设备**永久保持客户配置**，且界面上无法区分"这个值是 U 盘设的还是运维设的"。

**做法**：
1. 新增 `UsbConfigOverrideStore`：持久化"本次由 U 盘写入的键 + 写入前的原值"
2. 拔盘/下次启动无 U 盘时调用 `revert()`（只回滚未被运维改过的键）
3. 抽成纯函数 `mergeOverrides(base, overrides)` / `revertOverrides(current, snapshot)`，写单测

**Gate**：单测覆盖"拔盘回滚""运维改过的键不回滚""无快照时不动作"。

#### T3-2 远控 `set_media` / `batch` 的请求关联（20 min）

**问题**：`command_success` 不含请求 ID，CMS 重试时无法区分是哪次。
**做法**：命令 JSON 可选 `request_id`，回执原样回显；文档补"幂等性"表。
（`batch` 已是 `successCount>0` 语义，不要改，只文档化。）

#### T3-3 `get_status` 增加 USB/错误维度（10 min）

**问题**：`status_response` 只有 layout/interval/loop/players，CMS 无法区分
"没有媒体因为没插 U 盘"和"没有媒体因为拷贝失败"。这是现场支持最常见的问题。
**做法**：加 `usb_state` / `usb_path_present` / `last_error` 三个字段（只增不改）。

**Gate 3**：`./gradlew.bat testDebugUnitTest` 全绿，新增测试 ≥ 8 个。

---

### 阶段 4（04:00–05:00，60 min）文档真值收口

台账是"唯一权威来源"，但当前**多处与代码矛盾**。今晚只做事实订正，不写新叙事。

| 文件 | 必改 | 依据 |
|---|---|---|
| `docs/delivery-ledger.md:7` | 版本基线 1.1.2/4 → **1.1.3/5** | `libs.versions.toml:2-3` |
| 同上 `:38` | PlaybackSync "5 秒/1.5 秒 + 已修复" → **默认关闭；3s / 800ms** | `PlaybackSync.kt:50,59,68` |
| 同上 `:105` | `MainViewModel` 1074 行 → **1088** | `wc -l` |
| 同上 P1-24 重复编号 | 两条不同问题共用编号 → 拆分 | `:51` / `:65` |
| `docs/verification-matrix.md:34` | "47 个 @Test" → **77**（15 个测试类） | 本轮实测 |
| `docs/audits/INDEX.md:33` | 增量基准 `1cdb969` → **`c023c4b`**（并说明 02 号结论已不可直接沿用） | 48 个提交 |
| 同上 `:74` | HelpTip "已用 `key(lang)`" → **`remember(lang)`** | `MainScreen.kt:294` |
| `docs/remote-command-contract.md:55` | `hide` "等待回执（1 秒）" → **发送前判定前台，无回执** | `PlayerCommandHandler.kt:453-485` |
| 同上 `:227` | "已在 1.1.2 修复" → **1.1.3** | `CHANGELOG.md:24-29` |
| 同上 PIP 描述 | "2×2 网格" → **左大 + 右三小** | `QuadPlayerLayout.kt:245-255` |
| `docs/usb-config-schema.md:109` | "新增键无需升版本" vs 解析器会报 `Unknown key` → 统一口径 | `UsbConfig.kt:213-227` |
| `btf_config_sample.json:35` | 仍含 `showDebugInfo`，而 `TASK_LIST.md:99` 说"已不再出现在示例中" → 二选一 | 实测 |
| `docs/optimization-priority.md` | 整份已被取代 → 顶部加 `> ⚠️ 已过时` 横幅并指向台账 | 文档交叉 |
| `docs/risk-register.md` | 全绿但未含 1.1.3 的 U 盘永久失败 P0、85°C、`hide` 无效 → 补 4 行 | 台账 §三 |
| `docs/emulator-verification.md` | 同文件内 release/debug 包、能否验证 SAME 的结论互相矛盾 → 加"最终结论"段 | 文档交叉 |

**Gate 4**：`git diff` 只含文档；用 `grep` 抽查每条改动的依据行仍然存在。

---

### 阶段 5（05:00–06:00，60 min）现场交付文档（缺得最狠的一块）

当前仓库**没有任何客户可用的安装/升级/回滚/排障文档**，这是"交付型项目"的硬缺口。

写 3 份，短而准，每份 ≤ 2 页：

1. **`docs/deployment-guide.md`（安装/升级/回滚）**
   - 安装：APK + `appops set ... MANAGE_EXTERNAL_STORAGE allow`（现在这句只存在于验证清单里）
   - HOME 桌面应用如何设为默认 Launcher（现在是隐含假设，没有任何说明）
   - 升级：versionCode 递增才能覆盖安装（5→5 会失败）
   - 回滚：**降级必须卸载 → 会丢缓存与设置**，必须写清
2. **`docs/troubleshooting.md`（症状 → 日志 → 处置）**
   至少覆盖（日志锚点已核实存在于代码）：
   - `backup_prepare_failed` → 隐藏目录 `.playerN_backup` 残留 → 手工删除
   - `USB check result ... hasMedia=false` → 目录名与 `usbScanFolderName` 不符
   - `hide 未生效` → 前台判定失败
   - `存储空间不足` + `自动重试第 N 次` → 容量/退避行为
   - `AudioSink$InitializationException`（AC3）→ 换 AAC
   - `EACCES` on `/sys/.../hdmi_b*` → ROM 侧权限
3. **`docs/acceptance-evidence-1.1.3.md`**：把空模板实例化为"待填"，列 1.1.3 必须留的证据项

**Gate 5**：三份文档里每条命令都能在本机 `--help`/`adb shell` 下跑通（不能写没验证过的命令）。

---

### 阶段 6（06:00–07:00，60 min）真机验收包（设备恢复后 30 分钟能跑完）

把 `verification-1.1.3-checklist.md` 升级为**可执行脚本 + 判定表**：

- `tools/verify-device.ps1`：一键采集（版本、pid、前台 Activity、USB 状态、四格状态、日志计数）
- 输出 JSON + 截图，直接填进 `acceptance-evidence-1.1.3.md`
- **不做**自动化远控（需签名权限，脚本会失败并误导）

同时把本轮新增的两项加入清单：
- 鼠标**单击**设置项（U1 回归）
- 进设置 → 返回后视频**继续播放**（U2 回归）

**Gate 6**：脚本在模拟器上跑通（U 盘/远控段输出 `SKIPPED` 而不是 `FAIL`）。

---

### 阶段 7（07:00–07:40，40 min）复审与收尾

- [ ] `/code-review` 或 `/review` 扫本轮 diff（重点：U1/U2 与配置回滚）
- [ ] 完整基线再跑一次（同阶段 0 命令）
- [ ] 更新 `docs/audits/INDEX.md`：新增 03 号记录（指纹、方法、结论、失效条件）
- [ ] 更新 `docs/delivery-ledger.md`：新增本轮修复条目 + **明确标注哪些项"仅 JVM 验证，待真机"**
- [ ] `git status` 检查无密钥/无 APK/无临时文件；分 4–6 个逻辑提交

**机动 40 min**：用于 U2 若超时（它是唯一可能蔓延到 2 小时的项）。

---

## 四、今晚不要做的事（明确排除）

| 排除项 | 理由 |
|---|---|
| 抽 `SettingsDimens` / 统一字号（P1-31） | 已评估否决；批量替换已造成 4 次静默回归 |
| 开 R8 / minify | 无真机验证，反射风险 |
| 重构 `MainViewModel`（1088 行） | 与 U1/U2 同晚动结构风险过高 |
| 调整 `PlaybackSync` 参数 | 默认关闭；根因是四个独立 ExoPlayer，调参无用 |
| 为模拟器的 ANR/软解慢做优化 | 环境噪声，不是产品缺陷 |
| 改 `gap = 2` 像素 / 底部 96dp 触发区 | 均有明确设计注释，需产品确认 |
| 升级 Compose / AGP / Media3 | 交付前不动依赖版本 |
| 处理密钥轮换 | 需要决策，不是今晚能做的 |

---

## 五、审计发现汇总（按严重度，证据可核）

### P0（交付阻塞级；今晚阶段 1/2 处理前两项）

| ID | 问题 | 位置 | 今晚 |
|---|---|---|---|
| U1 | 鼠标 Press 被 `mouseDragScroll` 消费 → 设置页控件点击可能全部失效 | `MouseDragScroll.kt:42-46` | ✅ 阶段 1/2 |
| U2 | 进设置销毁四个 ExoPlayer → 整墙黑屏 + 视频从头播 | `MainActivity.kt:336-385`、`VideoPlayerView.kt:251,329` | ✅ 阶段 2 |
| A1 | `LocalStorageManager` 逐格提交，部分失败留下**混合代次**（1-2 新 / 3-4 旧）且旧数据已删 | `LocalStorageManager.kt:271-298` | ❌ 需真机 + L 工作量 |
| A2 | `onResume` 重放 USB 回调 → 每次从设置返回都可能**重弹覆盖确认框**、并把 `Error` 覆盖成 `Disconnected` | `UsbMonitorService.kt:202-213`、`MainActivity.kt:118-150` | ❌ 明晚 |
| A3 | Release 构建签名缺失时**静默产出未签名 APK**，CI 仍绿 | `app/build.gradle.kts:25-36,80-82`、`.github/workflows/android.yml:27-36` | ❌ 明晚（S） |

### P1（客户可见 / 现场高成本）

| ID | 问题 | 位置 |
|---|---|---|
| C1 | U 盘配置写入 `AppSettings` 后**无回滚**，拔盘永久粘住 | `MainViewModel.kt:710-831` |
| C2 | 状态载荷 `current_index`/`media_count` 只在 >1 时发送；`stopped` vs `idle` 词汇分裂 | `PlayerStatusBroadcaster.kt:110-121` |
| C3 | 远控无 `request_id`，重试无法关联；`batch` 部分成功不报 | `PlayerCommandHandler.kt:85-147,561` |
| C4 | `usbDetectionEnabled`、`maxCacheSizeMB`、`AudioOutput` 三个设置**完全不生效**（UI 可改、无任何代码读取） | `AppSettings.kt:148,155,171`；`AudioOutputManager` 零调用点 |
| C5 | `clearAllMedia` 不先停播；`set_media` 后立刻 `play` 可能先返回成功再 prepare | `MainViewModel.kt:989-1007` |
| C6 | 无进程死亡恢复：`currentIndex`/音量/SAF URI 全丢，`sendAppStarted` 无法与冷启动区分 | `MainViewModel.kt:86-89,152` |
| D1 | 英文模式仍有大量中文（裁剪页整页、播放器错误串、`MainViewModel` 14 处 toast） | `ImageSplitScreen.kt` 全文件、`VideoPlayerView.kt:484-490` |
| D2 | 覆盖确认框提示"可在设置中关闭"，但 Release **隐藏了整个 USB 设置分区** | `StringResources.kt:83`、`FeatureFlags.kt:191` |
| D3 | 冷启动白屏闪（Light 主题、无 `windowBackground`）；下拉菜单白底 | `res/values/themes.xml:4`、`ui/theme/Theme.kt:20-51` |
| D4 | 客户底部栏 ~164dp（27% 屏高），无关闭按钮（✕ 仅 debug 分支） | `MainScreen.kt:210,240,494-507` |
| D5 | 滑块每帧写整份设置到 DataStore（4 路解码同时在跑） | `SettingsScreen.kt:182,221,317` |
| D6 | 拷贝进度每 8KB 回调一次（1GB ≈ 13 万次 StateFlow 更新） | `FileUtils.kt:157-164` |
| D7 | 8–11sp 字号在电视观看距离不可读 | `SettingsScreen.kt:739,747`、`ImageSplitScreen.kt:383` |
| D8 | 悬浮 ▲▼ 按钮遮住右对齐的 Switch/Slider，点不到 | `SettingsScreen.kt:371-376` |

### P2（重要但可排后）

- `UsbBroadcastReceiver` exported=true 且无权限（`MEDIA_MOUNTED` 广播可伪造）
- `USB_MOUNT_PATHS` 含 `/storage`，SD 卡会被当作 U 盘
- `contentSignature` 对同尺寸同 mtime 的替换无法识别
- `set_media_dir` 无根目录限制、无文件数上限
- `BootReceiver` 启动失败只记日志、不重试（kiosk 黑屏风险）
- 无看门狗：主线程卡死时服务仍在报 `Connected`
- `SettingsRepository` 写入失败被静默吞掉
- `clearCaches()` 直接 `deleteRecursively()` 掉 Coil **正在使用**的 `cacheDir/image_cache`
  （`MediaPlayApplication.kt:126-132`；`newImageLoader` 把同一目录配成 `DiskCache`，
  journal 未关闭 → 之后图片可能全部加载失败直到进程重启）
- `requestPermissions()` 只在 `onCreate` 调用（`MainActivity.kt:239`）→ 授权返回**不重新检查** →
  前台服务要等下次冷启动才起
- 崩溃日志只写不读：`filesDir/crash_logs` 无任何读取/导出路径

### 文档

见阶段 4 表格（14 处事实性矛盾，均已逐条核实到代码行）。

### 测试缺口（最高 ROI）

- `PlayerCommandHandler.handleCommand` **零测试**（只有 7 个 helper/alias 用例）
- `MainViewModel` 只测了 `decideUsbCopyDecision` / `usbRetryDelayMs`；`getStatus`、`isPlayerInCurrentLayout`、`applyUsbConfigSettings`、`onUsbConnected` 协调逻辑**零覆盖**
- `UsbMonitorService` 的互斥/代数守卫/回调重放**零覆盖**
- `SettingsRepository` **零覆盖**

---

## 六、本轮的实测记录（可复现）

```bash
# 1024×600 @160 目标分辨率
adb shell wm size 1024x600 && adb shell wm density 160
adb shell am force-stop com.btf.rk3568_hdmi_mediaplay
adb shell am start -n com.btf.rk3568_hdmi_mediaplay/.MainActivity

# 真正可用于驱动 Compose 的输入方式（本应用在 API 35 上）
adb shell input keyevent KEYCODE_TAB          # ×N，聚焦顺序稳定
adb shell input keyevent KEYCODE_DPAD_CENTER  # 激活焦点控件
# input tap / input swipe（touchscreen 源）→ 不触发 onClick，不要用来判定交互
```

实测结果：
- 客户版（release）主界面、底部栏、设置页渲染正确；底部栏 5 个按钮文案无截断
- 设置页在竖屏 600×1024 下可读、模式卡完整；**切模式后方向按预期变化**（模式 4 → `Requested orientation: 1`）
- 冷启动在纯软件渲染 + 4 路解码下出现 3 次 `ANR ... failed to complete startup`（CPU 52%、iowait 38%）—— 模拟器环境噪声
- 空媒体状态提示正常（"长按选择媒体文件 / 或插入U盘自动加载"）

---

## 七、明日（真机恢复后）优先级

1. **A1 混合代次拷贝**（唯一可能丢数据的 P0）
2. **A2 `onResume` 重放**（每次进设置都重弹框）
3. U1/U2 的真机回归确认
4. A3 未签名 APK 的 CI 断言（10 分钟）
5. 1.1.3 全量验收（按阶段 6 的脚本）
