package com.btf.rk3568_hdmi_mediaplay.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSelectFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "播放器 ${playerIndex + 1}")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 当前状态
                playerConfig?.let { config ->
                    Text(
                        text = "状态: ${getStateText(config.state)}",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    
                    if (config.mediaItems.isNotEmpty()) {
                        Text(
                            text = "文件数: ${config.mediaItems.size}",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 操作按钮
                MenuButton(
                    text = if (playerConfig?.state == PlayerState.PLAYING) "⏸ 暂停" else "▶ 播放",
                    onClick = {
                        onTogglePlayPause()
                        onDismiss()
                    }
                )
                
                MenuButton(
                    text = if (playerConfig?.isMuted == true) "🔊 取消静音" else "🔇 静音",
                    onClick = {
                        onToggleMute()
                        onDismiss()
                    }
                )
                
                MenuButton(
                    text = "📁 选择文件",
                    onClick = onSelectFile
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        containerColor = Color.DarkGray,
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

@Composable
private fun MenuButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp
        )
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
