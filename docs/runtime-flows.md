# Runtime Flows

## 1. Startup Flow

`Application -> MainActivity -> requestPermissions -> bind/start UsbMonitorService -> MainViewModel.init -> loadLocalContent -> UI render`

关键文件：

- `MediaPlayApplication.kt`
- `MainActivity.kt`
- `MainViewModel.kt`

## 2. USB Flow

`UsbBroadcastReceiver / periodic scan / manual trigger -> UsbMonitorService.checkUsbContentAsync -> emit UsbRuntimeState -> MainActivity sync -> UsbConfigLoader / FeatureManager.applyUsbConfig -> MainViewModel.onUsbConnected -> LocalStorageManager.copyAllFromUsb(staging/backup swap) -> loadLocalContent`

关键文件：

- `UsbBroadcastReceiver.kt`
- `UsbMonitorService.kt`
- `UsbConfigLoader.kt`
- `FeatureManager.kt`
- `MainViewModel.kt`
- `LocalStorageManager.kt`

## 3. Remote Command Flow

`PlayerCommandReceiver -> PlayerCommandHandler -> MainViewModel callback -> PlayerStatusBroadcaster`

关键文件：

- `PlayerCommandReceiver.kt`
- `PlayerCommandHandler.kt`
- `PlayerStatusBroadcaster.kt`
- `MainViewModel.kt`

## 5. Orientation Auto-Switch Flow（竖屏墙模式旋转）

`设置页选择竖屏模式 / U盘配置 layoutMode 覆盖 / 远控 set_layout → settingsFlow → MainActivity LaunchedEffect(settings.layoutMode, featureFlags.allowOrientationAutoSwitch) → setRequestedOrientation(PORTRAIT / LANDSCAPE)`

关键文件：

- `MainActivity.kt`
- `AppSettings.kt`（`LayoutMode.isPortraitWall`）
- `FeatureFlags.kt` / `UsbConfig.kt`（`allowOrientationAutoSwitch` 开关）

行为约定：

- 竖屏墙模式（拼接板模式 3/4）请求 `PORTRAIT`；其余模式或开关关闭时强制 `LANDSCAPE`（防止方向残留）
- 开关可通过 `btf_config.json` 的 `features.allowOrientationAutoSwitch` 关闭（U 盘拔出后恢复默认 true）
- `configChanges` 已声明 `orientation|screenSize`：旋转不重建 Activity，Compose 自动重算布局，`QuadPlayerLayout` 按新宽高重排分屏
- `onConfigurationChanged` 重新施加沉浸式全屏（部分 RK ROM 旋转后 systemBars 标志复位）
- 已知行为：冷启动瞬间先横屏闪现再转竖屏（DataStore 异步加载真实 layoutMode 前使用默认 `GRID_2X2`）
- 已知限制：ROM 不支持旋转时 `setRequestedOrientation` 静默 no-op

模式 1/3 与模式 2/4 的**画面切分方向相同**（均为按列左右切分，
`QuadPlayerLayout.calculatePlacements` 中横屏与竖屏变体走同一分支），
差异只在于是否请求系统竖屏。因此在 ROM 不支持旋转时，模式 3 会退化为
与模式 1 相同的表现、模式 4 退化为与模式 2 相同，属预期降级而非缺陷。
验证项见 `docs/device-verification.md` 第 7、9 节。

## 4. Known Broken / Weak Flows

- 已引入共享 `UsbRuntimeState`，并由 Service 向 Activity / ViewModel 同步当前状态；但业务副作用仍存在少量 ViewModel 双写
- 事务式复制已补，但仍缺真机异常回归验证
- 播放事件已回写到 ViewModel，但仍缺真机播放异常/完成验证
