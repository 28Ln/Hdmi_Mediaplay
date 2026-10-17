package com.btf.rk3568_hdmi_mediaplay.ui.player

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.util.StringResources

private const val TAG = "MediaPlayerView"

/** 点击播放器后，编号徽章的临时显示时长 */
private const val BADGE_VISIBLE_MS = 3_000L

/**
 * 统一媒体播放器组件 - 支持中英文
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun MediaPlayerView(
    playerConfig: PlayerConfig,
    modifier: Modifier = Modifier,
    /** 所在格下标（0..3），用于「同画面」模式的多路同步；-1 表示无需同步 */
    cellIndex: Int = -1,
    settings: AppSettings = AppSettings(),
    showIndex: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null,
    onPlaybackCompleted: ((String?, Int?) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    onRecovered: (() -> Unit)? = null,
    onNextVideo: ((Int) -> Unit)? = null,
    onCurrentIndexChanged: ((Int) -> Unit)? = null
) {
    val mediaItems = playerConfig.mediaItems
    
    val (videoItems, imageItems) = remember(mediaItems) {
        val videos = mediaItems.filter { it.type == MediaType.VIDEO }
        val images = mediaItems.filter { it.type == MediaType.IMAGE }
        // 带上播放器编号：4 路并行时，没有编号的日志无法定位是哪一路
        Log.d(TAG, "P${playerConfig.index + 1} media items: ${videos.size} videos, ${images.size} images")
        videos to images
    }
    
    val hasVideos = videoItems.isNotEmpty()
    val hasImages = imageItems.isNotEmpty()
    val initialVideoIndex = remember(mediaItems.hashCode(), playerConfig.currentIndex, videoItems.size) {
        if (videoItems.isNotEmpty()) {
            playerConfig.currentIndex.coerceIn(0, videoItems.lastIndex)
        } else {
            0
        }
    }
    
    // 使用 mediaItems 的 hashCode 作为 key，确保列表变化时重置索引
    var currentVideoIndex by remember(mediaItems.hashCode(), playerConfig.currentIndex) {
        mutableIntStateOf(initialVideoIndex)
    }
    
    // 编号徽章（左上角 1/2/3/4）：初始显示 3 秒后自动隐藏，触摸该格再显示 3 秒。
    // 播放时常显会遮挡画面；触摸显示用于确认「这是第几路」。
    // 设置里关闭「显示播放器编号」则完全不显示。
    var badgeTapCount by remember { mutableIntStateOf(0) }
    var badgeVisible by remember { mutableStateOf(true) }
    val badgeEnabled = showIndex && settings.showPlayerIndex

    // 初始 3 秒后隐藏；badgeTapCount>0 的触摸会把可见时间重置为再 3 秒
    LaunchedEffect(badgeEnabled) {
        if (badgeEnabled) {
            delay(BADGE_VISIBLE_MS)
            badgeVisible = false
        }
    }
    LaunchedEffect(badgeTapCount) {
        if (badgeTapCount > 0) {
            badgeVisible = true
            delay(BADGE_VISIBLE_MS)
            badgeVisible = false
        }
    }

    val gestureModifier = remember(onClick, onLongClick) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    if (badgeEnabled) badgeTapCount++
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

            // 视频出错但本路还有图片时，回退显示图片而不是整格报错。
            // 例：MP4 音轨（AC3 直通）不被设备支持时，视频轨其实正常，
            // 不该因为一个文件的问题让整格黑屏。
            playerConfig.state == PlayerState.ERROR && hasImages -> {
                val imagePaths = remember(imageItems) { imageItems.map { it.path } }
                key(imagePaths.hashCode()) {
                    ImageDisplayView(
                        imagePaths = imagePaths,
                        modifier = Modifier.fillMaxSize(),
                        intervalSeconds = settings.imageIntervalSeconds,
                        transition = settings.imageTransition,
                        scaleMode = settings.videoScaleMode,
                        initialIndex = playerConfig.currentIndex,
                        // 出错态下仍让图片轮播，保证画面可用
                        isPlaying = true,
                        onImageChanged = { index ->
                            try { onCurrentIndexChanged?.invoke(index) } catch (e: Exception) { Log.e(TAG, "onCurrentIndexChanged error", e) }
                        },
                        onError = { msg ->
                            try { onError?.invoke(msg) } catch (e: Exception) { Log.e(TAG, "onError callback error", e) }
                        }
                    )
                }
            }

            // 出错且本路没有任何可显示内容时，才整格报错
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
                            cellIndex = cellIndex,
                            isPlaying = playerConfig.state == PlayerState.PLAYING,
                            volume = playerConfig.volume * (settings.defaultVolume / 100f),
                            isMuted = playerConfig.isMuted || settings.defaultMuted,
                            isLooping = videoItems.size == 1 && settings.loopMode != LoopMode.RANDOM,
                            scaleMode = settings.videoScaleMode,
                            audioFallbackSilent = settings.audioFallbackSilent,
                            onPlaybackEnded = {
                                if (videoItems.size > 1) {
                                    val nextIndex = when (settings.loopMode) {
                                        LoopMode.SINGLE -> currentVideoIndex
                                        LoopMode.LIST -> (currentVideoIndex + 1) % videoItems.size
                                        LoopMode.RANDOM -> (0 until videoItems.size).random()
                                    }
                                    currentVideoIndex = nextIndex
                                    try { onNextVideo?.invoke(nextIndex) } catch (e: Exception) { Log.e(TAG, "onNextVideo error", e) }
                                    try { onCurrentIndexChanged?.invoke(nextIndex) } catch (e: Exception) { Log.e(TAG, "onCurrentIndexChanged error", e) }
                                }
                                try { onPlaybackCompleted?.invoke(currentVideo.path, if (videoItems.size > 1) currentVideoIndex else null) } catch (e: Exception) { Log.e(TAG, "onPlaybackCompleted error", e) }
                                try { onPlaybackEnded?.invoke() } catch (e: Exception) { Log.e(TAG, "onPlaybackEnded error", e) }
                            },
                            onError = { e ->
                                try { onError?.invoke(e.message ?: StringResources.videoPlayError) } catch (ex: Exception) { Log.e(TAG, "onError callback error", ex) }
                            },
                            onRecovered = {
                                try { onRecovered?.invoke() } catch (ex: Exception) { Log.e(TAG, "onRecovered callback error", ex) }
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
                        initialIndex = playerConfig.currentIndex,
                        isPlaying = playerConfig.state == PlayerState.PLAYING,
                        onImageChanged = { index ->
                            try { onCurrentIndexChanged?.invoke(index) } catch (e: Exception) { Log.e(TAG, "onCurrentIndexChanged error", e) }
                        },
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
        
        if (badgeEnabled && badgeVisible) {
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
        // 📺 只是占位图形，紧跟其后的文字已经说明了状态；
        // 静音掉，避免 TalkBack 先念一遍 emoji 字形
        Text(text = "📺", fontSize = 48.sp, modifier = Modifier.clearAndSetSemantics {})
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
        // 同为装饰性占位图形，错误信息由下方 message/suggestion 朗读
        Text(text = "❌", fontSize = 48.sp, modifier = Modifier.clearAndSetSemantics {})
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
        // 暂停遮罩是纯图标、没有任何文字，必须给出语义描述，
        // 否则 TalkBack 只能念出字形本身
        Text(
            text = "⏸",
            fontSize = 64.sp,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = StringResources.pause
            }
        )
    }
}
