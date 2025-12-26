package com.btf.rk3568_hdmi_mediaplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.data.model.*

/**
 * 统一媒体播放器组件
 * 根据媒体类型自动切换视频播放器或图片显示
 */
@Composable
fun MediaPlayerView(
    playerConfig: PlayerConfig,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(),
    showIndex: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null
) {
    val mediaItems = playerConfig.mediaItems
    val currentItem = mediaItems.getOrNull(playerConfig.currentIndex)
    
    Box(
        modifier = modifier
            .background(Color(settings.backgroundColor))
            .clickable { onClick?.invoke() },
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
                ErrorContent(message = "播放错误")
            }
            
            // 视频内容
            currentItem?.type == MediaType.VIDEO -> {
                VideoPlayerView(
                    mediaPath = currentItem.path,
                    modifier = Modifier.fillMaxSize(),
                    isPlaying = playerConfig.state == PlayerState.PLAYING,
                    volume = playerConfig.volume,
                    isMuted = playerConfig.isMuted || settings.defaultMuted,
                    isLooping = playerConfig.isLooping,
                    scaleMode = settings.videoScaleMode,
                    onPlaybackEnded = onPlaybackEnded
                )
            }
            
            // 图片内容
            currentItem?.type == MediaType.IMAGE -> {
                val imagePaths = mediaItems
                    .filter { it.type == MediaType.IMAGE }
                    .map { it.path }
                
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
                ErrorContent(message = "不支持的格式")
            }
        }
        
        // 播放器编号标识
        if (showIndex && settings.showPlayerIndex) {
            PlayerIndexBadge(
                index = playerConfig.index,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
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
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (showIndex) "播放器 ${playerIndex + 1}" else "无内容",
            color = Color.Gray,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击选择媒体文件",
            color = Color.DarkGray,
            fontSize = 12.sp
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
            color = Color.White,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "加载中...",
            color = Color.White,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ErrorContent(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚠️",
            fontSize = 32.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color.Red,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun PlayerIndexBadge(
    index: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${index + 1}",
            color = Color.White,
            fontSize = 14.sp
        )
    }
}
