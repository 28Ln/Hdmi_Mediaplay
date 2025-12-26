package com.btf.rk3568_hdmi_mediaplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.data.model.*

/**
 * 统一媒体播放器组件
 * 根据媒体类型自动切换视频播放器或图片显示
 * 支持视频列表循环播放
 */
@Composable
fun MediaPlayerView(
    playerConfig: PlayerConfig,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(),
    showIndex: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    onNextVideo: ((Int) -> Unit)? = null  // 切换到下一个视频的回调
) {
    val mediaItems = playerConfig.mediaItems
    
    // 分离视频和图片
    val videoItems = remember(mediaItems) {
        mediaItems.filter { it.type == MediaType.VIDEO }
    }
    val imageItems = remember(mediaItems) {
        mediaItems.filter { it.type == MediaType.IMAGE }
    }
    
    // 当前播放的视频索引
    var currentVideoIndex by remember { mutableIntStateOf(0) }
    
    // 重置索引当媒体列表变化
    LaunchedEffect(mediaItems) {
        currentVideoIndex = 0
    }
    
    // 确定当前显示的内容类型
    val hasVideos = videoItems.isNotEmpty()
    val hasImages = imageItems.isNotEmpty()
    val currentItem = mediaItems.getOrNull(playerConfig.currentIndex)
    
    Box(
        modifier = modifier
            .background(Color(settings.backgroundColor))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { 
                        try {
                            onClick?.invoke() 
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onLongPress = { 
                        try {
                            onLongClick?.invoke() 
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            // 无内容
            mediaItems.isEmpty() -> {
                EmptyPlayerContent(
                    playerIndex = playerConfig.index,
                    showIndex = showIndex
                )
            }
            
            // 加载中
            playerConfig.state == PlayerState.LOADING -> {
                LoadingContent()
            }
            
            // 错误状态
            playerConfig.state == PlayerState.ERROR -> {
                ErrorContent(
                    message = "播放出错",
                    suggestion = "请检查文件格式或重新选择"
                )
            }
            
            // 优先播放视频
            hasVideos -> {
                val currentVideo = videoItems.getOrNull(currentVideoIndex)
                if (currentVideo != null) {
                    VideoPlayerView(
                        mediaPath = currentVideo.path,
                        modifier = Modifier.fillMaxSize(),
                        isPlaying = playerConfig.state == PlayerState.PLAYING,
                        volume = playerConfig.volume * (settings.defaultVolume / 100f),
                        isMuted = playerConfig.isMuted || settings.defaultMuted,
                        isLooping = videoItems.size == 1 && settings.loopMode != LoopMode.RANDOM,
                        scaleMode = settings.videoScaleMode,
                        onPlaybackEnded = {
                            // 视频播放完成，切换到下一个
                            if (videoItems.size > 1) {
                                currentVideoIndex = when (settings.loopMode) {
                                    LoopMode.SINGLE -> currentVideoIndex
                                    LoopMode.LIST -> (currentVideoIndex + 1) % videoItems.size
                                    LoopMode.RANDOM -> (0 until videoItems.size).random()
                                }
                                onNextVideo?.invoke(currentVideoIndex)
                            }
                            onPlaybackEnded?.invoke()
                        },
                        onError = { e ->
                            onError?.invoke(e.message ?: "视频播放错误")
                        }
                    )
                    
                    // 视频数量指示器
                    if (videoItems.size > 1) {
                        VideoIndexIndicator(
                            current = currentVideoIndex + 1,
                            total = videoItems.size,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        )
                    }
                }
            }
            
            // 只有图片
            hasImages -> {
                val imagePaths = imageItems.map { it.path }
                
                ImageDisplayView(
                    imagePaths = imagePaths,
                    modifier = Modifier.fillMaxSize(),
                    intervalSeconds = settings.imageIntervalSeconds,
                    transition = settings.imageTransition,
                    scaleMode = settings.videoScaleMode,
                    isPlaying = playerConfig.state == PlayerState.PLAYING
                )
            }
            
            // 未知类型
            else -> {
                ErrorContent(
                    message = "不支持的格式",
                    suggestion = "支持: MP4, MKV, AVI, JPG, PNG 等"
                )
            }
        }
        
        // 播放器编号标识
        if (showIndex && settings.showPlayerIndex) {
            PlayerIndexBadge(
                index = playerConfig.index,
                state = playerConfig.state,
                hasContent = mediaItems.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }
        
        // 暂停状态指示
        if (playerConfig.state == PlayerState.PAUSED && mediaItems.isNotEmpty()) {
            PausedOverlay()
        }
    }
}

@Composable
private fun EmptyPlayerContent(
    playerIndex: Int,
    showIndex: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "📺",
            fontSize = 48.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (showIndex) "播放器 ${playerIndex + 1}" else "无内容",
            color = Color.White,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "长按选择媒体文件\n或插入U盘自动加载",
            color = Color.Gray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = Color.Cyan,
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "加载中...",
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    suggestion: String? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "❌",
            fontSize = 48.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color.Red,
            fontSize = 16.sp
        )
        suggestion?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = Color.Gray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PlayerIndexBadge(
    index: Int,
    state: PlayerState,
    hasContent: Boolean,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, textColor) = when {
        state == PlayerState.ERROR -> Color.Red to Color.White
        state == PlayerState.PLAYING -> Color.Green.copy(alpha = 0.8f) to Color.White
        state == PlayerState.PAUSED -> Color.Yellow.copy(alpha = 0.8f) to Color.Black
        hasContent -> Color.Cyan.copy(alpha = 0.8f) to Color.Black
        else -> Color.Gray.copy(alpha = 0.8f) to Color.White
    }
    
    Box(
        modifier = modifier
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${index + 1}",
            color = textColor,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun VideoIndexIndicator(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$current/$total",
            color = Color.White,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun PausedOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⏸",
            fontSize = 64.sp
        )
    }
}
