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
    onCurrentIndexChanged: ((Int) -> Unit)? = null,
    /**
     * 是否为「同画面」模式的跟随格（P2–P4）。
     *
     * 为 true 时本格**不允许**自行推进播放索引，索引完全由 P1 驱动。
     * 否则各格按自己的播放完成时刻独立 `currentIndex++`，会渐渐停在不同文件上
     * —— 这正是客户反馈的「same 模式播着播着不一样了」。
     * 时间偏移可以靠 [PlaybackSync] 的 seekTo 纠正，**播的文件不同则无法纠正**。
     */
    followsPrimary: Boolean = false
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
    
    // 使用 mediaItems 的 hashCode 作为 key，确保列表变化时重置索引。
    //
    // 注意：这里**不能**把 playerConfig.currentIndex 放进 key。
    // 跟随格（P2–P4）的索引需要由下面的同步逻辑按 P1 的最新值设置；
    // 若把它作为 key，每次 P1 切换时跟随格的 remember 会被整体重建，
    // 于是「同步赋值」与「key 重建」互相覆盖，行为不可预测。
    var currentVideoIndex by remember(mediaItems.hashCode()) {
        mutableIntStateOf(initialVideoIndex)
    }

    // 上一次见到的「外部索引」（playerConfig.currentIndex）。
    // 用于区分"这是外部指令"还是"本格刚推进、外部还没跟上"。
    var lastSeenExternalIndex by remember(mediaItems.hashCode()) {
        mutableIntStateOf(playerConfig.currentIndex)
    }

    // 跟随格（「同画面」模式的 P2–P4）：播放项**完全由 P1 驱动**。
    //
    // 关键点：不能用本格自己的 remember 状态决定播哪个文件。
    // 四格各自 remember 时，初始值只在首次组合时取一次；之后 P1 切到下一首，
    // P2–P4 的状态不会跟着变，于是各自按自己的节奏循环 ——
    // 表现就是客户看到的「1、4、3、2 顺序播放」。
    //
    // 这里把 playerConfig.currentIndex 当作唯一真值来源：
    // 它由 ViewModel 在 SAME 模式下广播给所有格（见 updatePlayerCurrentIndex），
    // 也就是 P1 的当前项。只要它变了，跟随格立即同步。
    if (followsPrimary) {
        val syncedIndex = playerConfig.currentIndex
            .coerceIn(0, videoItems.lastIndex.coerceAtLeast(0))
        if (syncedIndex != currentVideoIndex) {
            Log.d(
                TAG,
                "P${playerConfig.index + 1} 跟随主格：${currentVideoIndex} -> $syncedIndex"
            )
            currentVideoIndex = syncedIndex
        }
    } else {
        // 非跟随格：只在**外部主动改** playerConfig.currentIndex 时才跟随
        // （远控 stop 重置为 0、set_media 换新列表）。
        //
        // 不能无条件跟随，否则会与本格自己的推进打架：
        //   1. 视频播完 → currentVideoIndex = next（本格 state 变了，触发重组）
        //   2. 重组发生在 ViewModel 收到 onCurrentIndexChanged **之前**
        //   3. 此时 playerConfig.currentIndex 仍是旧值 → 把本格改回去
        //   4. 画面跳回上一集，然后 ViewModel 才更新到 next
        //
        // 判据：只有当 playerConfig.currentIndex 相对**上次见到的值**发生变化时，
        // 才认为这是外部指令。这样本格推进期间（外部值尚未更新）不会被回滚。
        val external = playerConfig.currentIndex.coerceIn(0, videoItems.lastIndex.coerceAtLeast(0))
        if (videoItems.isNotEmpty() && external != lastSeenExternalIndex) {
            lastSeenExternalIndex = external
            if (external != currentVideoIndex) {
                Log.d(TAG, "P${playerConfig.index + 1} 外部索引变更 -> $external")
                currentVideoIndex = external
            }
        } else {
            lastSeenExternalIndex = external
        }
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
                            followsPrimary = followsPrimary,
                            onPlaybackEnded = {
                                if (videoItems.size > 1 && !followsPrimary) {
                                    val nextIndex = when (settings.loopMode) {
                                        LoopMode.SINGLE -> currentVideoIndex
                                        LoopMode.LIST -> (currentVideoIndex + 1) % videoItems.size
                                        LoopMode.RANDOM -> (0 until videoItems.size).random()
                                    }
                                    currentVideoIndex = nextIndex
                                    try { onNextVideo?.invoke(nextIndex) } catch (e: Exception) { Log.e(TAG, "onNextVideo error", e) }
                                    try { onCurrentIndexChanged?.invoke(nextIndex) } catch (e: Exception) { Log.e(TAG, "onCurrentIndexChanged error", e) }
                                }
                                // 跟随格到片尾时不做任何索引推进：
                                // 它的 currentIndex 会随 P1 的 onCurrentIndexChanged →
                                // ViewModel 更新 → 这里重新组合而同步到同一个文件。
                                try { onPlaybackCompleted?.invoke(currentVideo.path, if (videoItems.size > 1 && !followsPrimary) currentVideoIndex else null) } catch (e: Exception) { Log.e(TAG, "onPlaybackCompleted error", e) }
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
