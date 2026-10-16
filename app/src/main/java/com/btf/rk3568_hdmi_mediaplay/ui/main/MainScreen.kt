package com.btf.rk3568_hdmi_mediaplay.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbRuntimeState
import com.btf.rk3568_hdmi_mediaplay.ui.customerUiMode
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.ui.components.MessageType
import com.btf.rk3568_hdmi_mediaplay.ui.player.PlaybackSync
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastMessage
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiGpioController
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.OverwriteDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.PlayerMenuDialog
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BOTTOM_BAR_VISIBLE_MS = 15_000L

/**
 * 主界面 - 支持中英文
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToImageSplit: () -> Unit = {},
    onSelectFile: ((Int) -> Unit)? = null,
    onScanUsb: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    val playerConfigs by viewModel.playerConfigs.collectAsState()
    val usbState by viewModel.usbState.collectAsState()
    val copyProgress by viewModel.copyProgress.collectAsState()
    val showOverwriteDialog by viewModel.showOverwriteDialog.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    
    // 功能开关。
    // 注意：这里**不能**按 customerUiMode 替换成 releaseDefaults()——那会丢弃
    // FeatureManager 中已应用的 U 盘 btf_config.json features 覆盖，
    // 导致「文档承诺可用 U 盘配置开启功能」在 Release 包中失效。
    // 客户版与开发版的默认差异已由 FeatureManager.init(!customerUiMode) 决定。
    val featureFlags by FeatureManager.featureFlags.collectAsState()
    
    // 确保语言同步
    LaunchedEffect(settings.language) {
        StringResources.setLanguage(settings.language)
    }

    // 「2×2 同画面」多路同步：四格是独立播放器，会各自漂移导致越播越不同步。
    // 以 P1 为主时钟，每 5 秒检查一次，偏差超过 1.5 秒才 seek 对齐（见 PlaybackSync）。
    LaunchedEffect(settings.layoutMode) {
        PlaybackSync.enabled = settings.layoutMode.mirrorsPrimaryPlayer
        if (!PlaybackSync.enabled) {
            PlaybackSync.clear()
            return@LaunchedEffect
        }
        while (true) {
            delay(PlaybackSync.CHECK_INTERVAL_MS)
            val corrected = PlaybackSync.syncOnce()
            if (corrected > 0) {
                Log.d("MainScreen", "同画面同步：纠正 $corrected 路")
            }
        }
    }
    
    val scope = rememberCoroutineScope()
    var selectedPlayerIndex by remember { mutableStateOf<Int?>(null) }
    var showBottomBar by remember { mutableStateOf(false) }
    var bottomBarInteractionTick by remember { mutableLongStateOf(0L) }
    val keepBottomBarVisible = {
        showBottomBar = true
        bottomBarInteractionTick++
    }
    
    LaunchedEffect(showBottomBar, bottomBarInteractionTick) {
        if (showBottomBar) {
            delay(BOTTOM_BAR_VISIBLE_MS)
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
            showPlayerIndex = featureFlags.showPlayerIndex,
            modifier = Modifier.fillMaxSize(),
            onPlayerClick = { index ->
                if (featureFlags.allowPlayPauseControl) {
                    viewModel.togglePlayPause(index)
                }
            },
            onPlayerLongClick = { index -> selectedPlayerIndex = index },
            onPlaybackCompleted = { playerIndex, mediaPath, nextIndex ->
                viewModel.onPlayerPlaybackCompleted(playerIndex, mediaPath, nextIndex)
            },
            onPlaybackError = { playerIndex, errorMessage ->
                viewModel.setPlayerError(playerIndex, errorMessage)
            },
            onPlayerRecovered = { playerIndex ->
                viewModel.clearPlayerError(playerIndex)
            },
            onCurrentIndexChanged = { playerIndex, currentIndex ->
                viewModel.updatePlayerCurrentIndex(playerIndex, currentIndex)
            }
        )
        
        // 底部触发区域 - 根据功能开关决定是否显示
        if (featureFlags.showBottomControlBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (!customerUiMode) 60.dp else 96.dp)
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { keepBottomBarVisible() },
                            onPress = { keepBottomBarVisible() }
                        )
                    }
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // 帮助提示 - 根据功能开关
            if (featureFlags.showHelpTip) {
                HelpTip(lang = settings.language)
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
            
            // U盘状态 - 根据功能开关
            if (featureFlags.showUsbStatus) {
                UsbStatusIndicator(usbState = usbState)
            }
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
        
        // 底部控制栏 - 根据功能开关
        if (featureFlags.showBottomControlBar) {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = if (customerUiMode) 0.18f else 0.12f),
                                    Color.Black.copy(alpha = if (customerUiMode) 0.34f else 0.22f)
                                )
                            )
                        )
                        .padding(top = if (customerUiMode) 34.dp else 16.dp)
                ) {
                    BottomControlBar(
                        featureFlags = featureFlags,
                        onSettingsClick = { keepBottomBarVisible(); onNavigateToSettings() },
                        onImageSplitClick = { keepBottomBarVisible(); onNavigateToImageSplit() },
                        onPlayAllClick = { keepBottomBarVisible(); viewModel.playAll() },
                        onPauseAllClick = { keepBottomBarVisible(); viewModel.pauseAll() },
                        onScanUsbClick = { keepBottomBarVisible(); onScanUsb() },
                        onSwitchModeClick = {
                            keepBottomBarVisible()
                            scope.launch(Dispatchers.IO) {
                                val ok = HdmiGpioController.press(HdmiGpioController.MODE_KEY)
                                // 硬件控制节点可能仅 root 可写，失败时必须让用户看到，
                                // 否则按钮"按了没反应"，无法区分是坏了还是没生效
                                if (!ok) {
                                    viewModel.showToast(StringResources.hdmiKeyFailed, MessageType.ERROR)
                                }
                            }
                        },
                        onSwitchResolutionClick = {
                            keepBottomBarVisible()
                            scope.launch(Dispatchers.IO) {
                                val ok = HdmiGpioController.press(HdmiGpioController.RESOLUTION_KEY)
                                if (!ok) {
                                    viewModel.showToast(StringResources.hdmiKeyFailed, MessageType.ERROR)
                                }
                            }
                        },
                        onHide = { showBottomBar = false },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = if (customerUiMode) 34.dp else 16.dp, top = 8.dp)
                    )
                }
            }
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
            featureFlags = featureFlags,
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
private fun HelpTip(lang: AppLanguage) {
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
    usbState: UsbRuntimeState,
    modifier: Modifier = Modifier
) {
    // 简化显示，只在有内容时显示绿色
    val hasContent = usbState is UsbRuntimeState.Connected && usbState.hasMediaContent
    
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
 * 底部控制栏 - 支持中英文，根据功能开关显示按钮
 */
@Composable
private fun BottomControlBar(
    featureFlags: com.btf.rk3568_hdmi_mediaplay.data.model.FeatureFlags,
    onSettingsClick: () -> Unit,
    onImageSplitClick: () -> Unit,
    onPlayAllClick: () -> Unit,
    onPauseAllClick: () -> Unit,
    onScanUsbClick: () -> Unit,
    onSwitchModeClick: () -> Unit,
    onSwitchResolutionClick: () -> Unit,
    onHide: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val customerMode = customerUiMode

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = if (customerMode) 0.88f else 0.8f),
        shape = MaterialTheme.shapes.medium
    ) {
        if (customerMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (featureFlags.allowPlayPauseControl) {
                    CustomerControlButton("▶", StringResources.playAll, onPlayAllClick, Modifier.weight(1f))
                    CustomerControlButton("⏸", StringResources.pauseAll, onPauseAllClick, Modifier.weight(1f))
                }
                CustomerControlButton("🔁", StringResources.switchMode, onSwitchModeClick, Modifier.weight(1f))
                CustomerControlButton("🖥", StringResources.switchResolution, onSwitchResolutionClick, Modifier.weight(1.2f))
                // HDMI 全屏开关已移除：低频设置项不该占主页控制栏（设置页仍可操作）
                CustomerControlButton("⚙", StringResources.settings, onSettingsClick, Modifier.weight(0.9f))
            }
        } else {
            Row(
                // 必须 fillMaxWidth 才能让 SpaceEvenly 生效；
                // 否则 Row 按内容宽度收缩并靠左堆叠（按钮会挤在左侧）
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (featureFlags.allowPlayPauseControl) {
                    ControlButton(icon = "▶", text = StringResources.playAll, onClick = onPlayAllClick)
                    ControlButton(icon = "⏸", text = StringResources.pauseAll, onClick = onPauseAllClick)
                }
                if (featureFlags.showScanUsbButton) {
                    ControlButton(icon = "🔍", text = StringResources.scanUsb, onClick = onScanUsbClick)
                }
                if (featureFlags.showImageSplitTool) {
                    ControlButton(icon = "✂️", text = StringResources.imageSplit, onClick = onImageSplitClick)
                }
                if (featureFlags.showSettingsButton) {
                    ControlButton(icon = "⚙", text = StringResources.settings, onClick = onSettingsClick)
                }

                TextButton(
                    onClick = onHide,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                ) {
                    Text(text = "✕", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun CustomerControlButton(
    icon: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(68.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = icon, fontSize = 20.sp)
            Text(text = text, color = Color.White, fontSize = 14.sp, maxLines = 1)
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


