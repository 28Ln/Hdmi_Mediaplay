package com.btf.rk3568_hdmi_mediaplay.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode

/**
 * 视频播放器组件
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
    val context = LocalContext.current
    
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = isPlaying
        }
    }
    
    // 更新媒体源
    LaunchedEffect(mediaPath) {
        if (mediaPath.isNotEmpty()) {
            val mediaItem = MediaItem.fromUri(mediaPath)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }
    
    // 更新播放状态
    LaunchedEffect(isPlaying) {
        exoPlayer.playWhenReady = isPlaying
    }
    
    // 更新音量
    LaunchedEffect(volume, isMuted) {
        exoPlayer.volume = if (isMuted) 0f else volume
    }
    
    // 更新循环模式
    LaunchedEffect(isLooping) {
        exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    
    // 监听播放事件
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> onPlayerReady?.invoke(exoPlayer)
                    Player.STATE_ENDED -> onPlaybackEnded?.invoke()
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onError?.invoke(Exception(error.message))
            }
        }
        
        exoPlayer.addListener(listener)
        
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false  // 隐藏控制器
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                // 设置缩放模式
                resizeMode = when (scaleMode) {
                    VideoScaleMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoScaleMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoScaleMode.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    VideoScaleMode.ORIGINAL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        },
        modifier = modifier,
        update = { playerView ->
            playerView.resizeMode = when (scaleMode) {
                VideoScaleMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoScaleMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoScaleMode.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                VideoScaleMode.ORIGINAL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
    )
}
