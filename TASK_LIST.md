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
- [ ] 6.1 编译测试 Debug 版本
- [ ] 6.2 编译测试 Release 版本
- [x] 6.3 创建用户文档说明配置文件格式

## 配置文件格式 (btf_config.json)

完整可用的示例见仓库根目录 [`btf_config_sample.json`](btf_config_sample.json)，
字段定义与校验规则见 [`docs/usb-config-schema.md`](docs/usb-config-schema.md)。

```json
{
  "version": 1,
  "settings": {
    "layoutMode": "GRID_2X2",
    "language": "zh",
    "defaultVolume": 80,
    "imageIntervalSeconds": 5,
    "loopMode": "LIST"
  },
  "features": {
    "showSettingsButton": true,
    "allowManualFileSelect": true,
    "allowLocalMediaScan": false,
    "showHelpTip": false,
    "allowLayoutChange": true,
    "allowOrientationAutoSwitch": true,
    "showDebugInfo": false
  }
}
```

## 功能开关默认值

下表与 `FeatureFlags.debugDefaults()` / `FeatureFlags.releaseDefaults()` 保持一致。
未在 `btf_config.json` 中出现的键按构建类型的默认值生效。

| 功能 | Debug | Release |
|------|-------|---------|
| showSettingsButton | true | true |
| allowManualFileSelect | true | true |
| allowLocalMediaScan | true | false |
| showHelpTip | true | false |
| allowLayoutChange | true | true |
| allowOrientationAutoSwitch | true | true |
| showDebugInfo | true | false |
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
