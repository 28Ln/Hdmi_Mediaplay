package com.btf.rk3568_hdmi_mediaplay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.BuildConfig
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.ui.components.mouseDragScroll
import com.btf.rk3568_hdmi_mediaplay.ui.customerUiMode
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.ClearCacheDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.ResetSettingsDialog
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixController
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixResult
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiResolution
import com.btf.rk3568_hdmi_mediaplay.ui.components.rememberHdmiEfullUiState
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.launch

/**
 * 设置界面 - 完整支持中英文切换
 * 核心设置放在最前面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onClearCache: () -> Unit,
    onResetSettings: () -> Unit,
    onBack: () -> Unit,
    onScanUsb: () -> Unit = {},
    onImageSplit: () -> Unit = {},
    cacheSizeMB: Long = 0,
    featureFlags: FeatureFlags = FeatureFlags.releaseDefaults(),
    /**
     * 布局选择：由 MainViewModel 负责「保存 + 下发硬件模式」的先后顺序。
     *
     * 缺省回退到直接保存，保证预览/测试调用不依赖 ViewModel。
     */
    onLayoutModeSelected: (LayoutMode) -> Unit = { onSettingsChange(settings.copy(layoutMode = it)) }
) {
    // 当语言变化时强制重组
    val lang = settings.language
    
    // 确保 StringResources 同步
    LaunchedEffect(lang) {
        StringResources.setLanguage(lang)
    }
    
    val scrollState = rememberScrollState()
    val customerMode = customerUiMode
    // 不按 customerMode 替换成 releaseDefaults()：那会丢弃 U 盘配置的 features 覆盖。
    // 客户版默认矩阵已由 FeatureManager.init(!customerUiMode) 决定。
    val effectiveFeatureFlags = featureFlags
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }


    // 使用 key 强制在语言变化时重组整个界面
    key(lang) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A))
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "⚙ ${StringResources.settings}",
                            color = Color.White,
                            fontSize = if (customerMode) 22.sp else 18.sp
                        )
                        // 版本与构建类型常驻顶部：现场排查第一步就是确认装的是哪个包，
                        // 放在「关于」区块需要滚动才能看到，容易漏。
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                                if (BuildConfig.IS_DEBUG_BUILD) "DEBUG" else "RELEASE",
                            color = if (BuildConfig.IS_DEBUG_BUILD) Color(0xFFFFD54F) else Color(0xFF81C784),
                            fontSize = if (customerMode) 13.sp else 11.sp
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← ${StringResources.back}", color = Color.White, fontSize = if (customerMode) 18.sp else 14.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2A2A2A))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .mouseDragScroll(scrollState)
                    .padding(if (customerMode) 20.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (customerMode) 20.dp else 16.dp)
            ) {
                // ========== 核心设置（布局模式在最前） ==========
                // 只有当核心设置中有可显示的内容时才显示区块
                val showCoreSettings = effectiveFeatureFlags.showLanguageSetting ||
                                       effectiveFeatureFlags.allowLayoutChange ||
                                       effectiveFeatureFlags.showAudioOutputSetting
                if (showCoreSettings) {
                    SettingsSection(title = "⭐ ${StringResources.coreSettings}") {
                    // 布局模式（核心设置的第一项：现场最常调整的就是拼接模式）
                    if (effectiveFeatureFlags.allowLayoutChange) {
                        WallLayoutModeSetting(
                            selectedMode = settings.layoutMode,
                            onModeSelected = { onLayoutModeSelected(it) }
                        )
                    }

                    // 语言设置
                    if (effectiveFeatureFlags.showLanguageSetting) {
                        DropdownSetting(
                            title = "🌐 ${StringResources.language}",
                            options = listOf(
                                AppLanguage.CHINESE.name to "中文",
                                AppLanguage.ENGLISH.name to "English"
                            ),
                            selectedValue = settings.language.name,
                            onValueChange = {
                                val newLang = AppLanguage.valueOf(it)
                                StringResources.setLanguage(newLang)
                                onSettingsChange(settings.copy(language = newLang))
                            }
                        )
                    }

                    // 音频输出
                    if (effectiveFeatureFlags.showAudioOutputSetting) {
                        DropdownSetting(
                            title = "🔊 ${StringResources.audioOutput}",
                            options = AudioOutput.entries.map { it.name to StringResources.getAudioOutputText(it) },
                            selectedValue = settings.audioOutput.name,
                            onValueChange = { onSettingsChange(settings.copy(audioOutput = AudioOutput.valueOf(it))) }
                        )
                    }
                    }
                }

                // ========== HDMI 控制（核心设置之后） ==========
                HdmiEfullSection()
                if (!customerMode && effectiveFeatureFlags.showHdmiControl) {
                    HdmiControlSection()
                }

                // ========== 使用说明 ==========
                if (effectiveFeatureFlags.showHelpSection) {
                    HelpSection()
                }
                
                // ========== 播放设置 ==========
                if (effectiveFeatureFlags.showPlaybackSettings) {
                    SettingsSection(title = "▶️ ${StringResources.playbackSettings}") {
                        SwitchSetting(
                            title = StringResources.autoPlayOnStart,
                            subtitle = StringResources.autoPlayOnStartDesc,
                            checked = settings.autoPlayOnStart,
                            onCheckedChange = { onSettingsChange(settings.copy(autoPlayOnStart = it)) }
                        )
                        
                        DropdownSetting(
                            title = StringResources.loopMode,
                            options = LoopMode.entries.map { it.name to StringResources.getLoopModeText(it) },
                            selectedValue = settings.loopMode.name,
                            onValueChange = { onSettingsChange(settings.copy(loopMode = LoopMode.valueOf(it))) }
                        )
                        
                        SliderSetting(
                            title = StringResources.defaultVolume,
                            value = settings.defaultVolume.toFloat(),
                            valueRange = 0f..100f,
                            onValueChange = { onSettingsChange(settings.copy(defaultVolume = it.toInt())) },
                            valueText = "${settings.defaultVolume}%"
                        )
                        
                        SwitchSetting(
                            title = StringResources.defaultMuted,
                            subtitle = StringResources.defaultMutedDesc,
                            checked = settings.defaultMuted,
                            onCheckedChange = { onSettingsChange(settings.copy(defaultMuted = it)) }
                        )
                    }
                }
                
                // ========== 视频设置 ==========
                if (effectiveFeatureFlags.showVideoSettings) {
                    SettingsSection(title = "🎬 ${StringResources.videoSettings}") {
                        DropdownSetting(
                            title = StringResources.videoScaleMode,
                            options = VideoScaleMode.entries.map { it.name to StringResources.getScaleModeText(it) },
                            selectedValue = settings.videoScaleMode.name,
                            onValueChange = { onSettingsChange(settings.copy(videoScaleMode = VideoScaleMode.valueOf(it))) }
                        )
                        
                        SwitchSetting(
                            title = StringResources.hardwareDecode,
                            subtitle = StringResources.hardwareDecodeDesc,
                            checked = settings.useHardwareDecode,
                            onCheckedChange = { onSettingsChange(settings.copy(useHardwareDecode = it)) }
                        )
                    }
                }
                
                // ========== 图片设置 ==========
                if (effectiveFeatureFlags.showImageSettings) {
                    SettingsSection(title = "🖼 ${StringResources.imageSettings}") {
                        SliderSetting(
                            title = StringResources.slideInterval,
                            value = settings.imageIntervalSeconds.toFloat(),
                            valueRange = 1f..30f,
                            onValueChange = { onSettingsChange(settings.copy(imageIntervalSeconds = it.toInt())) },
                            valueText = "${settings.imageIntervalSeconds}${StringResources.seconds}"
                        )
                        
                        DropdownSetting(
                            title = StringResources.transitionEffect,
                            options = ImageTransition.entries.map { it.name to StringResources.getTransitionText(it) },
                            selectedValue = settings.imageTransition.name,
                            onValueChange = { onSettingsChange(settings.copy(imageTransition = ImageTransition.valueOf(it))) }
                        )
                    }
                }
                
                // ========== 存储设置 ==========
                if (effectiveFeatureFlags.showStorageSettings) {
                    SettingsSection(title = "💿 ${StringResources.storageSettings}") {
                        DropdownSetting(
                            title = StringResources.storageLocation,
                            options = StorageLocation.entries.map { it.name to StringResources.getStorageLocationText(it) },
                            selectedValue = settings.storageLocation.name,
                            onValueChange = { onSettingsChange(settings.copy(storageLocation = StorageLocation.valueOf(it))) }
                        )
                        
                        // 显示当前存储路径
                        StoragePathInfo(settings.storageLocation)
                    }
                }
                
                // ========== U盘设置 ==========
                if (effectiveFeatureFlags.showUsbSettings) {
                    SettingsSection(title = "💾 ${StringResources.usbSettings}") {
                        SwitchSetting(
                            title = StringResources.usbDetection,
                            subtitle = StringResources.usbDetectionDesc,
                            checked = settings.usbDetectionEnabled,
                            onCheckedChange = { onSettingsChange(settings.copy(usbDetectionEnabled = it)) }
                        )
                        
                        TextInputSetting(
                            title = StringResources.scanFolderName,
                            subtitle = StringResources.scanFolderNameDesc,
                            value = settings.usbScanFolderName,
                            onValueChange = { onSettingsChange(settings.copy(usbScanFolderName = it)) }
                        )
                        
                        UsbStructureHelp(folderName = settings.usbScanFolderName)
                        
                        SwitchSetting(
                            title = StringResources.overwriteConfirm,
                            subtitle = StringResources.overwriteConfirmDesc,
                            checked = settings.showOverwriteConfirm,
                            onCheckedChange = { onSettingsChange(settings.copy(showOverwriteConfirm = it)) }
                        )
                        
                        SwitchSetting(
                            title = StringResources.autoPlayAfterCopy,
                            subtitle = StringResources.autoPlayAfterCopyDesc,
                            checked = settings.autoPlayAfterCopy,
                            onCheckedChange = { onSettingsChange(settings.copy(autoPlayAfterCopy = it)) }
                        )
                    }
                }
                
                // ========== 显示设置 ==========
                if (effectiveFeatureFlags.showDisplaySettings) {
                    SettingsSection(title = "🖥 ${StringResources.displaySettings}") {
                        SwitchSetting(
                            title = StringResources.showPlayerIndex,
                            subtitle = StringResources.showPlayerIndexDesc,
                            checked = settings.showPlayerIndex,
                            onCheckedChange = { onSettingsChange(settings.copy(showPlayerIndex = it)) }
                        )
                        
                        SwitchSetting(
                            title = StringResources.keepScreenOn,
                            subtitle = StringResources.keepScreenOnDesc,
                            checked = settings.keepScreenOn,
                            onCheckedChange = { onSettingsChange(settings.copy(keepScreenOn = it)) }
                        )
                        
                        SwitchSetting(
                            title = StringResources.bootAutoStart,
                            subtitle = StringResources.bootAutoStartDesc,
                            checked = settings.bootAutoStart,
                            onCheckedChange = { onSettingsChange(settings.copy(bootAutoStart = it)) }
                        )
                    }
                }
                
                // ========== 高级设置 ==========
                if (effectiveFeatureFlags.showAdvancedSettings) {
                    SettingsSection(title = "🔧 ${StringResources.advancedSettings}") {
                        SliderSetting(
                            title = StringResources.maxCacheSize,
                            value = settings.maxCacheSizeMB.toFloat(),
                            valueRange = 512f..8192f,
                            onValueChange = { onSettingsChange(settings.copy(maxCacheSizeMB = it.toInt())) },
                            valueText = "${settings.maxCacheSizeMB} MB"
                        )
                        
                        SwitchSetting(
                            title = StringResources.debugLog,
                            subtitle = StringResources.debugLogDesc,
                            checked = settings.enableDebugLog,
                            onCheckedChange = { onSettingsChange(settings.copy(enableDebugLog = it)) }
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = { showClearCacheDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🗑 ${StringResources.clearCache}")
                            }
                            
                            Button(
                                onClick = { showResetDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔄 ${StringResources.resetSettings}")
                            }
                        }
                    }
                }
                
                // ========== 关于 ==========
                AboutSection()

                // ========== 维护操作（客户页面最后一项） ==========
                if (customerMode) {
                    CustomerMaintenanceSection(
                        showScanUsb = effectiveFeatureFlags.showScanUsbButton,
                        showImageSplit = effectiveFeatureFlags.showImageSplitTool,
                        onScanUsb = onScanUsb,
                        onImageSplit = onImageSplit,
                        bootAutoStart = settings.bootAutoStart,
                        onBootAutoStartChange = {
                            onSettingsChange(settings.copy(bootAutoStart = it))
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(if (customerMode) 96.dp else 32.dp))
            }
        }

        // 右侧上下滚动按钮（滚轮/拖动之外的兜底，仅依赖点击）
        ScrollButtons(
            scrollState = scrollState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = if (customerMode) 8.dp else 6.dp)
        )
        }
    }
    
    if (showClearCacheDialog) {
        ClearCacheDialog(
            cacheSizeMB = cacheSizeMB,
            onConfirm = { showClearCacheDialog = false; onClearCache() },
            onCancel = { showClearCacheDialog = false }
        )
    }
    
    if (showResetDialog) {
        ResetSettingsDialog(
            onConfirm = { showResetDialog = false; onResetSettings() },
            onCancel = { showResetDialog = false }
        )
    }
}

/**
 * 右侧上下滚动按钮（无滚轮时的兜底）。走 animateScrollTo 正常通道，仅依赖点击。
 */
@Composable
private fun ScrollButtons(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val step = 260
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FloatingScrollButton("▲") {
            scope.launch { scrollState.animateScrollTo((scrollState.value - step).coerceAtLeast(0)) }
        }
        FloatingScrollButton("▼") {
            scope.launch { scrollState.animateScrollTo((scrollState.value + step).coerceAtMost(scrollState.maxValue)) }
        }
    }
}

@Composable
private fun FloatingScrollButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3A3A3A).copy(alpha = 0.9f),
            contentColor = Color.White
        ),
        modifier = Modifier.size(44.dp)
    ) {
        Text(text = label, fontSize = 15.sp)
    }
}

@Composable
private fun HelpSection() {
    Surface(color = Color(0xFF1E3A5F), shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "💡 ${StringResources.usageTitle}", color = Color.Cyan, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = StringResources.usageSteps, color = Color.LightGray, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}


@Composable
private fun CustomerMaintenanceSection(
    showScanUsb: Boolean,
    showImageSplit: Boolean,
    onScanUsb: () -> Unit,
    onImageSplit: () -> Unit,
    bootAutoStart: Boolean,
    onBootAutoStartChange: (Boolean) -> Unit
) {
    if (!showScanUsb && !showImageSplit) return

    SettingsSection(title = "🛠 ${StringResources.maintenance}") {
        // 开机自启：客户设备上唯一可用的入口。
        //
        // 此前该开关只存在于 Debug 的「播放设置」区块（Release 的
        // showPlaybackSettings=false），而默认值是 false ——
        // 结果客户设备**永远无法开启开机自启**，而这是信发/kiosk 的头号诉求。
        // 除这里之外，U 盘配置的 settings.bootAutoStart 也可下发（2026-10-20 新增）。
        SwitchSetting(
            title = StringResources.bootAutoStart,
            subtitle = StringResources.bootAutoStartDesc,
            checked = bootAutoStart,
            onCheckedChange = onBootAutoStartChange
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showScanUsb) {
                Button(
                    onClick = onScanUsb,
                    modifier = Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text(StringResources.scanUsb, fontSize = 16.sp)
                }
            }
            if (showImageSplit) {
                Button(
                    onClick = onImageSplit,
                    modifier = Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
                ) {
                    Text(StringResources.imageSplit, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun HdmiEfullSection() {
    val customerMode = customerUiMode
    val isEn = StringResources.getLanguage() == AppLanguage.ENGLISH
    val state = rememberHdmiEfullUiState()

    SettingsSection(title = "🖥 ${StringResources.hdmiEfull}") {
        Text(
            text = StringResources.hdmiEfullDesc,
            color = Color.Gray,
            fontSize = if (customerMode) 14.sp else 11.sp
        )
        Spacer(modifier = Modifier.height(if (customerMode) 12.dp else 8.dp))
        Button(
            onClick = state.toggle,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (customerMode) 64.dp else 52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.enabled) Color(0xFF00838F) else Color(0xFF455A64)
            )
        ) {
            Text(
                text = if (state.enabled) {
                    if (isEn) "HDMI Fullscreen: ON" else "HDMI全屏：开"
                } else {
                    if (isEn) "HDMI Fullscreen: OFF" else "HDMI全屏：关"
                },
                fontSize = if (customerMode) 18.sp else 14.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isEn) {
                "Writes persist.sys.rotation.efull-1 = ${if (state.enabled) "true" else "false"}"
            } else {
                "当前属性 persist.sys.rotation.efull-1 = ${if (state.enabled) "true" else "false"}"
            },
            color = Color(0xFFB0BEC5),
            fontSize = if (customerMode) 12.sp else 10.sp
        )
        state.message?.let { error ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = error, color = Color(0xFFFF8A80), fontSize = 12.sp)
        }
    }
}

/**
 * HDMI 控制区域（I2C）。
 *
 * 与旧 GPIO 版本的区别：
 * - 不再脉冲按键（没有 `hdmi_b2/b3` 节点，也没有 200ms+ 的按键延时）；
 * - 布局、分辨率、电源、静音都走 [HdmiMatrixController]，可读回真实生效值；
 * - 融合带滑杆拖动时只做内存预览，**松手才写 flash**。
 */
@Composable
private fun HdmiControlSection() {
    val controller = remember { HdmiMatrixController.getInstance() }
    val matrixState by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    val isEn = StringResources.getLanguage() == AppLanguage.ENGLISH
    val snapshot = matrixState.lastSnapshot
    val applied = snapshot?.applied

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 进入设置页时主动读一次；陈旧数据只用于显示，不当作真值
    LaunchedEffect(Unit) {
        busy = true
        scope.launch {
            controller.probe()
            controller.readApplied()
            busy = false
        }
    }

    fun runWrite(label: String, block: suspend () -> HdmiMatrixResult<Unit>) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            when (val result = block()) {
                is HdmiMatrixResult.Success -> {
                    controller.probe()
                    controller.readApplied()
                }

                is HdmiMatrixResult.Failure -> message =
                    "$label: ${StringResources.hdmiMatrixErrorDetail(result.error)}"
            }
            busy = false
        }
    }

    SettingsSection(title = "🔌 ${StringResources.hdmiMatrix}") {
        // ---- 在线状态 ----
        Text(
            text = when {
                busy -> StringResources.loading
                snapshot == null -> StringResources.hdmiMatrixUnavailable
                snapshot.online -> "${StringResources.hdmiMatrixOnline} · " +
                    "${StringResources.hdmiMatrixVersion} ${snapshot.version}"
                else -> "${StringResources.hdmiMatrixOffline} · " +
                    "${StringResources.hdmiMatrixVersion} ${snapshot.version}"
            },
            color = when {
                snapshot?.online == true -> Color(0xFF81C784)
                snapshot == null -> Color(0xFFFF8A80)
                else -> Color(0xFFFFD54F)
            },
            fontSize = if (customerUiMode) 14.sp else 12.sp
        )

        snapshot?.let { snap ->
            Spacer(modifier = Modifier.height(6.dp))
            // 位域徽标：BUSY / POWER / ERROR / OVERFLOW
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(StringResources.hdmiMatrixBusy, snap.status.busy, Color(0xFFFFB74D))
                StatusBadge(StringResources.hdmiMatrixPower, snap.status.power, Color(0xFF81C784))
                StatusBadge(StringResources.hdmiMatrixErrorFlag, snap.status.error, Color(0xFFE57373))
                StatusBadge(StringResources.hdmiMatrixOverflow, snap.status.overflow, Color(0xFFBA68C8))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${StringResources.hdmiMatrixAppliedMode}: " +
                    (applied?.mode?.toString() ?: "-") +
                    " · ${StringResources.hdmiMatrixResolution}: " +
                    (applied?.resolution?.name ?: "-") +
                    " · ${StringResources.hdmiMatrixCmdCount}: " +
                    (snap.cmdCount?.toString() ?: "-"),
                color = Color(0xFFB0BEC5),
                fontSize = if (customerUiMode) 12.sp else 10.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 电源 / 静音 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { runWrite("power") { controller.setPower(applied?.power != true) } },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (applied?.power == true) Color(0xFF2E7D32) else Color(0xFF455A64)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("${StringResources.hdmiMatrixPower}: " +
                    if (applied?.power == true) "ON" else "OFF")
            }

            Button(
                onClick = { runWrite("mute") { controller.setMute(applied?.mute != true) } },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (applied?.mute == true) Color(0xFFC62828) else Color(0xFF455A64)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("${StringResources.hdmiMatrixMute}: " +
                    if (applied?.mute == true) "ON" else "OFF")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 分辨率 ----
        Text(
            text = "${StringResources.hdmiMatrixResolution}",
            color = Color.White,
            fontSize = if (customerUiMode) 16.sp else 13.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HdmiResolution.entries.forEach { resolution ->
                val selected = applied?.resolution == resolution
                Button(
                    onClick = { runWrite("resolution") { controller.setResolution(resolution) } },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF1976D2) else Color(0xFF37474F)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when (resolution) {
                            HdmiResolution.P720 -> "720p"
                            HdmiResolution.P1080 -> "1080p"
                            HdmiResolution.P2160_30 -> "2160p30"
                        },
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 融合带（hfusion / vfusion）----
        //
        // 拖动时只更新内存预览；onValueChangeFinished 才提交。
        // 这不是性能优化，而是硬件保护：每次成功写入都会写 MCU flash。
        var hFusion by remember(applied?.hfusion) {
            mutableFloatStateOf((applied?.hfusion ?: 0).toFloat())
        }
        var vFusion by remember(applied?.vfusion) {
            mutableFloatStateOf((applied?.vfusion ?: 0).toFloat())
        }

        Text(
            text = "${StringResources.hdmiMatrixFusion} · " +
                "${StringResources.hdmiMatrixHorizontal} ${hFusion.toInt()} / " +
                "${StringResources.hdmiMatrixVertical} ${vFusion.toInt()}",
            color = Color.White,
            fontSize = if (customerUiMode) 16.sp else 13.sp
        )
        Slider(
            value = hFusion,
            onValueChange = {
                hFusion = it
                controller.stageFusion(it.toInt(), vFusion.toInt())
            },
            onValueChangeFinished = {
                runWrite("hfusion") { controller.commitFusion() }
            },
            valueRange = 0f..255f,
            enabled = !busy
        )
        Slider(
            value = vFusion,
            onValueChange = {
                vFusion = it
                controller.stageFusion(hFusion.toInt(), it.toInt())
            },
            onValueChangeFinished = {
                runWrite("vfusion") { controller.commitFusion() }
            },
            valueRange = 0f..255f,
            enabled = !busy
        )
        Text(
            text = StringResources.hdmiMatrixFusionHint,
            color = Color.Gray,
            fontSize = if (customerUiMode) 12.sp else 10.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                busy = true
                message = null
                scope.launch {
                    controller.probe()
                    controller.readApplied()
                    busy = false
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(StringResources.hdmiMatrixRefresh)
        }

        message?.let { text ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = text, color = Color(0xFFFF8A80), fontSize = 12.sp)
        }
    }
}

/** 状态位徽标：亮 = 位为 1。 */
@Composable
private fun StatusBadge(label: String, active: Boolean, activeColor: Color) {
    Text(
        text = label,
        color = if (active) Color.Black else Color(0xFF90A4AE),
        fontSize = 10.sp,
        modifier = Modifier
            .background(
                if (active) activeColor else Color(0xFF263238),
                MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun StoragePathInfo(storageLocation: StorageLocation) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pathInfo = remember(storageLocation) {
        try {
            val dir = com.btf.rk3568_hdmi_mediaplay.util.FileUtils.getLocalMediaDir(context, storageLocation, "")
            val available = com.btf.rk3568_hdmi_mediaplay.util.FileUtils.getAvailableSpaceMB(dir)
            Pair(dir.absolutePath, available)
        } catch (e: Exception) {
            Pair("N/A", 0L)
        }
    }
    
    Surface(color = Color(0xFF333333), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "📂 ${StringResources.currentStoragePath}:", color = Color.Yellow, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = pathInfo.first, color = Color.LightGray, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "${StringResources.availableSpace}: ${pathInfo.second} MB", color = Color.Cyan, fontSize = 11.sp)
        }
    }
}

@Composable
private fun UsbStructureHelp(folderName: String) {
    Surface(color = Color(0xFF333333), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "📁 ${StringResources.usbStructure}", color = Color.Yellow, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "USB/\n└── $folderName/\n    ├── player1/\n    ├── player2/\n    ├── player3/\n    └── player4/",
                color = Color.LightGray,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val customerMode = customerUiMode
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2A2A), MaterialTheme.shapes.medium).padding(if (customerMode) 20.dp else 16.dp)
    ) {
        Text(text = title, color = Color.Cyan, fontSize = if (customerMode) 20.sp else 16.sp, modifier = Modifier.padding(bottom = if (customerMode) 16.dp else 12.dp))
        content()
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val customerMode = customerUiMode
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (customerMode) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = if (customerMode) 18.sp else 14.sp)
            subtitle?.let { Text(text = it, color = Color.Gray, fontSize = if (customerMode) 14.sp else 11.sp) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan, checkedTrackColor = Color.Cyan.copy(alpha = 0.5f)))
    }
}

@Composable
private fun SliderSetting(title: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, valueText: String) {
    val customerMode = customerUiMode
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = if (customerMode) 12.dp else 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, color = Color.White, fontSize = if (customerMode) 18.sp else 14.sp)
            Text(text = valueText, color = Color.Cyan, fontSize = if (customerMode) 18.sp else 14.sp)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan))
    }
}

@Composable
private fun WallLayoutModeSetting(
    selectedMode: LayoutMode,
    onModeSelected: (LayoutMode) -> Unit
) {
    val customerMode = customerUiMode
    val isEn = StringResources.getLanguage() == AppLanguage.ENGLISH

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (customerMode) 12.dp else 8.dp)
    ) {
        Text(
            text = "📐 ${StringResources.layoutMode}",
            color = Color.White,
            fontSize = if (customerMode) 18.sp else 14.sp
        )
        Text(
            text = if (isEn) {
                "Select the mode number shown on the HDMI wall diagram"
            } else {
                "请选择与 HDMI 拼接示意图一致的模式编号"
            },
            color = Color.Gray,
            fontSize = if (customerMode) 14.sp else 11.sp
        )

        Spacer(modifier = Modifier.height(if (customerMode) 12.dp else 8.dp))

        LayoutMode.customerModes.chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowModes.forEach { mode ->
                    val selected = mode == selectedMode
                    Button(
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier
                            .weight(1f)
                            // 用 heightIn 而非固定 height：选中态要放 4 行文字
                            // （模式名/布局名/描述/✓当前），固定 82dp 会裁掉最后一行。
                            .heightIn(min = if (customerMode) 82.dp else 68.dp),
                        colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF00838F) else Color(0xFF3A3A3A),
                        contentColor = Color.White
                    ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = mode.wallModeNumber?.let { number ->
                                    if (isEn) "Mode $number" else "模式 $number"
                                } ?: if (isEn) "Software" else "软件模式",
                                color = if (selected) Color.White else Color.Cyan,
                                fontSize = if (customerMode) 17.sp else 13.sp
                            )
                            Text(
                                text = StringResources.getLayoutModeText(mode),
                                fontSize = if (customerMode) 13.sp else 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = StringResources.getLayoutModeDescription(mode),
                                color = Color(0xFFB0BEC5),
                                fontSize = if (customerMode) 11.sp else 9.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selected) {
                                Text(
                                    text = if (isEn) "✓ Selected" else "✓ 当前",
                                    color = Color(0xFFA7FFEB),
                                    fontSize = if (customerMode) 11.sp else 9.sp
                                )
                            }
                        }
                    }
                }

                if (rowModes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
            text = if (isEn) {
                "Choosing a layout also switches the HDMI matrix to its hardware mode over I2C. " +
                    "Portrait and same-content layouts are software-only and leave the matrix unchanged."
            } else {
                "选择布局时会同时通过 I2C 把拼接板切到对应硬件模式；" +
                    "竖屏与「2×2 同画面」属纯软件布局，不会改动拼接板。"
            },
            color = Color(0xFFFFCC80),
            fontSize = if (customerMode) 13.sp else 10.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(title: String, options: List<Pair<String, String>>, selectedValue: String, onValueChange: (String) -> Unit) {
    val customerMode = customerUiMode
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.find { it.first == selectedValue }?.second ?: ""
    
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = if (customerMode) 12.dp else 8.dp)) {
        Text(text = title, color = Color.White, fontSize = if (customerMode) 18.sp else 14.sp)
        Spacer(modifier = Modifier.height(if (customerMode) 8.dp else 4.dp))
        
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selectedText,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (customerMode) 60.dp else 48.dp).menuAnchor(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (customerMode) 18.sp else 14.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Cyan, unfocusedBorderColor = Color.Gray)
            )
            
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, displayText) ->
                    DropdownMenuItem(text = { Text(displayText, fontSize = if (customerMode) 18.sp else 14.sp) }, onClick = { onValueChange(value); expanded = false }, contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding)
                }
            }
        }
    }
}

@Composable
private fun TextInputSetting(title: String, subtitle: String? = null, value: String, onValueChange: (String) -> Unit) {
    val customerMode = customerUiMode
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = if (customerMode) 12.dp else 8.dp)) {
        Text(text = title, color = Color.White, fontSize = if (customerMode) 18.sp else 14.sp)
        subtitle?.let { Text(text = it, color = Color.Gray, fontSize = if (customerMode) 14.sp else 11.sp) }
        Spacer(modifier = Modifier.height(if (customerMode) 8.dp else 4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (customerMode) 60.dp else 48.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (customerMode) 18.sp else 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Cyan, unfocusedBorderColor = Color.Gray),
            singleLine = true
        )
    }
}

/**
 * 关于部分 - 显示详细版本信息
 */
@Composable
private fun AboutSection() {
    val isEn = StringResources.getLanguage() == AppLanguage.ENGLISH
    // 构建类型直读 BuildConfig，不经过 customerUiMode。
    // customerUiMode 还受 FORCE_CUSTOMER_UI_PREVIEW 影响，用它推导会把 Debug 包显示成 Release。
    val buildType = if (BuildConfig.IS_DEBUG_BUILD) "Debug" else "Release"
    val buildTypeColor = if (BuildConfig.IS_DEBUG_BUILD) Color.Yellow else Color.Green
    
    SettingsSection(title = "ℹ️ ${StringResources.about}") {
        // 应用名称
        Text(text = StringResources.appName, color = Color.White, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        // 版本信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = if (isEn) "Version" else "版本号", color = Color.Gray, fontSize = 12.sp)
            Text(text = BuildConfig.VERSION_NAME, color = Color.White, fontSize = 12.sp)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = if (isEn) "Version Code" else "版本代码", color = Color.Gray, fontSize = 12.sp)
            Text(text = "${BuildConfig.VERSION_CODE}", color = Color.White, fontSize = 12.sp)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = if (isEn) "Build Type" else "构建类型", color = Color.Gray, fontSize = 12.sp)
            Text(text = buildType, color = buildTypeColor, fontSize = 12.sp)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = if (isEn) "Package" else "包名", color = Color.Gray, fontSize = 12.sp)
            Text(text = BuildConfig.APPLICATION_ID, color = Color.White, fontSize = 10.sp)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(modifier = Modifier.height(8.dp))
        
        // 平台信息
        Text(text = if (isEn) "Platform Info" else "平台信息", color = Color.Cyan, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Android 11+ / RK3568", color = Color.Gray, fontSize = 12.sp)
        Text(text = "Min SDK: ${BuildConfig.MIN_SDK_VERSION}", color = Color.Gray, fontSize = 11.sp)
        
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(modifier = Modifier.height(8.dp))
        
        // 支持格式
        Text(text = if (isEn) "Supported Formats" else "支持格式", color = Color.Cyan, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${if (isEn) "Video" else "视频"}: MP4, MKV, AVI, MOV, WMV, FLV",
            color = Color.Gray,
            fontSize = 11.sp
        )
        Text(
            text = "${if (isEn) "Image" else "图片"}: JPG, PNG, BMP, GIF, WEBP",
            color = Color.Gray,
            fontSize = 11.sp
        )
        
        // 功能模式提示
        if (customerUiMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color(0xFF1B5E20),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isEn) "🔒 Production Mode - Limited UI" else "🔒 生产模式 - 精简界面",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

