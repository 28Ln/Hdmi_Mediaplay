package com.btf.rk3568_hdmi_mediaplay.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerConfig
import com.btf.rk3568_hdmi_mediaplay.ui.player.MediaPlayerView

/**
 * 四宫格播放器布局
 */
@Composable
fun QuadPlayerLayout(
    playerConfigs: List<PlayerConfig>,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(),
    onPlayerClick: ((Int) -> Unit)? = null,
    onPlayerLongClick: ((Int) -> Unit)? = null
) {
    val backgroundColor = Color(settings.backgroundColor)
    
    when (settings.layoutMode) {
        LayoutMode.GRID_2X2 -> {
            Grid2x2Layout(
                playerConfigs = playerConfigs,
                settings = settings,
                backgroundColor = backgroundColor,
                modifier = modifier,
                onPlayerClick = onPlayerClick,
                onPlayerLongClick = onPlayerLongClick
            )
        }
        
        LayoutMode.ROW_1X4 -> {
            Row1x4Layout(
                playerConfigs = playerConfigs,
                settings = settings,
                backgroundColor = backgroundColor,
                modifier = modifier,
                onPlayerClick = onPlayerClick,
                onPlayerLongClick = onPlayerLongClick
            )
        }
        
        LayoutMode.COLUMN_4X1 -> {
            Column4x1Layout(
                playerConfigs = playerConfigs,
                settings = settings,
                backgroundColor = backgroundColor,
                modifier = modifier,
                onPlayerClick = onPlayerClick,
                onPlayerLongClick = onPlayerLongClick
            )
        }
    }
}

/**
 * 2x2 网格布局
 */
@Composable
private fun Grid2x2Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onPlayerClick: ((Int) -> Unit)? = null,
    onPlayerLongClick: ((Int) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 上半部分: 播放器 0 和 1
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            PlayerCell(
                config = playerConfigs.getOrNull(0) ?: PlayerConfig(index = 0),
                settings = settings,
                modifier = Modifier.weight(1f),
                onClick = { onPlayerClick?.invoke(0) },
                onLongClick = { onPlayerLongClick?.invoke(0) }
            )
            
            PlayerCell(
                config = playerConfigs.getOrNull(1) ?: PlayerConfig(index = 1),
                settings = settings,
                modifier = Modifier.weight(1f),
                onClick = { onPlayerClick?.invoke(1) },
                onLongClick = { onPlayerLongClick?.invoke(1) }
            )
        }
        
        // 下半部分: 播放器 2 和 3
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            PlayerCell(
                config = playerConfigs.getOrNull(2) ?: PlayerConfig(index = 2),
                settings = settings,
                modifier = Modifier.weight(1f),
                onClick = { onPlayerClick?.invoke(2) },
                onLongClick = { onPlayerLongClick?.invoke(2) }
            )
            
            PlayerCell(
                config = playerConfigs.getOrNull(3) ?: PlayerConfig(index = 3),
                settings = settings,
                modifier = Modifier.weight(1f),
                onClick = { onPlayerClick?.invoke(3) },
                onLongClick = { onPlayerLongClick?.invoke(3) }
            )
        }
    }
}

/**
 * 1x4 横向布局
 */
@Composable
private fun Row1x4Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onPlayerClick: ((Int) -> Unit)? = null,
    onPlayerLongClick: ((Int) -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        for (i in 0..3) {
            PlayerCell(
                config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                settings = settings,
                modifier = Modifier.weight(1f),
                onClick = { onPlayerClick?.invoke(i) },
                onLongClick = { onPlayerLongClick?.invoke(i) }
            )
        }
    }
}

/**
 * 4x1 纵向布局
 */
@Composable
private fun Column4x1Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onPlayerClick: ((Int) -> Unit)? = null,
    onPlayerLongClick: ((Int) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        for (i in 0..3) {
            PlayerCell(
                config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                settings = settings,
                modifier = Modifier.weight(1f),
                onClick = { onPlayerClick?.invoke(i) },
                onLongClick = { onPlayerLongClick?.invoke(i) }
            )
        }
    }
}

/**
 * 单个播放器单元格
 */
@Composable
private fun PlayerCell(
    config: PlayerConfig,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(1.dp)
            .border(1.dp, Color.DarkGray)
    ) {
        MediaPlayerView(
            playerConfig = config,
            settings = settings,
            modifier = Modifier.fillMaxSize(),
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}
