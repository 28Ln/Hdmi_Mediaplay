# 审计索引

> 每次审计的**代码指纹与结论状态**。扫描前先读本文件，判断哪些部分需要重扫。

---

## 记录列表

| # | 日期 | 代码指纹 | 方法 | 记录文件 | 状态 |
|---|---|---|---|---|---|
| 01 | 2026-10-15 | `27f6c0d` + 未提交工作区 | 20 个只读 agent 并发全量扫描 | `2026-10-15-audit-01.md` | ✅ 已归档（结论已并入台账） |
| 02 | 2026-10-17 | `1cdb969` | 2 个 agent 验证批量改动 + 既有结论复核 | `2026-10-17-audit-02.md` | 🔄 进行中 |

---

## 当前有效的结论来源

**优先级从高到低**：

1. **`docs/delivery-ledger.md`** —— **修复状态的唯一权威来源**
2. **`docs/audits/2026-10-17-audit-02.md`** —— 最近一次验证结论
3. `docs/audit-findings.md` / `audit-checklist.html` —— **仅作过程记录**，
   编号体系不同且部分已过时，**不要据此判断状态**

---

## 增量扫描指引（下次审计用）

```bash
# 1. 当前代码指纹
git rev-parse --short HEAD

# 2. 自上次审计（1cdb969）以来的变更
git diff --stat 1cdb969..HEAD

# 3. 只重扫变更涉及的文件；未变的文件沿用 02 号记录的结论
```

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
| 鼠标按住拖动不滚动 | Compose `scrollable` 只处理触摸源；已用 `MouseDragScroll`（Initial pass + consume）修 | 同上 |
| 同画面四格不同步 | 四个独立 ExoPlayer 无同步机制；已用 `PlaybackSync`（P1 主时钟）修 | `docs/delivery-ledger.md` |
| 样式无单一来源 | **评估后决定不做**（批量替换引入静默视觉回归） | `docs/delivery-ledger.md` |
| 密钥泄漏到公开历史 | 已知，待执行轮换 | `docs/key-rotation-checklist.md` |
| `media_path` 含绝对路径 | 已知，待客户确认是否脱敏 | `docs/remote-command-contract.md` |
| HDMI 节点 EACCES | ROM 侧权限问题，App 已支持 su 回退 | `docs/device-verification.md` |
| AC3 视频无声 | 设备无 AC3 直通，已降级静音播视频 | `CHANGELOG.md` |
| CI 硬编码 java.home | 已移除 | `gradle.properties` |

---

## 方法论教训（避免重犯）

| 教训 | 说明 |
|---|---|
| **验证方法要与问题路径一致** | 用 `adb input swipe` 测鼠标拖动 → 走的是触摸源分支，永远显示「正常」，掩盖真实问题。必须注入真实鼠标事件（`BTN_LEFT` + `REL_Y`） |
| **批量替换必须逐处核对** | 本项目 3 次静默回归全部来自批量脚本：按钮高度被改、函数作用域错误、字号 13sp→12sp。**能编译通过 ≠ 行为未变** |
| **文档结论要核代码，不凭记忆** | 写远控契约时把 status action 写成 `action.STATUS_CALLBACK`，实际是 `status.CALLBACK` —— 这类错误会让客户对接直接失败 |
| **状态文档要有唯一权威来源** | 三份审计文档给出 4/11/11 三种 P0 计数，无法判断谁对 |
