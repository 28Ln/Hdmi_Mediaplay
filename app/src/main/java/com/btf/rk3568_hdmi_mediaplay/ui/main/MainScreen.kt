package com.btf.rk3568_hdmi_mediaplay.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastMessage
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.OverwriteDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.PlayerMenuDialog

/**
 * 主界面
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    val playerConfigs by viewModel.playerConfigs.collectAsState()
    val usbState by viewModel.usbState.collectAsState()
    val copyProgress by viewModel.copyProgress.collectAsState()
    val showOverwriteDialog by viewModel.showOverwriteDialog.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    
    // 选中的播放器索引（用于显示菜单）
    var selectedPlayerIndex by remember { mutableStateOf<Int?>(null) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        // 四宫格播放器布局
        QuadPlayerLayout(
            playerConfigs = playerConfigs,
            settings = settings,
            modifier = Modifier.fillMaxSize(),
            onPlayerClick = { index ->
                // 单击切换播放/暂停
                viewModel.togglePlayPause(index)
            },
            onPlayerLongClick = { index ->
                // 长按显示菜单
                selectedPlayerIndex = index
            }
        )
        
        // 顶部状态栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // 帮助提示
            HelpTip()
            
            // U盘状态指示器
            UsbStatusIndicator(usbState = usbState)
        }
        
        // Toast 消息
        ToastMessage(
            toastData = toastMessage,
            onDismiss = { viewModel.dismissToast() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
        )
        
        // 拷贝进度
        copyProgress?.let { progress ->
            CopyProgressOverlay(
                progress = progress,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // 底部控制栏
        BottomControlBar(
            onSettingsClick = onNavigateToSettings,
            onPlayAllClick = { viewModel.playAll() },
            onPauseAllClick = { viewModel.pauseAll() },
            onScanUsbClick = { viewModel.scanUsb() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
    
    // 覆盖确认对话框
    if (showOverwriteDialog) {
        OverwriteDialog(
            onConfirm = { viewModel.confirmOverwrite() },
            onCancel = { viewModel.cancelOverwrite() }
        )
    }
    
    // 播放器菜单对话框
    selectedPlayerIndex?.let { index ->
        PlayerMenuDialog(
            playerIndex = index,
            playerConfig = playerConfigs.getOrNull(index),
            onDismiss = { selectedPlayerIndex = null },
            onTogglePlayPause = { viewModel.togglePlayPause(index) },
            onToggleMute = { viewModel.toggleMute(index) },
            onSelectFile = {
                // TODO: 打开文件选择器
                selectedPlayerIndex = null
            }
        )
    }
}

/**
 * 帮助提示
 */
@Composable
private fun HelpTip() {
    var showHelp by remember { mutableStateOf(true) }
    
    if (showHelp) {
        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.small,
            onClick = { showHelp = false }
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "💡 操作提示",
                    color = Color.Cyan,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• 单击播放器: 播放/暂停",
                    color = Color.White,
                    fontSize = 10.sp
                )
                Text(
                    text = "• 长按播放器: 打开菜单",
                    color = Color.White,
                    fontSize = 10.sp
                )
                Text(
                    text = "• 点击此处关闭提示",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * U盘状态指示器
 */
@Composable
private fun UsbStatusIndicator(
    usbState: MainViewModel.UsbState,
    modifier: Modifier = Modifier
) {
    val (text, color, icon) = when (usbState) {
        is MainViewModel.UsbState.Disconnected -> Triple("U盘未连接", Color.Gray, "🔌")
        is MainViewModel.UsbState.Connected -> {
            if (usbState.hasMediaContent) {
                Triple("U盘已连接", Color.Green, "✅")
            } else {
                Triple("U盘无媒体", Color.Yellow, "⚠️")
            }
        }
        is MainViewModel.UsbState.Error -> Triple("U盘错误", Color.Red, "❌")
    }
    
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = icon, fontSize = 14.sp)
            Text(
                text = text,
                color = color,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * 拷贝进度覆盖层
 */
@Composable
private fun CopyProgressOverlay(
    progress: MainViewModel.CopyProgress,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                progress.error != null -> {
                    Text(text = "❌", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "拷贝失败",
                        color = Color.Red,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progress.error,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                
                progress.isComplete -> {
                    Text(text = "✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "拷贝完成！",
                        color = Color.Green,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "即将开始播放...",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                
                else -> {
                    Text(text = "📁", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在拷贝文件",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "播放器 ${progress.playerIndex + 1} / 4",
                        color = Color.Cyan,
                        fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier
                            .width(240.dp)
                            .height(8.dp),
                        color = Color.Cyan,
                        trackColor = Color.DarkGray
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "${(progress.progress * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "请勿拔出U盘...",
                        color = Color.Yellow,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 底部控制栏
 */
@Composable
private fun BottomControlBar(
    onSettingsClick: () -> Unit,
    onPlayAllClick: () -> Unit,
    onPauseAllClick: () -> Unit,
    onScanUsbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.8f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(
                icon = "▶",
                text = "全部播放",
                onClick = onPlayAllClick
            )
            
            ControlButton(
                icon = "⏸",
                text = "全部暂停",
                onClick = onPauseAllClick
            )
            
            ControlButton(
                icon = "🔍",
                text = "扫描U盘",
                onClick = onScanUsbClick
            )
            
            ControlButton(
                icon = "⚙",
                text = "设置",
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: String,
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White
        )
    ) {
        Text(text = "$icon $text", fontSize = 13.sp)
    }
}
