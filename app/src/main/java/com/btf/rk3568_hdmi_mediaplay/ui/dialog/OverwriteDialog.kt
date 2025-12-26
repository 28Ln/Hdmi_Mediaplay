package com.btf.rk3568_hdmi_mediaplay.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 覆盖确认对话框
 */
@Composable
fun OverwriteDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "📁", fontSize = 24.sp)
                Text(
                    text = "检测到U盘内容",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "发现U盘中包含媒体文件，是否用U盘内容覆盖本地缓存？",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                
                Divider(color = Color.DarkGray)
                
                // 警告信息
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "⚠️", fontSize = 16.sp)
                    Column {
                        Text(
                            text = "注意事项：",
                            color = Color.Yellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• 覆盖后本地原有内容将被替换\n• 拷贝过程中请勿拔出U盘\n• 大文件拷贝可能需要几分钟",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                
                Divider(color = Color.DarkGray)
                
                // 提示
                Text(
                    text = "💡 提示：可在设置中关闭此确认提示，实现自动覆盖",
                    color = Color.Cyan,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("✓ 确认覆盖", fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text("✕ 取消", fontSize = 14.sp)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

/**
 * 清除缓存确认对话框
 */
@Composable
fun ClearCacheDialog(
    cacheSizeMB: Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "🗑️", fontSize = 24.sp)
                Text(
                    text = "清除缓存",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "确定要清除所有本地缓存吗？",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                
                Text(
                    text = "当前缓存大小: ${cacheSizeMB}MB",
                    color = Color.Cyan,
                    fontSize = 12.sp
                )
                
                Text(
                    text = "⚠️ 清除后所有播放器将停止播放，需要重新从U盘加载内容",
                    color = Color.Yellow,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                )
            ) {
                Text("确认清除", fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text("取消", fontSize = 14.sp)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

/**
 * 重置设置确认对话框
 */
@Composable
fun ResetSettingsDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "🔄 重置设置",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "确定要将所有设置恢复为默认值吗？\n\n此操作不会清除缓存的媒体文件。",
                color = Color.LightGray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray
                )
            ) {
                Text("确认重置", fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text("取消", fontSize = 14.sp)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}
