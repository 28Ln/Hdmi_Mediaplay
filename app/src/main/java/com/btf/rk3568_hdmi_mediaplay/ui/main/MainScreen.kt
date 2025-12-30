package com.btf.rk3568_hdmi_mediaplay.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastMessage
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.OverwriteDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.PlayerMenuDialog
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.delay

/**
 * 主界面 - 支持中英文
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onSelectFile: ((Int) -> Unit)? = null
) {
    val settings by viewModel.settings.collectAsState()
    val playerConfigs by viewModel.playerConfigs.collectAsState()
    val usbState by viewModel.usbState.collectAsState()
    val copyProgress by viewModel.copyProgress.collectAsState()
    val showOverwriteDialog by viewModel.showOverwriteDialog.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    
    // 确保语言同步
    LaunchedEffect(settings.language) {
        StringResources.setLanguage(settings.language)
    }
    
    var selectedPlayerIndex by remember { mutableStateOf<Int?>(null) }
    var showBottomBar by remember { mutableStateOf(false) }
    
    LaunchedEffect(showBottomBar) {
        if (showBottomBar) {
            delay(5000)
            showBottomBar = false
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        QuadPlayerLayout(
            playerConfigs = playerConfigs,
            settings = settings,
            modifier = Modifier.fillMaxSize(),
            onPlayerClick = { index -> viewModel.togglePlayPause(index) },
            onPlayerLongClick = { index -> selectedPlayerIndex = index }
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showBottomBar = true },
                        onPress = { showBottomBar = true }
                    )
                }
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            HelpTip(onShowBottomBar = { showBottomBar = true })
            UsbStatusIndicator(usbState = usbState)
        }
        
        ToastMessage(
            toastData = toastMessage,
            onDismiss = { viewModel.dismissToast() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
        )
        
        copyProgress?.let { progress ->
            CopyProgressOverlay(
                progress = progress,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        AnimatedVisibility(
            visible = showBottomBar,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControlBar(
                onSettingsClick = onNavigateToSettings,
                onPlayAllClick = { viewModel.playAll() },
                onPauseAllClick = { viewModel.pauseAll() },
                onScanUsbClick = { viewModel.scanUsb() },
                onHide = { showBottomBar = false },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    
    if (showOverwriteDialog) {
        OverwriteDialog(
            onConfirm = { viewModel.confirmOverwrite() },
            onCancel = { viewModel.cancelOverwrite() }
        )
    }
    
    selectedPlayerIndex?.let { index ->
        PlayerMenuDialog(
            playerIndex = index,
            playerConfig = playerConfigs.getOrNull(index),
            onDismiss = { selectedPlayerIndex = null },
            onTogglePlayPause = { viewModel.togglePlayPause(index) },
            onToggleMute = { viewModel.toggleMute(index) },
            onSelectFile = {
                selectedPlayerIndex = null
                onSelectFile?.invoke(index)
            },
            onClearContent = { viewModel.setMediaFiles(index, emptyList()) },
            onScanLocal = { viewModel.scanLocalMedia(index) }
        )
    }
}

/**
 * 帮助提示 - 支持中英文
 */
@Composable
private fun HelpTip(onShowBottomBar: () -> Unit = {}) {
    var showHelp by remember { mutableStateOf(true) }
    
    if (showHelp) {
        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.small,
            onClick = { showHelp = false }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "💡 ${StringResources.helpTitle}",
                    color = Color.Cyan,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = StringResources.helpTapPlayer, color = Color.White, fontSize = 10.sp)
                Text(text = StringResources.helpLongPressPlayer, color = Color.White, fontSize = 10.sp)
                Text(text = StringResources.helpTouchBottom, color = Color.White, fontSize = 10.sp)
                Text(text = StringResources.helpTapToClose, color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

/**
 * U盘状态指示器 - 简化版，不显示具体状态
 */
@Composable
private fun UsbStatusIndicator(
    usbState: MainViewModel.UsbState,
    modifier: Modifier = Modifier
) {
    // 简化显示，只在有内容时显示绿色
    val hasContent = usbState is MainViewModel.UsbState.Connected && usbState.hasMediaContent
    
    if (hasContent) {
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
                Text(text = "✅", fontSize = 14.sp)
                Text(text = StringResources.usbConnected, color = Color.Green, fontSize = 12.sp)
            }
        }
    }
    // 不显示"未连接"或"无媒体"状态，避免误导
}

/**
 * 拷贝进度覆盖层 - 支持中英文
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
                    Text(text = StringResources.copyFailed, color = Color.Red, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = progress.error, color = Color.Gray, fontSize = 12.sp)
                }
                
                progress.isComplete -> {
                    Text(text = "✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = StringResources.copyComplete, color = Color.Green, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = StringResources.startingPlayback, color = Color.Gray, fontSize = 12.sp)
                }
                
                else -> {
                    Text(text = "📁", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = StringResources.copying, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = StringResources.playerProgress(progress.playerIndex + 1, 4),
                        color = Color.Cyan,
                        fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.width(240.dp).height(8.dp),
                        color = Color.Cyan,
                        trackColor = Color.DarkGray
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "${(progress.progress * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = StringResources.doNotRemoveUsb, color = Color.Yellow, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 底部控制栏 - 支持中英文
 */
@Composable
private fun BottomControlBar(
    onSettingsClick: () -> Unit,
    onPlayAllClick: () -> Unit,
    onPauseAllClick: () -> Unit,
    onScanUsbClick: () -> Unit,
    onHide: () -> Unit = {},
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
            ControlButton(icon = "▶", text = StringResources.playAll, onClick = onPlayAllClick)
            ControlButton(icon = "⏸", text = StringResources.pauseAll, onClick = onPauseAllClick)
            ControlButton(icon = "🔍", text = StringResources.scanUsb, onClick = onScanUsbClick)
            ControlButton(icon = "⚙", text = StringResources.settings, onClick = onSettingsClick)
            
            TextButton(
                onClick = onHide,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
            ) {
                Text(text = "✕", fontSize = 16.sp)
            }
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
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
    ) {
        Text(text = "$icon $text", fontSize = 13.sp)
    }
}
