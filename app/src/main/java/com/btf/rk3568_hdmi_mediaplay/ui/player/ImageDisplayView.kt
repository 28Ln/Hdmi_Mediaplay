package com.btf.rk3568_hdmi_mediaplay.ui.player

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import kotlinx.coroutines.delay
import java.io.File

private const val TAG = "ImageDisplayView"

/**
 * 单格图片解码上限。4 路分屏时单格不超过约 1920x1080；
 * 按此上限请求可让 Coil 降采样，同时避免 Size.ORIGINAL 的全分辨率解码。
 */
private const val MAX_PANE_WIDTH = 1920
private const val MAX_PANE_HEIGHT = 1080

/**
 * 图片显示组件
 * 优化: 内存管理、缓存策略、错误处理
 */
@Composable
fun ImageDisplayView(
    imagePaths: List<String>,
    modifier: Modifier = Modifier,
    intervalSeconds: Int = 5,
    transition: ImageTransition = ImageTransition.FADE,
    scaleMode: VideoScaleMode = VideoScaleMode.STRETCH,  // 默认拉伸填满
    initialIndex: Int = 0,
    isPlaying: Boolean = true,
    onImageChanged: ((Int) -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    if (imagePaths.isEmpty()) {
        EmptyImagePlaceholder(modifier)
        return
    }
    
    // 过滤有效的图片路径
    val validPaths = remember(imagePaths) {
        imagePaths.filter { path ->
            path.isNotBlank() && (
                path.startsWith("http") || 
                path.startsWith("content://") || 
                File(path).exists()
            )
        }
    }
    
    if (validPaths.isEmpty()) {
        ErrorImagePlaceholder("无有效图片", modifier)
        return
    }
    
    var currentIndex by remember(validPaths, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, validPaths.lastIndex))
    }
    
    // 图片轮播逻辑 - 使用 LaunchedEffect 的 key 控制重启
    LaunchedEffect(validPaths.size, intervalSeconds, isPlaying) {
        if (validPaths.size > 1 && isPlaying && intervalSeconds > 0) {
            while (true) {
                delay(intervalSeconds * 1000L)
                currentIndex = (currentIndex + 1) % validPaths.size
                try {
                    onImageChanged?.invoke(currentIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "onImageChanged callback error", e)
                }
            }
        }
    }
    
    // 重置索引当图片列表变化
    LaunchedEffect(validPaths) {
        if (currentIndex >= validPaths.size) {
            currentIndex = 0
        }
    }
    
    val contentScale = remember(scaleMode) {
        when (scaleMode) {
            VideoScaleMode.FIT -> ContentScale.Fit
            VideoScaleMode.FILL -> ContentScale.Crop
            VideoScaleMode.STRETCH -> ContentScale.FillBounds
            VideoScaleMode.ORIGINAL -> ContentScale.None
        }
    }
    
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (transition) {
            ImageTransition.FADE -> {
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        fadeIn(animationSpec = androidx.compose.animation.core.tween(500)) togetherWith 
                        fadeOut(animationSpec = androidx.compose.animation.core.tween(500))
                    },
                    label = "image_fade"
                ) { index ->
                    OptimizedImageContent(
                        path = validPaths.getOrElse(index) { "" },
                        contentScale = contentScale,
                        onError = onError
                    )
                }
            }
            
            ImageTransition.SLIDE -> {
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        slideInHorizontally(
                            animationSpec = androidx.compose.animation.core.tween(500)
                        ) { it } togetherWith slideOutHorizontally(
                            animationSpec = androidx.compose.animation.core.tween(500)
                        ) { -it }
                    },
                    label = "image_slide"
                ) { index ->
                    OptimizedImageContent(
                        path = validPaths.getOrElse(index) { "" },
                        contentScale = contentScale,
                        onError = onError
                    )
                }
            }
            
            ImageTransition.NONE -> {
                OptimizedImageContent(
                    path = validPaths.getOrElse(currentIndex) { "" },
                    contentScale = contentScale,
                    onError = onError
                )
            }
        }
    }
}

/**
 * 优化的图片内容组件
 * 使用 Coil 的缓存策略和尺寸优化
 */
@Composable
private fun OptimizedImageContent(
    path: String,
    contentScale: ContentScale,
    onError: ((String) -> Unit)? = null
) {
    if (path.isBlank()) {
        EmptyImagePlaceholder(Modifier.fillMaxSize())
        return
    }
    
    val context = LocalContext.current
    
    // 构建图片请求。
    // 不能用 Size.ORIGINAL——那会**关闭** Coil 的降采样，
    // 一张 6000x4000 的照片按 ARGB_8888 解码约 96MB，4 路并行再叠加
    // 淡入淡出的新旧两份驻留，峰值可达 700MB+。
    // 但也不能完全省略 size：rememberAsyncImagePainter 不像 AsyncImage 那样
    // 能从布局约束推断目标尺寸，省略后请求会一直停在 Loading。
    // 因此显式给一个每格上限（4 路分屏时单格最大约 1920x1080）。
    val imageRequest = remember(path) {
        ImageRequest.Builder(context)
            .data(path)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(MAX_PANE_WIDTH, MAX_PANE_HEIGHT)
            .build()
    }
    
    val painter = rememberAsyncImagePainter(model = imageRequest)
    val painterState = painter.state
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (painterState) {
            is AsyncImagePainter.State.Loading -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp
                )
            }
            
            is AsyncImagePainter.State.Error -> {
                val errorMsg = "图片加载失败"
                LaunchedEffect(path) {
                    Log.e(TAG, "Image load error: $path", painterState.result.throwable)
                    onError?.invoke(errorMsg)
                }
                ErrorImagePlaceholder(errorMsg, Modifier.fillMaxSize())
            }
            
            else -> {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
            }
        }
    }
}

/**
 * 空图片占位
 */
@Composable
private fun EmptyImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "无图片",
            color = Color.Gray,
            fontSize = 14.sp
        )
    }
}

/**
 * 错误图片占位
 */
@Composable
private fun ErrorImagePlaceholder(message: String, modifier: Modifier = Modifier) {
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

/**
 * 单张图片显示
 */
@Composable
fun SingleImageView(
    imagePath: String,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    onError: ((String) -> Unit)? = null
) {
    val contentScale = remember(scaleMode) {
        when (scaleMode) {
            VideoScaleMode.FIT -> ContentScale.Fit
            VideoScaleMode.FILL -> ContentScale.Crop
            VideoScaleMode.STRETCH -> ContentScale.FillBounds
            VideoScaleMode.ORIGINAL -> ContentScale.None
        }
    }
    
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        OptimizedImageContent(
            path = imagePath, 
            contentScale = contentScale,
            onError = onError
        )
    }
}
