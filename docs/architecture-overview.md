# Architecture Overview

## Entry Points

- `MediaPlayApplication`
- `MainActivity`
- `UsbMonitorService`
- `BootReceiver`
- `UsbBroadcastReceiver`
- `PlayerCommandReceiver`

## Main Modules

- `data/model`：设置、媒体、功能开关、U 盘配置模型
- `data/repository`：DataStore 设置持久化
- `data/local`：本地缓存与复制管理
- `remote`：远程广播协议、命令处理、状态回传
- `service`：U 盘监听与周期扫描
- `ui/main`：主界面与主 ViewModel
- `ui/player`：图片/视频播放组件
- `util`：文件、U 盘、媒体扫描、字符串与音频工具

## Current Structural Risks

1. `MainViewModel` 过重
2. `UsbMonitorService` 与 `MainViewModel` 状态源分裂
3. `PlayerCommandHandler` 命令分发过于集中
4. 固定 4 路播放器常量散落在多个模块
