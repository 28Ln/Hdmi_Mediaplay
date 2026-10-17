# 远程控制契约

本应用通过**广播**接收命令并回传状态。接入方（信发 CMS）需按本契约实现。

---

## 一、权限模型

| 方向 | 权限 | 说明 |
|---|---|---|
| 命令（入站） | `com.btf.rk3568_hdmi_mediaplay.permission.SEND_PLAYER_COMMAND` | `protectionLevel="signature"` |
| 状态（出站） | `com.btf.rk3568_hdmi_mediaplay.permission.RECEIVE_PLAYER_STATUS` | `protectionLevel="signature"` |

> **重要**：两个权限都是 **signature 级**，意味着只有**与本应用同一签名证书**签名的
> 应用才能发送命令或接收状态。接入方必须使用同一 keystore 签名，
> 否则发送时会抛 `SecurityException`。
>
> 这是刻意的信任模型：本应用是 kiosk/信发终端，远控能力不做公开暴露。
> 若客户 CMS 无法同签名，需另行评估（改用系统签名或自定义鉴权协议）。

---

## 二、命令（入站）

**Action**：`com.btf.player.action.COMMAND`
**Extra**：`command_json`（String，JSON）

```kotlin
Intent("com.btf.player.action.COMMAND").apply {
    putExtra("command_json", """{"action":"play_all"}""")
}
sendBroadcast(intent)
```

### 命令清单

| `action` | 参数 | 说明 |
|---|---|---|
| `play_all` | — | 全部播放 |
| `pause_all` | — | 全部暂停 |
| `stop_all` | — | 全部停止 |
| `play` | `player_index` | 指定路播放 |
| `pause` | `player_index` | 指定路暂停 |
| `stop` | `player_index` | 指定路停止 |
| `set_layout` | `layout_mode` | 切换布局（取值见下） |
| `set_media` | `player_index`, `media_path` 或 `media_paths[]` | 设置该路媒体 |
| `set_media_dir` | `player_index`, `directory_path` | 用目录内容设置该路媒体 |
| `clear` | `player_index` | 清除该路内容 |
| `clear_all` | — | 清除全部内容 |
| `set_interval` | `interval`（秒） | 图片轮播间隔，范围 1–60 |
| `set_volume` | `player_index`, `volume`（0–100） | 音量 |
| `set_mute` | `player_index`, `mute`（bool） | 静音 |
| `set_loop_mode` | `loop_mode` | `SINGLE` / `LIST` / `RANDOM`；非法值返回错误，缺省为 `LIST` |
| `show` | — | 把应用拉到前台 |
| `hide` | — | 退到后台。**需 Activity 在前台**，等待回执（超时 1 秒）；不在前台时返回错误 |
| `get_status` | — | 请求状态回传 |
| `batch` | `commands[]` | 批量执行 |

`player_index` 为 0-based（0 = P1）。

### `layout_mode` 取值

按拼接板示意图编号（详见 `docs/usb-config-schema.md`）：

| 值 | 含义 |
|---|---|
| `WALL_MODE_1` 或 `GRID_1X2` | 模式 1：横屏 1×2，2 路左右并列 |
| `WALL_MODE_2` 或 `GRID_1X3` | 模式 2：横屏 1×3，3 路左右并列 |
| `WALL_MODE_3` 或 `PORTRAIT_1X2` | 模式 3：竖屏 1×2，2 路左右并列 |
| `WALL_MODE_4` 或 `PORTRAIT_1X3` | 模式 4：竖屏 1×3，3 路左右并列 |
| `WALL_MODE_5` 或 `GRID_2X1` | 模式 5：横屏 2×1，2 路上下分屏 |
| `WALL_MODE_6` 或 `GRID_3X1` | 模式 6：横屏 3×1，3 路上下分布 |
| `WALL_MODE_7` 或 `GRID_2X2` | 模式 7：横屏 2×2，4 路方阵 |
| `SAME_2X2` / `QUAD_SAME` / `GRID_2X2_SAME` | 软件模式：2×2 同画面（四格播 P1） |
| `SINGLE` | 单屏全屏 |

> **注意**：`WALL_MODE_8` 不是硬件编号（拼接板只有 1–7），
> 它被映射到「2×2 同画面」软件模式，属历史兼容别名。**不建议新接入方使用**，
> 请用 `SAME_2X2`。

### 兼容别名

为兼容旧接入方，以下别名同样被接受：

- `LANDSCAPE_1X2` / `LANDSCAPE_1X3` / `LANDSCAPE_2X1` / `LANDSCAPE_3X1` / `LANDSCAPE_2X2`
- `SPLIT_1X3` / `SPLIT_3X1` / `SPLIT_1X4` / `SPLIT_4X1`
- `DUAL_HORIZONTAL` / `DUAL_VERTICAL` / `QUAD` / `MIRROR_2X2`

---

## 三、状态（出站）

**Action**：`com.btf.player.status.CALLBACK`
**Extra**：`status_json`（String，JSON）

所有事件都含 `type` 字段，接入方按 `type` 分发。

### 事件类型

| `type` | 触发时机 | 字段 |
|---|---|---|
| `app_started` | 应用启动 | — |
| `app_stopping` | 应用退出（`onCleared`） | — |
| `playback_changed` | 播放/暂停状态变化 | `player_index`, `state` |
| `playback_completed` | 一路播放完成 | `player_index`, `media_path` |
| `layout_changed` | 布局切换 | `layout_mode` |
| `media_loaded` | 某路媒体被设置 | `player_index`, `media_path`, `media_count` |
| `error` | 播放或命令出错 | `error_code`, `error_message` |
| `status_response` | 响应 `get_status` | `layout_mode`, `interval_seconds`, `loop_mode`, `players[]` |
| `command_success` | 命令执行成功 | `action`, `message`（可选） |

### `state` 取值

`playing` / `paused` / `stopped` / `idle` / `error` / `loading`

> **已知不一致**：`playback_changed` 事件中停止时上报 `stopped`，
> 而 `status_response` 中同一状态映射为 `idle`。接入方判停请同时兼容两者。
> （此项已记录在 `docs/delivery-ledger.md`，待统一。）

### `players[]` 结构

`status_response` 中的每项：

```json
{
  "player_index": 0,
  "media_path": "/storage/emulated/0/.../video.mp4",
  "media_count": 3,
  "current_index": 1,
  "volume": 80,
  "muted": false,
  "state": "playing"
}
```

> `media_count` 与 `current_index` 仅在媒体数 > 1 时出现。

---

## 四、错误码

| 码 | 常量 | 含义 |
|---|---|---|
| 1001 | `INVALID_PLAYER_INDEX` | 路序号越界 |
| 1002 | `INVALID_LAYOUT_MODE` | 布局模式无效 |
| 1003 | `MEDIA_NOT_FOUND` | 媒体文件不存在 |
| 1004 | `MEDIA_NOT_READABLE` | 媒体不可读 |
| 1005 | `UNSUPPORTED_FORMAT` | 格式不支持 |
| 1006 | `MEDIA_LOAD_FAILED` | 媒体加载失败 |
| 2001 | `JSON_PARSE_ERROR` | 命令 JSON 解析失败 |
| 2002 | `UNKNOWN_ACTION` | 未知的 action |

> **已知问题**：1004 / 1005 当前**不会被发出** —— 媒体校验把
> 「不存在 / 不可读 / 格式不支持」折叠为同一个 `1003`。
> 接入方目前只需处理 1003。此项已记录在 `docs/delivery-ledger.md`，待细化。

---

## 五、接入示例

```kotlin
// 发送命令
val intent = Intent("com.btf.player.action.COMMAND").apply {
    putExtra("command_json", """{"action":"set_layout","layout_mode":"WALL_MODE_7"}""")
    setPackage("com.btf.rk3568_hdmi_mediaplay")
}
sendBroadcast(intent)

// 接收状态（需持有 RECEIVE_PLAYER_STATUS 权限）
val filter = IntentFilter("com.btf.player.status.CALLBACK")
registerReceiver(receiver, filter,
    "com.btf.rk3568_hdmi_mediaplay.permission.RECEIVE_PLAYER_STATUS", null)
```

---

## 六、待补强（已记录，勿视为已实现）

以下项**已在 1.1.2 中修复**（不再列为问题）：

- ✅ `hide` 现在等待 Activity 回执，超时返回错误而非盲目报成功
- ✅ `set_loop_mode` 非法值返回错误，缺省值改为枚举中真实存在的 `LIST`
- ✅ `set_interval` 回执显示实际生效值（clamp 后）

以下项仍在，接入前请知悉：

| 项 | 状态 |
|---|---|
| 1004 / 1005 错误码 | 已声明但不会发出，见第四节 |
| `state` 取值 `stopped` vs `idle` | 两个事件中不一致，见第三节 |
| 播放器级命令对无效索引 | 仍上报成功并广播 `playing` 状态 |
| `batch` 嵌套深度 | 无限制，深层嵌套有栈溢出风险 |
| 状态载荷中的 `media_path` | 为本地绝对路径（含 U 盘挂载点），未脱敏 |

完整清单与优先级见 `docs/delivery-ledger.md`。
