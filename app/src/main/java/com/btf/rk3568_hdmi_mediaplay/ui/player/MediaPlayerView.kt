package com.btf.rk3568_hdmi_mediaplay.ui.player

import android.util.Log
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
import com.btf.rk3568_hdmi_mediaplay.util.StringResources

private const val TAG = "MediaPlayerView"

/**
 * 统一媒体播放器组件 - 支持中英文
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
    onNextVideo: ((Int) -> Unit)? = null
) {
    val mediaItems = playerConfig.mediaItems
    
    val (videoItems, imageItems) = remember(mediaItems) {
        val videos = mediaItems.filter { it.type == MediaType.VIDEO }
        val images = mediaItems.filter { it.type == MediaType.IMAGE }
        Log.d(TAG, "Media items updated: ${videos.size} videos, ${images.size} images")
        videos to images
    }
    
    // 使用 mediaItems 的 hashCode 作为 key，确保列表变化时重置索引
    var currentVideoIndex by remember(mediaItems.hashCode()) { mutableIntStateOf(0) }
    
    val hasVideos = videoItems.isNotEmpty()
    val hasImages = imageItems.isNotEmpty()
    
    val gestureModifier = remember(onClick, onLongClick) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { 
                    try { onClick?.invoke() } catch (e: Exception) { Log.e(TAG, "onClick error", e) }
                },
                onLongPress = { 
                    try { onLongClick?.invoke() } catch (e: Exception) { Log.e(TAG, "onLongClick error", e) }
                }
            )
        }
    }
    
    Box(
        modifier = modifier
            .background(Color(settings.backgroundColor))
            .then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            mediaItems.isEmpty() -> {
                EmptyPlayerContent(playerIndex = playerConfig.index, showIndex = showIndex)
            }
            
            playerConfig.state == PlayerState.LOADING -> {
                LoadingContent()
            }
            
            playerConfig.state == PlayerState.ERROR -> {
                ErrorContent(
                    message = StringResources.playbackError,
                    suggestion = StringResources.checkFileFormat
                )
            }
            
            hasVideos -> {
                val currentVideo = videoItems.getOrNull(currentVideoIndex)
                if (currentVideo != null) {
                    key(currentVideo.path) {
                        VideoPlayerView(
                            mediaPath = currentVideo.path,
                            modifier = Modifier.fillMaxSize(),
                            isPlaying = playerConfig.state == PlayerState.PLAYING,
                            volume = playerConfig.volume * (settings.defaultVolume / 100f),
                            isMuted = playerConfig.isMuted || settings.defaultMuted,
                            isLooping = videoItems.size == 1 && settings.loopMode != LoopMode.RANDOM,
                            scaleMode = settings.videoScaleMode,
                            onPlaybackEnded = {
                                if (videoItems.size > 1) {
                                    val nextIndex = when (settings.loopMode) {
                                        LoopMode.SINGLE -> currentVideoIndex
                                        LoopMode.LIST -> (currentVideoIndex + 1) % videoItems.size
                                        LoopMode.RANDOM -> (0 until videoItems.size).random()
                                    }
                                    currentVideoIndex = nextIndex
                                    try { onNextVideo?.invoke(nextIndex) } catch (e: Exception) { Log.e(TAG, "onNextVideo error", e) }
                                }
                                try { onPlaybackEnded?.invoke() } catch (e: Exception) { Log.e(TAG, "onPlaybackEnded error", e) }
                            },
                            onError = { e ->
                                try { onError?.invoke(e.message ?: StringResources.videoPlayError) } catch (ex: Exception) { Log.e(TAG, "onError callback error", ex) }
                            }
                        )
                    }
                    
                    if (videoItems.size > 1) {
                        VideoIndexIndicator(
                            current = currentVideoIndex + 1,
                            total = videoItems.size,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        )
                    }
                }
            }
            
            hasImages -> {
                val imagePaths = remember(imageItems) { imageItems.map { it.path } }
                
                // 使用 key 确保图片列表变化时重新创建组件
                key(imagePaths.hashCode()) {
                    ImageDisplayView(
                        imagePaths = imagePaths,
                        modifier = Modifier.fillMaxSize(),
                        intervalSeconds = settings.imageIntervalSeconds,
                        transition = settings.imageTransition,
                        scaleMode = settings.videoScaleMode,
                        isPlaying = playerConfig.state == PlayerState.PLAYING,
                        onError = { msg ->
                            try { onError?.invoke(msg) } catch (e: Exception) { Log.e(TAG, "onError callback error", e) }
                        }
                    )
                }
            }
            
            else -> {
                ErrorContent(
                    message = StringResources.unsupportedFormat,
                    suggestion = StringResources.supportedFormatsHint
                )
            }
        }
        
        if (showIndex && settings.showPlayerIndex) {
            PlayerIndexBadge(
                index = playerConfig.index,
                state = playerConfig.state,
                hasContent = mediaItems.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
        }
        
        if (playerConfig.state == PlayerState.PAUSED && mediaItems.isNotEmpty()) {
            PausedOverlay()
        }
    }
}

@Composable
private fun EmptyPlayerContent(playerIndex: Int, showIndex: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Text(text = "📺", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (showIndex) StringResources.playerN(playerIndex + 1) else StringResources.noContent,
            color = Color.White,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = StringResources.longPressToSelect,
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
        Text(text = StringResources.loading, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorContent(message: String, suggestion: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Text(text = "❌", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = message, color = Color.Red, fontSize = 16.sp)
        suggestion?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
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
    val (backgroundColor, textColor) = remember(state, hasContent) {
        when {
            state == PlayerState.ERROR -> Color.Red to Color.White
            state == PlayerState.PLAYING -> Color.Green.copy(alpha = 0.8f) to Color.White
            state == PlayerState.PAUSED -> Color.Yellow.copy(alpha = 0.8f) to Color.Black
            hasContent -> Color.Cyan.copy(alpha = 0.8f) to Color.Black
            else -> Color.Gray.copy(alpha = 0.8f) to Color.White
        }
    }
    
    Box(
        modifier = modifier
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = "${index + 1}", color = textColor, fontSize = 14.sp)
    }
}

@Composable
private fun VideoIndexIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = "$current/$total", color = Color.White, fontSize = 10.sp)
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
        Text(text = "⏸", fontSize = 64.sp)
    }
}
