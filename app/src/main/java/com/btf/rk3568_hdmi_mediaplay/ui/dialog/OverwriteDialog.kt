package com.btf.rk3568_hdmi_mediaplay.ui.dialog

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
            Text(text = "检测到U盘内容")
        },
        text = {
            Text(text = "是否用U盘内容覆盖本地缓存？\n\n覆盖后本地原有内容将被替换。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("覆盖", color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        },
        containerColor = Color.DarkGray,
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}
