package com.btf.rk3568_hdmi_mediaplay.util

import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage

/**
 * 多语言字符串资源
 */
object StringResources {
    
    private var currentLanguage: AppLanguage = AppLanguage.CHINESE
    
    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
    }
    
    fun getLanguage(): AppLanguage = currentLanguage
    
    // 通用
    val back: String get() = if (currentLanguage == AppLanguage.CHINESE) "返回" else "Back"
    val confirm: String get() = if (currentLanguage == AppLanguage.CHINESE) "确认" else "Confirm"
    val cancel: String get() = if (currentLanguage == AppLanguage.CHINESE) "取消" else "Cancel"
    val close: String get() = if (currentLanguage == AppLanguage.CHINESE) "关闭" else "Close"
    val settings: String get() = if (currentLanguage == AppLanguage.CHINESE) "设置" else "Settings"
    val loading: String get() = if (currentLanguage == AppLanguage.CHINESE) "加载中..." else "Loading..."
    val noContent: String get() = if (currentLanguage == AppLanguage.CHINESE) "无内容" else "No Content"
    val noVideo: String get() = if (currentLanguage == AppLanguage.CHINESE) "无视频" else "No Video"
    val noImage: String get() = if (currentLanguage == AppLanguage.CHINESE) "无图片" else "No Image"
    
    // 播放器
    val player: String get() = if (currentLanguage == AppLanguage.CHINESE) "播放器" else "Player"
    val play: String get() = if (currentLanguage == AppLanguage.CHINESE) "播放" else "Play"
    val pause: String get() = if (currentLanguage == AppLanguage.CHINESE) "暂停" else "Pause"
    val playAll: String get() = if (currentLanguage == AppLanguage.CHINESE) "全部播放" else "Play All"
    val pauseAll: String get() = if (currentLanguage == AppLanguage.CHINESE) "全部暂停" else "Pause All"
    val mute: String get() = if (currentLanguage == AppLanguage.CHINESE) "静音" else "Mute"
    val unmute: String get() = if (currentLanguage == AppLanguage.CHINESE) "取消静音" else "Unmute"
    val selectFile: String get() = if (currentLanguage == AppLanguage.CHINESE) "选择文件" else "Select File"
    val scanLocalMedia: String get() = if (currentLanguage == AppLanguage.CHINESE) "扫描本地媒体" else "Scan Local Media"
    val clearContent: String get() = if (currentLanguage == AppLanguage.CHINESE) "清除内容" else "Clear Content"
    val longPressToSelect: String get() = if (currentLanguage == AppLanguage.CHINESE) "长按选择媒体文件\n或插入U盘自动加载" else "Long press to select media\nor insert USB to auto load"
    
    // U盘
    val usbConnected: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘已连接" else "USB Connected"
    val usbDisconnected: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘未连接" else "USB Disconnected"
    val usbNoMedia: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘无媒体" else "USB No Media"
    val usbError: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘错误" else "USB Error"
    val scanUsb: String get() = if (currentLanguage == AppLanguage.CHINESE) "扫描U盘" else "Scan USB"
    val copying: String get() = if (currentLanguage == AppLanguage.CHINESE) "正在拷贝文件" else "Copying Files"
    val copyComplete: String get() = if (currentLanguage == AppLanguage.CHINESE) "拷贝完成！" else "Copy Complete!"
    val copyFailed: String get() = if (currentLanguage == AppLanguage.CHINESE) "拷贝失败" else "Copy Failed"
    val doNotRemoveUsb: String get() = if (currentLanguage == AppLanguage.CHINESE) "请勿拔出U盘..." else "Do not remove USB..."
    val startingPlayback: String get() = if (currentLanguage == AppLanguage.CHINESE) "即将开始播放..." else "Starting playback..."
    
    // 设置页面
    val basicSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "基础设置" else "Basic Settings"
    val videoSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "视频设置" else "Video Settings"
    val audioSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "音频设置" else "Audio Settings"
    val imageSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "图片设置" else "Image Settings"
    val usbSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘设置" else "USB Settings"
    val displaySettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "显示设置" else "Display Settings"
    val advancedSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "高级设置" else "Advanced Settings"
    val about: String get() = if (currentLanguage == AppLanguage.CHINESE) "关于" else "About"
    
    // 基础设置项
    val language: String get() = if (currentLanguage == AppLanguage.CHINESE) "语言" else "Language"
    val overwriteConfirm: String get() = if (currentLanguage == AppLanguage.CHINESE) "覆盖确认提示" else "Overwrite Confirmation"
    val overwriteConfirmDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "插入U盘时是否提示覆盖本地内容" else "Prompt before overwriting local content"
    val autoPlayOnStart: String get() = if (currentLanguage == AppLanguage.CHINESE) "启动后自动播放" else "Auto Play on Start"
    val autoPlayOnStartDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "应用启动后自动播放本地缓存内容" else "Auto play cached content on app start"
    val bootAutoStart: String get() = if (currentLanguage == AppLanguage.CHINESE) "开机自启动" else "Auto Start on Boot"
    val bootAutoStartDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "设备开机后自动启动本应用" else "Auto start app when device boots"
    val loopMode: String get() = if (currentLanguage == AppLanguage.CHINESE) "循环模式" else "Loop Mode"
    
    // 视频设置项
    val defaultVolume: String get() = if (currentLanguage == AppLanguage.CHINESE) "默认音量" else "Default Volume"
    val defaultMuted: String get() = if (currentLanguage == AppLanguage.CHINESE) "默认静音" else "Default Muted"
    val defaultMutedDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "启动时默认静音播放视频" else "Mute video by default on start"
    val videoScaleMode: String get() = if (currentLanguage == AppLanguage.CHINESE) "视频缩放模式" else "Video Scale Mode"
    val hardwareDecode: String get() = if (currentLanguage == AppLanguage.CHINESE) "硬件解码" else "Hardware Decode"
    val hardwareDecodeDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "使用硬件加速解码视频（推荐开启）" else "Use hardware acceleration (recommended)"
    
    // 音频设置项
    val audioOutput: String get() = if (currentLanguage == AppLanguage.CHINESE) "音频输出" else "Audio Output"
    val audioOutputDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "选择音频输出设备" else "Select audio output device"
    
    // 图片设置项
    val slideInterval: String get() = if (currentLanguage == AppLanguage.CHINESE) "轮播间隔" else "Slide Interval"
    val transitionEffect: String get() = if (currentLanguage == AppLanguage.CHINESE) "切换动画" else "Transition Effect"
    
    // U盘设置项
    val usbDetection: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘检测" else "USB Detection"
    val usbDetectionDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "自动检测U盘插入并扫描媒体文件" else "Auto detect USB and scan media files"
    val scanFolderName: String get() = if (currentLanguage == AppLanguage.CHINESE) "扫描目录名" else "Scan Folder Name"
    val scanFolderNameDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "U盘中的媒体目录名称" else "Media folder name in USB"
    val autoPlayAfterCopy: String get() = if (currentLanguage == AppLanguage.CHINESE) "拷贝后自动播放" else "Auto Play After Copy"
    val autoPlayAfterCopyDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "从U盘拷贝完成后自动开始播放" else "Auto play after copying from USB"
    val showCopyProgress: String get() = if (currentLanguage == AppLanguage.CHINESE) "显示拷贝进度" else "Show Copy Progress"
    val showCopyProgressDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "拷贝文件时显示进度条" else "Show progress bar when copying"
    
    // 显示设置项
    val layoutMode: String get() = if (currentLanguage == AppLanguage.CHINESE) "布局模式" else "Layout Mode"
    val showPlayerIndex: String get() = if (currentLanguage == AppLanguage.CHINESE) "显示播放器编号" else "Show Player Index"
    val showPlayerIndexDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "在播放器左上角显示编号标识" else "Show index badge on player"
    val keepScreenOn: String get() = if (currentLanguage == AppLanguage.CHINESE) "屏幕常亮" else "Keep Screen On"
    val keepScreenOnDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "播放时保持屏幕常亮不休眠" else "Keep screen on during playback"
    
    // 高级设置项
    val maxCacheSize: String get() = if (currentLanguage == AppLanguage.CHINESE) "最大缓存大小" else "Max Cache Size"
    val debugLog: String get() = if (currentLanguage == AppLanguage.CHINESE) "调试日志" else "Debug Log"
    val debugLogDesc: String get() = if (currentLanguage == AppLanguage.CHINESE) "启用详细日志输出（用于排查问题）" else "Enable detailed logging for debugging"
    val clearCache: String get() = if (currentLanguage == AppLanguage.CHINESE) "清除缓存" else "Clear Cache"
    val resetSettings: String get() = if (currentLanguage == AppLanguage.CHINESE) "重置设置" else "Reset Settings"
    
    // 帮助提示
    val helpTitle: String get() = if (currentLanguage == AppLanguage.CHINESE) "操作提示" else "Tips"
    val helpTapPlayer: String get() = if (currentLanguage == AppLanguage.CHINESE) "单击播放器: 播放/暂停" else "Tap player: Play/Pause"
    val helpLongPressPlayer: String get() = if (currentLanguage == AppLanguage.CHINESE) "长按播放器: 打开菜单" else "Long press: Open menu"
    val helpTouchBottom: String get() = if (currentLanguage == AppLanguage.CHINESE) "触摸底部: 显示控制栏" else "Touch bottom: Show controls"
    val helpTapToClose: String get() = if (currentLanguage == AppLanguage.CHINESE) "点击此处关闭提示" else "Tap here to close"
    
    // 使用说明
    val usageTitle: String get() = if (currentLanguage == AppLanguage.CHINESE) "使用说明" else "Usage Guide"
    val usageStep1: String get() = if (currentLanguage == AppLanguage.CHINESE) "1. 准备U盘，创建 media 目录" else "1. Prepare USB, create media folder"
    val usageStep2: String get() = if (currentLanguage == AppLanguage.CHINESE) "2. 在 media 下创建 player1~4 文件夹" else "2. Create player1~4 folders in media"
    val usageStep3: String get() = if (currentLanguage == AppLanguage.CHINESE) "3. 将视频/图片放入对应文件夹" else "3. Put videos/images in folders"
    val usageStep4: String get() = if (currentLanguage == AppLanguage.CHINESE) "4. 插入U盘，自动检测并拷贝" else "4. Insert USB, auto detect and copy"
    val usageStep5: String get() = if (currentLanguage == AppLanguage.CHINESE) "5. 拷贝完成后自动播放" else "5. Auto play after copy complete"
    
    // 关于
    val appName: String get() = if (currentLanguage == AppLanguage.CHINESE) "RK3568 HDMI 媒体播放器" else "RK3568 HDMI Media Player"
    val version: String get() = if (currentLanguage == AppLanguage.CHINESE) "版本" else "Version"
    val platform: String get() = if (currentLanguage == AppLanguage.CHINESE) "适用平台" else "Platform"
    val supportedFormats: String get() = if (currentLanguage == AppLanguage.CHINESE) "支持格式" else "Supported Formats"
    
    // 枚举值显示文本
    fun getLoopModeText(mode: com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.SINGLE -> if (currentLanguage == AppLanguage.CHINESE) "单个循环" else "Single Loop"
        com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.LIST -> if (currentLanguage == AppLanguage.CHINESE) "列表循环" else "List Loop"
        com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.RANDOM -> if (currentLanguage == AppLanguage.CHINESE) "随机播放" else "Random"
    }
    
    fun getScaleModeText(mode: com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.FIT -> if (currentLanguage == AppLanguage.CHINESE) "适应 (保持比例)" else "Fit (Keep Ratio)"
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.FILL -> if (currentLanguage == AppLanguage.CHINESE) "填充 (裁剪)" else "Fill (Crop)"
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.STRETCH -> if (currentLanguage == AppLanguage.CHINESE) "拉伸" else "Stretch"
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.ORIGINAL -> if (currentLanguage == AppLanguage.CHINESE) "原始大小" else "Original"
    }
    
    fun getTransitionText(transition: com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition): String = when (transition) {
        com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition.FADE -> if (currentLanguage == AppLanguage.CHINESE) "淡入淡出" else "Fade"
        com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition.SLIDE -> if (currentLanguage == AppLanguage.CHINESE) "滑动" else "Slide"
        com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition.NONE -> if (currentLanguage == AppLanguage.CHINESE) "无动画" else "None"
    }
    
    fun getLayoutModeText(mode: com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.SINGLE -> if (currentLanguage == AppLanguage.CHINESE) "单屏 (全屏)" else "Single (Fullscreen)"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2 -> if (currentLanguage == AppLanguage.CHINESE) "1×2 左右分屏" else "1×2 Side by Side"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X1 -> if (currentLanguage == AppLanguage.CHINESE) "2×1 上下分屏" else "2×1 Top/Bottom"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X2 -> if (currentLanguage == AppLanguage.CHINESE) "2×2 四宫格" else "2×2 Grid"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X3 -> if (currentLanguage == AppLanguage.CHINESE) "1×3 三分屏" else "1×3 Triple"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_3X1 -> if (currentLanguage == AppLanguage.CHINESE) "3×1 三分屏" else "3×1 Triple"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.ROW_1X4 -> if (currentLanguage == AppLanguage.CHINESE) "1×4 横向四分" else "1×4 Horizontal"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.COLUMN_4X1 -> if (currentLanguage == AppLanguage.CHINESE) "4×1 纵向四分" else "4×1 Vertical"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.PIP -> if (currentLanguage == AppLanguage.CHINESE) "画中画 (1大3小)" else "PIP (1 Large + 3 Small)"
    }
    
    fun getLanguageText(lang: AppLanguage): String = when (lang) {
        AppLanguage.CHINESE -> "中文"
        AppLanguage.ENGLISH -> "English"
    }
    
    fun getAudioOutputText(output: com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput): String = when (output) {
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.AUTO -> if (currentLanguage == AppLanguage.CHINESE) "自动" else "Auto"
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.HDMI -> "HDMI"
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.SPEAKER -> if (currentLanguage == AppLanguage.CHINESE) "扬声器/3.5mm" else "Speaker/3.5mm"
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.ALL -> if (currentLanguage == AppLanguage.CHINESE) "全部输出" else "All Outputs"
    }
    
    // 状态文本
    fun getPlayerStateText(state: com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState): String = when (state) {
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.IDLE -> if (currentLanguage == AppLanguage.CHINESE) "空闲" else "Idle"
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.LOADING -> if (currentLanguage == AppLanguage.CHINESE) "加载中" else "Loading"
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.PLAYING -> if (currentLanguage == AppLanguage.CHINESE) "播放中" else "Playing"
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.PAUSED -> if (currentLanguage == AppLanguage.CHINESE) "已暂停" else "Paused"
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.ERROR -> if (currentLanguage == AppLanguage.CHINESE) "错误" else "Error"
    }
}
