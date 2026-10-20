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
 * 物理屏幕方向
 */
enum class PanelOrientation {
    LANDSCAPE,
    PORTRAIT
}

/**
 * 播放器布局模式。
 *
 * ## ⚠️ wallModeNumber 是「示意图编号」，不是硬件模式
 *
 * `wallModeNumber` 对应 HDMI 拼接板**示意图**上的模式 1~7（客户设置页按它排序、
 * 远控 `WALL_MODE_n` 也按它寻址），其中 **`GRID_2X1` 的示意图编号是 5**。
 *
 * 但 I2C 控制器的硬件寄存器值是另一套：
 * `0=1X1, 1=1X2, 2=2X1, 3=1X3, 4=3X1, 5=2X2, 6=1X4, 7=4X1`。
 * 也就是说 **2×1 的硬件值是 2**，直接把 `wallModeNumber=5` 写进 `mode`
 * 会把画面切成 2×2（而且不会报错）。
 *
 * 硬件映射的唯一来源是 [com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixModeMapping]，
 * 本字段**永远不能**直接下发给硬件。
 *
 * 非空项目与示意图模式 1~7 一一对应；其余项目仅为旧配置和远程控制兼容保留，
 * 不在客户设置页面中显示。
 */
enum class LayoutMode(
    val rows: Int,
    val columns: Int,
    val panelOrientation: PanelOrientation,
    val wallModeNumber: Int? = null
) {
    SINGLE(1, 1, PanelOrientation.LANDSCAPE),
    GRID_1X2(1, 2, PanelOrientation.LANDSCAPE, 1),
    GRID_2X1(2, 1, PanelOrientation.LANDSCAPE, 5),
    GRID_2X2(2, 2, PanelOrientation.LANDSCAPE, 7),
    GRID_1X3(1, 3, PanelOrientation.LANDSCAPE, 2),
    GRID_3X1(3, 1, PanelOrientation.LANDSCAPE, 6),
    GRID_1X2_PORTRAIT(1, 2, PanelOrientation.PORTRAIT, 3),
    GRID_1X3_PORTRAIT(1, 3, PanelOrientation.PORTRAIT, 4),
    GRID_2X2_SAME(2, 2, PanelOrientation.LANDSCAPE),
    ROW_1X4(1, 4, PanelOrientation.LANDSCAPE),
    COLUMN_4X1(4, 1, PanelOrientation.LANDSCAPE),
    PIP(2, 2, PanelOrientation.LANDSCAPE);

    val visiblePlayerCount: Int
        get() = when (this) {
            SINGLE -> 1
            PIP -> 4
            else -> (rows * columns).coerceAtMost(4)
        }

    /**
     * 是否为竖屏拼接墙模式。
     * 竖屏模式（GRID_1X2_PORTRAIT / GRID_1X3_PORTRAIT）下，
     * 由 MainActivity 请求系统旋转，使输出画面与面板朝向一致。
     */
    val isPortraitWall: Boolean
        get() = panelOrientation == PanelOrientation.PORTRAIT

    /**
     * 四格都播同一路内容（P1 / player1）。用于 2×2 同画面，不占用拼接板编号。
     */
    val mirrorsPrimaryPlayer: Boolean
        get() = this == GRID_2X2_SAME

    fun sourcePlayerIndex(cellIndex: Int): Int =
        if (mirrorsPrimaryPlayer) 0 else cellIndex

    companion object {
        /**
         * 客户设置页面只展示拼接板实际支持的 7 种模式，并按示意图编号排序。
         */
        val wallModes: List<LayoutMode> =
            entries.filter { it.wallModeNumber != null }.sortedBy { it.wallModeNumber }

        /**
         * 软件附加模式，不对应拼接板按键编号。
         */
        val softwareModes: List<LayoutMode> = listOf(GRID_2X2_SAME)

        val customerModes: List<LayoutMode> = wallModes + softwareModes
    }
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
 * 存储位置
 */
enum class StorageLocation {
    INTERNAL,   // 内部存储 (应用私有目录)
    SDCARD      // SD卡/外部存储 (公共目录)
}

/**
 * 应用设置
 */
data class AppSettings(
    // 基础设置
    val showOverwriteConfirm: Boolean = true,       // 覆盖确认提示
    val autoPlayOnStart: Boolean = true,            // 启动后自动播放
    /**
     * 开机自启动。
     *
     * **默认 true**（2026-10-20 调整）：本应用定位是 kiosk/信发终端，
     * 「开机即播放」是交付的基本要求；默认关闭会让客户现场每次都要人工点开。
     * 仍可在设置页 / U 盘配置里关掉。
     */
    val bootAutoStart: Boolean = true,
    val loopMode: LoopMode = LoopMode.LIST,         // 循环模式
    val language: AppLanguage = AppLanguage.CHINESE, // 语言设置
    
    // 视频设置
    val defaultVolume: Int = 100,                   // 默认音量 0-100
    val defaultMuted: Boolean = false,              // 默认静音
    val videoScaleMode: VideoScaleMode = VideoScaleMode.STRETCH,  // 默认拉伸填满
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
    
    // 存储设置
    val storageLocation: StorageLocation = StorageLocation.SDCARD, // 存储位置
    val customStoragePath: String = "",             // 自定义存储路径 (为空则使用默认)
    
    // 显示设置
    val layoutMode: LayoutMode = LayoutMode.GRID_2X2,
    val showPlayerIndex: Boolean = true,            // 显示播放器编号
    val keepScreenOn: Boolean = true,               // 屏幕常亮
    val backgroundColor: Long = 0xFF000000,         // 背景颜色 (黑色)
    
    // 高级设置
    val maxCacheSizeMB: Int = 2048,                 // 最大缓存大小 MB
    val enableDebugLog: Boolean = false,            // 调试日志

    /**
     * 音频轨不受支持时，丢弃音频继续静音播视频。
     *
     * 现场场景：MP4 带 AC3 音轨，设备 ROM 声明支持 AC3 直通但实际建不出输出流，
     * 导致整个播放器报错、该格黑屏。开启后改为静音播视频——
     * 拼接墙场景下黑屏比无声严重。
     *
     * 关闭则维持原行为（整格报错），便于在"必须有声"的场合暴露问题。
     */
    val audioFallbackSilent: Boolean = true
)
