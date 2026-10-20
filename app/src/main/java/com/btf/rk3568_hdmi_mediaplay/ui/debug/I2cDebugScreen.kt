package com.btf.rk3568_hdmi_mediaplay.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.BusState
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.NodeReport
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.WriteStrategy
import com.btf.rk3568_hdmi_mediaplay.ui.components.mouseDragScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 状态自动刷新间隔（需求：5 秒） */
private const val REFRESH_INTERVAL_MS = 5_000L

/** 持续写间隔（需求：1 秒） */
private const val WRITE_INTERVAL_MS = 1_000L

/**
 * I2C 现场调试页 —— **Debug 包启动首页**。
 *
 * 1. 总线状态（每 5 秒自动刷新）
 * 2. **全部节点**：14 个 ABI 节点 + 实际读数（每 5 秒刷新）
 * 3. 持续写（1 秒一次；节点存在时自动开始）
 */
@Composable
fun I2cDebugScreen(onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var busReport by remember { mutableStateOf<I2cProbe.BusReport?>(null) }
    var nodeReport by remember { mutableStateOf<NodeReport?>(null) }
    var readings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    var strategy by remember { mutableStateOf(WriteStrategy.UNAVAILABLE) }
    var strategyLocked by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var autoStarted by remember { mutableStateOf(false) }
    var writeOk by remember { mutableIntStateOf(0) }
    var writeFail by remember { mutableIntStateOf(0) }
    var lastWrite by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        busy = true
        try {
            val bus = I2cProbe.probeBusWithTimeout()
            val nodes = withContext(Dispatchers.IO) { I2cProbe.inspectNodes() }
            val values = withContext(Dispatchers.IO) { I2cProbe.readAllPresent(nodes.present) }
            busReport = bus
            nodeReport = nodes
            readings = values
            refreshCount++

            if (!strategyLocked) {
                strategy = I2cProbe.resolveStrategy(bus)
            }
            // 需求 3：**默认启动 I2C** —— 进入页面就自动开始持续写。
            // 只要存在任一可用策略（mode 节点在，或 su 可用）就自动开；
            // 两者都不可用时保持停止，并在页面明确显示原因（不假装在发）。
            if (!autoStarted && strategy != WriteStrategy.UNAVAILABLE) {
                autoStarted = true
                writing = true
            }
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            refresh()
        }
    }

    LaunchedEffect(writing, strategy) {
        if (!writing) return@LaunchedEffect
        while (isActive && writing) {
            val err = I2cProbe.writeOnce(strategy)
            if (err == null) {
                writeOk++
                lastWrite = "OK"
            } else {
                writeFail++
                lastWrite = err
            }
            delay(WRITE_INTERVAL_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1318))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18232E))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("I2C 调试", color = Color(0xFF80DEEA), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text(
                    "每 ${REFRESH_INTERVAL_MS / 1000}s 自动刷新（已 $refreshCount 次）",
                    color = Color(0xFF78909C),
                    fontSize = 11.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (!busy) scope.launch { refresh() } },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("刷新", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("进入播放", fontSize = 12.sp) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .mouseDragScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BusSection(busReport)
            NodesSection(nodeReport, readings)
            WriteSection(
                strategy = strategy,
                strategyLocked = strategyLocked,
                writing = writing,
                ok = writeOk,
                fail = writeFail,
                last = lastWrite,
                onStrategy = { strategy = it; strategyLocked = true },
                onAuto = {
                    scope.launch {
                        val latest = I2cProbe.probeBus()
                        strategy = I2cProbe.resolveStrategy(latest)
                        strategyLocked = false
                    }
                },
                onToggle = { writing = !writing },
                onReset = { writeOk = 0; writeFail = 0; lastWrite = null }
            )
        }
    }
}

@Composable
private fun BusSection(report: I2cProbe.BusReport?) {
    Card2("① I2C 总线状态") {
        if (report == null) {
            Text("探测中…", color = Color(0xFF90A4AE), fontSize = 13.sp)
            return@Card2
        }
        val label: String
        val color: Color
        when (report.state) {
            BusState.ONLINE -> { label = "通 · 从机应答"; color = Color(0xFF66BB6A) }
            BusState.NO_DEVICE -> { label = "不通 · 从机无应答 (ENODEV)"; color = Color(0xFFEF5350) }
            BusState.NODE_MISSING -> { label = "不通 · 驱动节点不存在 (ENOENT)"; color = Color(0xFFFFA726) }
            BusState.UNREADABLE -> { label = "节点存在但读取失败"; color = Color(0xFFFFA726) }
            BusState.CANNOT_PROBE -> { label = "无法探测 · 无节点且无 su"; color = Color(0xFF90A4AE) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(13.dp).background(color, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(label, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Kv("探测方式", report.method)
        report.version?.let { Kv("version", it.toString()) }
        report.detectLine?.let { Kv("0x30", it.trim()) }
        report.detail?.let { Kv("说明", it, Color(0xFFFFCC80)) }
    }
}

@Composable
private fun NodesSection(report: NodeReport?, readings: Map<String, String>) {
    Card2("② 驱动节点（${I2cProbe.basePath()}）") {
        if (report == null) {
            Text("读取中…", color = Color(0xFF90A4AE), fontSize = 13.sp)
            return@Card2
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "存在 ${report.present.size} / ${report.required.size}",
                color = if (report.missing.isEmpty()) Color(0xFF66BB6A) else Color(0xFFFFA726),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (report.missing.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "缺失：" + report.missing.joinToString(" "),
                    color = Color(0xFFE57373),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 两列布局：每列 7 个节点（14 个 ABI 节点刚好 2×7）。
        // 单列 14 行在 600px 高的屏上要滚两次才能看全，现场核对不方便。
        val nodes = I2cProbe.REQUIRED_NODES
        val left = nodes.take(7)
        val right = nodes.drop(7)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            NodeColumn(left, report, readings, Modifier.weight(1f))
            NodeColumn(right, report, readings, Modifier.weight(1f))
        }

        if (report.extra.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "目录内其他条目：" + report.extra.joinToString("  "),
                color = Color(0xFF607D8B),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** 一列节点：节点名 / 状态 / 当前值（说明放在悬浮提示里，两列排布下空间不够） */
@Composable
private fun NodeColumn(
    names: List<String>,
    report: NodeReport,
    readings: Map<String, String>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 列头
        Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Cell("节点", 92.dp, Color(0xFF607D8B), bold = true, size = 11)
            Cell("状态", 52.dp, Color(0xFF607D8B), bold = true, size = 11)
            Cell("当前值", 0.dp, Color(0xFF607D8B), bold = true, size = 11)
        }

        names.forEach { name ->
            val ok = name in report.present
            val value = readings[name]
            val isError = value != null && (
                value.contains("ENODEV") || value.contains("EACCES") ||
                    value.contains("EPERM") || value.contains("EIO") ||
                    value.contains("ETIMEDOUT") || value.startsWith("errno=")
                )

            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cell(name, 92.dp, if (ok) Color(0xFFCFD8DC) else Color(0xFF8A8A8A), mono = true, size = 11)
                    Cell(
                        if (ok) "存在" else "缺失",
                        52.dp,
                        if (ok) Color(0xFF81C784) else Color(0xFFE57373),
                        size = 11
                    )
                    // 直接显示数值；失败时显示具体 errno 语义，
                    // 现场靠它区分「从机不应答」与「权限不足」。
                    Cell(
                        when {
                            !ok -> "—"
                            value == null -> "读取失败"
                            else -> value
                        },
                        0.dp,
                        when {
                            !ok -> Color(0xFF666666)
                            value == null -> Color(0xFFFFAB91)
                            isError -> Color(0xFFFF8A80)
                            else -> Color(0xFFFFF59D)
                        },
                        mono = true,
                        size = 10
                    )
                }
                // 描述：协议里每个节点的含义与取值范围（值下方小字）
                Text(
                    I2cProbe.describeNode(name),
                    color = Color(0xFF6B7C8A),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp, top = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun WriteSection(
    strategy: WriteStrategy,
    strategyLocked: Boolean,
    writing: Boolean,
    ok: Int,
    fail: Int,
    last: String?,
    onStrategy: (WriteStrategy) -> Unit,
    onAuto: () -> Unit,
    onToggle: () -> Unit,
    onReset: () -> Unit
) {
    Card2("③ 持续写（每 ${WRITE_INTERVAL_MS / 1000} 秒一次）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("策略：", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            Chip("自动", !strategyLocked, onAuto)
            Spacer(Modifier.width(6.dp))
            Chip("mode 幂等写", strategyLocked && strategy == WriteStrategy.MODE_IDEMPOTENT) {
                onStrategy(WriteStrategy.MODE_IDEMPOTENT)
            }
            Spacer(Modifier.width(6.dp))
            Chip("总线探测 (su)", strategyLocked && strategy == WriteStrategy.BUS_PROBE) {
                onStrategy(WriteStrategy.BUS_PROBE)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (writing) Color(0xFFC62828) else Color(0xFF2E7D32)
                ),
                modifier = Modifier.width(104.dp)
            ) { Text(if (writing) "停止" else "启动", fontSize = 14.sp) }

            Spacer(Modifier.width(18.dp))
            Stat("成功", ok, Color(0xFF81C784))
            Spacer(Modifier.width(18.dp))
            Stat("失败", fail, Color(0xFFE57373))
            Spacer(Modifier.width(18.dp))
            TextButton(onClick = onReset) { Text("清零", fontSize = 12.sp) }

            last?.let {
                Spacer(Modifier.width(14.dp))
                Text(
                    "最近：$it",
                    color = if (it == "OK") Color(0xFF81C784) else Color(0xFFFFAB91),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        val hint: String
        val hintColor: Color
        when (strategy) {
            WriteStrategy.MODE_IDEMPOTENT -> {
                hint = "写 mode 的当前值（幂等）。文档 §4.4：相同值安全，画面不变，但总线有事务。"
                hintColor = Color(0xFF78909C)
            }
            WriteStrategy.BUS_PROBE -> {
                hint = "每秒执行一次 i2cdetect -y 4（SMBus quick probe），节点缺失时的唯一手段。"
                hintColor = Color(0xFF78909C)
            }
            WriteStrategy.UNAVAILABLE -> {
                hint = "无可用路径：节点不存在且无 su。此时无法产生任何 I2C 流量。"
                hintColor = Color(0xFFFF8A80)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(hint, color = hintColor, fontSize = 11.sp)
    }
}

@Composable
private fun Card2(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161E26))
            .border(1.dp, Color(0xFF273441))
            .padding(14.dp)
    ) {
        Text(title, color = Color(0xFF80DEEA), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Kv(key: String, value: String, valueColor: Color = Color(0xFFCFD8DC)) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$key：", color = Color(0xFF78909C), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    width: Dp,
    color: Color,
    bold: Boolean = false,
    mono: Boolean = false,
    size: Int = 12
) {
    val m = if (width.value == 0f) Modifier.weight(1f) else Modifier.width(width)
    Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = m
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) Color(0xFF00695C) else Color(0xFF22303C),
            contentColor = if (selected) Color.White else Color(0xFFB0BEC5)
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
    ) { Text(label, fontSize = 11.sp) }
}

@Composable
private fun Stat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFF78909C), fontSize = 10.sp)
    }
}
