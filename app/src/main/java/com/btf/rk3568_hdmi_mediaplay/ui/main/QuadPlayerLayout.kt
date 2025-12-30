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
import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerConfig
import com.btf.rk3568_hdmi_mediaplay.ui.player.MediaPlayerView

/**
 * 多布局播放器组件
 * 支持多种布局模式
 */
@Composable
fun QuadPlayerLayout(
    playerConfigs: List<PlayerConfig>,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(),
    onPlayerClick: ((Int) -> Unit)? = null,
    onPlayerLongClick: ((Int) -> Unit)? = null
) {
    val backgroundColor = remember(settings.backgroundColor) { 
        Color(settings.backgroundColor) 
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        when (settings.layoutMode) {
            LayoutMode.SINGLE -> SingleLayout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.GRID_1X2 -> Grid1x2Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.GRID_2X1 -> Grid2x1Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.GRID_2X2 -> Grid2x2Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.GRID_1X3 -> Grid1x3Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.GRID_3X1 -> Grid3x1Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.ROW_1X4 -> Row1x4Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.COLUMN_4X1 -> Column4x1Layout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
            LayoutMode.PIP -> PipLayout(playerConfigs, settings, onPlayerClick, onPlayerLongClick)
        }
    }
}

/**
 * 单屏布局 - 1个播放器全屏
 */
@Composable
private fun SingleLayout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    key(0) {
        PlayerCell(
            config = playerConfigs.getOrNull(0) ?: PlayerConfig(index = 0),
            settings = settings,
            modifier = Modifier.fillMaxSize(),
            onClick = { onPlayerClick?.invoke(0) },
            onLongClick = { onPlayerLongClick?.invoke(0) }
        )
    }
}

/**
 * 1行2列布局
 */
@Composable
private fun Grid1x2Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Row(modifier = Modifier.fillMaxSize()) {
        for (i in 0..1) {
            key(i) {
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onPlayerClick?.invoke(i) },
                    onLongClick = { onPlayerLongClick?.invoke(i) }
                )
            }
        }
    }
}

/**
 * 2行1列布局
 */
@Composable
private fun Grid2x1Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        for (i in 0..1) {
            key(i) {
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onClick = { onPlayerClick?.invoke(i) },
                    onLongClick = { onPlayerLongClick?.invoke(i) }
                )
            }
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
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(0) {
                PlayerCell(
                    config = playerConfigs.getOrNull(0) ?: PlayerConfig(index = 0),
                    settings = settings,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlayerClick?.invoke(0) },
                    onLongClick = { onPlayerLongClick?.invoke(0) }
                )
            }
            key(1) {
                PlayerCell(
                    config = playerConfigs.getOrNull(1) ?: PlayerConfig(index = 1),
                    settings = settings,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlayerClick?.invoke(1) },
                    onLongClick = { onPlayerLongClick?.invoke(1) }
                )
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(2) {
                PlayerCell(
                    config = playerConfigs.getOrNull(2) ?: PlayerConfig(index = 2),
                    settings = settings,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlayerClick?.invoke(2) },
                    onLongClick = { onPlayerLongClick?.invoke(2) }
                )
            }
            key(3) {
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
}

/**
 * 1行3列布局
 */
@Composable
private fun Grid1x3Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Row(modifier = Modifier.fillMaxSize()) {
        for (i in 0..2) {
            key(i) {
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onPlayerClick?.invoke(i) },
                    onLongClick = { onPlayerLongClick?.invoke(i) }
                )
            }
        }
    }
}

/**
 * 3行1列布局
 */
@Composable
private fun Grid3x1Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        for (i in 0..2) {
            key(i) {
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onClick = { onPlayerClick?.invoke(i) },
                    onLongClick = { onPlayerLongClick?.invoke(i) }
                )
            }
        }
    }
}

/**
 * 1行4列布局
 */
@Composable
private fun Row1x4Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Row(modifier = Modifier.fillMaxSize()) {
        for (i in 0..3) {
            key(i) {
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onPlayerClick?.invoke(i) },
                    onLongClick = { onPlayerLongClick?.invoke(i) }
                )
            }
        }
    }
}

/**
 * 4行1列布局
 */
@Composable
private fun Column4x1Layout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        for (i in 0..3) {
            key(i) {
                PlayerCell(
                    config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onClick = { onPlayerClick?.invoke(i) },
                    onLongClick = { onPlayerLongClick?.invoke(i) }
                )
            }
        }
    }
}

/**
 * 画中画布局 - 1大3小
 * 布局: 主画面占左侧2/3，右侧3个小画面
 */
@Composable
private fun PipLayout(
    playerConfigs: List<PlayerConfig>,
    settings: AppSettings,
    onPlayerClick: ((Int) -> Unit)?,
    onPlayerLongClick: ((Int) -> Unit)?
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // 主画面 (播放器0)
        key(0) {
            PlayerCell(
                config = playerConfigs.getOrNull(0) ?: PlayerConfig(index = 0),
                settings = settings,
                modifier = Modifier.weight(2f).fillMaxHeight(),
                onClick = { onPlayerClick?.invoke(0) },
                onLongClick = { onPlayerLongClick?.invoke(0) }
            )
        }
        
        // 右侧3个小画面
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            for (i in 1..3) {
                key(i) {
                    PlayerCell(
                        config = playerConfigs.getOrNull(i) ?: PlayerConfig(index = i),
                        settings = settings,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onClick = { onPlayerClick?.invoke(i) },
                        onLongClick = { onPlayerLongClick?.invoke(i) }
                    )
                }
            }
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
    // 使用 config 的 mediaItems hashCode 作为额外的 key，确保内容变化时重组
    key(config.index, config.mediaItems.hashCode()) {
        Box(
            modifier = modifier
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
}
