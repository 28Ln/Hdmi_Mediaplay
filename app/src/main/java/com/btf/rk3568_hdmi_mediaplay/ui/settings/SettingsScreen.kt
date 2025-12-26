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

/**
 * 设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onClearCache: () -> Unit,
    onResetSettings: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        // 顶部栏
        TopAppBar(
            title = { Text("设置", color = Color.White) },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF2A2A2A)
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 基础设置
            SettingsSection(title = "基础设置") {
                SwitchSetting(
                    title = "覆盖确认提示",
                    subtitle = "插入U盘时是否提示覆盖本地内容",
                    checked = settings.showOverwriteConfirm,
                    onCheckedChange = { onSettingsChange(settings.copy(showOverwriteConfirm = it)) }
                )
                
                SwitchSetting(
                    title = "启动后自动播放",
                    subtitle = "应用启动后自动播放本地内容",
                    checked = settings.autoPlayOnStart,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPlayOnStart = it)) }
                )
                
                SwitchSetting(
                    title = "开机自启动",
                    subtitle = "设备开机后自动启动应用",
                    checked = settings.bootAutoStart,
                    onCheckedChange = { onSettingsChange(settings.copy(bootAutoStart = it)) }
                )
                
                DropdownSetting(
                    title = "循环模式",
                    options = LoopMode.entries.map { it.name to getLoopModeText(it) },
                    selectedValue = settings.loopMode.name,
                    onValueChange = { onSettingsChange(settings.copy(loopMode = LoopMode.valueOf(it))) }
                )
            }
            
            // 视频设置
            SettingsSection(title = "视频设置") {
                SliderSetting(
                    title = "默认音量",
                    value = settings.defaultVolume.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChange(settings.copy(defaultVolume = it.toInt())) },
                    valueText = "${settings.defaultVolume}%"
                )
                
                SwitchSetting(
                    title = "默认静音",
                    subtitle = "启动时默认静音播放",
                    checked = settings.defaultMuted,
                    onCheckedChange = { onSettingsChange(settings.copy(defaultMuted = it)) }
                )
                
                DropdownSetting(
                    title = "视频缩放模式",
                    options = VideoScaleMode.entries.map { it.name to getScaleModeText(it) },
                    selectedValue = settings.videoScaleMode.name,
                    onValueChange = { onSettingsChange(settings.copy(videoScaleMode = VideoScaleMode.valueOf(it))) }
                )
                
                SwitchSetting(
                    title = "硬件解码",
                    subtitle = "使用硬件加速解码视频",
                    checked = settings.useHardwareDecode,
                    onCheckedChange = { onSettingsChange(settings.copy(useHardwareDecode = it)) }
                )
            }
            
            // 图片设置
            SettingsSection(title = "图片设置") {
                SliderSetting(
                    title = "轮播间隔",
                    value = settings.imageIntervalSeconds.toFloat(),
                    valueRange = 1f..30f,
                    onValueChange = { onSettingsChange(settings.copy(imageIntervalSeconds = it.toInt())) },
                    valueText = "${settings.imageIntervalSeconds}秒"
                )
                
                DropdownSetting(
                    title = "切换动画",
                    options = ImageTransition.entries.map { it.name to getTransitionText(it) },
                    selectedValue = settings.imageTransition.name,
                    onValueChange = { onSettingsChange(settings.copy(imageTransition = ImageTransition.valueOf(it))) }
                )
            }
            
            // U盘设置
            SettingsSection(title = "U盘设置") {
                SwitchSetting(
                    title = "U盘检测",
                    subtitle = "自动检测U盘插入",
                    checked = settings.usbDetectionEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(usbDetectionEnabled = it)) }
                )
                
                TextInputSetting(
                    title = "扫描目录名",
                    value = settings.usbScanFolderName,
                    onValueChange = { onSettingsChange(settings.copy(usbScanFolderName = it)) }
                )
                
                SwitchSetting(
                    title = "拷贝后自动播放",
                    subtitle = "从U盘拷贝完成后自动开始播放",
                    checked = settings.autoPlayAfterCopy,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPlayAfterCopy = it)) }
                )
                
                SwitchSetting(
                    title = "显示拷贝进度",
                    subtitle = "拷贝文件时显示进度条",
                    checked = settings.showCopyProgress,
                    onCheckedChange = { onSettingsChange(settings.copy(showCopyProgress = it)) }
                )
            }
            
            // 显示设置
            SettingsSection(title = "显示设置") {
                DropdownSetting(
                    title = "布局模式",
                    options = LayoutMode.entries.map { it.name to getLayoutModeText(it) },
                    selectedValue = settings.layoutMode.name,
                    onValueChange = { onSettingsChange(settings.copy(layoutMode = LayoutMode.valueOf(it))) }
                )
                
                SwitchSetting(
                    title = "显示播放器编号",
                    subtitle = "在播放器左上角显示编号",
                    checked = settings.showPlayerIndex,
                    onCheckedChange = { onSettingsChange(settings.copy(showPlayerIndex = it)) }
                )
                
                SwitchSetting(
                    title = "屏幕常亮",
                    subtitle = "播放时保持屏幕常亮",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { onSettingsChange(settings.copy(keepScreenOn = it)) }
                )
            }
            
            // 高级设置
            SettingsSection(title = "高级设置") {
                SliderSetting(
                    title = "最大缓存大小",
                    value = settings.maxCacheSizeMB.toFloat(),
                    valueRange = 512f..8192f,
                    onValueChange = { onSettingsChange(settings.copy(maxCacheSizeMB = it.toInt())) },
                    valueText = "${settings.maxCacheSizeMB} MB"
                )
                
                SwitchSetting(
                    title = "调试日志",
                    subtitle = "启用详细日志输出",
                    checked = settings.enableDebugLog,
                    onCheckedChange = { onSettingsChange(settings.copy(enableDebugLog = it)) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onClearCache,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除缓存")
                    }
                    
                    Button(
                        onClick = onResetSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重置设置")
                    }
                }
            }
            
            // 关于
            SettingsSection(title = "关于") {
                Text(
                    text = "RK3568 HDMI 媒体播放器",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = "版本: 1.0.0",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), MaterialTheme.shapes.medium)
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = Color.Cyan,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 14.sp)
            subtitle?.let {
                Text(text = it, color = Color.Gray, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Cyan,
                checkedTrackColor = Color.Cyan.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, color = Color.White, fontSize = 14.sp)
            Text(text = valueText, color = Color.Cyan, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.Cyan,
                activeTrackColor = Color.Cyan
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    title: String,
    options: List<Pair<String, String>>,  // value to display text
    selectedValue: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = options.find { it.first == selectedValue }?.second ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Cyan,
                    unfocusedBorderColor = Color.Gray
                )
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, displayText) ->
                    DropdownMenuItem(
                        text = { Text(displayText) },
                        onClick = {
                            onValueChange(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInputSetting(
    title: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
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

// 辅助函数
private fun getLoopModeText(mode: LoopMode): String = when (mode) {
    LoopMode.SINGLE -> "单个循环"
    LoopMode.LIST -> "列表循环"
    LoopMode.RANDOM -> "随机播放"
}

private fun getScaleModeText(mode: VideoScaleMode): String = when (mode) {
    VideoScaleMode.FIT -> "适应 (保持比例)"
    VideoScaleMode.FILL -> "填充 (裁剪)"
    VideoScaleMode.STRETCH -> "拉伸"
    VideoScaleMode.ORIGINAL -> "原始大小"
}

private fun getTransitionText(transition: ImageTransition): String = when (transition) {
    ImageTransition.FADE -> "淡入淡出"
    ImageTransition.SLIDE -> "滑动"
    ImageTransition.NONE -> "无动画"
}

private fun getLayoutModeText(mode: LayoutMode): String = when (mode) {
    LayoutMode.GRID_2X2 -> "2×2 网格"
    LayoutMode.ROW_1X4 -> "1×4 横向"
    LayoutMode.COLUMN_4X1 -> "4×1 纵向"
}
