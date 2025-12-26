package com.btf.rk3568_hdmi_mediaplay.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerConfig
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState

/**
 * 播放器菜单对话框
 */
@Composable
fun PlayerMenuDialog(
    playerIndex: Int,
    playerConfig: PlayerConfig?,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectFile: () -> Unit,
    onClearContent: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📺 播放器 ${playerIndex + 1}",
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 当前状态信息
                playerConfig?.let { config ->
                    // 状态
                    InfoRow(
                        label = "状态",
                        value = getStateText(config.state),
                        valueColor = getStateColor(config.state)
                    )
                    
                    // 文件数量
                    if (config.mediaItems.isNotEmpty()) {
                        val videoCount = config.mediaItems.count { it.type == MediaType.VIDEO }
                        val imageCount = config.mediaItems.count { it.type == MediaType.IMAGE }
                        
                        InfoRow(
                            label = "内容",
                            value = buildString {
                                if (videoCount > 0) append("$videoCount 个视频")
                                if (videoCount > 0 && imageCount > 0) append(", ")
                                if (imageCount > 0) append("$imageCount 张图片")
                            }
                        )
                        
                        // 当前播放文件
                        config.mediaItems.getOrNull(config.currentIndex)?.let { current ->
                            InfoRow(
                                label = "当前",
                                value = current.name,
                                valueColor = Color.Cyan
                            )
                        }
                    }
                    
                    // 音量状态
                    InfoRow(
                        label = "音量",
                        value = if (config.isMuted) "静音" else "${(config.volume * 100).toInt()}%"
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))
                
                // 操作按钮
                MenuButton(
                    icon = if (playerConfig?.state == PlayerState.PLAYING) "⏸" else "▶",
                    text = if (playerConfig?.state == PlayerState.PLAYING) "暂停" else "播放",
                    onClick = {
                        onTogglePlayPause()
                        onDismiss()
                    }
                )
                
                MenuButton(
                    icon = if (playerConfig?.isMuted == true) "🔊" else "🔇",
                    text = if (playerConfig?.isMuted == true) "取消静音" else "静音",
                    onClick = {
                        onToggleMute()
                        onDismiss()
                    }
                )
                
                MenuButton(
                    icon = "📁",
                    text = "选择文件",
                    onClick = onSelectFile
                )
                
                if (playerConfig?.mediaItems?.isNotEmpty() == true) {
                    MenuButton(
                        icon = "🗑",
                        text = "清除内容",
                        textColor = Color.Red,
                        onClick = {
                            onClearContent?.invoke()
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color.White)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.LightGray
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun MenuButton(
    icon: String,
    text: String,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = "$icon  $text",
                color = textColor,
                fontSize = 14.sp
            )
        }
    }
}

private fun getStateText(state: PlayerState): String {
    return when (state) {
        PlayerState.IDLE -> "空闲"
        PlayerState.LOADING -> "加载中"
        PlayerState.PLAYING -> "播放中"
        PlayerState.PAUSED -> "已暂停"
        PlayerState.ERROR -> "错误"
    }
}

private fun getStateColor(state: PlayerState): Color {
    return when (state) {
        PlayerState.IDLE -> Color.Gray
        PlayerState.LOADING -> Color.Yellow
        PlayerState.PLAYING -> Color.Green
        PlayerState.PAUSED -> Color.Yellow
        PlayerState.ERROR -> Color.Red
    }
}
