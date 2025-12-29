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
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.ClearCacheDialog
import com.btf.rk3568_hdmi_mediaplay.ui.dialog.ResetSettingsDialog
import com.btf.rk3568_hdmi_mediaplay.util.StringResources

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
    cacheSizeMB: Long = 0
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
                // ========== 核心设置 (最重要，放最前面) ==========
                SettingsSection(title = "⭐ ${StringResources.coreSettings}") {
                    // 语言设置
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
                    
                    // 布局模式
                    DropdownSetting(
                        title = "📐 ${StringResources.layoutMode}",
                        options = LayoutMode.entries.map { it.name to StringResources.getLayoutModeText(it) },
                        selectedValue = settings.layoutMode.name,
                        onValueChange = { onSettingsChange(settings.copy(layoutMode = LayoutMode.valueOf(it))) }
                    )
                    
                    // 音频输出
                    DropdownSetting(
                        title = "🔊 ${StringResources.audioOutput}",
                        options = AudioOutput.entries.map { it.name to StringResources.getAudioOutputText(it) },
                        selectedValue = settings.audioOutput.name,
                        onValueChange = { onSettingsChange(settings.copy(audioOutput = AudioOutput.valueOf(it))) }
                    )
                }
                
                // ========== 使用说明 ==========
                HelpSection()
                
                // ========== 播放设置 ==========
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
                
                // ========== 视频设置 ==========
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
                
                // ========== 图片设置 ==========
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
                
                // ========== U盘设置 ==========
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
                
                // ========== 显示设置 ==========
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
                
                // ========== 高级设置 ==========
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
                
                // ========== 关于 ==========
                SettingsSection(title = "ℹ️ ${StringResources.about}") {
                    Text(text = StringResources.appName, color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "${StringResources.version}: 1.0.0", color = Color.Gray, fontSize = 12.sp)
                    Text(text = "${StringResources.platform}: Android 11 / RK3568", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${StringResources.supportedFormats}:\n${StringResources.video}: MP4, MKV, AVI, MOV, WMV, FLV\n${StringResources.image}: JPG, PNG, BMP, GIF, WEBP",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
                
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
