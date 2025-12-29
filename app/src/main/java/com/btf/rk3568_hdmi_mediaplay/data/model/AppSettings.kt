package com.btf.rk3568_hdmi_mediaplay.data.model

/**
 * 视频缩放模式
 */
enum class VideoScaleMode {
    FIT,        // 适应 (保持比例)
    FILL,       // 填充 (裁剪)
    STRETCH,    // 拉伸
    ORIGINAL    // 原始大小
}

/**
 * 图片切换动画
 */
enum class ImageTransition {
    FADE,       // 淡入淡出
    SLIDE,      // 滑动
    NONE        // 无动画
}

/**
 * 循环播放模式
 */
enum class LoopMode {
    SINGLE,     // 单个循环
    LIST,       // 列表循环
    RANDOM      // 随机播放
}

/**
 * 布局模式
 */
enum class LayoutMode {
    SINGLE,     // 单屏 (1个播放器全屏)
    GRID_1X2,   // 1行2列
    GRID_2X1,   // 2行1列
    GRID_2X2,   // 2x2 网格
    GRID_1X3,   // 1行3列
    GRID_3X1,   // 3行1列
    ROW_1X4,    // 1行4列
    COLUMN_4X1, // 4行1列
    PIP         // 画中画 (1大3小)
}

/**
 * 语言设置
 */
enum class AppLanguage {
    CHINESE,    // 中文
    ENGLISH     // English
}

/**
 * 音频输出设置
 */
enum class AudioOutput {
    AUTO,       // 自动
    HDMI,       // HDMI 输出
    SPEAKER,    // 扬声器/3.5mm
    ALL         // 全部输出
}

/**
 * 应用设置
 */
data class AppSettings(
    // 基础设置
    val showOverwriteConfirm: Boolean = true,       // 覆盖确认提示
    val autoPlayOnStart: Boolean = true,            // 启动后自动播放
    val bootAutoStart: Boolean = false,             // 开机自启动
    val loopMode: LoopMode = LoopMode.LIST,         // 循环模式
    val language: AppLanguage = AppLanguage.CHINESE, // 语言设置
    
    // 视频设置
    val defaultVolume: Int = 100,                   // 默认音量 0-100
    val defaultMuted: Boolean = false,              // 默认静音
    val videoScaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val useHardwareDecode: Boolean = true,          // 硬件解码
    
    // 音频设置
    val audioOutput: AudioOutput = AudioOutput.AUTO, // 音频输出
    
    // 图片设置
    val imageIntervalSeconds: Int = 5,              // 图片轮播间隔(秒)
    val imageTransition: ImageTransition = ImageTransition.FADE,
    
    // U盘设置
    val usbDetectionEnabled: Boolean = true,        // U盘检测开关
    val usbScanFolderName: String = "media",        // U盘扫描目录名
    val autoPlayAfterCopy: Boolean = true,          // 拷贝完成后自动播放
    val showCopyProgress: Boolean = true,           // 显示拷贝进度
    
    // 显示设置
    val layoutMode: LayoutMode = LayoutMode.GRID_2X2,
    val showPlayerIndex: Boolean = true,            // 显示播放器编号
    val keepScreenOn: Boolean = true,               // 屏幕常亮
    val backgroundColor: Long = 0xFF000000,         // 背景颜色 (黑色)
    
    // 高级设置
    val maxCacheSizeMB: Int = 2048,                 // 最大缓存大小 MB
    val enableDebugLog: Boolean = false             // 调试日志
)
