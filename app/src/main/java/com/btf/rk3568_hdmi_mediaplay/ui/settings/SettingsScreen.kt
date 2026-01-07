package com.btf.rk3568_hdmi_mediaplay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.BuildConfig
import com.btf.rk3568_hdmi_mediaplay.FeatureManager
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.ClearCacheDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.ResetSettingsDialog
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.Dispatchers
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
    cacheSizeMB: Long = 0,
    featureFlags: FeatureFlags = FeatureFlags.releaseDefaults()
) {
    // 当语言变化时强制重组
    val lang = settings.language
    
    // 确保 StringResources 同步
    LaunchedEffect(lang) {
        StringResources.setLanguage(lang)
    }
    
    val scrollState = rememberScrollState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    
    // 使用 key 强制在语言变化时重组整个界面
    key(lang) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A))
        ) {
            TopAppBar(
                title = { Text("⚙ ${StringResources.settings}", color = Color.White) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← ${StringResources.back}", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2A2A2A))
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ========== HDMI 控制 (放最顶部) ==========
                if (featureFlags.showHdmiControl) {
                    HdmiControlSection()
                }
                
                // ========== 核心设置 ==========
                // 只有当核心设置中有可显示的内容时才显示区块
                val showCoreSettings = featureFlags.showLanguageSetting || 
                                       featureFlags.allowLayoutChange || 
                                       featureFlags.showAudioOutputSetting
                if (showCoreSettings) {
                    SettingsSection(title = "⭐ ${StringResources.coreSettings}") {
                    // 语言设置
                    if (featureFlags.showLanguageSetting) {
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
                    
                    // 布局模式
                    if (featureFlags.allowLayoutChange) {
                        DropdownSetting(
                            title = "📐 ${StringResources.layoutMode}",
                            options = LayoutMode.entries.map { it.name to StringResources.getLayoutModeText(it) },
                            selectedValue = settings.layoutMode.name,
                            onValueChange = { onSettingsChange(settings.copy(layoutMode = LayoutMode.valueOf(it))) }
                        )
                    }
                    
                    // 音频输出
                    if (featureFlags.showAudioOutputSetting) {
                        DropdownSetting(
                            title = "🔊 ${StringResources.audioOutput}",
                            options = AudioOutput.entries.map { it.name to StringResources.getAudioOutputText(it) },
                            selectedValue = settings.audioOutput.name,
                            onValueChange = { onSettingsChange(settings.copy(audioOutput = AudioOutput.valueOf(it))) }
                        )
                    }
                    }
                }
                
                // ========== 使用说明 ==========
                if (featureFlags.showHelpSection) {
                    HelpSection()
                }
                
                // ========== 播放设置 ==========
                if (featureFlags.showPlaybackSettings) {
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
                if (featureFlags.showVideoSettings) {
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
                if (featureFlags.showImageSettings) {
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
                if (featureFlags.showStorageSettings) {
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
                if (featureFlags.showUsbSettings) {
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
                if (featureFlags.showDisplaySettings) {
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
                if (featureFlags.showAdvancedSettings) {
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
                
                Spacer(modifier = Modifier.height(32.dp))
            }
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

/**
 * HDMI 控制区域
 */
@Composable
private fun HdmiControlSection() {
    val scope = rememberCoroutineScope()
    var lastClickTime by remember { mutableLongStateOf(0L) }
    var clickResult by remember { mutableStateOf("") }
    
    val isEn = StringResources.getLanguage() == AppLanguage.ENGLISH
    
    SettingsSection(title = "🔌 ${if (isEn) "HDMI Control" else "HDMI 控制"}") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        scope.launch(Dispatchers.IO) {
                            val success = simulateHdmiKeyPress("/sys/devices/platform/hdmi-control/hdmi_b2")
                            clickResult = if (success) "✓" else "✗"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isEn) "Switch Mode" else "切换模式")
            }
            
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        scope.launch(Dispatchers.IO) {
                            val success = simulateHdmiKeyPress("/sys/devices/platform/hdmi-control/hdmi_b3")
                            clickResult = if (success) "✓" else "✗"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isEn) "Switch Resolution" else "切换分辨率")
            }
        }
        
        if (clickResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = clickResult,
                color = if (clickResult == "✓") Color.Green else Color.Red,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * 模拟 HDMI 按键按下
 */
private fun simulateHdmiKeyPress(filePath: String): Boolean {
    return try {
        val command = "echo 0 > $filePath; sleep 0.05; echo 1 > $filePath"
        executeRootCommand(command)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * 执行 root 命令
 */
private fun executeRootCommand(command: String) {
    try {
        val process = Runtime.getRuntime().exec("su")
        java.io.DataOutputStream(process.outputStream).use { os ->
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
        }
        process.waitFor()
    } catch (e: java.io.IOException) {
        e.printStackTrace()
    } catch (e: InterruptedException) {
        e.printStackTrace()
    }
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
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2A2A), MaterialTheme.shapes.medium).padding(16.dp)
    ) {
        Text(text = title, color = Color.Cyan, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 14.sp)
            subtitle?.let { Text(text = it, color = Color.Gray, fontSize = 11.sp) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan, checkedTrackColor = Color.Cyan.copy(alpha = 0.5f)))
    }
}

@Composable
private fun SliderSetting(title: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, valueText: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, color = Color.White, fontSize = 14.sp)
            Text(text = valueText, color = Color.Cyan, fontSize = 14.sp)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(title: String, options: List<Pair<String, String>>, selectedValue: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.find { it.first == selectedValue }?.second ?: ""
    
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selectedText,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Cyan, unfocusedBorderColor = Color.Gray)
            )
            
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, displayText) ->
                    DropdownMenuItem(text = { Text(displayText) }, onClick = { onValueChange(value); expanded = false }, contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding)
                }
            }
        }
    }
}

@Composable
private fun TextInputSetting(title: String, subtitle: String? = null, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
        subtitle?.let { Text(text = it, color = Color.Gray, fontSize = 11.sp) }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
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
        if (!BuildConfig.IS_DEBUG_BUILD) {
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
