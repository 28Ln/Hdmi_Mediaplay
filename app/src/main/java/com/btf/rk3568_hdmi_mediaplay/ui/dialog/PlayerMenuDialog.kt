package com.btf.rk3568_hdmi_mediaplay.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.data.model.FeatureFlags
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerConfig
import com.btf.rk3568_hdmi_mediaplay.data.model.PlayerState
import com.btf.rk3568_hdmi_mediaplay.util.StringResources

/**
 * 播放器菜单对话框 - 支持中英文，根据功能开关显示选项
 */
@Composable
fun PlayerMenuDialog(
    playerIndex: Int,
    playerConfig: PlayerConfig?,
    featureFlags: FeatureFlags = FeatureFlags(),
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectFile: () -> Unit,
    onClearContent: (() -> Unit)? = null,
    onScanLocal: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📺 ${StringResources.playerN(playerIndex + 1)}",
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
                        label = StringResources.menuStatus,
                        value = StringResources.getPlayerStateText(config.state),
                        valueColor = getStateColor(config.state)
                    )
                    
                    // 文件数量
                    if (config.mediaItems.isNotEmpty()) {
                        val videoCount = config.mediaItems.count { it.type == MediaType.VIDEO }
                        val imageCount = config.mediaItems.count { it.type == MediaType.IMAGE }
                        
                        InfoRow(
                            label = StringResources.menuContent,
                            value = buildString {
                                if (videoCount > 0) append(StringResources.nVideos(videoCount))
                                if (videoCount > 0 && imageCount > 0) append(", ")
                                if (imageCount > 0) append(StringResources.nImages(imageCount))
                            }
                        )
                        
                        // 当前播放文件
                        config.mediaItems.getOrNull(config.currentIndex)?.let { current ->
                            InfoRow(
                                label = StringResources.menuCurrent,
                                value = current.name,
                                valueColor = Color.Cyan
                            )
                        }
                    }
                    
                    // 音量状态 - 根据功能开关
                    if (featureFlags.allowVolumeControl) {
                        InfoRow(
                            label = StringResources.menuVolume,
                            value = if (config.isMuted) StringResources.mute else "${(config.volume * 100).toInt()}%"
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))
                
                // 操作按钮 - 根据功能开关显示
                
                // 播放/暂停
                if (featureFlags.allowPlayPauseControl) {
                    MenuButton(
                        icon = if (playerConfig?.state == PlayerState.PLAYING) "⏸" else "▶",
                        text = if (playerConfig?.state == PlayerState.PLAYING) StringResources.pause else StringResources.play,
                        onClick = {
                            onTogglePlayPause()
                            onDismiss()
                        }
                    )
                }
                
                // 静音/取消静音
                if (featureFlags.allowVolumeControl) {
                    MenuButton(
                        icon = if (playerConfig?.isMuted == true) "🔊" else "🔇",
                        text = if (playerConfig?.isMuted == true) StringResources.unmute else StringResources.mute,
                        onClick = {
                            onToggleMute()
                            onDismiss()
                        }
                    )
                }
                
                // 选择文件
                if (featureFlags.allowManualFileSelect) {
                    MenuButton(
                        icon = "📁",
                        text = StringResources.selectFile,
                        onClick = onSelectFile
                    )
                }
                
                // 扫描本地媒体
                if (featureFlags.allowLocalMediaScan) {
                    MenuButton(
                        icon = "🔍",
                        text = StringResources.scanLocalMedia,
                        onClick = {
                            onScanLocal?.invoke()
                            onDismiss()
                        }
                    )
                }
                
                // 清除内容
                if (featureFlags.allowClearCache && playerConfig?.mediaItems?.isNotEmpty() == true) {
                    MenuButton(
                        icon = "🗑",
                        text = StringResources.clearContent,
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
                Text(StringResources.close, color = Color.White)
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

private fun getStateColor(state: PlayerState): Color {
    return when (state) {
        PlayerState.IDLE -> Color.Gray
        PlayerState.LOADING -> Color.Yellow
        PlayerState.PLAYING -> Color.Green
        PlayerState.PAUSED -> Color.Yellow
        PlayerState.ERROR -> Color.Red
    }
}
