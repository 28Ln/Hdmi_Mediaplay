# USB Config Schema

## File

- Filename: `btf_config.json`
- Location: USB root directory

## Top-Level Fields

- `version: Int`
- `settings: Object?`
- `features: Object?`

## Current Supported `settings`

- `layoutMode`
- `language`
- `backgroundColor`
- `defaultVolume`
- `defaultMuted`
- `imageIntervalSeconds`
- `imageTransition`
- `loopMode`
- `videoScaleMode`
- `autoPlayOnStart`
- `autoPlayAfterCopy`
- `keepScreenOn`
- `usbScanFolderName`
- `showOverwriteConfirm`
- `audioFallbackSilent`

### `audioFallbackSilent`

- 类型：`Boolean`，默认 `true`
- 含义：音频轨不受支持时（如设备无 AC3 直通输出），是否丢弃音频轨、
  继续**静音播放视频**。
- `true`（默认）：保留画面，无声播放。拼接墙场景下黑屏比无声严重。
- `false`：维持原行为——音频失败即整格报错，便于在"必须有声"的场合暴露问题。

> 背景：部分 ROM 会声明支持 AC3 直通（ExoPlayer 报 `format_supported=YES`），
> 但音频 HAL 实际建不出输出流（`SpdifStreamOut::open() status=-61`
> → `createTrack -38` → `Cannot create AudioTrack`）。
> 此时视频轨本身正常，不应因音频问题丢掉画面。

### `layoutMode` Wall Modes

客户设置页面按 HDMI 拼接示意图（原始图见 `docs/hdmi-wall-mode-diagram.jpg`，
转正版 `docs/hdmi-wall-mode-diagram-rotated.jpg`）提供以下 7 种模式，
编号与拼接板硬件「切换模式」按键一致：

| 编号 | 值 | 布局名 | 实际画面切分 |
|---|---|---|---|
| 1 | `GRID_1X2` | 横屏 1×2 | 2 路左右并列 |
| 2 | `GRID_1X3` | 横屏 1×3 | 3 路左右并列 |
| 3 | `GRID_1X2_PORTRAIT` | 竖屏 1×2 | 竖屏输出 + 2 路左右并列 |
| 4 | `GRID_1X3_PORTRAIT` | 竖屏 1×3 | 竖屏输出 + 3 路左右并列 |
| 5 | `GRID_2X1` | 横屏 2×1 | 2 路上下分屏 |
| 6 | `GRID_3X1` | 横屏 3×1 | 3 路上下分布 |
| 7 | `GRID_2X2` | 横屏 2×2 | 4 路 2×2 方阵 |

> 「切分方向」以**画面**为准，与屏幕是否旋转无关：模式 3/4 旋转为竖屏后，
> 仍是把画面按列左右切分（`QuadPlayerLayout.calculatePlacements` 中
> `GRID_1X2_PORTRAIT` 与 `GRID_1X2` 走同一分支）。
>
> 注意示意图上手写的模式 5/6/7 标注（如模式 5 写作「1×2」）采用「列×行」记法，
> 与上表「布局名」的「行×列」记法相反，但所指画面相同。

软件附加模式（不占用拼接板编号，设置页单独列出）：

- `GRID_2X2_SAME`：2×2 同画面，四个格子都播放 P1 / `player1` 的内容，
  P2–P4 自动静音且不上报播放事件（避免同一路内容的重复远控事件）

旧的 `SINGLE`、`ROW_1X4`、`COLUMN_4X1` 和 `PIP` 值继续保留解析兼容，但不在客户设置页面中显示。

## Current Supported `features`

- `showBottomControlBar`
- `showSettingsButton`
- `showImageSplitTool`
- `allowPlayPauseControl`
- `allowManualFileSelect`
- `allowLocalMediaScan`
- `allowVolumeControl`
- `allowLayoutChange`
- `showHelpTip`
- `showPlayerIndex`
- `showUsbStatus`
- `showDebugInfo`
- `allowClearCache`
- `showLanguageSetting`
- `showAudioOutputSetting`
- `showPlaybackSettings`
- `showVideoSettings`
- `showImageSettings`
- `showUsbSettings`
- `showDisplaySettings`
- `showAdvancedSettings`
- `showStorageSettings`
- `showHdmiControl`
- `showHelpSection`
- `showScanUsbButton`
- `allowOrientationAutoSwitch`

### `allowOrientationAutoSwitch`

- 默认 `true`（Debug / Release 一致）
- 为 `true` 时，选中竖屏墙模式（`GRID_1X2_PORTRAIT` / `GRID_1X3_PORTRAIT`，即拼接板模式 3/4）会请求系统旋转到竖屏；切回横屏模式时自动恢复
- 为 `false` 时，竖屏墙模式仍按横屏渲染（恢复旧行为，用于不支持旋转的 ROM）
- 新增可选键**不需要 `version` bump**：`version == 1` 解析器对缺失键按 best-effort 处理，未知键仅告警不报错

## Required Improvements

当前实现已经补充：

1. 未知字段告警
2. 不支持的 `version` 告警
3. 基础类型不匹配告警（Boolean / Int / String）
4. 枚举值合法性校验：
   - `layoutMode`
   - `imageTransition`
   - `loopMode`
   - `videoScaleMode`
5. 取值集合校验：
   - `language` 仅接受 `zh/chinese/en/english`
6. 数值范围校验：
   - `defaultVolume`: `0..100`
   - `imageIntervalSeconds`: `1..60`
7. 字符串约束：
   - `backgroundColor` 仅接受 `#RRGGBB` 或 `#AARRGGBB`
   - `usbScanFolderName` 不允许空白字符串

仍待补齐：

1. 配置迁移策略（当 `version > 1` 时不仅告警，还应定义行为）
2. 更细粒度的字段间组合约束（例如某些 feature 与 setting 的联动规则）
3. 样例配置与验收配置模板

## Version Strategy

当前策略是：

- `version == 1`：按当前 schema 正常解析
- `version != 1`：记录 warning，但仍以 **best-effort** 方式按 v1 规则解析

这符合当前项目“向后兼容优先、现场可恢复优先”的落地策略，但后续仍应补 migration 规则。
