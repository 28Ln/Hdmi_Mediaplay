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
