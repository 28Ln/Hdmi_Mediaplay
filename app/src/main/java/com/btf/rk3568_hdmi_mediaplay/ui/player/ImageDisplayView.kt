package com.btf.rk3568_hdmi_mediaplay.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import kotlinx.coroutines.delay

/**
 * 图片显示组件
 * 支持图片轮播
 */
@Composable
fun ImageDisplayView(
    imagePaths: List<String>,
    modifier: Modifier = Modifier,
    intervalSeconds: Int = 5,
    transition: ImageTransition = ImageTransition.FADE,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    isPlaying: Boolean = true,
    onImageChanged: ((Int) -> Unit)? = null
) {
    if (imagePaths.isEmpty()) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // 空状态
        }
        return
    }
    
    var currentIndex by remember { mutableIntStateOf(0) }
    
    // 图片轮播逻辑
    LaunchedEffect(imagePaths, intervalSeconds, isPlaying) {
        if (imagePaths.size > 1 && isPlaying) {
            while (true) {
                delay(intervalSeconds * 1000L)
                currentIndex = (currentIndex + 1) % imagePaths.size
                onImageChanged?.invoke(currentIndex)
            }
        }
    }
    
    // 重置索引当图片列表变化
    LaunchedEffect(imagePaths) {
        currentIndex = 0
    }
    
    val contentScale = when (scaleMode) {
        VideoScaleMode.FIT -> ContentScale.Fit
        VideoScaleMode.FILL -> ContentScale.Crop
        VideoScaleMode.STRETCH -> ContentScale.FillBounds
        VideoScaleMode.ORIGINAL -> ContentScale.None
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
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "image_fade"
                ) { index ->
                    ImageContent(
                        path = imagePaths.getOrNull(index) ?: "",
                        contentScale = contentScale
                    )
                }
            }
            
            ImageTransition.SLIDE -> {
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    },
                    label = "image_slide"
                ) { index ->
                    ImageContent(
                        path = imagePaths.getOrNull(index) ?: "",
                        contentScale = contentScale
                    )
                }
            }
            
            ImageTransition.NONE -> {
                ImageContent(
                    path = imagePaths.getOrNull(currentIndex) ?: "",
                    contentScale = contentScale
                )
            }
        }
    }
}

@Composable
private fun ImageContent(
    path: String,
    contentScale: ContentScale
) {
    val context = LocalContext.current
    
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(path)
            .crossfade(true)
            .build()
    )
    
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale
    )
}

/**
 * 单张图片显示
 */
@Composable
fun SingleImageView(
    imagePath: String,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.FIT
) {
    val contentScale = when (scaleMode) {
        VideoScaleMode.FIT -> ContentScale.Fit
        VideoScaleMode.FILL -> ContentScale.Crop
        VideoScaleMode.STRETCH -> ContentScale.FillBounds
        VideoScaleMode.ORIGINAL -> ContentScale.None
    }
    
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        ImageContent(path = imagePath, contentScale = contentScale)
    }
}
