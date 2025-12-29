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
import com.btf.rk3568_hdmi_mediaplay.util.StringResources as S

/**
 * 设置界面 - 支持中英文切换
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
    // 强制重组当语言变化时
    val currentLang = settings.language
    val scrollState = rememberScrollState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    
    // 根据语言获取文本
    val isEnglish = currentLang == AppLanguage.ENGLISH
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        TopAppBar(
            title = { Text("⚙ ${S.settings}", color = Color.White) },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("← ${S.back}", color = Color.White)
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
            // 使用说明
            HelpSection(isEnglish)
            
            // 基础设置
            SettingsSection(title = "📋 ${S.basicSettings}") {
                DropdownSetting(
                    title = "${S.language} / Language",
                    options = AppLanguage.entries.map { it.name to S.getLanguageText(it) },
                    selectedValue = settings.language.name,
                    onValueChange = { 
                        val newLang = AppLanguage.valueOf(it)
                        S.setLanguage(newLang)
                        onSettingsChange(settings.copy(language = newLang)) 
                    }
                )
                
                SwitchSetting(
                    title = S.overwriteConfirm,
                    subtitle = S.overwriteConfirmDesc,
                    checked = settings.showOverwriteConfirm,
                    onCheckedChange = { onSettingsChange(settings.copy(showOverwriteConfirm = it)) }
                )
                
                SwitchSetting(
                    title = S.autoPlayOnStart,
                    subtitle = S.autoPlayOnStartDesc,
                    checked = settings.autoPlayOnStart,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPlayOnStart = it)) }
                )
                
                SwitchSetting(
                    title = S.bootAutoStart,
                    subtitle = S.bootAutoStartDesc,
                    checked = settings.bootAutoStart,
                    onCheckedChange = { onSettingsChange(settings.copy(bootAutoStart = it)) }
                )
                
                DropdownSetting(
                    title = S.loopMode,
                    options = LoopMode.entries.map { it.name to S.getLoopModeText(it) },
                    selectedValue = settings.loopMode.name,
                    onValueChange = { onSettingsChange(settings.copy(loopMode = LoopMode.valueOf(it))) }
                )
            }
            
            // 视频设置
            SettingsSection(title = "🎬 ${S.videoSettings}") {
                SliderSetting(
                    title = S.defaultVolume,
                    value = settings.defaultVolume.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChange(settings.copy(defaultVolume = it.toInt())) },
                    valueText = "${settings.defaultVolume}%"
                )
                
                SwitchSetting(
                    title = S.defaultMuted,
                    subtitle = S.defaultMutedDesc,
                    checked = settings.defaultMuted,
                    onCheckedChange = { onSettingsChange(settings.copy(defaultMuted = it)) }
                )
                
                DropdownSetting(
                    title = S.videoScaleMode,
                    options = VideoScaleMode.entries.map { it.name to S.getScaleModeText(it) },
                    selectedValue = settings.videoScaleMode.name,
                    onValueChange = { onSettingsChange(settings.copy(videoScaleMode = VideoScaleMode.valueOf(it))) }
                )
                
                SwitchSetting(
                    title = S.hardwareDecode,
                    subtitle = S.hardwareDecodeDesc,
                    checked = settings.useHardwareDecode,
                    onCheckedChange = { onSettingsChange(settings.copy(useHardwareDecode = it)) }
                )
            }
            
            // 音频设置
            SettingsSection(title = "🔊 ${S.audioSettings}") {
                DropdownSetting(
                    title = S.audioOutput,
                    options = AudioOutput.entries.map { it.name to S.getAudioOutputText(it) },
                    selectedValue = settings.audioOutput.name,
                    onValueChange = { onSettingsChange(settings.copy(audioOutput = AudioOutput.valueOf(it))) }
                )
            }
            
            // 图片设置
            SettingsSection(title = "🖼 ${S.imageSettings}") {
                SliderSetting(
                    title = S.slideInterval,
                    value = settings.imageIntervalSeconds.toFloat(),
                    valueRange = 1f..30f,
                    onValueChange = { onSettingsChange(settings.copy(imageIntervalSeconds = it.toInt())) },
                    valueText = "${settings.imageIntervalSeconds}${if (isEnglish) "s" else "秒"}"
                )
                
                DropdownSetting(
                    title = S.transitionEffect,
                    options = ImageTransition.entries.map { it.name to S.getTransitionText(it) },
                    selectedValue = settings.imageTransition.name,
                    onValueChange = { onSettingsChange(settings.copy(imageTransition = ImageTransition.valueOf(it))) }
                )
            }
            
            // U盘设置
            SettingsSection(title = "💾 ${S.usbSettings}") {
                SwitchSetting(
                    title = S.usbDetection,
                    subtitle = S.usbDetectionDesc,
                    checked = settings.usbDetectionEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(usbDetectionEnabled = it)) }
                )
                
                TextInputSetting(
                    title = S.scanFolderName,
                    subtitle = S.scanFolderNameDesc,
                    value = settings.usbScanFolderName,
                    onValueChange = { onSettingsChange(settings.copy(usbScanFolderName = it)) }
                )
                
                UsbStructureHelp(folderName = settings.usbScanFolderName, isEnglish = isEnglish)
                
                SwitchSetting(
                    title = S.autoPlayAfterCopy,
                    subtitle = S.autoPlayAfterCopyDesc,
                    checked = settings.autoPlayAfterCopy,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPlayAfterCopy = it)) }
                )
                
                SwitchSetting(
                    title = S.showCopyProgress,
                    subtitle = S.showCopyProgressDesc,
                    checked = settings.showCopyProgress,
                    onCheckedChange = { onSettingsChange(settings.copy(showCopyProgress = it)) }
                )
            }
            
            // 显示设置
            SettingsSection(title = "🖥 ${S.displaySettings}") {
                DropdownSetting(
                    title = S.layoutMode,
                    options = LayoutMode.entries.map { it.name to S.getLayoutModeText(it) },
                    selectedValue = settings.layoutMode.name,
                    onValueChange = { onSettingsChange(settings.copy(layoutMode = LayoutMode.valueOf(it))) }
                )
                
                SwitchSetting(
                    title = S.showPlayerIndex,
                    subtitle = S.showPlayerIndexDesc,
                    checked = settings.showPlayerIndex,
                    onCheckedChange = { onSettingsChange(settings.copy(showPlayerIndex = it)) }
                )
                
                SwitchSetting(
                    title = S.keepScreenOn,
                    subtitle = S.keepScreenOnDesc,
                    checked = settings.keepScreenOn,
                    onCheckedChange = { onSettingsChange(settings.copy(keepScreenOn = it)) }
                )
            }
            
            // 高级设置
            SettingsSection(title = "🔧 ${S.advancedSettings}") {
                SliderSetting(
                    title = S.maxCacheSize,
                    value = settings.maxCacheSizeMB.toFloat(),
                    valueRange = 512f..8192f,
                    onValueChange = { onSettingsChange(settings.copy(maxCacheSizeMB = it.toInt())) },
                    valueText = "${settings.maxCacheSizeMB} MB"
                )
                
                SwitchSetting(
                    title = S.debugLog,
                    subtitle = S.debugLogDesc,
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
                        Text("🗑 ${S.clearCache}")
                    }
                    
                    Button(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄 ${S.resetSettings}")
                    }
                }
            }
            
            // 关于
            SettingsSection(title = "ℹ️ ${S.about}") {
                Text(text = S.appName, color = Color.White, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${S.version}: 1.0.0", color = Color.Gray, fontSize = 12.sp)
                Text(text = "${S.platform}: Android 11 / RK3568", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${S.supportedFormats}:\n" +
                           "${if (isEnglish) "Video" else "视频"}: MP4, MKV, AVI, MOV, WMV, FLV\n" +
                           "${if (isEnglish) "Image" else "图片"}: JPG, PNG, BMP, GIF, WEBP",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
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
private fun HelpSection(isEnglish: Boolean) {
    Surface(color = Color(0xFF1E3A5F), shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "💡 ${S.usageTitle}", color = Color.Cyan, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${S.usageStep1}\n${S.usageStep2}\n${S.usageStep3}\n${S.usageStep4}\n${S.usageStep5}",
                color = Color.LightGray,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun UsbStructureHelp(folderName: String, isEnglish: Boolean) {
    Surface(color = Color(0xFF333333), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "📁 ${if (isEnglish) "USB folder structure:" else "U盘目录结构示例:"}",
                color = Color.Yellow,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            val playerLabel = if (isEnglish) "Player" else "播放器"
            val contentLabel = if (isEnglish) "content" else "的内容"
            Text(
                text = """
                    USB/
                    └── $folderName/
                        ├── player1/  ← ${playerLabel}1$contentLabel
                        ├── player2/  ← ${playerLabel}2$contentLabel
                        ├── player3/  ← ${playerLabel}3$contentLabel
                        └── player4/  ← ${playerLabel}4$contentLabel
                """.trimIndent(),
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
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), MaterialTheme.shapes.medium)
            .padding(16.dp)
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
            subtitle?.let { Text(text = it, color = Color.Gray, fontSize = 12.sp) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan, checkedTrackColor = Color.Cyan.copy(alpha = 0.5f))
        )
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
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Cyan,
                    unfocusedBorderColor = Color.Gray
                )
            )
            
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, displayText) ->
                    DropdownMenuItem(
                        text = { Text(displayText) },
                        onClick = { onValueChange(value); expanded = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInputSetting(title: String, subtitle: String? = null, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
        subtitle?.let { Text(text = it, color = Color.Gray, fontSize = 12.sp) }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Cyan,
                unfocusedBorderColor = Color.Gray
            ),
            singleLine = true
        )
    }
}
