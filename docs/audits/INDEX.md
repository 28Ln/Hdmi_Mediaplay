# 审计索引

> 每次审计的**代码指纹与结论状态**。扫描前先读本文件，判断哪些部分需要重扫。

---

## 记录列表

| # | 日期 | 代码指纹 | 方法 | 记录文件 | 状态 |
|---|---|---|---|---|---|
| 01 | 2026-10-15 | `27f6c0d` + 未提交工作区 | 20 个只读 agent 并发全量扫描 | `2026-10-15-audit-01.md` | ✅ 已归档（结论已并入台账） |
| 02 | 2026-10-17 | `4acc859` | 2 个验证 agent 验证批量改动 + 3 个实现 agent + 真机复核 | `2026-10-17-audit-02.md` | ⚠️ **结论部分失效**（其后 48 个提交改动面大，按「文件已变则重扫」规则不可直接沿用） |
| 03 | 2026-10-19 | `c023c4b` | 3 个只读 agent（UI/架构/文档）+ 本机 grep 复核 + **API 30 模拟器端到端实测** | `2026-10-19-audit-03.md` | ✅ 当前有效 |

---

## 当前有效的结论来源

**优先级从高到低**：

1. **`docs/audits/2026-10-19-audit-03.md`** —— 最近一次验证结论（含首次跑通的 U 盘链路）
2. **`docs/delivery-ledger.md`** —— 修复状态台账（**注意：其版本基线仍写 1.1.2/4，已过时**）
3. `docs/audit-findings.md` / `audit-checklist.html` —— **仅作过程记录**，
   编号体系不同且部分已过时，**不要据此判断状态**

---

## 增量扫描指引（下次审计用）

```bash
# 1. 当前代码指纹
git rev-parse --short HEAD

# 2. 自上次审计以来的变更
git diff --stat c023c4b..HEAD

# 3. 只重扫变更涉及的文件；未变的文件沿用 03 号记录的结论
```

> ⚠️ 旧版本此处写的是 `1cdb969`，与记录表里的 `4acc859` 不一致且已落后 49 个提交。
> 已更正为 03 号审计的基准 `c023c4b`。

### 判定

| 情况 | 处理 |
|---|---|
| 文件未变 | 沿用 `2026-10-17-audit-02.md` 的结论 |
| 文件已变 | 只重扫这些文件 |
| 新增文件 | 需扫描 |
| 标注「待决策」的项 | 不重扫，等用户决定 |
| 标注「评估后不做」的项 | 永不重扫 |

---

## 不要重复报告的问题清单

以下项**已完成完整评估**，再次「发现」即为重复劳动，应直接引用既有结论：

| 问题 | 既有结论 | 详见 |
|---|---|---|
| 设置页无法滚动 | 滚动容器被套多余 `Box(fillMaxSize)`，已删 | `docs/mouse-drag-scroll-fix.md` |
| 鼠标按住拖动不滚动 | Compose `scrollable` 只处理触摸源；已用 `MouseDragScroll`。**2026-10-19 改版**：不再无条件消费 Press（会与 `awaitFirstDown(requireUnconsumed=true)` 冲突），只在超过 touchSlop 后消费 Move | 同上 + `2026-10-19-audit-03.md` §4.1 |
| 同画面四格不同步 | 四个独立 ExoPlayer 无同步机制；`PlaybackSync` **默认关闭**（3s 检查 / 800ms 阈值，非文档曾写的 5s/1.5s）。2026-10-19 实测：四格**文件相同**（已成立），时间漂移稳定在 0.4~0.8s | `2026-10-19-audit-03.md` §三 |
| 样式无单一来源 | **评估后决定不做**（批量替换引入静默视觉回归） | `docs/delivery-ledger.md` |
| 密钥泄漏到公开历史 | 已知，待执行轮换 | `docs/key-rotation-checklist.md` |
| `media_path` 含绝对路径 | 已知，待客户确认是否脱敏 | `docs/remote-command-contract.md` |
| HDMI 节点 EACCES | ROM 侧权限问题，App 已支持 su 回退 | `docs/device-verification.md` |
| AC3 视频无声 | 设备无 AC3 直通，已降级静音播视频 | `CHANGELOG.md` |
| CI 硬编码 java.home | 已移除 | `gradle.properties` |
| `uppercase()` 受 locale 影响 | **⚠️ 原判断有误**。Kotlin 无参 `lowercase()`/`uppercase()` **自 1.5 起就用 `Locale.ROOT`**，与 Java 的 `toLowerCase()` 不同。实测 tr-TR 下 `"AVI".lowercase()` == `"avi"`（正确），只有显式传 `Locale.getDefault()` 才会得到 `"avı"`。**原有的 tr-TR 回归测试抓不到任何 bug，因为本来就等价**。保留显式 `Locale.ROOT` 仅为表达意图；**不要再来"修"这个** | `MediaTypeLocaleTest` 顶部注释 |
| `WALL_MODE_8` 是「臆造别名」 | **不是缺陷** —— 是有意保留的兼容别名，仅支持别名表，非硬件编号；契约文档已说明 | `docs/remote-command-contract.md:77` |
| `hide` 命令不生效 | **部分修复**：已改为发送前判定前台，不再谎报成功。但**本应用注册为 HOME（桌面）**，`moveTaskToBack` 对桌面应用通常无效 —— 操作本身是否生效**从未在真机验证过**。待验证项，勿当作"已修完" | `docs/audits/2026-10-17-audit-02.md` 专题 |
| U 盘拷贝永久失败 | **已修** —— 备份清理条件曾被写反成 `if (!backupDir.exists())`。**该处不能加任何 `exists` 条件** | `LocalStorageManager.kt:127-145` |
| 无效索引命令报成功 | **已修**（`play/pause/stop/set_volume/set_mute` 校验是否在当前布局内；`*_all` 按实际生效返回） | `docs/audits/2026-10-17-audit-02.md` 新发现 3 |
| `players[].index` 字段名 | 契约文档曾误写为 `player_index`，**已订正** | `docs/remote-command-contract.md` 第三节 |
| `layout_mode` 别名不全 | **已补全**为完整表格（含 `SPLIT_1X4`/`ROW_1X4`/`COLUMN_4X1`/`PIP` 等 14 项）。再次「发现别名不全」= 没看新表 | `docs/remote-command-contract.md` 二·兼容别名 |
| `set_media` 批量静默跳过 | **已知设计**：`media_path` 单个失败立即报错；`media_paths[]` 部分失败**跳过并继续**、回执仍成功。接入方须核对回执 `media_count`。**不是 bug，勿改** | 同上，五节末尾 |
| `HelpTip` 的 `lang` 参数 | **不是死参数** —— 它是语言切换的唯一重组键，**代码实际用的是 `remember(lang)`**（早先的 `key(lang)` 会重置「用户已关闭」状态，已废弃）。**删掉会造成永久缺陷** | `MainScreen.kt:294` |
| `values-en` 不生效 | **已解决**：`setApplicationLocales` 在本机不可用（API 33+ 才有 LocaleManager，且未引入 appcompat）。改为通知文案直接取 `StringResources`。仅桌面图标名仍随系统语言，属平台限制 | `docs/audits/2026-10-17-audit-02.md` 第三节 |
| 底部触发区吞点击 / 15s 竞态 | **已诊断到根因，属产品取舍**，等客户确认，**不要重复扫** | `docs/audits/2026-10-17-audit-02.md` 第四节 |
| 设备过热 85°C / 四路越播越不同步 | **热设计问题，非 App 逻辑缺陷**。关键线索：`DynamicANWBuffer failed -1010` 使硬解无法零拷贝。需 ROM 方核对解码 HAL。App 侧降分辨率等手段**未经 A/B 验证，勿盲改** | 同上，第四节专题 |
| SAME 四格不同步 | **默认关闭同步**（`ENABLED_BY_DEFAULT=false`）。根因是四个独立 ExoPlayer 各自缓冲/循环；seek 纠正必然转圈。**除非做单解码器多 Surface 的架构改动，否则别再尝试调参** | `PlaybackSync.kt` 顶部注释 |

---

## 方法论教训（避免重犯）

| 教训 | 说明 |
|---|---|
| **验证方法要与问题路径一致** | 用 `adb input swipe` 测鼠标拖动 → 走的是触摸源分支，永远显示「正常」，掩盖真实问题。必须注入真实鼠标事件（`BTN_LEFT` + `REL_Y`） |
| **批量替换必须逐处核对** | 本项目**四次**静默回归全部来自批量脚本：按钮高度被改、函数作用域错误、字号 13sp→12sp、**备份清理条件被写反**。**能编译通过 ≠ 行为未变** |
| **加条件前先问「原来为什么无条件」** | 最严重的一次回归（`LocalStorageManager` 备份清理）就是给一条原本无条件的 `deleteRecursively()` 加了 `exists` 守卫。原代码的「无条件」往往是刻意的 |
| **「已修复」必须在代码里指到具体实现** | `hide` 曾写成「✅ 已修」，但实现（`onReceive` 里注册接收器 + 主线程 latch）**在框架层面不可能成功**，且比修之前更差。写台账/契约时必须逐个核对实现 |
| **文档结论要核代码，不凭记忆** | 契约文档把 `players[].index` 写成 `player_index` → 接入方按文档写会直接取不到值 |
| **`@return` 语义要落到实现** | `play(index): Boolean` 的注释写「不在当前布局中返回 false」，实现却只查了「有没有媒体」。注释与实现不符时，**以实现为准并立刻补齐** |
| **未使用告警可能是漏接线，不是死代码** | `HelpTip(lang)` 的 `lang` 被判为 unused；实际它是语言切换时**唯一**的重组触发源，删掉会把「切语言不刷新」变成永久缺陷 |
| **修「缺陷」前先在测试里复现它** | P1-26（locale 大小写）从未被复现过就当成 bug 修了，后续还"补全"了两处。后来实测发现 Kotlin 无参 `lowercase()` 本就是 ROOT 语义 —— **修的是不存在的问题**，还附带了永远抓不到 bug 的测试。没有失败先行的测试，就等于没验证 |
| **修一个模式要 grep 全部同类调用点** | P1-26 只修了报出问题的那一处，`UsbConfig.kt` 的同类裸调用被漏掉近一个月，且无任何症状 |
| **状态文档要有唯一权威来源** | 三份审计文档给出 4/11/11 三种 P0 计数，无法判断谁对 |
| **「能证明的语义风险」≠「已观测的缺陷」** | 2026-10-19 的 U1：字节码证明 `awaitFirstDown` 要求 unconsumed，父级消费 Press 理应让子级点不动；但 API30 实测鼠标点击**正常**。仍按语义修了，但记录里必须同时写「理论依据」与「实测结果」—— 否则就是 P1-26 的翻版（修不存在的问题） |
| **模拟器不是不能测，是镜像选错了** | 之前结论「模拟器无法验证 U 盘链路」只在 `google_apis_playstore`（不可 root）下成立。换 `google_apis`（userdebug）后 `adb root` + `mount --bind` 即可完整跑通插拔与复制 |
| **回调 setter 可能有副作用** | `UsbMonitorService.onUsbConnected` 的 setter 会回放 `lastDetectedUsb`；而 `onResume` 每次都重设回调 → 弹窗重放。**给带副作用的 setter 赋值 = 触发一次事件**，任何在 onResume 里重连的地方都要去重 |
