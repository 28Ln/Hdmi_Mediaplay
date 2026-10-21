package com.btf.rk3568_hdmi_mediaplay.ui.debug

import android.os.SystemClock
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.BusState
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.NodeReport
import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.WriteStrategy
import com.btf.rk3568_hdmi_mediaplay.ui.components.mouseDragScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    // 带时间戳的读数：慢总线上 14 个节点轮一遍要 3.6 分钟，
    // 界面必须能说明每个值有多旧。
    var readings by remember {
        mutableStateOf<Map<String, I2cProbe.Reading>>(emptyMap())
    }
    var refreshCount by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    var strategy by remember { mutableStateOf(WriteStrategy.UNAVAILABLE) }
    var strategyLocked by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var autoStarted by remember { mutableStateOf(false) }

    /**
     * 暂停**全部** I2C 流量（刷新 + 写循环都停）。
     *
     * ## 为什么需要单独的开关（2026-10-21）
     *
     * 现场排查「I2C 先好后坏」时，需要一段**总线上绝对零流量**的观察窗口。
     * 而原来的「停止」按钮只停写循环，`refresh()` 仍在每 5 秒读一次 ——
     * 窗口并不干净，无法据此判断「不访问是否就不会坏」。
     *
     * 实测依据：App force-stop 后 61 秒内核零 timeout；
     * 而停在调试页 46 秒产生 30 条 timeout。可见流量确实全部来自本页。
     */
    var paused by remember { mutableStateOf(false) }
    var writeOk by remember { mutableIntStateOf(0) }
    var writeFail by remember { mutableIntStateOf(0) }
    var lastWrite by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        busy = true
        try {
            // ## 三步**并行**，各自超时，互不拖累（2026-10-20 第二次修复）
            //
            // 第一版把三步串行包在一个 withTimeoutOrNull 里，实测后果：
            // 读 `version` 会阻塞（驱动等 I2C 超时），整轮直接超时返回 null，
            // 于是 busReport 显示了，**nodeReport 却一直没被赋值** ——
            // 界面永远停在「读取中…」。
            //
            // 现在并行发起：
            // - 总线探测：最慢（要等驱动 I2C 超时），给 6s
            // - 节点列举：File.list 很快，给 2s
            // - 节点读数：每个节点 800ms 硬上限，整体给 3s
            // 任一失败只影响自己那一块，其余照常显示。
            val bus = coroutineScope {
                // 节点列举立刻发起，**先发布到界面**，不等总线探测。
                // 探测要 6s（驱动等 I2C 超时），节点表不该陪着一起等。
                val nodesDeferred = async(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) { I2cProbe.inspectNodes() }
                }
                val busDeferred = async(Dispatchers.IO) {
                    // ## 注意：这个 6 秒超时**对 probeBus 不起作用**
                    // （2026-10-21 真机实测确认）
                    //
                    // probeBus 内部通过 `readBounded` 读 version，而 readBounded
                    // 用 `future.get(...)` **阻塞等待**。协程取消无法打断阻塞中的
                    // future.get，因此 withTimeoutOrNull 只能放弃「等待结果」，
                    // 后台读仍会跑完。
                    //
                    // 真机证据：轮转间隔实测 15.7 秒（= 驱动返回耗时 + 刷新间隔），
                    // 而不是 6 秒。
                    //
                    // 保留它只是兜住「probeBus 自身卡在非阻塞段」的极端情况；
                    // 真正的耗时上限由 I2cProbe.NODE_READ_TIMEOUT_MS +
                    // SLOW_RETURN_GRACE_MS 决定（当前 15.8 秒）。
                    withTimeoutOrNull(6_000L) { I2cProbe.probeBus() }
                }

                nodesDeferred.await()?.let { nodes ->
                    nodeReport = nodes
                    // 预算 6 秒：慢总线（无应答时单节点要等驱动超时约 10s）
                    // 下优先保证界面持续刷新，读不完的节点本轮留空，下轮再补。
                    val values = withTimeoutOrNull(8_000L) {
                        withContext(Dispatchers.IO) {
                            I2cProbe.readAllPresentStamped(nodes.present, budgetMs = 6_000L)
                        }
                    }
                    if (values != null) {
                        // 合并而不是替换：本轮没读到的节点保留旧值，
                        // 靠时间戳说明它有多旧。
                        //
                        // 但必须**按当前节点集合裁剪** —— 驱动重绑定后节点可能
                        // 增减，残留的旧条目会一直留在 Map 里；若同名节点后来
                        // 又被加回来，会先显示一次来路不明的旧值。
                        val keep = nodes.required + nodes.extra
                        val merged = (readings + values).filterKeys { it in keep }
                        readings = merged
                    }
                }
                busDeferred.await()
            }

            busReport = bus ?: I2cProbe.BusReport(
                state = BusState.PROBE_TIMEOUT,
                method = "总线探测超时（6s）",
                detail = "读 version 时驱动未返回。驱动已绑定、节点也在（见 ②），" +
                    "只是单次读要等内核 I2C 超时（实测 5.6~25 秒），超出了本次预算"
            )
            refreshCount++

            if (!strategyLocked) {
                strategy = I2cProbe.resolveStrategy(busReport!!)
            }
            // ## 默认改为**只读**，写需手动启动（2026-10-21 修正）
            //
            // 原实现「进入页面就自动开始每秒写 mode」。但实测发现：
            // - 总线 ONLINE 时该策略是**真实的写命令**（读 mode → 写回）
            // - 每秒 1 次 × 数百秒 = 数百次向 MCU 的写
            // - 现场「只是打开 App」= 立即开始持续写拼接板
            //
            // 对示波器测量来说，**读事务同样有完整波形**
            // （START + 地址 + R + STOP），完全够用，且对面板零副作用。
            //
            // 因此默认停留在「只读探测」，需要写时由操作者显式点「启动」。
            if (!autoStarted && !paused && strategy != WriteStrategy.UNAVAILABLE) {
                autoStarted = true
                // 仅当策略是只读探测时才自动开始；写策略必须手动启动
                writing = (strategy == WriteStrategy.READ_PROBE)
            }
        } finally {
            busy = false
        }
    }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        // 每一轮都单独兜异常：单次失败（超时/IO 错误）不能让整个自动刷新停摆，
        // 否则界面会永久停在"探测中"——这正是本轮在真机上观察到的现象。
        runCatching { refresh() }
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            runCatching { refresh() }
        }
    }

    LaunchedEffect(writing, strategy) {
        if (!writing) return@LaunchedEffect
        // ## 按**固定节拍**发送，而不是「发完再等 1 秒」
        //
        // 从机不应答时 writeOnce 本身要占满 800ms（读超时），
        // 「发完再 delay(1000)」会让实际周期变成 ~1.8s，
        // 示波器上量出来的间隔与标注的 1 秒对不上。
        // 这里以起始时刻为基准补齐剩余时间，保持 1s 节拍稳定。
        var next = SystemClock.elapsedRealtime()
        while (isActive && writing) {
            // 熔断后不再发送：每一次都会再留下一个无法回收的 D 态线程
            if (I2cProbe.isChannelBlocked()) {
                writing = false
                lastWrite = "总线已挂死，发送停止"
                break
            }
            val outcome = I2cProbe.writeOnce(strategy)
            if (outcome.onBus) writeOk++ else writeFail++
            lastWrite = outcome.text
            next += WRITE_INTERVAL_MS
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) {
                delay(wait)
            } else {
                // 本轮超时了（单次事务比周期还长），重新对齐基准，避免追帧
                next = SystemClock.elapsedRealtime()
            }
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
            // ## 左侧必须可收缩（2026-10-21 真机发现）
            //
            // 原来左右两个 Row 都没有约束，标题 + 状态文字占满了宽度，
            // 右侧三个按钮被**完全挤出屏幕**（1024px 宽下只看到「进入播放」，
            // 「刷新」和「暂停全部」都看不见）。
            //
            // 改为：左侧 weight(1f) 可收缩，状态文字单行省略；
            // 右侧按钮保持自然宽度，优先可见。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "I2C 调试",
                    color = Color(0xFF80DEEA),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    if (paused) "⏸ 已暂停（零流量）"
                    else "每 ${REFRESH_INTERVAL_MS / 1000}s 刷新 · 已 $refreshCount 次",
                    color = if (paused) Color(0xFFFFB74D) else Color(0xFF78909C),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp)
            ) {
                // 暂停全部流量：排查「不访问是否就不会坏」时，需要一段
                // 总线上绝对零事务的干净窗口。只停写循环是不够的 ——
                // refresh 仍会每 5 秒读一次。
                Button(
                    onClick = {
                        paused = !paused
                        if (paused) writing = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (paused) Color(0xFFEF6C00) else Color(0xFF37474F)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text(if (paused) "恢复" else "暂停", fontSize = 12.sp) }
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
            BusSection(
                report = busReport,
                channelBlocked = I2cProbe.isChannelBlocked(),
                onRetry = {
                    I2cProbe.resetChannel()
                    scope.launch { runCatching { refresh() } }
                }
            )
            NodesSection(nodeReport, readings)
            WriteSection(
                strategy = strategy,
                strategyLocked = strategyLocked,
                channelBlocked = I2cProbe.isChannelBlocked(),
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
private fun BusSection(report: I2cProbe.BusReport?, channelBlocked: Boolean, onRetry: () -> Unit) {
    Card2("① I2C 总线状态") {
        // 熔断优先展示：此时所有读数都已停止，必须让人一眼看出原因
        if (channelBlocked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(13.dp).background(Color(0xFFEF5350), CircleShape))
                Spacer(Modifier.width(9.dp))
                Text("总线挂死 · 已停止发送", color = Color(0xFFEF5350), fontSize = 19.sp,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Kv("原因", "首次传输无 ACK 后，rk3x-i2c 未完成该请求：调用方永久停在 D 态，" +
                "适配器锁一直不释放")
            Kv("后果", "此后任何 I2C 访问都 EBUSY；继续发只会继续泄漏线程")
            Kv("处置", "硬件排查（VDD / SDA-SCL 上拉 / 接线 / 地址跳线）后重启设备")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry, modifier = Modifier.width(120.dp)) {
                Text("重试一次", fontSize = 13.sp)
            }
            return@Card2
        }
        if (report == null) {
            Text("探测中…", color = Color(0xFF90A4AE), fontSize = 13.sp)
            return@Card2
        }
        val label: String
        val color: Color
        when (report.state) {
            BusState.ONLINE -> { label = "通 · 从机应答"; color = Color(0xFF66BB6A) }
            BusState.NO_DEVICE -> { label = "不通 · 从机无应答（驱动等待超时）"; color = Color(0xFFEF5350) }
            BusState.NODE_MISSING -> { label = "不通 · 驱动节点不存在 (ENOENT)"; color = Color(0xFFFFA726) }
            BusState.UNREADABLE -> { label = "节点存在但读取失败"; color = Color(0xFFFFA726) }
            BusState.CANNOT_PROBE -> { label = "无法探测 · 驱动未绑定且无 root"; color = Color(0xFF90A4AE) }
            BusState.PROBE_TIMEOUT -> { label = "探测超时 · 读 version 超过预算"; color = Color(0xFFFFA726) }
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

        // ## 区分「从未通」与「先好后坏」（2026-10-21）
        //
        // 现场反馈「刚开机是好的，运行一段时间后失效，必须重启」。
        // 若属实，根因多半是上电时序（MCU 比 SoC 慢几秒启动）。
        // 只靠「当前不通」无法区分两种处置完全不同的情况：
        // - 从未成功 → 硬件从开机就坏（查供电/上拉/接线）
        // - 曾经成功 → 上电时序问题（需驱动侧延迟重试）
        if (report.everSucceeded) {
            val at = I2cProbe.firstSuccessAtElapsedMs
            Kv(
                "历史",
                "本进程内曾成功读取（首次在开机后 ${at / 1000}s）→ 属「先好后坏」",
                Color(0xFFFFAB91)
            )
        } else if (report.state != BusState.ONLINE) {
            Kv(
                "历史",
                "本进程内从未成功读取过 → 更像是硬件链路本身不通",
                Color(0xFF90A4AE)
            )
        }

        // 超时（驱动挂住）是最容易被误判成「App 卡了」的情况，单独解释。
        if (report.method.contains("超时")) {
            Spacer(Modifier.height(6.dp))
            Text(
                "内核 rk3x-i2c 反复报 timeout 说明：请求发出去了，但总线上没有从机回应，" +
                    "驱动会一直等到超时才返回（因此本页每次刷新都要等一会儿）。" +
                    "用 adb logcat | grep rk3x-i2c 可以看到同样的报错。",
                color = Color(0xFFFFAB91),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NodesSection(report: NodeReport?, readings: Map<String, I2cProbe.Reading>) {
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
/** 正常读数：十进制整数，或 `0x` 前缀的十六进制（sysfs 两种都可能输出）。 */
private fun looksLikeValue(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    if (t.startsWith("0x", ignoreCase = true)) {
        return t.substring(2).toLongOrNull(16) != null
    }
    return t.toLongOrNull() != null
}

@Composable
private fun NodeColumn(
    names: List<String>,
    report: NodeReport,
    readings: Map<String, I2cProbe.Reading>,
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
            val reading = readings[name]
            val value = reading?.value
            val ageMs = reading?.let {
                android.os.SystemClock.elapsedRealtime() - it.readAtElapsedMs
            }
            // 判据是「是否像正常读数」：十进制或 0x 十六进制都算正常，
            // 其余文本（超时、通道阻塞、EACCES…）一律按错误色显示。
            //
            // 早先只匹配 errno 关键字，导致「读取通道阻塞（前一次读未返回）」
            // 和「读取超时（驱动未返回）」被当成正常值显示成黄色，
            // 现场会误以为那是节点读数。
            val isError = value != null && !looksLikeValue(value)

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
                            value == null -> "未读到"
                            // 慢总线上节点可能几分钟才轮到一次，
                            // 超过 30 秒就标注年龄，避免被当成「当前值」
                            ageMs != null && ageMs > 30_000L ->
                                "$value  (${ageMs / 1000}s 前)"
                            else -> value
                        },
                        0.dp,
                        when {
                            !ok -> Color(0xFF666666)
                            value == null -> Color(0xFFFFAB91)
                            isError -> Color(0xFFFF8A80)
                            // 数据偏旧用暗黄提示，区别于「刚读到」的亮黄
                            ageMs != null && ageMs > 30_000L -> Color(0xFFBCAAA4)
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
    channelBlocked: Boolean,
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
            // 自动模式下把**实际选中的策略**显示在标签上，
            // 否则现场只看到"自动"高亮，无法知道当前到底在跑哪条路径。
            Chip(
                if (strategyLocked) "自动" else "自动（${
                    when (strategy) {
                        WriteStrategy.MODE_IDEMPOTENT -> "mode 幂等写"
                        WriteStrategy.READ_PROBE -> "只读探测"
                        WriteStrategy.BUS_PROBE -> "总线探测"
                        WriteStrategy.UNAVAILABLE -> "不可用"
                    }
                }）",
                !strategyLocked,
                onAuto
            )
            Spacer(Modifier.width(6.dp))
            Chip("mode 幂等写", strategyLocked && strategy == WriteStrategy.MODE_IDEMPOTENT) {
                onStrategy(WriteStrategy.MODE_IDEMPOTENT)
            }
            Spacer(Modifier.width(6.dp))
            Chip("只读 version", strategyLocked && strategy == WriteStrategy.READ_PROBE) {
                onStrategy(WriteStrategy.READ_PROBE)
            }
            Spacer(Modifier.width(6.dp))
            Chip("总线探测 (su)", strategyLocked && strategy == WriteStrategy.BUS_PROBE) {
                onStrategy(WriteStrategy.BUS_PROBE)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 熔断时禁用启动：每次发送都要赔上一个不可回收的 D 态线程
            Button(
                onClick = onToggle,
                enabled = !channelBlocked,
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

        // ## 累计写入量提示（2026-10-21）
        //
        // MODE_IDEMPOTENT 的真实行为是「读 mode → 原值写回」，
        // 每次都会**向 MCU 发一条写命令**（不是纯读）。
        // 每秒 1 次 × 持续运行 = 可观的写入量，现场需要知道这个数字。
        //
        // 实测背景：调试页进入约 5 秒后自动开始每秒写，
        // 在可用窗口（约 200 秒）内约产生 200 次 mode 写。
        if (strategy == WriteStrategy.MODE_IDEMPOTENT && (ok + fail) > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "已向 MCU 发送 ${ok + fail} 次 mode 写命令" +
                    "（每秒 1 次；MODE 写会占用 MCU 的 I2C 带宽）",
                color = Color(0xFFFFB74D),
                fontSize = 11.sp
            )
        }

        val hint: String
        val hintColor: Color
        when (strategy) {
            WriteStrategy.MODE_IDEMPOTENT -> {
                hint = "写 mode 的当前值（幂等）。文档 §4.4：相同值安全，画面不变，" +
                    "但**每次都会向 MCU 发一条写命令**。"
                hintColor = Color(0xFF78909C)
            }
            WriteStrategy.READ_PROBE -> {
                hint = "每秒读一次 version。读也是完整 I2C 事务（START+地址+R+STOP），" +
                    "示波器可见；从机不应答时为 NACK+STOP，对面板无任何副作用。"
                hintColor = Color(0xFF78909C)
            }
            WriteStrategy.BUS_PROBE -> {
                hint = "每秒执行一次 i2cdetect -y 4（SMBus quick probe），仅在驱动未绑定时使用。"
                hintColor = Color(0xFF78909C)
            }
            WriteStrategy.UNAVAILABLE -> {
                hint = "无可用路径：驱动未绑定且无 root。此时无法产生任何 I2C 流量。"
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
