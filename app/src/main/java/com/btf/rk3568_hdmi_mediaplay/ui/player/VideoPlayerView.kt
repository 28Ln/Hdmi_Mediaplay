package com.btf.rk3568_hdmi_mediaplay.ui.player

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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import java.io.File

/**
 * 视频播放器组件
 * 修复: 空指针、内存泄漏、生命周期管理
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
    val file = File(mediaPath)
    if (!file.exists() && !mediaPath.startsWith("http")) {
        ErrorPlaceholder("文件不存在", modifier)
        onError?.invoke(Exception("文件不存在: $mediaPath"))
        return
    }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 播放器状态
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // 使用 remember 创建 ExoPlayer，确保只创建一次
    val exoPlayer = remember(context) {
        try {
            ExoPlayer.Builder(context).build()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // 如果播放器创建失败
    if (exoPlayer == null) {
        ErrorPlaceholder("播放器初始化失败", modifier)
        return
    }
    
    // 设置媒体源
    LaunchedEffect(mediaPath) {
        try {
            isLoading = true
            hasError = false
            
            val mediaItem = MediaItem.fromUri(mediaPath)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
        }
    }
    
    // 更新音量
    LaunchedEffect(volume, isMuted) {
        try {
            exoPlayer.volume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 更新循环模式
    LaunchedEffect(isLooping) {
        try {
            exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    try {
                        exoPlayer.pause()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (isPlaying) {
                        try {
                            exoPlayer.play()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 播放器事件监听
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        isLoading = false
                        hasError = false
                        try {
                            onPlayerReady?.invoke(exoPlayer)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    Player.STATE_ENDED -> {
                        try {
                            onPlaybackEnded?.invoke()
                        } catch (e: Exception) {
                            e.printStackTrace()
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
                isLoading = false
                hasError = true
                errorMessage = error.message ?: "播放错误"
                try {
                    onError?.invoke(Exception(error.message))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        try {
            exoPlayer.addListener(listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        onDispose {
            try {
                exoPlayer.removeListener(listener)
                exoPlayer.stop()
                exoPlayer.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // UI
    Box(modifier = modifier.background(Color.Black)) {
        if (hasError) {
            ErrorPlaceholder(errorMessage, Modifier.fillMaxSize())
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        try {
                            player = exoPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            resizeMode = getResizeMode(scaleMode)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { playerView ->
                    try {
                        playerView.resizeMode = getResizeMode(scaleMode)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )
            
            // 加载指示器
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
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
