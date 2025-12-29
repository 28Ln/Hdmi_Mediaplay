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
@Composable
fun VideoPlayerView(
    mediaPath: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    volume: Float = 1f,
    isMuted: Boolean = false,
    isLooping: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    onPlayerReady: ((ExoPlayer) -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null,
    onError: ((Exception) -> Unit)? = null
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
    
    // 创建低内存配置的 ExoPlayer
    val exoPlayer = remember(context) {
        try {
            createLowMemoryExoPlayer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ExoPlayer", e)
            null
        }
    }
    
    if (exoPlayer == null) {
        ErrorPlaceholder("播放器初始化失败", modifier)
        return
    }
    
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
                Log.e(TAG, "Player error: ${error.message}", error)
                isLoading = false
                hasError = true
                errorMessage = getErrorMessage(error)
                try {
                    onError?.invoke(Exception(errorMessage))
                } catch (e: Exception) {
                    Log.e(TAG, "onError callback error", e)
                }
            }
        }
        
        exoPlayer.addListener(listener)
        
        onDispose {
            try {
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
 * 针对4路同时播放优化
 */
private fun createLowMemoryExoPlayer(context: android.content.Context): ExoPlayer {
    // 低内存缓冲配置
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            2000,   // minBufferMs - 最小缓冲 2秒
            5000,   // maxBufferMs - 最大缓冲 5秒 (默认50秒太大)
            1000,   // bufferForPlaybackMs - 开始播放需要 1秒
            2000    // bufferForPlaybackAfterRebufferMs - 重新缓冲后需要 2秒
        )
        .setTargetBufferBytes(C.LENGTH_UNSET) // 不限制字节，用时间控制
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    
    // 轨道选择器 - 限制视频分辨率
    val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setMaxVideoSizeSd() // 限制最大 SD 分辨率 (720p)
                .setForceLowestBitrate(false)
        )
    }
    
    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .setHandleAudioBecomingNoisy(true)
        .build()
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
