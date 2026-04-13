# Runtime Flows

## 1. Startup Flow

`Application -> MainActivity -> requestPermissions -> bind/start UsbMonitorService -> MainViewModel.init -> loadLocalContent -> UI render`

关键文件：

- `MediaPlayApplication.kt`
- `MainActivity.kt`
- `MainViewModel.kt`

## 2. USB Flow

`UsbBroadcastReceiver / periodic scan / manual trigger -> UsbMonitorService.checkUsbContentAsync -> UsbConfigLoader -> FeatureManager.applyUsbConfig -> MainViewModel.onUsbConnected -> LocalStorageManager.copyAllFromUsb(staging/backup swap) -> loadLocalContent`

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

## 4. Known Broken / Weak Flows

- 已引入共享 `UsbRuntimeState`，并由 Service 向 Activity / ViewModel 同步当前状态；但业务副作用仍存在少量 ViewModel 双写
- 事务式复制已补，但仍缺真机异常回归验证
- 播放事件已回写到 ViewModel，但仍缺真机播放异常/完成验证
