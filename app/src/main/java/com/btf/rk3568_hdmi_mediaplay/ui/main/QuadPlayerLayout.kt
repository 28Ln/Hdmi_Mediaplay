package com.btf.rk3568_hdmi_mediaplay.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerConfig
import com.btf.rk3568_hdmi_mediaplay.ui.player.MediaPlayerView

/**
 * 多布局播放器组件
 * 支持多种布局模式
 * 
 * 【重要优化】使用自定义 Layout 实现布局切换，避免重建播放器
 * 布局切换只改变位置和大小，不销毁/重建 ExoPlayer
 */
@Composable
fun QuadPlayerLayout(
    playerConfigs: List<PlayerConfig>,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(),
    showPlayerIndex: Boolean = true,
    onPlayerClick: ((Int) -> Unit)? = null,
    onPlayerLongClick: ((Int) -> Unit)? = null,
    onPlaybackCompleted: ((Int, String?, Int?) -> Unit)? = null,
    onPlaybackError: ((Int, String) -> Unit)? = null,
    onCurrentIndexChanged: ((Int, Int) -> Unit)? = null
) {
    val backgroundColor = remember(settings.backgroundColor) { 
        Color(settings.backgroundColor) 
    }
    
    // 合并 settings 和 featureFlags 的 showPlayerIndex
    val effectiveSettings = remember(settings, showPlayerIndex) {
        settings.copy(showPlayerIndex = settings.showPlayerIndex && showPlayerIndex)
    }
    
    // 获取当前布局需要显示的播放器数量
    val visibleCount = remember(settings.layoutMode) {
        when (settings.layoutMode) {
            LayoutMode.SINGLE -> 1
            LayoutMode.GRID_1X2, LayoutMode.GRID_2X1 -> 2
            LayoutMode.GRID_1X3, LayoutMode.GRID_3X1 -> 3
            else -> 4
        }
    }
    
    // 使用自定义 Layout，播放器组件始终存在，只是位置/大小变化
    FlexiblePlayerLayout(
        layoutMode = settings.layoutMode,
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 始终创建4个播放器，使用稳定的 key 避免重建
        for (i in 0 until 4) {
            key("player_$i") {  // 稳定的 key，布局切换不会导致重建
                val isVisible = i < visibleCount
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = effectiveSettings,
                    modifier = Modifier,  // 大小由 Layout 控制
                    onClick = { if (isVisible) onPlayerClick?.invoke(i) },
                    onLongClick = { if (isVisible) onPlayerLongClick?.invoke(i) },
                    isVisible = isVisible,
                    onPlaybackCompleted = { mediaPath, nextIndex ->
                        if (isVisible) onPlaybackCompleted?.invoke(i, mediaPath, nextIndex)
                    },
                    onPlaybackError = { message ->
                        if (isVisible) onPlaybackError?.invoke(i, message)
                    },
                    onCurrentIndexChanged = { currentIndex ->
                        if (isVisible) onCurrentIndexChanged?.invoke(i, currentIndex)
                    }
                )
            }
        }
    }
}

/**
 * 自定义布局：根据 layoutMode 计算每个播放器的位置和大小
 * 关键：子组件不会因为布局模式变化而重建
 */
@Composable
private fun FlexiblePlayerLayout(
    layoutMode: LayoutMode,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        
        // 根据布局模式计算每个播放器的位置和大小
        val placements = calculatePlacements(layoutMode, width, height)
        
        // 测量每个子组件
        val placeables = measurables.mapIndexed { index, measurable ->
            val placement = placements.getOrNull(index)
            if (placement != null && placement.visible) {
                measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(placement.width, placement.height)
                )
            } else {
                // 不可见的播放器测量为 1x1（最小尺寸，避免占用资源）
                measurable.measure(androidx.compose.ui.unit.Constraints.fixed(1, 1))
            }
        }
        
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val placement = placements.getOrNull(index)
                if (placement != null && placement.visible) {
                    placeable.place(placement.x, placement.y)
                } else {
                    // 不可见的放到屏幕外
                    placeable.place(-10, -10)
                }
            }
        }
    }
}

/**
 * 播放器位置信息
 */
private data class PlayerPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val visible: Boolean = true
)

/**
 * 根据布局模式计算4个播放器的位置
 */
private fun calculatePlacements(layoutMode: LayoutMode, totalWidth: Int, totalHeight: Int): List<PlayerPlacement> {
    val gap = 2  // 间隙
    
    return when (layoutMode) {
        LayoutMode.SINGLE -> {
            listOf(
                PlayerPlacement(0, 0, totalWidth, totalHeight),
                PlayerPlacement(0, 0, 0, 0, visible = false),
                PlayerPlacement(0, 0, 0, 0, visible = false),
                PlayerPlacement(0, 0, 0, 0, visible = false)
            )
        }
        
        LayoutMode.GRID_1X2 -> {
            val w = (totalWidth - gap) / 2
            listOf(
                PlayerPlacement(0, 0, w, totalHeight),
                PlayerPlacement(w + gap, 0, totalWidth - w - gap, totalHeight),
                PlayerPlacement(0, 0, 0, 0, visible = false),
                PlayerPlacement(0, 0, 0, 0, visible = false)
            )
        }
        
        LayoutMode.GRID_2X1 -> {
            val h = (totalHeight - gap) / 2
            listOf(
                PlayerPlacement(0, 0, totalWidth, h),
                PlayerPlacement(0, h + gap, totalWidth, totalHeight - h - gap),
                PlayerPlacement(0, 0, 0, 0, visible = false),
                PlayerPlacement(0, 0, 0, 0, visible = false)
            )
        }
        
        LayoutMode.GRID_2X2 -> {
            val w = (totalWidth - gap) / 2
            val h = (totalHeight - gap) / 2
            listOf(
                PlayerPlacement(0, 0, w, h),
                PlayerPlacement(w + gap, 0, totalWidth - w - gap, h),
                PlayerPlacement(0, h + gap, w, totalHeight - h - gap),
                PlayerPlacement(w + gap, h + gap, totalWidth - w - gap, totalHeight - h - gap)
            )
        }
        
        LayoutMode.GRID_1X3 -> {
            val w = (totalWidth - gap * 2) / 3
            listOf(
                PlayerPlacement(0, 0, w, totalHeight),
                PlayerPlacement(w + gap, 0, w, totalHeight),
                PlayerPlacement(w * 2 + gap * 2, 0, totalWidth - w * 2 - gap * 2, totalHeight),
                PlayerPlacement(0, 0, 0, 0, visible = false)
            )
        }
        
        LayoutMode.GRID_3X1 -> {
            val h = (totalHeight - gap * 2) / 3
            listOf(
                PlayerPlacement(0, 0, totalWidth, h),
                PlayerPlacement(0, h + gap, totalWidth, h),
                PlayerPlacement(0, h * 2 + gap * 2, totalWidth, totalHeight - h * 2 - gap * 2),
                PlayerPlacement(0, 0, 0, 0, visible = false)
            )
        }
        
        LayoutMode.ROW_1X4 -> {
            val w = (totalWidth - gap * 3) / 4
            listOf(
                PlayerPlacement(0, 0, w, totalHeight),
                PlayerPlacement(w + gap, 0, w, totalHeight),
                PlayerPlacement(w * 2 + gap * 2, 0, w, totalHeight),
                PlayerPlacement(w * 3 + gap * 3, 0, totalWidth - w * 3 - gap * 3, totalHeight)
            )
        }
        
        LayoutMode.COLUMN_4X1 -> {
            val h = (totalHeight - gap * 3) / 4
            listOf(
                PlayerPlacement(0, 0, totalWidth, h),
                PlayerPlacement(0, h + gap, totalWidth, h),
                PlayerPlacement(0, h * 2 + gap * 2, totalWidth, h),
                PlayerPlacement(0, h * 3 + gap * 3, totalWidth, totalHeight - h * 3 - gap * 3)
            )
        }
        
        LayoutMode.PIP -> {
            // 画中画: 左侧2/3主画面，右侧1/3分3个小画面
            val mainW = totalWidth * 2 / 3
            val sideW = totalWidth - mainW - gap
            val sideH = (totalHeight - gap * 2) / 3
            listOf(
                PlayerPlacement(0, 0, mainW, totalHeight),
                PlayerPlacement(mainW + gap, 0, sideW, sideH),
                PlayerPlacement(mainW + gap, sideH + gap, sideW, sideH),
                PlayerPlacement(mainW + gap, sideH * 2 + gap * 2, sideW, totalHeight - sideH * 2 - gap * 2)
            )
        }
    }
}

/**
 * 单个播放器单元格
 * 
 * @param isVisible 是否可见，不可见时仍然保持组件存在但不渲染内容
 */
@Composable
private fun PlayerCell(
    config: PlayerConfig,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isVisible: Boolean = true,
    onPlaybackCompleted: ((String?, Int?) -> Unit)? = null,
    onPlaybackError: ((String) -> Unit)? = null,
    onCurrentIndexChanged: ((Int) -> Unit)? = null
) {
    // 不可见时返回空 Box，但组件仍然存在于树中
    if (!isVisible) {
        Box(modifier = modifier)
        return
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(1.dp)
            .border(1.dp, Color.DarkGray)
    ) {
        MediaPlayerView(
            playerConfig = config,
            settings = settings,
            modifier = Modifier.fillMaxSize(),
            onClick = onClick,
            onLongClick = onLongClick,
            onPlaybackCompleted = onPlaybackCompleted,
            onError = onPlaybackError,
            onCurrentIndexChanged = onCurrentIndexChanged
        )
    }
}
