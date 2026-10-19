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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbRuntimeState
import com.btf.rk3568_hdmi_mediaplay.ui.customerUiMode
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.ui.player.PlaybackSync
import com.btf.rk3568_hdmi_mediaplay.ui.components.ToastMessage
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.OverwriteDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.PlayerMenuDialog
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.delay

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
    // 必须在**组合期间**同步设置，不能放进 LaunchedEffect ——
    // LaunchedEffect 在组合完成后才执行，因此本次组合里所有
    // StringResources.xxx 读到的仍是旧语言，界面会先用旧文案渲染一帧，
    // 下一帧才更新（HelpTip 的 remember(lang) 也是在这一帧读到旧值）。
    // 这里只是给一个单例字段赋值，无副作用、可在组合期安全执行。
    StringResources.setLanguage(settings.language)

    // 「2×2 同画面」多路同步：四格是独立播放器，会各自漂移导致越播越不同步。
    //
    // **默认关闭**（PlaybackSync.ENABLED_BY_DEFAULT = false）：
    // 同步依赖 seekTo，每次 seek 都会重新缓冲，实测表现为「播 1 秒转一次圈」，
    // 比不同步更难用。保留代码作为实验路径，需要时改常量即可开启。
    LaunchedEffect(settings.layoutMode) {
        PlaybackSync.enabled = PlaybackSync.ENABLED_BY_DEFAULT &&
            settings.layoutMode.mirrorsPrimaryPlayer

        // 只有**离开**「同画面」模式才清空注册表。
        //
        // 这里不能写成 `if (!enabled) { clear(); return }` ——
        // 周期同步默认关闭，那样会在进入 SAME 模式时立刻清空注册表，
        // 使起始对齐（ALIGN_ON_START）拿不到主格与跟随格，对齐必然失败。
        // 注册由 VideoPlayerView 的 SideEffect 在每次组合时刷新。
        if (!settings.layoutMode.mirrorsPrimaryPlayer) {
            PlaybackSync.clear()
            return@LaunchedEffect
        }
        if (!PlaybackSync.enabled) return@LaunchedEffect

        while (true) {
            delay(PlaybackSync.CHECK_INTERVAL_MS)
            val corrected = PlaybackSync.syncOnce()
            if (corrected > 0) {
                Log.d("MainScreen", "同画面同步：纠正 $corrected 路")
            }
        }
    }
    
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
        
        // 受 settings.showCopyProgress 控制。
        // 该开关此前只存在于 Debug 的高级设置里、且**没有任何代码读取** —— 是失效开关。
        // 现场有时不希望进度浮层遮挡画面（例如演出/展示场景），故接上。
        if (settings.showCopyProgress) {
            copyProgress?.let { progress ->
                CopyProgressOverlay(
                    progress = progress,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
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
                            // 不再脉冲 GPIO 按键（新固件已移除 hdmi_b2）：
                            // 改为推进到下一个拼接墙布局，由 ViewModel 走 I2C 下发。
                            // 这样也不会切到 App 不展示的 1X4 / 4X1。
                            viewModel.cycleWallLayout()
                        },
                        onSwitchResolutionClick = {
                            keepBottomBarVisible()
                            // 分辨率由 MCU 保存，不写 App 设置；失败按 errno 给结构化提示
                            viewModel.cycleResolution()
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
    // ## 为什么需要显式处理 lang
    //
    // `StringResources` 是普通单例，不是 Compose State。语言切换的时序是：
    //   1. settings.language 变化 → 本组件重组（此时 StringResources 仍是旧语言）
    //   2. LaunchedEffect(settings.language) 才调用 setLanguage 更新它
    //   3. 没有第三次重组 → 文案停在旧语言
    //
    // 因此必须用 lang 主动触发一次重组。
    //
    // ## 为什么不用 key(lang)
    //
    // 曾经用 `key(lang) { HelpTipContent() }`，但那会重建整个子树，
    // 把 `remember { mutableStateOf(true) }` 一起重置 ——
    // 表现为"切换语言后，用户已经关掉的帮助提示又弹出来"。
    //
    // 正确做法：把 lang 作为 remember 的 key 之一，既让读取发生在
    // lang 变化后的重组里，又保留用户关闭过的事实。
    val helpTitle = remember(lang) { StringResources.helpTitle }
    val helpTapPlayer = remember(lang) { StringResources.helpTapPlayer }
    val helpLongPress = remember(lang) { StringResources.helpLongPressPlayer }
    val helpTouchBottom = remember(lang) { StringResources.helpTouchBottom }
    val helpClose = remember(lang) { StringResources.helpTapToClose }
    HelpTipContent(helpTitle, helpTapPlayer, helpLongPress, helpTouchBottom, helpClose)
}

@Composable
private fun HelpTipContent(
    helpTitle: String,
    helpTapPlayer: String,
    helpLongPress: String,
    helpTouchBottom: String,
    helpClose: String
) {
    // 不随语言重置：用户关掉就不再弹出
    var showHelp by remember { mutableStateOf(true) }

    if (showHelp) {
        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.small,
            onClick = { showHelp = false }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "💡 $helpTitle",
                    color = Color.Cyan,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = helpTapPlayer, color = Color.White, fontSize = 10.sp)
                Text(text = helpLongPress, color = Color.White, fontSize = 10.sp)
                Text(text = helpTouchBottom, color = Color.White, fontSize = 10.sp)
                Text(text = helpClose, color = Color.Gray, fontSize = 10.sp)
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
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        // "✕" 是符号字形，TalkBack 只会念符号本身（或直接念不出），
                        // 用户无法判断这个按钮做什么；用文字描述覆盖掉字形文本
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = StringResources.close
                        }
                    )
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
        // 图标是 emoji（▶/⏸/🔁/🖥/⚙），单独暴露给 TalkBack 会被念成
        // "black square" 之类的噪声。emoji 用 clearAndSetSemantics 静音，
        // 再让 Column 合并后代语义，朗读出来就只有按钮文字（如"全部播放"）。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics(mergeDescendants = true) {}
        ) {
            Text(text = icon, fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
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
        // 单个 Text 里拼了 emoji（▶/⏸/🔍/✂️/⚙），TalkBack 会先念出
        // "black square" 之类的字形再念文字。这里用纯文字语义替换整个节点，
        // 保留按钮原有的可点击语义（clearAndSetSemantics 只清后代节点）。
        Text(
            text = "$icon $text",
            fontSize = 13.sp,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = text
            }
        )
    }
}


