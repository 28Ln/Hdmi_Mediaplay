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
        
        // U盘状态指示器
        UsbStatusIndicator(
            usbState = usbState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
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
 * U盘状态指示器
 */
@Composable
private fun UsbStatusIndicator(
    usbState: MainViewModel.UsbState,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (usbState) {
        is MainViewModel.UsbState.Disconnected -> "U盘未连接" to Color.Gray
        is MainViewModel.UsbState.Connected -> {
            if (usbState.hasMediaContent) {
                "U盘已连接 ✓" to Color.Green
            } else {
                "U盘已连接 (无媒体)" to Color.Yellow
            }
        }
    }
    
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
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
        color = Color.Black.copy(alpha = 0.8f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (progress.isComplete) {
                Text(
                    text = "✓ 拷贝完成",
                    color = Color.Green,
                    fontSize = 16.sp
                )
            } else {
                Text(
                    text = "正在拷贝播放器 ${progress.playerIndex + 1} 的内容...",
                    color = Color.White,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.width(200.dp),
                    color = Color.Cyan
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "${(progress.progress * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 12.sp
                )
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
    // 默认隐藏，鼠标悬停或触摸时显示
    var isVisible by remember { mutableStateOf(false) }
    
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onPlayAllClick) {
                Text("▶ 全部播放", color = Color.White, fontSize = 12.sp)
            }
            
            TextButton(onClick = onPauseAllClick) {
                Text("⏸ 全部暂停", color = Color.White, fontSize = 12.sp)
            }
            
            TextButton(onClick = onScanUsbClick) {
                Text("🔍 扫描U盘", color = Color.White, fontSize = 12.sp)
            }
            
            TextButton(onClick = onSettingsClick) {
                Text("⚙ 设置", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
