package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
        val base = File(HardwareConfig.I2C_BASE_PATH)
        if (!base.exists() || !base.isDirectory) {
            return@withContext BusReport(
                state = BusState.NODE_MISSING,
                method = "sysfs 目录不存在",
                detail = "${HardwareConfig.I2C_BASE_PATH} 不可访问"
            )
        }

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
            detail = "需要 root 才能运行 i2cdetect"
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
        val base = File(HardwareConfig.I2C_BASE_PATH)
        val actual = runCatching { base.list()?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val present = REQUIRED_NODES.filter { it in actual }
        NodeReport(
            required = REQUIRED_NODES,
            present = present,
            missing = REQUIRED_NODES - present.toSet(),
            extra = actual - REQUIRED_NODES.toSet()
        )
    }

    // ==================== 3. 持续写 ====================

    /** 持续写的策略；UI 可手动选择，默认由 [resolveStrategy] 自动决定。 */
    enum class WriteStrategy(val label: String) {
        /** sysfs 幂等写：先读 mode，再原值写回 */
        MODE_IDEMPOTENT("mode 幂等写"),
        /** su + i2cdetect：SMBus quick probe，节点缺失时的唯一可行手段 */
        BUS_PROBE("总线探测 (i2cdetect)"),
        /** 两者都不可行 */
        UNAVAILABLE("不可用")
    }

    /**
     * 根据当前环境选择可行的写入策略。
     *
     * 优先级：能幂等写就幂等写（不依赖 root）；否则看 su 是否可用。
     */
    suspend fun resolveStrategy(report: BusReport): WriteStrategy = withContext(Dispatchers.IO) {
        if (File("${HardwareConfig.I2C_BASE_PATH}/mode").exists()) {
            return@withContext WriteStrategy.MODE_IDEMPOTENT
        }
        if (report.state == BusState.ONLINE || report.state == BusState.NO_DEVICE) {
            // 上一步能跑通 i2cdetect，说明 su 可用
            return@withContext WriteStrategy.BUS_PROBE
        }
        WriteStrategy.UNAVAILABLE
    }

    /**
     * 执行一次持续写动作。
     *
     * @return null = 成功；否则为可展示的错误文本
     */
    suspend fun writeOnce(strategy: WriteStrategy): String? = withContext(Dispatchers.IO) {
        when (strategy) {
            WriteStrategy.MODE_IDEMPOTENT -> writeModeIdempotent()
            WriteStrategy.BUS_PROBE -> if (runI2cDetect() != null) null else "su/i2cdetect 不可用"
            WriteStrategy.UNAVAILABLE -> "无可用的写入路径"
        }
    }

    /**
     * 读出 `mode` 当前值后原值写回。
     *
     * 为什么不写 `key`：`key` 是切换语义，每秒一次会让画面剧烈跳变。
     * 为什么可以反复写 `mode`：文档 §4.4 明确「重复写相同值安全，固件返回 OK 且不做任何事」。
     */
    private fun writeModeIdempotent(): String? {
        val path = "${HardwareConfig.I2C_BASE_PATH}/mode"
        return try {
            val current = File(path).readText().trim()
            if (current.isEmpty()) return "mode 为空，无法写回"

            val fd = android.system.Os.open(path, android.system.OsConstants.O_WRONLY, 0)
            try {
                val bytes = current.toByteArray(Charsets.US_ASCII)
                val written = android.system.Os.write(fd, bytes, 0, bytes.size)
                if (written != bytes.size) "短写 $written/${bytes.size}" else null
            } finally {
                runCatching { android.system.Os.close(fd) }
            }
        } catch (e: android.system.ErrnoException) {
            "errno=${e.errno} ${e.message}"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * 读取所有「存在」节点的当前值，供调试页一屏展示。
     *
     * 只读，不写；读失败的节点不出现在结果里（UI 显示「读取失败」）。
     */
    suspend fun readAllPresent(present: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            present.forEach { name ->
                out[name] = readNodeValue(name)
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
    private fun readNodeValue(name: String): String {
        val path = "${HardwareConfig.I2C_BASE_PATH}/$name"
        return try {
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
                android.system.OsConstants.ETIMEDOUT -> "ETIMEDOUT 超时"
                else -> "errno=${e.errno}"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
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
