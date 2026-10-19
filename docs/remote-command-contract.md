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
    setPackage("com.btf.rk3568_hdmi_mediaplay")
}
// ⚠️ 不要再传第二个参数（receiverPermission）
sendBroadcast(intent)
```

> **常见错误（2026-10-19 实测踩到）**
>
> `sendBroadcast(intent, permission)` 的 `permission` 语义是「**接收方**必须持有该权限」。
> 若把 `SEND_PLAYER_COMMAND` 传进去，系统会去找**接收应用自己**是否持有这个
> 由它自己声明的权限 —— 通常不持有 → 投递被拒：
> `Permission Denial … due to sender <你的包名>`。
>
> Receiver 的 `android:permission` 已经由系统在投递时校验**发送方**证书，
> 接入方只需：① manifest 里 `uses-permission` 申请该权限；② 用同一证书签名；③ 正常 `sendBroadcast`。

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
| `hide` | — | 退到后台。**发送前**同步判定 Activity 是否在前台（`AppForegroundState`），**不回执、不等待**；不在前台时返回 `1006` |
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

以下别名同样被接受（**大小写不敏感**，内部统一 `uppercase(Locale.ROOT)` 后匹配）。

| 别名 | 实际布局 | 说明 |
|---|---|---|
| `LANDSCAPE_1X2` | `GRID_1X2` | 旧版命名 |
| `LANDSCAPE_1X3` | `GRID_1X3` | 旧版命名 |
| `LANDSCAPE_2X1` | `GRID_2X1` | 旧版命名 |
| `LANDSCAPE_3X1` | `GRID_3X1` | 旧版命名 |
| `LANDSCAPE_2X2` | `GRID_2X2` | 旧版命名 |
| `SPLIT_1X3` | `GRID_1X3` | 与图片裁剪的布局名对齐 |
| `SPLIT_3X1` | `GRID_3X1` | 同上 |
| `SPLIT_1X4` / `ROW_1X4` | `ROW_1X4` | **1 行 × 4 列**（4 路左右并列） |
| `SPLIT_4X1` / `COLUMN_4X1` | `COLUMN_4X1` | **4 行 × 1 列**（4 路上下分布） |
| `PIP` | `PIP` | 画中画布局（2×2 网格排布方式，与 `GRID_2X2` 不同） |
| `DUAL_HORIZONTAL` | `GRID_1X2` | 同上 |
| `DUAL_VERTICAL` | `GRID_2X1` | 同上 |
| `QUAD` | `GRID_2X2` | 同上 |
| `MIRROR_2X2` | `GRID_2X2_SAME` | 同画面 |

> 除上表外，**枚举名本身也直接可用**（`SINGLE`、`GRID_1X2`、`GRID_1X2_PORTRAIT`、
> `GRID_1X3_PORTRAIT`、`GRID_2X1`、`GRID_3X1`、`GRID_2X2`、`GRID_2X2_SAME`、
> `ROW_1X4`、`COLUMN_4X1`、`PIP`）——解析器在别名表未命中时会回退到
> `LayoutMode.valueOf(...)`。
>
> **无法识别的值**会返回错误码 `1002`（`INVALID_LAYOUT_MODE`），不会静默忽略。

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
  "index": 0,
  "state": "playing",
  "media_type": "video",
  "media_path": "/storage/emulated/0/.../video.mp4",
  "media_count": 3,
  "current_index": 1,
  "volume": 80,
  "muted": false
}
```

> **注意字段名差异**（易错点）：
> `players[]` 内用的是 **`index`**，而 `playback_changed` / `media_loaded`
> 等**事件**里用的是 **`player_index`**。二者不是同一个字段名。

> `media_type` 仅在当前路已加载媒体时出现；
> `media_count` 与 `current_index` 仅在媒体数 > 1 时出现。

> 顶层另有 **`timestamp`**（`System.currentTimeMillis()`），**所有**事件都会带，
> 用于接入方判断事件新鲜度。事件中不再重复出现。

---

## 四、错误码

| 码 | 常量 | 含义 |
|---|---|---|
| 1001 | `INVALID_PLAYER_INDEX` | 路序号越界，或该路**不在当前布局中** |
| 1002 | `INVALID_LAYOUT_MODE` | 布局模式无效 |
| 1003 | `MEDIA_NOT_FOUND` | 媒体文件不存在 |
| 1004 | `MEDIA_NOT_READABLE` | 媒体不可读 |
| 1005 | `UNSUPPORTED_FORMAT` | 格式不支持 |
| 1006 | `MEDIA_LOAD_FAILED` | 媒体加载失败；亦用于 `hide` 等命令**未实际生效** |
| 2001 | `JSON_PARSE_ERROR` | 命令 JSON 解析失败 |
| 2002 | `UNKNOWN_ACTION` | 未知的 action，或参数值非法（如 `loop_mode` 取值不在枚举中） |
| 5001 | `PLAYBACK_ERROR_CODE` | **播放期**错误（由播放器上报，非命令回执） |

> 说明：
> - `1004` / `1005` 由 `set_media` 的实际失败原因区分（不存在 / 不可读 / 格式不支持），
>   **会**被发出。
> - `5001` 不是命令回执，而是播放过程中出错时主动广播的错误事件，接入方需一并处理。

---

## 五、接入示例

```kotlin
// 发送命令
val intent = Intent("com.btf.player.action.COMMAND").apply {
    putExtra("command_json", """{"action":"set_layout","layout_mode":"WALL_MODE_7"}""")
    setPackage("com.btf.rk3568_hdmi_mediaplay")
}
sendBroadcast(intent)

// 接收状态（需持有 RECEIVE_PLAYER_STATUS 权限——在 manifest 中 uses-permission 申请）
val filter = IntentFilter("com.btf.player.status.CALLBACK")
registerReceiver(receiver, filter)   // 不要传第 3 个参数
```

### 状态回传的权限方向（2026-10-19 实测踩到）

主应用发状态用的是 `sendBroadcast(intent, RECEIVE_PLAYER_STATUS)` ——
即「**接收方需持有**该权限」。接入方只要在 manifest 里申请到该权限
（同签名即自动授予），用两参 `registerReceiver` 注册即可。

若注册时写成
`registerReceiver(receiver, filter, RECEIVE_PLAYER_STATUS, null)`，
第三个参数的语义是「**发送方**必须持有该权限」，会与主应用的用法冲突，
现象是主应用侧报：
`Permission Denial: broadcasting … requires RECEIVE_PLAYER_STATUS due to registered receiver`。

---

### `set_media` 的两种参数在失败时行为**不同**（重要）

| 参数形式 | 某个文件无效时的行为 |
|---|---|
| `media_path`（单个） | **立即返回错误**（`1003`/`1004`/`1005`），该路内容不变 |
| `media_paths[]`（多个） | **跳过无效项并继续**，其余有效项照常设置；回执仍是 `command_success` |

也就是说，用 `media_paths` 批量下发时，**必须自行核对回执中的 `media_count`**
与下发的数量是否一致，否则会以为全部成功、实际少了几路：

```
下发 5 个 → 回执 media_loaded{media_count: 3} → 说明有 2 个被跳过
```

被跳过的具体原因会写入设备日志（`跳过（1003）: …`），但不单独回调。
若需要逐项确认，请改用 `media_path` 逐个下发（失败会立即报错）。

---

## 五bis、真机验证状态（2026-10-20，RK3568）

**全部 19 条命令均已在真机验证通过**，包括：

- 正常路径：`play_all` / `pause_all` / `stop_all` / `play` / `pause` / `stop` /
  `set_layout` / `set_media` / `set_media_dir` / `clear` / `clear_all` /
  `set_interval` / `set_volume` / `set_mute` / `set_loop_mode` /
  `show` / `hide` / `get_status` / `batch`
- 错误路径：越界索引 → `1001`；文件不存在 → `1003`；非法枚举值 → `2002`；
  `batch` 部分失败 → `2002`；`hide` 不在前台 → `1006`
- **参数钳制**：`set_interval=200` 回执 `60秒（请求 200 已限制到 1-60）`
- **状态回读一致性**：命令生效后 `get_status` 的 `interval_seconds` /
  `loop_mode` / `muted` 与命令效果一一对应

## 六、命令生效性与错误回报

以下项**已在 1.1.3 中修复**（1.1.2 的 `hide` 实现是错的，见下方「曾走过的弯路」）：

- ✅ `set_loop_mode` 非法值返回错误，缺省值改为枚举中真实存在的 `LIST`
- ✅ `set_interval` 回执显示实际生效值（clamp 后）
- ✅ `hide` 在应用**不在前台**时返回错误，不再盲目报成功
  （实现方式：发送前读取 `AppForegroundState` 判定，而非等待广播回执；
  回执方案不可行，原因见下方「曾走过的弯路」）
- ✅ 播放器级命令（`play` / `pause` / `stop` / `set_volume` / `set_mute`）
  对**不在当前布局中**的路返回 `1001`，不再广播虚假状态
- ✅ 全量命令（`play_all` / `pause_all` / `stop_all`）无实际效果时返回 `1006`
- ✅ `batch` 嵌套深度上限为 3，超出返回 `2002`；子命令部分失败时不再报整体成功

### 曾走过的弯路（供接入方与后续维护者参考）

`hide` 最初采用「Activity 回执 + 阻塞等待」实现，**该方案不可能成功**：

1. 命令处理发生在 `BroadcastReceiver.onReceive` 内、运行在主线程；
   `onReceive` 拿到的是 `ReceiverRestrictedContext`，在其中调用
   `registerReceiver` 会抛 `ReceiverCallNotAllowedException`。
2. 即便注册成功，回执也在主线程投递，而主线程正阻塞在等待上 → 必然超时互锁。

现改为「发送前判定前台状态」，同步、无阻塞、无回执。

### 仍在的项

| 项 | 状态 |
|---|---|
| `state` 取值 `stopped` vs `idle` | 两个事件中不一致，见第三节 |
| 状态载荷中的 `media_path` | 为**本地绝对路径**（如 `/storage/emulated/0/RK3568MediaPlayer/player1/video.mp4`），含 U 盘挂载点。当前版本**有意保持**该行为，因为部分接入方用它做内容匹配。若要脱敏需与接入方约定后统一变更，见下方说明 |

### 关于 `media_path` 的说明

`media_path` 返回的是设备本地绝对路径，**不是**接入方下发时的原始路径 ——
本应用会把 U 盘媒体复制到本地缓存目录后再播放，因此：

- 接入方下发的 `/mnt/media_rw/XXXX/media/player1/a.mp4`
- 在状态回传中会变成 `/storage/emulated/0/RK3568MediaPlayer/player1/a.mp4`

**若接入方需要把状态与下发内容对应，建议用文件名（末段）或自定义的媒体 ID 匹配，
不要用完整路径比对。**

脱敏（去掉挂载点前缀或只返回文件名）是可选改进，但会改变现有契约，
需与接入方确认后统一升级，故当前版本未改动。

完整清单与优先级见 `docs/delivery-ledger.md`。
