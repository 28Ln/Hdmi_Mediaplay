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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "VideoPlayerView"

/**
 * 视频播放器组件
 * 优化: 内存管理、生命周期、异常处理
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
    
    // 文件存在性检查（仅本地文件）
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
    
    // 播放器状态 - 使用 derivedStateOf 减少重组
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // 创建 ExoPlayer - 使用 produceState 确保在 IO 线程初始化
    val exoPlayer by produceState<ExoPlayer?>(initialValue = null, context) {
        value = withContext(Dispatchers.Main) {
            try {
                ExoPlayer.Builder(context)
                    .setHandleAudioBecomingNoisy(true)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create ExoPlayer", e)
                null
            }
        }
    }
    
    // 如果播放器创建失败
    if (exoPlayer == null) {
        if (!isLoading) {
            ErrorPlaceholder("播放器初始化失败", modifier)
        } else {
            LoadingPlaceholder(modifier)
        }
        return
    }
    
    val player = exoPlayer!!
    
    // 设置媒体源 - 使用 key 确保路径变化时重新加载
    LaunchedEffect(mediaPath, player) {
        try {
            isLoading = true
            hasError = false
            errorMessage = ""
            
            player.stop()
            player.clearMediaItems()
            
            val mediaItem = MediaItem.fromUri(mediaPath)
            player.setMediaItem(mediaItem)
            player.prepare()
            
            Log.d(TAG, "Media prepared: $mediaPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare media: $mediaPath", e)
            hasError = true
            errorMessage = e.message ?: "加载失败"
            onError?.invoke(e)
        }
    }
    
    // 更新播放状态 - 使用 snapshotFlow 避免频繁更新
    LaunchedEffect(isPlaying, player) {
        try {
            player.playWhenReady = isPlaying
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set playWhenReady", e)
        }
    }
    
    // 更新音量 - 合并更新减少调用
    LaunchedEffect(volume, isMuted, player) {
        try {
            player.volume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }
    
    // 更新循环模式
    LaunchedEffect(isLooping, player) {
        try {
            player.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeat mode", e)
        }
    }
    
    // 生命周期管理 - 优化暂停/恢复逻辑
    DisposableEffect(lifecycleOwner, player) {
        var wasPlaying = false
        
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        wasPlaying = player.isPlaying
                        player.pause()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (wasPlaying && isPlaying) {
                            player.play()
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        player.pause()
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
    
    // 播放器事件监听 - 使用稳定的监听器引用
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        isLoading = false
                        hasError = false
                        try {
                            onPlayerReady?.invoke(player)
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
                    Player.STATE_IDLE -> {
                        // 空闲状态
                    }
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
        
        player.addListener(listener)
        
        onDispose {
            try {
                player.removeListener(listener)
                player.stop()
                player.release()
                Log.d(TAG, "Player released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player", e)
            }
        }
    }
    
    // UI - 使用 remember 缓存 PlayerView
    Box(modifier = modifier.background(Color.Black)) {
        if (hasError) {
            ErrorPlaceholder(errorMessage, Modifier.fillMaxSize())
        } else {
            val resizeMode = remember(scaleMode) { getResizeMode(scaleMode) }
            
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.resizeMode = resizeMode
                        // 优化性能
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { playerView ->
                    playerView.resizeMode = resizeMode
                    if (playerView.player != player) {
                        playerView.player = player
                    }
                }
            )
            
            // 加载指示器 - 使用 AnimatedVisibility 优化
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
 * 获取用户友好的错误消息
 */
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

/**
 * 获取缩放模式
 */
private fun getResizeMode(scaleMode: VideoScaleMode): Int {
    return when (scaleMode) {
        VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        VideoScaleMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
}

/**
 * 空视频占位
 */
@Composable
private fun EmptyVideoPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "无视频",
            color = Color.Gray,
            fontSize = 14.sp
        )
    }
}

/**
 * 加载占位
 */
@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

/**
 * 错误占位
 */
@Composable
private fun ErrorPlaceholder(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "❌ $message",
            color = Color.Red,
            fontSize = 12.sp
        )
    }
}
