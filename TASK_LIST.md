# RK3568 HDMI 多媒体播放器 - 任务清单

## 项目概述
- 平台: Android 11, RK3568
- 技术栈: Kotlin + Jetpack Compose
- 核心功能: 4路2x2布局媒体播放器，支持U盘自动检测和本地缓存

---

## 阶段一：基础架构搭建 ✅

### 1.1 项目配置
- [x] 添加必要依赖 (ExoPlayer/Media3, Coil图片加载, DataStore设置存储)
- [x] 配置 AndroidManifest 权限
  - 存储读写权限 (READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)
  - USB设备权限
  - 开机自启动权限 (RECEIVE_BOOT_COMPLETED)
  - 前台服务权限
- [x] 配置全屏主题 (隐藏状态栏、导航栏)

### 1.2 数据层架构
- [x] 创建数据模型
  - MediaItem (媒体文件信息: 路径、类型、来源)
  - PlayerConfig (播放器配置)
  - AppSettings (应用设置)
- [x] 创建本地存储管理器 (LocalStorageManager)
- [x] 创建 DataStore 设置管理器 (SettingsRepository)

---

## 阶段二：核心播放功能 ✅

### 2.1 播放器组件
- [x] 创建视频播放器组件 (VideoPlayerView) - 基于 Media3/ExoPlayer
- [x] 创建图片显示组件 (ImageDisplayView) - 支持图片轮播
- [x] 创建统一媒体播放器组件 (MediaPlayerView) - 根据文件类型自动切换
- [x] 实现播放器控制逻辑 (播放、暂停、循环)

### 2.2 2x2 布局实现
- [x] 创建 QuadPlayerLayout 组件 (4宫格布局)
- [x] 实现各播放器独立控制
- [ ] 实现全屏切换功能 (单击某个播放器全屏)
- [x] 实现布局自适应 (横屏/竖屏)

### 2.3 媒体文件管理
- [x] 创建文件类型识别工具 (MediaTypeUtils)
  - 支持视频格式: mp4, mkv, avi, mov, wmv, flv
  - 支持图片格式: jpg, jpeg, png, bmp, gif, webp
- [ ] 创建文件选择器功能 (打开系统文件管理器)
- [x] 创建媒体扫描器 (MediaScanner)

---

## 阶段三：U盘检测与管理 ✅

### 3.1 U盘监听服务
- [x] 创建 USB 广播接收器 (UsbBroadcastReceiver)
- [x] 创建 U盘挂载监听服务 (UsbMonitorService)
- [x] 实现 U盘插入/拔出事件处理
- [x] 实现 U盘路径自动检测

### 3.2 固定目录扫描
- [x] 定义 U盘固定目录结构
  ```
  /USB_ROOT/
    ├── player1/  (第1个播放器内容)
    ├── player2/  (第2个播放器内容)
    ├── player3/  (第3个播放器内容)
    └── player4/  (第4个播放器内容)
  ```
- [x] 实现目录扫描逻辑
- [x] 实现文件有效性验证

### 3.3 文件拷贝与缓存
- [x] 创建本地缓存目录结构
  ```
  /data/data/包名/files/media/
    ├── player1/
    ├── player2/
    ├── player3/
    └── player4/
  ```
- [x] 实现文件拷贝功能 (带进度显示)
- [x] 实现增量更新 (只拷贝新文件/修改的文件)
- [x] 实现缓存清理功能

---

## 阶段四：播放优先级与覆盖逻辑 ✅

### 4.1 播放源优先级
- [x] 实现播放源优先级逻辑
  1. U盘插入时 → 优先播放U盘内容
  2. U盘拔出时 → 自动切换到本地缓存
  3. 无内容时 → 显示默认占位图
- [x] 实现无缝切换 (U盘拔出不中断播放)

### 4.2 覆盖确认机制
- [x] 创建覆盖确认对话框
  - 显示将被覆盖的文件列表
  - 提供"覆盖"/"取消"/"仅本次"选项
- [x] 实现自动覆盖模式 (根据设置)
- [ ] 实现覆盖历史记录

---

## 阶段五：设置功能 ✅

### 5.1 基础设置
- [x] 覆盖确认开关 (默认开启提示)
- [x] 自动播放开关 (应用启动后自动播放)
- [x] 开机自启动开关
- [x] 循环播放模式 (单个循环/列表循环/随机播放)

### 5.2 播放器设置
- [x] 视频播放设置
  - 默认音量 (0-100%)
  - 静音模式
  - 视频缩放模式 (适应/填充/拉伸/原始)
  - 硬件解码开关
- [x] 图片播放设置
  - 图片轮播间隔 (3s/5s/10s/自定义)
  - 图片切换动画 (淡入淡出/滑动/无)
  - 图片缩放模式

### 5.3 U盘设置
- [x] U盘检测开关
- [x] 自定义U盘扫描目录名
- [x] 拷贝完成后是否自动播放
- [x] 拷贝时是否显示进度
- [ ] 仅WiFi下拷贝 (如果有网络功能)

### 5.4 显示设置
- [x] 布局模式 (2x2 / 1x4 / 4x1 / 自定义)
- [ ] 播放器边框样式
- [x] 背景颜色
- [x] 显示/隐藏播放器编号
- [x] 屏幕常亮开关

### 5.5 高级设置
- [x] 缓存大小限制
- [x] 清除所有缓存
- [x] 重置所有设置
- [ ] 导出/导入配置
- [x] 日志开关 (调试用)
- [x] 关于页面 (版本信息)

### 5.6 设置界面
- [x] 创建设置主界面 (SettingsScreen)
- [x] 实现设置项分组显示
- [x] 实现设置即时生效

---

## 阶段六：用户交互 (部分完成)

### 6.1 主界面交互
- [x] 长按播放器 → 显示操作菜单
  - 选择文件
  - 暂停/播放
  - 静音
  - 全屏
- [ ] 双击播放器 → 全屏/退出全屏
- [ ] 滑动手势 → 调节音量/亮度 (可选)

### 6.2 状态显示
- [x] U盘状态指示器 (已连接/未连接)
- [x] 播放状态指示 (播放中/暂停/错误)
- [x] 文件拷贝进度条
- [ ] Toast/Snackbar 提示

### 6.3 错误处理
- [x] 文件不存在处理
- [x] 格式不支持处理
- [ ] 存储空间不足处理
- [ ] U盘读取失败处理

---

## 阶段七：系统集成 ✅

### 7.1 开机自启动
- [x] 创建 BootReceiver
- [x] 实现开机自动启动应用
- [x] 实现启动后自动播放

### 7.2 后台服务
- [x] 创建前台服务 (保持应用运行)
- [x] 实现后台U盘监听
- [ ] 实现应用保活机制 (RK3568平台适配)

### 7.3 权限管理
- [x] 运行时权限请求
- [x] 权限拒绝处理
- [ ] 引导用户开启权限

---

## 阶段八：测试与优化

### 8.1 功能测试
- [ ] 4路播放器同时播放测试
- [ ] U盘热插拔测试
- [ ] 大文件拷贝测试
- [ ] 长时间运行稳定性测试

### 8.2 性能优化
- [ ] 内存优化 (避免OOM)
- [ ] 视频解码优化 (硬解)
- [ ] 文件IO优化
- [ ] UI流畅度优化

### 8.3 兼容性
- [ ] RK3568 平台适配
- [ ] Android 11 API 适配
- [ ] 不同分辨率适配
- [ ] 不同U盘格式支持 (FAT32/NTFS/exFAT)

---

## 文件结构规划

```
app/src/main/java/com/btf/rk3568_hdmi_mediaplay/
├── MainActivity.kt                 # 主Activity
├── data/
│   ├── model/
│   │   ├── MediaItem.kt           # 媒体文件模型
│   │   ├── PlayerConfig.kt        # 播放器配置
│   │   └── AppSettings.kt         # 应用设置
│   ├── repository/
│   │   ├── MediaRepository.kt     # 媒体文件仓库
│   │   └── SettingsRepository.kt  # 设置仓库
│   └── local/
│       └── LocalStorageManager.kt # 本地存储管理
├── service/
│   ├── UsbMonitorService.kt       # U盘监听服务
│   └── MediaPlaybackService.kt    # 媒体播放服务
├── receiver/
│   ├── UsbBroadcastReceiver.kt    # USB广播接收器
│   └── BootReceiver.kt            # 开机广播接收器
├── ui/
│   ├── main/
│   │   ├── MainScreen.kt          # 主界面
│   │   ├── MainViewModel.kt       # 主界面ViewModel
│   │   └── QuadPlayerLayout.kt    # 4宫格布局
│   ├── player/
│   │   ├── VideoPlayerView.kt     # 视频播放器
│   │   ├── ImageDisplayView.kt    # 图片显示
│   │   └── MediaPlayerView.kt     # 统一媒体播放器
│   ├── settings/
│   │   ├── SettingsScreen.kt      # 设置界面
│   │   └── SettingsViewModel.kt   # 设置ViewModel
│   ├── dialog/
│   │   ├── OverwriteDialog.kt     # 覆盖确认对话框
│   │   └── PlayerMenuDialog.kt    # 播放器菜单对话框
│   └── components/
│       ├── StatusIndicator.kt     # 状态指示器
│       └── ProgressOverlay.kt     # 进度覆盖层
├── util/
│   ├── MediaTypeUtils.kt          # 媒体类型工具
│   ├── FileUtils.kt               # 文件工具
│   ├── UsbUtils.kt                # U盘工具
│   └── PermissionUtils.kt         # 权限工具
└── theme/
    └── Theme.kt                   # 主题配置
```

---

## 优先级排序

| 优先级 | 任务 | 预估时间 |
|--------|------|----------|
| P0 | 阶段一：基础架构 | 1天 |
| P0 | 阶段二：核心播放功能 | 2-3天 |
| P0 | 阶段三：U盘检测与管理 | 2天 |
| P1 | 阶段四：播放优先级与覆盖 | 1天 |
| P1 | 阶段五：设置功能 | 2天 |
| P2 | 阶段六：用户交互 | 1-2天 |
| P2 | 阶段七：系统集成 | 1天 |
| P3 | 阶段八：测试与优化 | 持续 |

---

## 备注

1. U盘路径在RK3568上通常为 `/mnt/media_rw/` 或 `/storage/` 下
2. 需要考虑RK3568的硬件解码能力，优先使用硬解
3. 建议使用 Media3 (ExoPlayer) 作为视频播放器
4. 图片加载推荐使用 Coil (Compose友好)
5. 设置存储推荐使用 DataStore (替代SharedPreferences)
