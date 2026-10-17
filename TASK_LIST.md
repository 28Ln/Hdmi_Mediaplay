# U盘配置系统 - 任务清单

## 目标
实现通过U盘JSON配置文件控制功能开关和默认设置，Debug/Release版本有不同的默认行为。

## 任务列表

### 阶段1: 数据模型 (不影响现有功能)
- [x] 1.1 创建 FeatureFlags 数据类 - 定义所有功能开关
- [x] 1.2 创建 UsbConfig 数据类 - 定义U盘配置文件结构
- [x] 1.3 创建示例配置文件 btf_config_sample.json

### 阶段2: 配置加载器 (不影响现有功能)
- [x] 2.1 创建 UsbConfigLoader - 从U盘读取JSON配置
- [x] 2.2 创建 FeatureManager 单例 - 管理功能开关状态
- [x] 2.3 在 Application 中初始化 FeatureManager

### 阶段3: Build配置 (不影响现有功能)
- [x] 3.1 修改 build.gradle.kts 添加 BuildConfig 字段
- [x] 3.2 配置 Debug/Release 不同的默认值

### 阶段4: UI集成 (渐进式修改)
- [x] 4.1 修改 MainScreen - 根据功能开关显示/隐藏设置按钮
- [x] 4.2 修改 MainScreen - 根据功能开关显示/隐藏帮助提示
- [x] 4.3 修改 PlayerMenuDialog - 根据功能开关显示/隐藏选项
- [x] 4.4 修改 BottomControlBar - 根据功能开关显示/隐藏按钮

### 阶段5: U盘检测集成
- [x] 5.1 修改 UsbMonitorService - 检测到U盘时加载配置
- [x] 5.2 修改 MainViewModel - 应用U盘配置到设置
- [x] 5.3 添加配置加载状态提示

### 阶段6: 测试与文档
- [x] 6.1 编译测试 Debug 版本
- [x] 6.2 编译测试 Release 版本
- [x] 6.3 创建用户文档说明配置文件格式

> 6.1 / 6.2 的完成证据：`docs/verification-matrix.md` 中
> `assembleDebug` / `assembleRelease` 已在多轮验证中通过
> （含 `clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease` 稳定基线）。

## 配置文件格式 (btf_config.json)

完整可用的示例见仓库根目录 [`btf_config_sample.json`](btf_config_sample.json)，
字段定义与校验规则见 [`docs/usb-config-schema.md`](docs/usb-config-schema.md)。

```json
{
  "version": 1,
  "settings": {
    "layoutMode": "GRID_2X2",
    "language": "zh",
    "backgroundColor": "#FF000000",
    "defaultVolume": 80,
    "defaultMuted": false,
    "imageIntervalSeconds": 5,
    "imageTransition": "FADE",
    "loopMode": "LIST",
    "videoScaleMode": "FIT",
    "autoPlayOnStart": true,
    "autoPlayAfterCopy": true,
    "keepScreenOn": true,
    "usbScanFolderName": "btf_media",
    "showOverwriteConfirm": false,
    "audioFallbackSilent": true
  },
  "features": {
    "showBottomControlBar": true,
    "showSettingsButton": true,
    "showImageSplitTool": true,
    "allowPlayPauseControl": true,
    "allowManualFileSelect": true,
    "allowLocalMediaScan": false,
    "allowVolumeControl": true,
    "allowLayoutChange": true,
    "allowOrientationAutoSwitch": true,
    "showHelpTip": false,
    "showPlayerIndex": true,
    "showUsbStatus": true,
    "allowClearCache": true,
    "showLanguageSetting": true,
    "showAudioOutputSetting": true,
    "showPlaybackSettings": true,
    "showVideoSettings": true,
    "showImageSettings": true,
    "showUsbSettings": true,
    "showDisplaySettings": true,
    "showAdvancedSettings": true,
    "showStorageSettings": true,
    "showHdmiControl": true,
    "showHelpSection": true,
    "showScanUsbButton": true
  }
}
```

> 上例为**解析器全量键**（与 `UsbConfigSettings.SETTINGS_KEYS` /
> `UsbConfigFeatures.FEATURE_KEYS` 一一对应）。
> 其中 `showDebugInfo` 解析器仍接受（`FEATURE_KEYS` 保留该键，避免旧配置产生
> 「未知键」告警），但 `app/src/main` **零处读取**，是僵尸标志 ——
> 故不再出现在示例中；真实调试开关请用 `BuildConfig.IS_DEBUG_BUILD` /
> `enableDebugLog`。

## 功能开关默认值

下表与 `FeatureFlags.debugDefaults()` / `FeatureFlags.releaseDefaults()` 保持一致。
未在 `btf_config.json` 中出现的键按构建类型的默认值生效。

> `showDebugInfo` 解析器仍接受该键（`FEATURE_KEYS` 保留），但代码零处读取，
> 属僵尸标志（见 `docs/audit-findings.md` P2-1），故不计入本表。

| 功能 | Debug | Release |
|------|-------|---------|
| showSettingsButton | true | true |
| allowManualFileSelect | true | true |
| allowLocalMediaScan | true | false |
| showHelpTip | true | false |
| allowLayoutChange | true | true |
| allowOrientationAutoSwitch | true | true |
| showPlayerIndex | true | true |
| allowVolumeControl | true | true |
| allowClearCache | true | false |
| showLanguageSetting | true | false |
| showAudioOutputSetting | true | false |
| showPlaybackSettings | true | false |
| showVideoSettings | true | false |
| showImageSettings | true | false |
| showUsbSettings | true | false |
| showDisplaySettings | true | false |
| showAdvancedSettings | true | false |
| showStorageSettings | true | false |
| showHdmiControl | true | false |
| showHelpSection | true | false |
| showScanUsbButton | true | true |
| showBottomControlBar | true | true |
| showImageSplitTool | true | true |
| allowPlayPauseControl | true | true |
| showUsbStatus | true | true |

## 注意事项
- 所有修改必须向后兼容
- 没有配置文件时使用默认值
- 配置文件解析失败时不影响正常运行
