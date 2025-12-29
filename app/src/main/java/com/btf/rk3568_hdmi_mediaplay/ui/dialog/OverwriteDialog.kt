package com.btf.rk3568_hdmi_mediaplay.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.util.StringResources

/**
 * 覆盖确认对话框 - 支持中英文
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
                    text = StringResources.usbContentDetected,
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
                    text = StringResources.overwriteQuestion,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                
                HorizontalDivider(color = Color.DarkGray)
                
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "⚠️", fontSize = 16.sp)
                    Column {
                        Text(
                            text = StringResources.overwriteNotes,
                            color = Color.Yellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${StringResources.overwriteNote1}\n${StringResources.overwriteNote2}\n${StringResources.overwriteNote3}",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                
                HorizontalDivider(color = Color.DarkGray)
                
                Text(
                    text = StringResources.overwriteTip,
                    color = Color.Cyan,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(StringResources.confirmOverwrite, fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("✕ ${StringResources.cancel}", fontSize = 14.sp)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

/**
 * 清除缓存确认对话框 - 支持中英文
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
                    text = StringResources.clearCacheTitle,
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
                    text = StringResources.clearCacheQuestion,
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                
                Text(
                    text = StringResources.currentCacheSize(cacheSizeMB),
                    color = Color.Cyan,
                    fontSize = 12.sp
                )
                
                Text(
                    text = StringResources.clearCacheWarning,
                    color = Color.Yellow,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text(StringResources.confirmClear, fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(StringResources.cancel, fontSize = 14.sp)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

/**
 * 重置设置确认对话框 - 支持中英文
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
                text = StringResources.resetSettingsTitle,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = StringResources.resetSettingsQuestion,
                color = Color.LightGray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text(StringResources.confirmReset, fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(StringResources.cancel, fontSize = 14.sp)
            }
        },
        containerColor = Color(0xFF2A2A2A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}
