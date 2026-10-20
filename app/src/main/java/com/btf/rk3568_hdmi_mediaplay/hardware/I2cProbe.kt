package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * I2C 总线 / 驱动节点的现场探针（**调试用，仅出现在 Debug 包首页**）。
 *
 * ## 它回答三个问题
 *
 * 1. **I2C 从机是否应答** —— 优先读 sysfs `version`；节点不在时退回
 *    `su -c i2cdetect -y 4` 解析 0x30 行。
 * 2. **驱动节点是否存在** —— 列出 `/sys/bus/i2c/devices/4-0030/` 实际内容，
 *    逐项标注 ABI 要求的 14 个节点。
 * 3. **持续制造总线流量** —— 1 秒一次，便于示波器/逻辑分析仪观察。
 *
 * ## 为什么持续写是「写回当前 mode」而不是 `key`
 *
 * 文档 §2.4 的 `key` 是**切换语义**：`0x05` 让面板在 1X1↔2X2 之间跳，
 * `0x07` 换分辨率。每秒一次会让画面剧烈闪烁。
 *
 * 而写 `mode` 的**当前值**是幂等的（文档 §4.4：相同值返回 OK 且不动作），
 * 驱动的 I2C 命令照常发出，但画面不变 —— 正是「只要总线有流量」的诉求。
 *
 * **绝不使用 `hfusion`/`vfusion`**：文档明确每次成功写都写 MCU flash。
 *
 * ## 什么时候根本发不出去
 *
 * 驱动没有绑定（节点不存在）时，**sysfs 路径无法产生任何 I2C 流量** ——
 * 因为没有任何代码会把 write() 转成 I2C 事务。此时只能：
 * - 有 root → 退到 `i2cdetect -y 4`（每次执行会向 0..0x7F 发 SMBus quick probe）
 * - 无 root → **如实显示「无法产生 I2C 流量」**，不假装在发
 */
object I2cProbe {

    private const val TAG = "I2cProbe"

    /**
     * 单节点读取的超时（毫秒）。
     *
     * 实测本机固件上读 version 会永久阻塞，必须给硬上限。
     */
    private const val NODE_READ_TIMEOUT_MS = 800L

    /** I2C 总线号（DT alias `i2c4`） */
    const val BUS = 4

    /** 从机 7-bit 地址 */
    const val SLAVE_ADDR = 0x30

    /** ABI 要求的 14 个节点 */
    val REQUIRED_NODES = listOf(
        "mode", "resolution", "mirror", "rotate",
        "hfusion", "vfusion", "mute", "power_state", "key",
        "version", "status", "result", "cmd_count", "rx_state"
    )

    // ==================== 结果模型 ====================

    /**
     * 总线状态。
     *
     * **`NoDevice` 与 `NodeMissing` 不能混为一谈**：
     * 前者查线缆/供电/电平，后者查固件/DTS。
     */
    enum class BusState {
        ONLINE,
        /** 节点存在但 ENODEV：从机不应答（硬件） */
        NO_DEVICE,
        /** 节点不存在：驱动未加载 / DTS 未配（软件） */
        NODE_MISSING,
        /** 节点存在但读失败（权限等） */
        UNREADABLE,
        /** 无节点且无 su */
        CANNOT_PROBE
    }

    data class NodeReport(
        val required: List<String>,
        val present: List<String>,
        val missing: List<String>,
        /** 目录里存在但 ABI 未要求的名字（modalias/name/of_node/power/...） */
        val extra: List<String>
    )

    data class BusReport(
        val state: BusState,
        val version: Int? = null,
        /** 探测方式，便于现场判断结论可信度 */
        val method: String = "",
        /** i2cdetect 的 0x30 原文行（仅 su 路径） */
        val detectLine: String? = null,
        val detail: String? = null
    )

    // ==================== 1. I2C 是否通 ====================

    suspend fun probeBus(): BusReport = withContext(Dispatchers.IO) {
        // 注意：`exists()` / `isDirectory` 在 sysfs 上同样可能被驱动阻塞。
        // 调用方（UI 的 refresh）已对整轮探测加了 5s 总超时，这里不再叠加；
        // 但节点读数与目录列举各自还有 800ms 的硬上限。
        val base = File(HardwareConfig.I2C_BASE_PATH)
        if (!base.exists() || !base.isDirectory) {
            return@withContext BusReport(
                state = BusState.NODE_MISSING,
                method = "sysfs 目录不存在",
                detail = "${HardwareConfig.I2C_BASE_PATH} 不可访问"
            )
        }

        // 注意：不能用 File.exists() 先判断 —— 本机实测目录列举（getdents）正常，
        // 但读 `version` 会阻塞在驱动里。节点是否存在于 NodeReport 有独立结论，
        // 这里只要读一次就知道总线状况。
        val versionFile = File(base, "version")
        if (versionFile.exists()) {
            val text = readNodeValue("version")
            val value = text.toIntOrNull()
            if (value != null) {
                return@withContext BusReport(
                    state = if (value == 2) BusState.ONLINE else BusState.UNREADABLE,
                    version = value,
                    method = "sysfs: version",
                    detail = if (value == 2) null else "version=$value（协议版本应为 2）"
                )
            }
            // 节点在，但读失败：ENODEV 说明从机不应答，这是**硬件**结论
            if (text.contains("ENODEV")) {
                return@withContext BusReport(
                    state = BusState.NO_DEVICE,
                    method = "sysfs: version（存在但读失败）",
                    detail = "节点存在，读取返回 ENODEV → 从机没有 ACK"
                )
            }
            if (text.contains("EACCES")) {
                return@withContext BusReport(
                    state = BusState.UNREADABLE,
                    method = "sysfs: version",
                    detail = "权限不足（节点未 chmod 0666）"
                )
            }
            // 节点存在、但读取在 800ms 内没有返回 → 驱动在等从机 ACK，
            // 内核 `rk3x-i2c` 会一路超时（logcat: timeout, ipd: 0x00, state: 1）。
            // **这是硬件结论**，不能继续往下走到 i2cdetect 兜底
            // （那条路径需要 su，失败后会误报「无节点」，与本页 @@②矛盾）。
            if (text.contains("超时")) {
                return@withContext BusReport(
                    state = BusState.NO_DEVICE,
                    method = "sysfs: version（节点存在，读取无返回）",
                    detail = "驱动等 800ms 未拿到从机应答 → SDA/SCL 无 ACK（查供电/上拉/接线/地址）"
                )
            }
        }

        // 节点不在或读不出 → i2cdetect 兜底（需 root）
        val detect = runI2cDetect()
        if (detect != null) {
            val acked = ACK_PATTERN.containsMatchIn(detect)
            return@withContext BusReport(
                state = if (acked) BusState.ONLINE else BusState.NO_DEVICE,
                method = "su: i2cdetect -y $BUS",
                detectLine = detect,
                detail = if (acked) null else "0x30 未应答（--）"
            )
        }

        BusReport(
            state = BusState.CANNOT_PROBE,
            method = "无 version 节点，且 su 不可用",
            detail = "驱动未绑定且无 root：无法产生任何 I2C 事务"
        )
    }

    /** `30: UU` 或 `30: 30` 视为应答；`30: --` 为未应答 */
    private val ACK_PATTERN = Regex("""\b30:\s*(UU|[0-9a-fA-F]{2})""")

    /**
     * 运行一次 `i2cdetect -y 4`，返回含 `30:` 的那一行。
     *
     * 返回 null = **su 不可用/命令失败**，不是「无应答」。
     */
    private fun runI2cDetect(): String? = try {
        val process = ProcessBuilder(*HardwareConfig.SU_PREFIX, "i2cdetect -y $BUS")
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(HardwareConfig.SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "i2cdetect timed out")
            null
        } else if (process.exitValue() != 0) {
            Log.w(TAG, "i2cdetect exit=${process.exitValue()}")
            null
        } else {
            process.inputStream.bufferedReader().use { r ->
                r.readLines().firstOrNull { it.trimStart().startsWith("30:") }
            }
        }
    } catch (e: Exception) {
        Log.i(TAG, "su unavailable: ${e.message}")
        null
    }

    // ==================== 2. 驱动节点 ====================

    suspend fun inspectNodes(): NodeReport = withContext(Dispatchers.IO) {
        // 与 readNodeValue 同理：sysfs 上的 `list()` 也可能被驱动阻塞，
        // 实测本机固件上页面卡在「读取中」而不返回。给硬上限。
        val actual = listWithTimeout(HardwareConfig.I2C_BASE_PATH) ?: emptyList()
        val present = REQUIRED_NODES.filter { it in actual }
        NodeReport(
            required = REQUIRED_NODES,
            present = present,
            missing = REQUIRED_NODES - present.toSet(),
            extra = actual - REQUIRED_NODES.toSet()
        )
    }

    /** 带硬超时的目录列举；超时返回 null（调用方按「读不到」处理）。 */
    private fun listWithTimeout(path: String): List<String>? {
        var out: List<String>? = null
        val worker = Thread {
            out = runCatching { File(path).list()?.toList() }.getOrNull()
        }
        worker.isDaemon = true
        worker.start()
        worker.join(NODE_READ_TIMEOUT_MS)
        return if (worker.isAlive) null else out
    }

    // ==================== 3. 持续写 ====================

    /** 持续写的策略；UI 可手动选择，默认由 [resolveStrategy] 自动决定。 */
    enum class WriteStrategy(val label: String) {
        /** sysfs 幂等写：先读 mode，再原值写回 */
        MODE_IDEMPOTENT("mode 幂等写"),
        /** su + i2cdetect：SMBus quick probe，节点缺失时的唯一可行手段 */
        BUS_PROBE("总线探测 (i2cdetect)"),
        /**
         * 只读探测：读 `version`。
         *
         * 从机不应答时最安全的选择 —— 读事务同样是 START + 地址 + STOP，
         * 示波器上看得到完整波形，但**对面板没有任何副作用**
         * （不像 `key` 会切画面、`mode` 会在从机恢复瞬间改画面）。
         */
        READ_PROBE("只读探测（version）"),
        /** 两者都不可行 */
        UNAVAILABLE("不可用")
    }

    /**
     * 根据当前环境选择可行的写入策略。
     *
     * 优先级：能幂等写就幂等写（不依赖 root）；否则看 su 是否可用。
     */
    suspend fun resolveStrategy(report: BusReport): WriteStrategy = withContext(Dispatchers.IO) {
        // ## 优先级：先看「能不能真的产生流量」，而不是「节点在不在」
        //
        // 节点存在**不代表**能写成功：实测（RK3568）mode 等 14 个节点全部存在，
        // 但读取返回 ENODEV —— 此时 MODE_IDEMPOTENT 会每秒失败一次，
        // 总线上**一次事务都不会发出**，示波器上什么都没有。
        //
        // 因此只有在总线确实 ONLINE（从机应答）时才用 mode 幂等写；
        // 否则退到 i2cdetect（它直接对 /dev/i2c-4 发 SMBus quick probe，
        // 不依赖驱动，是节点不可读时唯一能在总线上产生动作的手段）。
        if (report.state == BusState.ONLINE &&
            File("${HardwareConfig.I2C_BASE_PATH}/mode").exists()
        ) {
            return@withContext WriteStrategy.MODE_IDEMPOTENT
        }
        // 节点在但总线不通（NO_DEVICE / UNREADABLE）→ 读 version 造流量。
        //
        // 不走 i2cdetect：0x30 被 rk3x-i2c 驱动占用，`i2cget -y 4 0x30`
        // 会立刻返回 EBUSY，i2cdetect 扫描到 0x30 就卡住，
        // 3 秒超时被杀后**误报成「su 不可用」**（2026-10-20 真机实锤）。
        if (report.state == BusState.NO_DEVICE || report.state == BusState.UNREADABLE) {
            return@withContext WriteStrategy.READ_PROBE
        }
        // 节点不存在（驱动未绑定）才退回 i2cdetect
        if (report.state == BusState.NODE_MISSING && suAvailable()) {
            return@withContext WriteStrategy.BUS_PROBE
        }
        WriteStrategy.UNAVAILABLE
    }

    /**
     * 一次持续写（或探测）动作的结果。
     *
     * **区分「事务没发出去」和「发出去了但从机没应答」**：
     * 示波器用途关心的是「总线上有没有波形」，后者已经满足；
     * 若两者都记成失败，界面会显示「成功 0」，
     * 现场会误以为程序根本没在发东西。
     */
    data class WriteOutcome(
        /** 事务是否已经真的跑上总线（含 NACK 的情形） */
        val onBus: Boolean,
        /** 从机是否应答；探测类动作为 null（读到了算 true） */
        val acked: Boolean?,
        /** 展示文本 */
        val text: String
    )

    suspend fun writeOnce(strategy: WriteStrategy): WriteOutcome = withContext(Dispatchers.IO) {
        when (strategy) {
            WriteStrategy.MODE_IDEMPOTENT -> writeModeIdempotent()
            WriteStrategy.READ_PROBE -> readProbe()
            WriteStrategy.BUS_PROBE -> if (runI2cDetect() != null) {
                WriteOutcome(true, true, "i2cdetect 已执行")
            } else {
                WriteOutcome(false, null, "su/i2cdetect 不可用")
            }
            WriteStrategy.UNAVAILABLE -> WriteOutcome(false, null, "无可用的写入路径")
        }
    }

    /**
     * 对 `version` 做一次只读事务，制造总线波形。
     *
     * 从机在时返回版本号；不在时返回超时/ENODEV 文本 ——
     * 两种情况下驱动都已经在总线上跑过完整的 START/地址/STOP。
     */
    private fun readProbe(): WriteOutcome {
        val text = readNodeValue("version")
        return when {
            // 读到数值 → 事务完成且从机 ACK
            text.toIntOrNull() != null ->
                WriteOutcome(true, true, "ACK，version=$text")
            // 驱动等满超时 → 事务已发出，从机 NACK
            text.contains("超时") ->
                WriteOutcome(true, false, "已发出（NACK，波形已产生）")
            text.contains("ENODEV") ->
                WriteOutcome(true, false, "ENODEV（从机不应答）")
            // 没有产生任何 I2C 事务
            else -> WriteOutcome(false, null, "读 version 失败：$text")
        }
    }

    /**
     * `su` 是否真的可用。
     *
     * 不能用「i2cdetect 是否跑成功」来判断 —— 它会被驱动占用总线卡住，
     * 与本机是否 root 完全无关。
     */
    private fun suAvailable(): Boolean = try {
        val p = ProcessBuilder(*HardwareConfig.SU_PREFIX, "id")
            .redirectErrorStream(true).start()
        val ok = p.waitFor(1_500L, TimeUnit.MILLISECONDS)
        if (!ok) { p.destroyForcibly(); false }
        else p.exitValue() == 0 &&
            p.inputStream.bufferedReader().readText().contains("uid=0")
    } catch (e: Exception) {
        false
    }

    /**
     * 读出 `mode` 当前值后原值写回。
     *
     * 为什么不写 `key`：`key` 是切换语义，每秒一次会让画面剧烈跳变。
     * 为什么可以反复写 `mode`：文档 §4.4 明确「重复写相同值安全，固件返回 OK 且不做任何事」。
     */
    private fun writeModeIdempotent(): WriteOutcome {
        val path = "${HardwareConfig.I2C_BASE_PATH}/mode"
        return try {
            // 读也必须有硬上限：从机不应答时 `readText()` 会永久挂住，
            // 1 秒一次的写循环会就此停摆，示波器上再也看不到波形。
            val current = readNodeValue("mode").trim()
            if (current.isEmpty()) return WriteOutcome(false, null, "mode 为空，无法写回")
            if (current.contains("超时")) {
                // 读 mode 本身已发出一次事务，从机 NACK
                return WriteOutcome(true, false, "读 mode 已发出（NACK）")
            }
            if (current.contains("ENODEV") || current.contains("errno") ||
                current.contains("EACCES")
            ) {
                return WriteOutcome(false, null, "读 mode 失败：$current")
            }

            val fd = android.system.Os.open(path, android.system.OsConstants.O_WRONLY, 0)
            try {
                val bytes = current.toByteArray(Charsets.US_ASCII)
                val written = android.system.Os.write(fd, bytes, 0, bytes.size)
                if (written != bytes.size) {
                    WriteOutcome(false, null, "短写 $written/${bytes.size}")
                } else {
                    WriteOutcome(true, true, "写回 mode=$current，ACK")
                }
            } finally {
                runCatching { android.system.Os.close(fd) }
            }
        } catch (e: android.system.ErrnoException) {
            // 写事务发出后从机 NACK 时，驱动通常返回 EIO 或 EREMOTEIO
            val nack = e.errno == android.system.OsConstants.EIO
            WriteOutcome(nack, false, "写失败 errno=${e.errno} ${e.message}")
        } catch (e: Exception) {
            WriteOutcome(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 读取所有「存在」节点的当前值，供调试页一屏展示。
     *
     * 只读，不写；读失败的节点不出现在结果里（UI 显示「读取失败」）。
     */
    /**
     * 并行读全部节点。
     *
     * ## 为什么必须并行（2026-10-20 真机实测）
     *
     * 无应答时每个节点读都要**等满 800ms 硬超时**，串行 14 个 ≈ 11 秒，
     * 远超 5 秒刷新周期 —— 界面看起来就是「卡死」。
     * 并行后总耗时 ≈ 单个节点耗时（800ms）。
     *
     * readNodeValue 内部是「后台线程 + join」，会占住 IO 线程，
     * 但 Dispatchers.IO 默认 64 个线程，14 个节点不会排队。
     */
    suspend fun readAllPresent(present: List<String>): Map<String, String> =
        coroutineScope {
            val jobs = present.map { name ->
                async(Dispatchers.IO) { name to readNodeValue(name) }
            }
            val out = LinkedHashMap<String, String>()
            jobs.forEach { job ->
                val (name, value) = job.await()
                out[name] = value
            }
            out
        }

    /**
     * 读单个节点，成功返回数值文本，失败返回**可读的错误原因**。
     *
     * 必须区分三种失败，因为现场处置完全不同：
     * - `ENODEV`（No such device）→ **从机不应答**：查线缆/供电/上拉/电平
     * - `EACCES`（Permission denied）→ 权限未放开：查 init rc 的 chmod
     * - 其他 → 原样显示
     */
    /**
     * 读单个节点，带**硬超时**。
     *
     * ## 为什么需要超时（2026-10-20 真机实测）
     *
     * 在本机固件上，`cat /sys/bus/i2c/devices/4-0030/version` 会**永久挂住**
     * —— 驱动在等 I2C 从机应答，而从机不在，读请求卡在内核里不返回。
     * 直接 `Os.read` 会把协程一起挂死，页面因此永远停在「读取中」。
     *
     * 这里把读操作丢到独立线程，最多等 [NODE_READ_TIMEOUT_MS]；
     * 超时就把该线程标记为放弃（不 join），主流程继续 ——
     * 泄漏一个阻塞线程总比整页卡死好，且下次刷新会重试。
     */
    private fun readNodeValue(name: String): String {
        val path = "${HardwareConfig.I2C_BASE_PATH}/$name"
        var result: String = "读取超时（驱动未返回）"

        val worker = Thread {
            result = try {
                val fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY, 0)
                try {
                    val buf = ByteArray(128)
                    val n = android.system.Os.read(fd, buf, 0, buf.size)
                    if (n <= 0) "（空）" else String(buf, 0, n).trim().ifEmpty { "（空）" }
                } finally {
                    runCatching { android.system.Os.close(fd) }
                }
            } catch (e: android.system.ErrnoException) {
                when (e.errno) {
                    android.system.OsConstants.ENODEV -> "ENODEV 从机不应答"
                    android.system.OsConstants.EACCES -> "EACCES 权限不足"
                    android.system.OsConstants.EPERM -> "EPERM 待机中"
                    android.system.OsConstants.EIO -> "EIO 总线错误"
                    else -> "errno=${e.errno}"
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(NODE_READ_TIMEOUT_MS)
        return if (worker.isAlive) "读取超时（驱动未返回）" else result
    }

    /** 节点用途说明（调试页展示用；与协议文档 §2.1/§2.2 对应） */
    fun describeNode(name: String): String = when (name) {
        "mode" -> "拼接模式 0..7"
        "resolution" -> "0=720p 1=1080p 2=2160p30"
        "mirror" -> "TX 镜像位掩码 0..15"
        "rotate" -> "旋转 0/90/180/270"
        "hfusion" -> "水平融合 0..255（写 flash）"
        "vfusion" -> "垂直融合 0..255（写 flash）"
        "mute" -> "音频静音 0/1"
        "power_state" -> "电源 0=待机 1=开机"
        "key" -> "按键仿真（兼容用）"
        "version" -> "协议版本，2=在线"
        "status" -> "bit0 BUSY bit1 POWER bit6 ERR bit7 OVF"
        "result" -> "上一条命令结果，0=OK"
        "cmd_count" -> "已执行命令计数（模 256）"
        "rx_state" -> "bit n=1 表示 RX n 有稳定输入"
        else -> ""
    }

    suspend fun probeBusWithTimeout(timeoutMs: Long = 4_000L): BusReport =
        withTimeoutOrNull(timeoutMs) { probeBus() }
            ?: BusReport(state = BusState.CANNOT_PROBE, method = "探测超时（${timeoutMs}ms）")

    /** 节点根目录（供 UI 展示） */
    fun basePath(): String = HardwareConfig.I2C_BASE_PATH
}
