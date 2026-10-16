package com.btf.rk3568_hdmi_mediaplay.ui.player

/**
 * 播放器配置（集中管理，避免散落在播放器构建代码里硬编码）。
 *
 * 这些值与设备的解码能力相关，换硬件方案时只需改这一个文件。
 */
object PlaybackConfig {

    // ============ 缓冲 ============
    /** 最小缓冲（毫秒）。规则：minBufferMs >= bufferForPlaybackAfterRebufferMs */
    const val BUFFER_MIN_MS = 5_000

    /** 最大缓冲（毫秒）。默认 50 秒对 4 路并发过大，压到 15 秒。 */
    const val BUFFER_MAX_MS = 15_000

    /** 开始播放所需缓冲（毫秒） */
    const val BUFFER_FOR_PLAYBACK_MS = 1_500

    /** 重新缓冲后所需缓冲（毫秒） */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    /**
     * 缓冲字节上限。
     *
     * 原实现用 `C.LENGTH_UNSET`（不限），缓冲量会随分辨率自动放大 ——
     * 4 路高码率同时播放时这是内存的主要压力来源。此处固定上限。
     */
    const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024  // 32 MB

    // ============ 轨道选择上限 ============
    /**
     * 视频轨最大分辨率。
     *
     * 本机输出为 1024×600，4 路分屏时单格更小，无需 4K 源；
     * 且 4 路 4K 同时解码有 OOM 与解码器耗尽风险。
     * 限制后 ExoPlayer 会优先选择不超过该尺寸的轨道。
     *
     * 若客户确需 4K 单路播放，可上调此值（或改为按布局动态计算）。
     */
    const val MAX_VIDEO_WIDTH = 1920
    const val MAX_VIDEO_HEIGHT = 1080

    /** 视频轨最大码率（bps）。限制异常高码率素材造成的内存/带宽压力。 */
    const val MAX_VIDEO_BITRATE = 8_000_000  // 8 Mbps

    /** 视频轨最大帧率（fps）。注意 setMaxVideoFrameRate 接受 Int。 */
    const val MAX_VIDEO_FRAME_RATE = 60
}
