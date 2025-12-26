package com.btf.rk3568_hdmi_mediaplay.data.model

/**
 * 媒体类型枚举
 */
enum class MediaType {
    VIDEO,
    IMAGE,
    UNKNOWN
}

/**
 * 媒体来源枚举
 */
enum class MediaSource {
    LOCAL,      // 本地缓存
    USB,        // U盘
    MANUAL      // 手动选择
}

/**
 * 媒体文件信息
 */
data class MediaItem(
    val path: String,               // 文件路径
    val name: String,               // 文件名
    val type: MediaType,            // 媒体类型
    val source: MediaSource,        // 来源
    val size: Long = 0,             // 文件大小
    val lastModified: Long = 0      // 最后修改时间
)

/**
 * 播放器状态
 */
enum class PlayerState {
    IDLE,       // 空闲
    LOADING,    // 加载中
    PLAYING,    // 播放中
    PAUSED,     // 暂停
    ERROR       // 错误
}

/**
 * 播放器配置
 */
data class PlayerConfig(
    val index: Int,                         // 播放器索引 (0-3)
    val mediaItems: List<MediaItem> = emptyList(),  // 媒体文件列表
    val currentIndex: Int = 0,              // 当前播放索引
    val state: PlayerState = PlayerState.IDLE,
    val volume: Float = 1.0f,               // 音量 0-1
    val isMuted: Boolean = false,           // 是否静音
    val isLooping: Boolean = true           // 是否循环
)
