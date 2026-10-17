package com.btf.rk3568_hdmi_mediaplay.ui.player

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import java.io.File

private const val TAG = "VideoPlayerView"

/**
 * 视频播放器组件
 * 优化: 内存管理、低内存缓冲配置
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoPlayerView(
    mediaPath: String,
    modifier: Modifier = Modifier,
    /** 所在格的下标（0..3），用于「同画面」模式的多路同步 */
    cellIndex: Int = -1,
    isPlaying: Boolean = true,
    volume: Float = 1f,
    isMuted: Boolean = false,
    isLooping: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    /** 音频轨不受支持时，丢弃音频继续静音播视频（见 AppSettings.audioFallbackSilent） */
    audioFallbackSilent: Boolean = true,
    onPlayerReady: ((ExoPlayer) -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null,
    onError: ((Exception) -> Unit)? = null,
    /** 播放从出错恢复正常时回调，用于让上层清除 ERROR 状态 */
    onRecovered: (() -> Unit)? = null,
    /**
     * 跟随模式：本格只作为主格（P1）的镜像，播放进度由 P1 驱动。
     *
     * 四格是**四个独立 ExoPlayer**（同一个 player 无法渲染到多个 Surface，
     * 这是 Media3 的限制），因此无法共享实例。但各自播放必然漂移：
     * 启动有先后、缓冲速度不同、设备热降频后解码速率还会变化。
     *
     * 为 true 时，本格就绪后会立即 seek 到 P1 的当前进度，并在播放期间
     * 持续跟随（由 [PlaybackSync] 的轮询兜底）。
     */
    followsPrimary: Boolean = false
) {
    // 空路径检查
    if (mediaPath.isBlank()) {
        EmptyVideoPlaceholder(modifier)
        return
    }
    
    // 文件存在性检查
    if (!mediaPath.startsWith("http") && !mediaPath.startsWith("content://")) {
        val file = remember(mediaPath) { File(mediaPath) }
        if (!file.exists()) {
            ErrorPlaceholder("文件不存在", modifier)
            LaunchedEffect(mediaPath) {
                onError?.invoke(Exception("文件不存在: $mediaPath"))
            }
            return
        }
    }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // 创建低内存配置的 ExoPlayer - 使用 mediaPath 作为 key，确保路径变化时重建播放器
    val playerPair = remember(mediaPath) {
        try {
            Log.d(TAG, "Creating new ExoPlayer for: $mediaPath")
            createLowMemoryExoPlayer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ExoPlayer", e)
            null
        }
    }
    val exoPlayer = playerPair?.first
    val trackSelector = playerPair?.second

    if (exoPlayer == null) {
        ErrorPlaceholder("播放器初始化失败", modifier)
        // 必须上报：否则 MediaPlayerView → MainViewModel.setPlayerError 链路不会触发，
        // ViewModel 的 state 会一直停在 PLAYING，角标显示绿色、远控也回 "playing"，
        // 与实际"根本没能创建播放器"矛盾。
        LaunchedEffect(mediaPath) {
            try {
                onError?.invoke(Exception("播放器初始化失败"))
            } catch (e: Exception) {
                Log.e(TAG, "onError callback error", e)
            }
        }
        return
    }

    // 音频轨被禁用的标记：音频直通失败后置 true，用于抑制重复报错
    var audioDisabled by remember(mediaPath) { mutableStateOf(false) }

    // 是否已把错误上报给上层：用于在恢复时只上报一次 onRecovered
    var reportedError by remember(mediaPath) { mutableStateOf(false) }
    
    // 设置媒体源
    LaunchedEffect(mediaPath) {
        try {
            isLoading = true
            hasError = false
            errorMessage = ""

            exoPlayer.stop()
            exoPlayer.clearMediaItems()

            val mediaItem = MediaItem.fromUri(mediaPath)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()

            Log.d(TAG, "Media prepared: $mediaPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare media: $mediaPath", e)
            hasError = true
            errorMessage = e.message ?: "加载失败"
            onError?.invoke(e)
        }
    }

    // 「同画面」模式的跟随格：媒体源换好后**对齐一次**到主格进度。
    //
    // 受 PlaybackSync.ENABLED_BY_DEFAULT 控制，默认关闭 ——
    // 对齐会触发 seek 与重新缓冲，实测表现为画面反复转圈。
    LaunchedEffect(mediaPath, followsPrimary) {
        if (!followsPrimary || cellIndex < 0) return@LaunchedEffect
        if (!PlaybackSync.ENABLED_BY_DEFAULT) return@LaunchedEffect
        try {
            val deadline = System.currentTimeMillis() + 8_000L
            while (exoPlayer.playbackState != ExoPlayer.STATE_READY &&
                System.currentTimeMillis() < deadline
            ) {
                delay(50)
            }
            if (exoPlayer.playbackState == ExoPlayer.STATE_READY) {
                if (PlaybackSync.alignToPrimary(cellIndex)) {
                    Log.d(TAG, "P${cellIndex + 1} 已对齐到主格进度")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "对齐主格失败: ${e.message}")
        }
    }

    // 每次重组都刷新注册表。
    //
    // 用 SideEffect 而不是只在 STATE_READY 注册：切换布局时播放器早已 READY，
    // 该回调不会再触发，P2–P4 会永远注册不上（实测日志「syncOnce: P2 未注册」）。
    // 这里只是往 ConcurrentHashMap 写一次，开销与一次属性读取同级。
    SideEffect {
        if (cellIndex >= 0) {
            PlaybackSync.register(cellIndex, exoPlayer)
        }
    }

    // 更新播放状态
    LaunchedEffect(isPlaying) {
        try {
            exoPlayer.playWhenReady = isPlaying
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set playWhenReady", e)
        }
    }
    
    // 更新音量
    LaunchedEffect(volume, isMuted) {
        try {
            exoPlayer.volume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }
    
    // 更新循环模式
    LaunchedEffect(isLooping) {
        try {
            exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeat mode", e)
        }
    }
    
    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        var wasPlaying = false
        
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        wasPlaying = exoPlayer.isPlaying
                        exoPlayer.pause()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (wasPlaying && isPlaying) {
                            exoPlayer.play()
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        exoPlayer.pause()
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lifecycle event error", e)
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 播放器事件监听和释放
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        isLoading = false
                        hasError = false
                        // 注：不在此处注册到同步器。切换布局时播放器早已 READY，
                        // 该回调不会再触发，注册会漏；统一由下方 SideEffect 负责。
                        // 播放已恢复正常：通知上层把 ERROR 状态清掉。
                        // 否则 MainViewModel 的 state 会永久停在 ERROR（其状态机没有其他出口），
                        // 导致格子即使已经能播也仍显示报错、远控一直上报 error。
                        if (reportedError) {
                            reportedError = false
                            try {
                                onRecovered?.invoke()
                            } catch (e: Exception) {
                                Log.e(TAG, "onRecovered callback error", e)
                            }
                        }
                        try {
                            onPlayerReady?.invoke(exoPlayer)
                        } catch (e: Exception) {
                            Log.e(TAG, "onPlayerReady callback error", e)
                        }
                    }
                    Player.STATE_ENDED -> {
                        try {
                            onPlaybackEnded?.invoke()
                        } catch (e: Exception) {
                            Log.e(TAG, "onPlaybackEnded callback error", e)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        isLoading = true
                    }
                    Player.STATE_IDLE -> {}
                }
            }
            
            override fun onPlayerError(error: PlaybackException) {
                // 音频轨建不起来（如设备 AC3 直通未实现）时，视频轨其实正常。
                // 此时禁用音频轨重新 prepare，改为静音播视频——
                // 拼接墙场景下黑屏比无声严重，不应因音频问题丢掉画面。
                if (audioFallbackSilent && !audioDisabled && trackSelector != null && isAudioTrackFailure(error)) {
                    Log.w(TAG, "Audio track failure, retrying without audio: ${error.message}")
                    audioDisabled = true
                    try {
                        trackSelector.parameters = trackSelector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                        exoPlayer.stop()
                        exoPlayer.setMediaItem(MediaItem.fromUri(mediaPath))
                        exoPlayer.prepare()
                        isLoading = true
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to recover from audio error", e)
                    }
                }

                Log.e(TAG, "Player error: ${error.message}", error)
                isLoading = false
                hasError = true
                reportedError = true
                errorMessage = getErrorMessage(error)
                try {
                    onError?.invoke(Exception(errorMessage))
                } catch (e: Exception) {
                    Log.e(TAG, "onError callback error", e)
                }
            }
        }
        
        exoPlayer.addListener(listener)

        // 注册统一由 SideEffect 负责（见上方），此处不再重复。

        onDispose {
            try {
                // 只在"当前注册的确实是本实例"时才注销。
                // 否则旧实例销毁时会把新实例的注册一起清掉：
                //   SideEffect(重组) → register(i, newPlayer)
                //   onDispose(旧实例) → register(i, null)   ← 误删新的
                if (cellIndex >= 0 && PlaybackSync.isRegistered(cellIndex, exoPlayer)) {
                    PlaybackSync.register(cellIndex, null)
                }
                exoPlayer.removeListener(listener)
                exoPlayer.stop()
                exoPlayer.release()
                Log.d(TAG, "Player released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player", e)
            }
        }
    }
    
    // UI
    Box(modifier = modifier.background(Color.Black)) {
        if (hasError) {
            ErrorPlaceholder(errorMessage, Modifier.fillMaxSize())
        } else {
            val resizeMode = remember(scaleMode) { getResizeMode(scaleMode) }
            
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.resizeMode = resizeMode
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { playerView ->
                    playerView.resizeMode = resizeMode
                    if (playerView.player != exoPlayer) {
                        playerView.player = exoPlayer
                    }
                }
            )
            
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }
}

/**
 * 创建低内存配置的 ExoPlayer
 * 针对4路同时播放优化缓冲区，不限制分辨率
 *
 * 同时返回 trackSelector：音频直通失败时需要靠它禁用音频轨（见 [isAudioTrackFailure]）。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun createLowMemoryExoPlayer(
    context: android.content.Context
): Pair<ExoPlayer, DefaultTrackSelector> {
    // 低内存缓冲配置（值见 PlaybackConfig）
    // 规则: minBufferMs >= bufferForPlaybackAfterRebufferMs
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            PlaybackConfig.BUFFER_MIN_MS,
            PlaybackConfig.BUFFER_MAX_MS,
            PlaybackConfig.BUFFER_FOR_PLAYBACK_MS,
            PlaybackConfig.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        // 固定缓冲字节上限：原先不限，缓冲量随分辨率放大，
        // 4 路高码率并播时是内存的主要压力源
        .setTargetBufferBytes(PlaybackConfig.TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    // 轨道选择器：限制最大分辨率/码率/帧率。
    // 本机输出 1024×600、4 路分屏，4K 源无收益且有 OOM 与解码器耗尽风险。
    // 上限值见 PlaybackConfig，可按客户素材调整。
    val trackSelector = DefaultTrackSelector(
        context,
        DefaultTrackSelector.Parameters.Builder(context)
            .setMaxVideoSize(PlaybackConfig.MAX_VIDEO_WIDTH, PlaybackConfig.MAX_VIDEO_HEIGHT)
            .setMaxVideoBitrate(PlaybackConfig.MAX_VIDEO_BITRATE)
            .setMaxVideoFrameRate(PlaybackConfig.MAX_VIDEO_FRAME_RATE)
            .build()
    )

    val player = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .setHandleAudioBecomingNoisy(true)
        .build()

    return player to trackSelector
}

/**
 * 判断播放错误是否属于「音频轨无法建立」。
 *
 * 现场典型场景：MP4 带 AC3 音轨，设备 ROM 声明支持 AC3 直通
 * （`format_supported=YES`），但音频 HAL 实际建不出该输出流：
 *
 * ```
 * AudioFlinger: SpdifStreamOut::open() status = -61
 * AudioTrack: createTrack_l(0): AudioFlinger could not create track, status: -38
 * MediaCodecAudioRenderer: Caused by: UnsupportedOperationException: Cannot create AudioTrack
 * ```
 *
 * 此时视频轨本身正常，不应因音频问题导致整格黑屏——
 * 由调用方禁用音频轨后重新 prepare，改为静音播视频。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun isAudioTrackFailure(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
    ) {
        return true
    }

    // 兜底：部分 ROM 不填 errorCode，改为检查 cause 链
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 8) {
        val name = cause::class.java.name
        val msg = cause.message.orEmpty()
        if (cause is androidx.media3.exoplayer.audio.AudioSink.InitializationException ||
            name.contains("AudioSink") ||
            name.contains("MediaCodecAudioRenderer") ||
            msg.contains("Cannot create AudioTrack")
        ) {
            return true
        }
        cause = cause.cause
        depth++
    }
    return false
}

private fun getErrorMessage(error: PlaybackException): String {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件未找到"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络超时"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的格式"
        else -> error.message ?: "播放错误 (${error.errorCode})"
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun getResizeMode(scaleMode: VideoScaleMode): Int {
    return when (scaleMode) {
        VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        VideoScaleMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
}

@Composable
private fun EmptyVideoPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "无视频", color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun ErrorPlaceholder(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "❌ $message", color = Color.Red, fontSize = 12.sp)
    }
}
