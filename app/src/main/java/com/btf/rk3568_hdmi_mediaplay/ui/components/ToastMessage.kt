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
    val durationMs: Long = 3000
)

/**
 * Toast 消息组件
 */
@Composable
fun ToastMessage(
    toastData: ToastData?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(toastData) {
        if (toastData != null) {
            visible = true
            delay(toastData.durationMs)
            visible = false
            delay(300) // 等待动画完成
            onDismiss()
        }
    }
    
    AnimatedVisibility(
        visible = visible && toastData != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        toastData?.let { data ->
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
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * 全局消息管理器
 */
object ToastManager {
    private val _currentToast = mutableStateOf<ToastData?>(null)
    val currentToast: State<ToastData?> = _currentToast
    
    fun show(message: String, type: MessageType = MessageType.INFO, durationMs: Long = 3000) {
        _currentToast.value = ToastData(message, type, durationMs)
    }
    
    fun showInfo(message: String) = show(message, MessageType.INFO)
    fun showSuccess(message: String) = show(message, MessageType.SUCCESS)
    fun showWarning(message: String) = show(message, MessageType.WARNING)
    fun showError(message: String) = show(message, MessageType.ERROR, 5000)
    
    fun dismiss() {
        _currentToast.value = null
    }
}
