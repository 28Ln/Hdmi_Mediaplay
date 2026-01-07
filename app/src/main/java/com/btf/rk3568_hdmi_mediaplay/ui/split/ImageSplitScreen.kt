package com.btf.rk3568_hdmi_mediaplay.ui.split

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btf.rk3568_hdmi_mediaplay.data.model.SplitLayout

// 统一配色
private val BgColor = Color(0xFF1A1A1A)
private val CardColor = Color(0xFF2A2A2A)
private val AccentColor = Color.Cyan
private val TextPrimary = Color.White
private val TextSecondary = Color.Gray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSplitScreen(
    viewModel: ImageSplitViewModel = viewModel(),
    onBack: () -> Unit,
    onSelectImage: () -> Unit,
    onApplyComplete: () -> Unit
) {
    val selectedImageUri by viewModel.selectedImageUri.collectAsState()
    val imageInfo by viewModel.imageInfo.collectAsState()
    val selectedLayout by viewModel.selectedLayout.collectAsState()
    val recommendedLayout by viewModel.recommendedLayout.collectAsState()
    val splitConfig by viewModel.splitConfig.collectAsState()
    val splitResult by viewModel.splitResult.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(successMessage) {
        successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("✂️ 图片裁剪", color = TextPrimary) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← 返回", color = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardColor)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 图片预览区域
                ImagePreviewSection(
                    imageUri = selectedImageUri,
                    imageInfo = imageInfo,
                    selectedLayout = selectedLayout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )

                // 图片信息
                imageInfo?.let { info ->
                    SectionCard {
                        Text("📷 图片信息", color = AccentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("文件: ${info.name}", color = TextPrimary, fontSize = 12.sp)
                        Text("尺寸: ${info.dimensionText}", color = TextSecondary, fontSize = 12.sp)
                        Text("大小: ${info.sizeFormatted}", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                // 布局选择器 - 紧凑的横向排列
                SectionCard {
                    Text("📐 选择裁剪布局", color = AccentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SplitLayout.entries.forEach { layout ->
                            LayoutOptionItem(
                                layout = layout,
                                isSelected = layout == selectedLayout,
                                isRecommended = layout == recommendedLayout,
                                onClick = { viewModel.selectLayout(layout) }
                            )
                        }
                    }
                }

                // 裁剪配置信息
                splitConfig?.let { config ->
                    SectionCard {
                        Text("⚙️ 裁剪配置", color = AccentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("布局: ${config.layout.displayName} - ${config.layout.description}", color = TextPrimary, fontSize = 12.sp)
                        Text("每块尺寸: ${config.partWidth} × ${config.partHeight}", color = TextSecondary, fontSize = 12.sp)
                        Text("总块数: ${config.layout.totalParts}", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                // 裁剪结果预览
                splitResult?.let { result ->
                    if (result.success && result.parts.isNotEmpty()) {
                        SectionCard {
                            Text("✅ 播放预览", color = AccentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))
                            SplitResultPreview(result = result)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSelectImage,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Text("📁 选择图片", color = TextPrimary)
                    }

                    Button(
                        onClick = { viewModel.executeSplit() },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && selectedImageUri != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF388E3C),
                            disabledContainerColor = Color(0xFF333333)
                        )
                    ) {
                        Text("✂️ 执行裁剪", color = if (selectedImageUri != null) TextPrimary else TextSecondary)
                    }
                }

                // 保存应用按钮
                Button(
                    onClick = {
                        viewModel.saveAndApply { success, _ ->
                            if (success) onApplyComplete()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && splitResult?.success == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        disabledContainerColor = Color(0xFF333333)
                    )
                ) {
                    Text(
                        "💾 保存并应用到播放器",
                        color = if (splitResult?.success == true) Color.Black else TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // 加载指示器
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentColor)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("处理中...", color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}


@Composable
private fun ImagePreviewSection(
    imageUri: android.net.Uri?,
    imageInfo: com.btf.rk3568_hdmi_mediaplay.data.model.ImageInfo?,
    selectedLayout: SplitLayout,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = CardColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null && imageInfo != null) {
                // 根据图片实际比例计算显示区域
                val imageAspectRatio = imageInfo.width.toFloat() / imageInfo.height.toFloat()
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(imageAspectRatio, matchHeightConstraintsFirst = true),
                    contentAlignment = Alignment.Center
                ) {
                    // 图片填满这个按比例的区域
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "预览图片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds  // 填满整个区域
                    )
                    // 裁剪线覆盖在图片上
                    SplitGridOverlay(
                        layout = selectedLayout,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (imageUri != null) {
                // 还没获取到图片信息时的临时显示
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "预览图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🖼️", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("请选择图片", color = TextSecondary, fontSize = 14.sp)
                    Text("点击下方按钮选择要裁剪的图片", color = TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SplitGridOverlay(layout: SplitLayout, modifier: Modifier = Modifier) {
    val lineColor = Color.Red.copy(alpha = 0.8f)
    
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellWidth = size.width / layout.cols
            val cellHeight = size.height / layout.rows
            
            // 垂直线
            for (i in 1 until layout.cols) {
                drawLine(
                    color = lineColor,
                    start = Offset(cellWidth * i, 0f),
                    end = Offset(cellWidth * i, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
            // 水平线
            for (i in 1 until layout.rows) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, cellHeight * i),
                    end = Offset(size.width, cellHeight * i),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

@Composable
private fun LayoutOptionItem(
    layout: SplitLayout,
    isSelected: Boolean,
    isRecommended: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        isSelected -> AccentColor
        isRecommended -> Color.Yellow
        else -> Color(0xFF444444)
    }
    val bgColor = if (isSelected) AccentColor.copy(alpha = 0.2f) else Color(0xFF333333)

    Surface(
        modifier = Modifier
            .width(72.dp)
            .height(64.dp)
            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = bgColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LayoutPreviewGrid(rows = layout.rows, cols = layout.cols, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = layout.displayName,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) AccentColor else TextPrimary,
                textAlign = TextAlign.Center
            )
            if (isRecommended) {
                Text("推荐", fontSize = 8.sp, color = Color.Yellow)
            }
        }
    }
}

@Composable
private fun LayoutPreviewGrid(rows: Int, cols: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cellWidth = size.width / cols
        val cellHeight = size.height / rows
        val gridColor = Color.White
        
        // 外边框
        drawRect(color = gridColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        // 垂直线
        for (i in 1 until cols) {
            drawLine(color = gridColor, start = Offset(cellWidth * i, 0f), end = Offset(cellWidth * i, size.height), strokeWidth = 1.dp.toPx())
        }
        // 水平线
        for (i in 1 until rows) {
            drawLine(color = gridColor, start = Offset(0f, cellHeight * i), end = Offset(size.width, cellHeight * i), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun SplitResultPreview(result: com.btf.rk3568_hdmi_mediaplay.data.model.SplitResult) {
    val layout = result.config.layout
    // 播放预览使用 16:9 比例模拟实际屏幕效果
    val screenAspectRatio = 16f / 9f
    
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📺 播放预览效果 (模拟拉伸填满)", color = TextSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        
        // 按布局的行列显示
        for (row in 0 until layout.rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (col in 0 until layout.cols) {
                    val index = row * layout.cols + col
                    val part = result.parts.getOrNull(index)
                    
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(screenAspectRatio),  // 使用 16:9 屏幕比例
                        color = Color(0xFF333333),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            part?.bitmap?.let { bitmap ->
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds  // 拉伸填满，模拟播放效果
                                )
                            }
                            // 块编号
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("${index + 1}", color = AccentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
    
    // 显示实际尺寸信息
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "裁剪尺寸: ${result.config.partWidth} × ${result.config.partHeight} → 拉伸至屏幕",
        color = TextSecondary,
        fontSize = 11.sp
    )
}
