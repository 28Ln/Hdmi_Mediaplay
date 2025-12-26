package com.btf.rk3568_hdmi_mediaplay.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 消息类型
 */
enum class MessageType {
    INFO,       // 信息
    SUCCESS,    // 成功
    WARNING,    // 警告
    ERROR       // 错误
}

/**
 * 消息数据
 */
data class ToastData(
    val message: String,
    val type: MessageType = MessageType.INFO,
    val durationMs: Long = 3000,
    val id: Long = System.currentTimeMillis()  // 唯一ID，用于区分消息
)

/**
 * Toast 消息组件
 * 修复: 使用唯一ID避免消息重叠问题
 */
@Composable
fun ToastMessage(
    toastData: ToastData?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentToast by remember { mutableStateOf<ToastData?>(null) }
    var visible by remember { mutableStateOf(false) }
    
    // 当新消息到来时，立即显示（取消前一个）
    LaunchedEffect(toastData?.id) {
        if (toastData != null) {
            // 如果有新消息，先隐藏旧的
            if (visible) {
                visible = false
                delay(100)  // 短暂延迟让动画完成
            }
            
            currentToast = toastData
            visible = true
            
            // 等待显示时间
            delay(toastData.durationMs)
            
            // 只有当前显示的消息ID匹配时才隐藏
            if (currentToast?.id == toastData.id) {
                visible = false
                delay(300)  // 等待退出动画
                
                // 再次检查，避免新消息被误清除
                if (currentToast?.id == toastData.id) {
                    try {
                        onDismiss()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    
    AnimatedVisibility(
        visible = visible && currentToast != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        currentToast?.let { data ->
            val (backgroundColor, icon) = when (data.type) {
                MessageType.INFO -> Color(0xFF2196F3) to "ℹ️"
                MessageType.SUCCESS -> Color(0xFF4CAF50) to "✅"
                MessageType.WARNING -> Color(0xFFFF9800) to "⚠️"
                MessageType.ERROR -> Color(0xFFF44336) to "❌"
            }
            
            Box(
                modifier = Modifier
                    .background(backgroundColor, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = icon, fontSize = 18.sp)
                    Text(
                        text = data.message,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
