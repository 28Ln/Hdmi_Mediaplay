package com.btf.rk3568_hdmi_mediaplay.util

import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage

/**
 * 多语言字符串资源 - 完整版
 */
object StringResources {
    
    private var currentLanguage: AppLanguage = AppLanguage.CHINESE
    
    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
    }
    
    fun getLanguage(): AppLanguage = currentLanguage
    
    private val isEn: Boolean get() = currentLanguage == AppLanguage.ENGLISH
    
    // ========== 通用 ==========
    val back: String get() = if (isEn) "Back" else "返回"
    val confirm: String get() = if (isEn) "Confirm" else "确认"
    val cancel: String get() = if (isEn) "Cancel" else "取消"
    val close: String get() = if (isEn) "Close" else "关闭"
    val settings: String get() = if (isEn) "Settings" else "设置"
    val loading: String get() = if (isEn) "Loading..." else "加载中..."
    val noContent: String get() = if (isEn) "No Content" else "无内容"
    val noVideo: String get() = if (isEn) "No Video" else "无视频"
    val noImage: String get() = if (isEn) "No Image" else "无图片"
    val seconds: String get() = if (isEn) "s" else "秒"
    val video: String get() = if (isEn) "Video" else "视频"
    val image: String get() = if (isEn) "Image" else "图片"
    
    // ========== 播放器 ==========
    val player: String get() = if (isEn) "Player" else "播放器"
    val play: String get() = if (isEn) "Play" else "播放"
    val pause: String get() = if (isEn) "Pause" else "暂停"
    val playAll: String get() = if (isEn) "Play All" else "全部播放"
    val pauseAll: String get() = if (isEn) "Pause All" else "全部暂停"
    val mute: String get() = if (isEn) "Mute" else "静音"
    val unmute: String get() = if (isEn) "Unmute" else "取消静音"
    val selectFile: String get() = if (isEn) "Select File" else "选择文件"
    val scanLocalMedia: String get() = if (isEn) "Scan Local Media" else "扫描本地媒体"
    val clearContent: String get() = if (isEn) "Clear Content" else "清除内容"
    val longPressToSelect: String get() = if (isEn) "Long press to select media\nor insert USB to auto load" else "长按选择媒体文件\n或插入U盘自动加载"
    fun playerN(n: Int): String = if (isEn) "Player $n" else "播放器 $n"
    
    // ========== 播放器状态 ==========
    val stateIdle: String get() = if (isEn) "Idle" else "空闲"
    val stateLoading: String get() = if (isEn) "Loading" else "加载中"
    val statePlaying: String get() = if (isEn) "Playing" else "播放中"
    val statePaused: String get() = if (isEn) "Paused" else "已暂停"
    val stateError: String get() = if (isEn) "Error" else "错误"
    
    // ========== 播放器菜单 ==========
    val menuStatus: String get() = if (isEn) "Status" else "状态"
    val menuContent: String get() = if (isEn) "Content" else "内容"
    val menuCurrent: String get() = if (isEn) "Current" else "当前"
    val menuVolume: String get() = if (isEn) "Volume" else "音量"
    fun nVideos(n: Int): String = if (isEn) "$n videos" else "$n 个视频"
    fun nImages(n: Int): String = if (isEn) "$n images" else "$n 张图片"
    
    // ========== U盘 ==========
    val usbConnected: String get() = if (isEn) "USB Connected" else "U盘已连接"
    val usbDisconnected: String get() = if (isEn) "USB Disconnected" else "U盘未连接"
    val usbNoMedia: String get() = if (isEn) "USB No Media" else "U盘无媒体"
    val usbError: String get() = if (isEn) "USB Error" else "U盘错误"
    val scanUsb: String get() = if (isEn) "Scan USB" else "扫描U盘"
    val copying: String get() = if (isEn) "Copying Files" else "正在拷贝文件"
    val copyComplete: String get() = if (isEn) "Copy Complete!" else "拷贝完成！"
    val copyFailed: String get() = if (isEn) "Copy Failed" else "拷贝失败"
    val doNotRemoveUsb: String get() = if (isEn) "Do not remove USB..." else "请勿拔出U盘..."
    val startingPlayback: String get() = if (isEn) "Starting playback..." else "即将开始播放..."
    fun playerProgress(current: Int, total: Int): String = if (isEn) "Player $current / $total" else "播放器 $current / $total"
    
    // ========== 覆盖对话框 ==========
    val usbContentDetected: String get() = if (isEn) "USB Content Detected" else "检测到U盘内容"
    val overwriteQuestion: String get() = if (isEn) "Found media files in USB. Overwrite local cache with USB content?" else "发现U盘中包含媒体文件，是否用U盘内容覆盖本地缓存？"
    val overwriteNotes: String get() = if (isEn) "Notes:" else "注意事项："
    val overwriteNote1: String get() = if (isEn) "• Local content will be replaced" else "• 覆盖后本地原有内容将被替换"
    val overwriteNote2: String get() = if (isEn) "• Do not remove USB during copy" else "• 拷贝过程中请勿拔出U盘"
    val overwriteNote3: String get() = if (isEn) "• Large files may take minutes" else "• 大文件拷贝可能需要几分钟"
    val overwriteTip: String get() = if (isEn) "💡 Tip: Disable this prompt in settings for auto-overwrite" else "💡 提示：可在设置中关闭此确认提示，实现自动覆盖"
    val confirmOverwrite: String get() = if (isEn) "✓ Confirm" else "✓ 确认覆盖"
    
    // ========== 清除缓存对话框 ==========
    val clearCacheTitle: String get() = if (isEn) "Clear Cache" else "清除缓存"
    val clearCacheQuestion: String get() = if (isEn) "Are you sure to clear all local cache?" else "确定要清除所有本地缓存吗？"
    fun currentCacheSize(mb: Long): String = if (isEn) "Current cache size: ${mb}MB" else "当前缓存大小: ${mb}MB"
    val clearCacheWarning: String get() = if (isEn) "⚠️ All players will stop. Need to reload from USB." else "⚠️ 清除后所有播放器将停止播放，需要重新从U盘加载内容"
    val confirmClear: String get() = if (isEn) "Confirm Clear" else "确认清除"
    
    // ========== 重置设置对话框 ==========
    val resetSettingsTitle: String get() = if (isEn) "🔄 Reset Settings" else "🔄 重置设置"
    val resetSettingsQuestion: String get() = if (isEn) "Reset all settings to default?\n\nThis will not clear cached media files." else "确定要将所有设置恢复为默认值吗？\n\n此操作不会清除缓存的媒体文件。"
    val confirmReset: String get() = if (isEn) "Confirm Reset" else "确认重置"
    
    // ========== 帮助提示 ==========
    val helpTitle: String get() = if (isEn) "Tips" else "操作提示"
    val helpTapPlayer: String get() = if (isEn) "• Tap player: Play/Pause" else "• 单击播放器: 播放/暂停"
    val helpLongPressPlayer: String get() = if (isEn) "• Long press: Open menu" else "• 长按播放器: 打开菜单"
    val helpTouchBottom: String get() = if (isEn) "• Touch bottom: Show controls" else "• 触摸底部: 显示控制栏"
    val helpTapToClose: String get() = if (isEn) "• Tap here to close" else "• 点击此处关闭提示"
    
    // ========== 错误提示 ==========
    val playbackError: String get() = if (isEn) "Playback Error" else "播放出错"
    val checkFileFormat: String get() = if (isEn) "Please check file format or reselect" else "请检查文件格式或重新选择"
    val unsupportedFormat: String get() = if (isEn) "Unsupported Format" else "不支持的格式"
    val supportedFormatsHint: String get() = if (isEn) "Supported: MP4, MKV, AVI, JPG, PNG, etc." else "支持: MP4, MKV, AVI, JPG, PNG 等"
    val videoPlayError: String get() = if (isEn) "Video playback error" else "视频播放错误"

    // ========== 权限 ==========
    /**
     * Android 13+ 通知权限被拒绝。
     * 属于降级提示：前台服务仍会运行，只是常驻通知不可见。
     */
    val notificationDenied: String get() = if (isEn) {
        "Notification permission denied: USB monitoring still runs, but its status notification is hidden"
    } else {
        "通知权限被拒绝：U盘监听仍在运行，但状态通知不会显示"
    }
    
    // ========== 设置页面 ==========
    val coreSettings: String get() = if (isEn) "Core Settings" else "核心设置"
    val basicSettings: String get() = if (isEn) "Basic Settings" else "基础设置"
    val playbackSettings: String get() = if (isEn) "Playback" else "播放设置"
    val videoSettings: String get() = if (isEn) "Video" else "视频设置"
    val audioSettings: String get() = if (isEn) "Audio Settings" else "音频设置"
    val imageSettings: String get() = if (isEn) "Image" else "图片设置"
    val usbSettings: String get() = if (isEn) "USB" else "U盘设置"
    val displaySettings: String get() = if (isEn) "Display" else "显示设置"
    val advancedSettings: String get() = if (isEn) "Advanced" else "高级设置"
    val about: String get() = if (isEn) "About" else "关于"
    
    // ========== 基础设置项 ==========
    val language: String get() = if (isEn) "Language" else "语言"
    val overwriteConfirm: String get() = if (isEn) "Overwrite Confirm" else "覆盖确认提示"
    val overwriteConfirmDesc: String get() = if (isEn) "Prompt before overwriting local content" else "插入U盘时是否提示覆盖本地内容"
    val autoPlayOnStart: String get() = if (isEn) "Auto Play on Start" else "启动后自动播放"
    val autoPlayOnStartDesc: String get() = if (isEn) "Auto play cached content when app starts" else "应用启动后自动播放本地缓存内容"
    val bootAutoStart: String get() = if (isEn) "Auto Start on Boot" else "开机自启动"
    val bootAutoStartDesc: String get() = if (isEn) "Auto start app when device boots" else "设备开机后自动启动本应用"
    val loopMode: String get() = if (isEn) "Loop Mode" else "循环模式"
    
    // ========== 视频设置项 ==========
    val defaultVolume: String get() = if (isEn) "Default Volume" else "默认音量"
    val defaultMuted: String get() = if (isEn) "Default Muted" else "默认静音"
    val defaultMutedDesc: String get() = if (isEn) "Mute video by default" else "启动时默认静音播放视频"
    val videoScaleMode: String get() = if (isEn) "Scale Mode" else "视频缩放模式"
    val hardwareDecode: String get() = if (isEn) "Hardware Decode" else "硬件解码"
    val hardwareDecodeDesc: String get() = if (isEn) "Use hardware acceleration (recommended)" else "使用硬件加速解码视频（推荐开启）"
    
    // ========== 音频设置项 ==========
    val audioOutput: String get() = if (isEn) "Audio Output" else "音频输出"
    val audioOutputDesc: String get() = if (isEn) "Select audio output device" else "选择音频输出设备"
    
    // ========== 图片设置项 ==========
    val slideInterval: String get() = if (isEn) "Slide Interval" else "轮播间隔"
    val transitionEffect: String get() = if (isEn) "Transition" else "切换动画"
    
    // ========== U盘设置项 ==========
    val usbDetection: String get() = if (isEn) "USB Detection" else "U盘检测"
    val usbDetectionDesc: String get() = if (isEn) "Auto detect USB and scan media" else "自动检测U盘插入并扫描媒体文件"
    val scanFolderName: String get() = if (isEn) "Scan Folder" else "扫描目录名"
    val scanFolderNameDesc: String get() = if (isEn) "Media folder name in USB" else "U盘中的媒体目录名称"
    val autoPlayAfterCopy: String get() = if (isEn) "Auto Play After Copy" else "拷贝后自动播放"
    val autoPlayAfterCopyDesc: String get() = if (isEn) "Auto play after copying from USB" else "从U盘拷贝完成后自动开始播放"
    val showCopyProgress: String get() = if (isEn) "Show Copy Progress" else "显示拷贝进度"
    val showCopyProgressDesc: String get() = if (isEn) "Show progress bar when copying" else "拷贝文件时显示进度条"
    val usbStructure: String get() = if (isEn) "USB folder structure:" else "U盘目录结构示例:"
    
    // ========== 存储设置项 ==========
    val storageSettings: String get() = if (isEn) "Storage" else "存储设置"
    val storageLocation: String get() = if (isEn) "Storage Location" else "存储位置"
    val storageLocationDesc: String get() = if (isEn) "Where to save media files" else "媒体文件保存位置"
    val storageInternal: String get() = if (isEn) "Internal (App Private)" else "内部存储 (应用私有)"
    val storageSdcard: String get() = if (isEn) "SD Card (Public)" else "SD卡/外部存储 (公共)"
    val currentStoragePath: String get() = if (isEn) "Current Path" else "当前路径"
    val availableSpace: String get() = if (isEn) "Available Space" else "可用空间"
    
    fun getStorageLocationText(location: com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation): String = when (location) {
        com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation.INTERNAL -> storageInternal
        com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation.SDCARD -> storageSdcard
    }
    
    // ========== 显示设置项 ==========
    val layoutMode: String get() = if (isEn) "Layout Mode" else "布局模式"
    val hdmiEfull: String get() = if (isEn) "HDMI Fullscreen" else "HDMI全屏"
    /** 底部控制栏：切换拼接板模式（写 sysfs 控制节点） */
    val switchMode: String get() = if (isEn) "Switch Mode" else "切换模式"
    /** 底部控制栏：切换分辨率（写 sysfs 控制节点） */
    val switchResolution: String get() = if (isEn) "Switch Resolution" else "切换分辨率"
    /** 图片裁剪入口 */
    val imageSplit: String get() = if (isEn) "Image Split" else "裁剪"
    /** 设置页「维护操作」区块标题 */
    val maintenance: String get() = if (isEn) "Maintenance" else "维护操作"
    val hdmiEfullDesc: String get() = if (isEn) {
        "Toggles persist.sys.rotation.efull-1 for HDMI rotation fullscreen"
    } else {
        "控制 persist.sys.rotation.efull-1（HDMI 旋转全屏）"
    }
    val hdmiEfullFailed: String get() = if (isEn) {
        "Failed to set HDMI fullscreen property"
    } else {
        "HDMI全屏属性设置失败"
    }
    /**
     * @deprecated 旧 GPIO 按键路径的文案，仅过渡期保留。
     * 新代码使用 [hdmiMatrixErrorText] 按 [com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind] 取文案。
     */
    @Deprecated("Use hdmiMatrixErrorText(kind) instead")
    val hdmiKeyFailed: String get() = if (isEn) {
        "Hardware key failed: no write permission to HDMI control node"
    } else {
        "硬件按键失败：无 HDMI 控制节点写入权限"
    }
    val showPlayerIndex: String get() = if (isEn) "Show Player Index" else "显示播放器编号"
    val showPlayerIndexDesc: String get() = if (isEn) "Show index badge on player" else "在播放器左上角显示编号标识"
    val keepScreenOn: String get() = if (isEn) "Keep Screen On" else "屏幕常亮"
    val keepScreenOnDesc: String get() = if (isEn) "Keep screen on during playback" else "播放时保持屏幕常亮不休眠"
    
    // ========== 高级设置项 ==========
    val maxCacheSize: String get() = if (isEn) "Max Cache Size" else "最大缓存大小"
    val debugLog: String get() = if (isEn) "Debug Log" else "调试日志"
    val debugLogDesc: String get() = if (isEn) "Enable detailed logging" else "启用详细日志输出（用于排查问题）"
    val clearCache: String get() = if (isEn) "Clear Cache" else "清除缓存"
    val resetSettings: String get() = if (isEn) "Reset" else "重置设置"
    
    // ========== 使用说明 ==========
    val usageTitle: String get() = if (isEn) "Usage Guide" else "使用说明"
    val usageSteps: String get() = if (isEn) {
        "1. Prepare USB, create media folder\n2. Create player1~4 folders in media\n3. Put videos/images in folders\n4. Insert USB, auto detect and copy\n5. Auto play after copy complete"
    } else {
        "1. 准备U盘，创建 media 目录\n2. 在 media 下创建 player1~4 文件夹\n3. 将视频/图片放入对应文件夹\n4. 插入U盘，自动检测并拷贝\n5. 拷贝完成后自动播放"
    }
    
    // ========== 关于 ==========
    val appName: String get() = if (isEn) "RK3568 HDMI Media Player" else "RK3568 HDMI 媒体播放器"
    val version: String get() = if (isEn) "Version" else "版本"
    val platform: String get() = if (isEn) "Platform" else "适用平台"
    val supportedFormats: String get() = if (isEn) "Supported Formats" else "支持格式"
    
    // ========== 枚举值显示文本 ==========
    fun getLoopModeText(mode: com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.SINGLE -> if (isEn) "Single Loop" else "单个循环"
        com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.LIST -> if (isEn) "List Loop" else "列表循环"
        com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode.RANDOM -> if (isEn) "Random" else "随机播放"
    }
    
    fun getScaleModeText(mode: com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.FIT -> if (isEn) "Fit" else "适应"
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.FILL -> if (isEn) "Fill (Crop)" else "填充 (裁剪)"
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.STRETCH -> if (isEn) "Stretch" else "拉伸"
        com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode.ORIGINAL -> if (isEn) "Original" else "原始大小"
    }
    
    fun getTransitionText(transition: com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition): String = when (transition) {
        com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition.FADE -> if (isEn) "Fade" else "淡入淡出"
        com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition.SLIDE -> if (isEn) "Slide" else "滑动"
        com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition.NONE -> if (isEn) "None" else "无动画"
    }
    
    fun getLayoutModeText(mode: com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.SINGLE -> if (isEn) "Single (Fullscreen)" else "单屏 (全屏)"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2 -> if (isEn) "Landscape 1×2" else "横屏 1×2"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X3 -> if (isEn) "Landscape 1×3" else "横屏 1×3"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2_PORTRAIT -> if (isEn) "Portrait 1×2" else "竖屏 1×2"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X3_PORTRAIT -> if (isEn) "Portrait 1×3" else "竖屏 1×3"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X1 -> if (isEn) "Landscape 2×1" else "横屏 2×1"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_3X1 -> if (isEn) "Landscape 3×1" else "横屏 3×1"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X2 -> if (isEn) "Landscape 2×2" else "横屏 2×2"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X2_SAME -> if (isEn) "2×2 Same Content" else "2×2 同画面"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.ROW_1X4 -> if (isEn) "1×4 Horizontal" else "1×4 横向四分"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.COLUMN_4X1 -> if (isEn) "4×1 Vertical" else "4×1 纵向四分"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.PIP -> if (isEn) "PIP (1+3)" else "画中画 (1大3小)"
    }

    fun getLayoutModeDescription(mode: com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode): String = when (mode) {
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.SINGLE ->
            if (isEn) "One player full screen" else "单一路径全屏播放"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2 ->
            if (isEn) "2 players side by side" else "2路左右并列"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X2_PORTRAIT ->
            if (isEn) "2 players side by side (portrait output)" else "2路左右并列（竖屏）"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X3 ->
            if (isEn) "3 players side by side" else "3路左右并列"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_1X3_PORTRAIT ->
            if (isEn) "3 players side by side (portrait output)" else "3路左右并列（竖屏）"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X1 ->
            if (isEn) "2 players stacked top to bottom" else "2路上下分屏"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_3X1 ->
            if (isEn) "3 players stacked top to bottom" else "3路上下分布"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X2 ->
            if (isEn) "4 players in 2×2 grid" else "4路2×2方阵"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.GRID_2X2_SAME ->
            if (isEn) "4 panes play player 1 content" else "四格都播 P1 同一路内容"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.ROW_1X4 ->
            if (isEn) "1×4 row split" else "1×4 横向四屏"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.COLUMN_4X1 ->
            if (isEn) "4×1 column split" else "4×1 纵向四屏"
        com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode.PIP ->
            if (isEn) "1 big player with 3 small thumbnails" else "1大画面+3小画面"
    }
    
    fun getLanguageText(lang: AppLanguage): String = when (lang) {
        AppLanguage.CHINESE -> "中文"
        AppLanguage.ENGLISH -> "English"
    }
    
    fun getAudioOutputText(output: com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput): String = when (output) {
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.AUTO -> if (isEn) "Auto" else "自动"
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.HDMI -> "HDMI"
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.SPEAKER -> if (isEn) "Speaker/3.5mm" else "扬声器/3.5mm"
        com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput.ALL -> if (isEn) "All Outputs" else "全部输出"
    }
    
    fun getPlayerStateText(state: com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState): String = when (state) {
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.IDLE -> stateIdle
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.LOADING -> stateLoading
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.PLAYING -> statePlaying
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.PAUSED -> statePaused
        com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState.ERROR -> stateError
    }

    // ========== 前台服务通知 ==========
    //
    // 通知文案不能走 R.string：Android 按**系统语言**解析资源，
    // 而本应用的语言是应用内设置（见 setLanguage）。
    // 「系统中文 + 应用内英文」时，R.string 会给出中文，与实际界面不一致。
    //
    // 期望的解法是 AppCompatDelegate/LocaleManager.setApplicationLocales，
    // 但：
    //   - LocaleManager 需要 API 33+，本机是 Android 11 (API 30)
    //   - AppCompatDelegate 需要 appcompat 依赖，本项目是纯 Compose，未引入
    // 因此改为直接从 StringResources 取文案，与界面语言保持一致。
    val notificationChannelName: String
        get() = if (isEn) "USB Monitor Service" else "U盘监听服务"
    val notificationTitle: String
        get() = if (isEn) "Media Player" else "媒体播放器"
    val notificationText: String
        get() = if (isEn) "Monitoring USB drive…" else "正在监听U盘…"
    val notificationChannelDescription: String
        get() = if (isEn) "Monitors USB drive insertion and removal"
        else "监听U盘插入和拔出"

    // ========== HDMI 拼接板（I2C）==========
    //
    // 控制器只产出 HdmiMatrixErrorKind，文案在这里统一翻译。
    // 这样控制器保持纯 JVM 可测，且中英文切换不需要重新发起任何 IO。

    val hdmiMatrix: String get() = if (isEn) "HDMI Matrix (I2C)" else "HDMI 拼接板（I2C）"
    val hdmiMatrixOnline: String get() = if (isEn) "Online" else "在线"
    val hdmiMatrixOffline: String get() = if (isEn) "Offline" else "离线"
    val hdmiMatrixBusy: String get() = if (isEn) "Busy" else "执行中"
    val hdmiMatrixOverflow: String get() = if (isEn) "Overflow" else "溢出"
    val hdmiMatrixErrorFlag: String get() = if (isEn) "Error" else "错误"
    val hdmiMatrixPower: String get() = if (isEn) "Power" else "电源"
    val hdmiMatrixMode: String get() = if (isEn) "Mode" else "模式"
    val hdmiMatrixResolution: String get() = if (isEn) "Resolution" else "分辨率"
    val hdmiMatrixMirror: String get() = if (isEn) "Mirror" else "镜像"
    val hdmiMatrixRotate: String get() = if (isEn) "Rotate" else "旋转"
    val hdmiMatrixFusion: String get() = if (isEn) "Edge fusion" else "融合带"
    val hdmiMatrixHorizontal: String get() = if (isEn) "Horizontal" else "水平"
    val hdmiMatrixVertical: String get() = if (isEn) "Vertical" else "垂直"
    val hdmiMatrixMute: String get() = if (isEn) "Mute" else "静音"
    val hdmiMatrixApply: String get() = if (isEn) "Apply" else "应用"
    val hdmiMatrixRefresh: String get() = if (isEn) "Refresh status" else "刷新状态"
    val hdmiMatrixVersion: String get() = if (isEn) "Version" else "版本"
    val hdmiMatrixCmdCount: String get() = if (isEn) "Commands" else "命令数"
    val hdmiMatrixAppliedMode: String get() = if (isEn) "Applied mode" else "已生效模式"
    /** 驱动未加载 / DTS 未配置（ENOENT）——**软件**问题。 */
    val hdmiMatrixUnavailable: String get() = if (isEn) {
        "HDMI matrix unavailable (driver/node missing)"
    } else {
        "HDMI 拼接板不可用（驱动节点缺失）"
    }

    /**
     * 节点存在但从机不应答（ENODEV）——**硬件**问题。
     *
     * 与上一条分开：现场看到「节点缺失」会去查固件/DTS，
     * 看到「矩阵未应答」才会去查线缆、供电与电平。
     */
    val hdmiMatrixNoDevice: String get() = if (isEn) {
        "HDMI matrix not responding (check cable / power)"
    } else {
        "HDMI 拼接板无应答（请检查连接与供电）"
    }
    val hdmiMatrixNoHardwareMode: String get() = if (isEn) {
        "This layout is software-only; no hardware mode to apply"
    } else {
        "该布局为纯软件布局，无需下发硬件模式"
    }
    val hdmiMatrixFusionHint: String get() = if (isEn) {
        "Release the slider to write. Each write stores to MCU flash, so it is rate-limited."
    } else {
        "松开滑杆后下发。每次写入都会写 MCU flash，因此有最小间隔限制。"
    }

    /** 按错误种类取用户可见文案；控制器从不构造这些文本。 */
    fun hdmiMatrixErrorText(
        kind: com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind
    ): String = when (kind) {
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.NOT_AVAILABLE ->
            if (isEn) "HDMI node missing (driver not loaded)"
            else "HDMI 节点缺失（驱动未加载）"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.PERMISSION_DENIED ->
            if (isEn) "Permission denied after 3 attempts"
            else "权限不足（已重试 3 次）"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.DEVICE_STANDBY ->
            if (isEn) "Device in standby; power on first"
            else "设备处于待机，请先上电"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.INVALID_VALUE ->
            if (isEn) "Invalid value (fix the value; retrying will not help)"
            else "参数非法（请修改参数，重试无效）"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE ->
            if (isEn) "Not supported in the current mode"
            else "当前模式不支持该操作"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.TIMEOUT ->
            if (isEn) "Timed out talking to the MCU"
            else "与 MCU 通信超时"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.IO_FAILURE ->
            if (isEn) "I/O failure on the HDMI node"
            else "HDMI 节点读写失败"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.BUSY ->
            if (isEn) "MCU is busy"
            else "MCU 忙"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.NO_HARDWARE_MODE ->
            hdmiMatrixNoHardwareMode
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.THROTTLED ->
            if (isEn) "Too frequent; use the slider then release"
            else "操作过于频繁，请松开滑杆后再试"
        com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.UNKNOWN ->
            if (isEn) "Unknown HDMI error"
            else "未知 HDMI 错误"
    }

    /** 带 errno 的详细文案，便于现场日志/贴图排查。 */
    fun hdmiMatrixErrorDetail(
        error: com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixError
    ): String {
        val base = hdmiMatrixErrorText(error.kind)
        val errno = error.errno
        val node = error.node?.relativePath
        return when {
            errno != null && node != null -> "$base (errno=$errno, $node)"
            errno != null -> "$base (errno=$errno)"
            node != null -> "$base ($node)"
            else -> base
        }
    }
}
