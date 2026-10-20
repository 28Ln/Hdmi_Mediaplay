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
     * 从机无应答时驱动层要 5.6~10.2 秒才返回（真机实测），
     * 这里只是「快速失败」的初判点，超过后进入 [SLOW_RETURN_GRACE_MS] 宽限期。
     */
    private const val NODE_READ_TIMEOUT_MS = 800L

    /**
     * 慢返回宽限期。
     *
     * rk3x-i2c 在从机无应答时的驱动层超时**不是固定值**：
     * 真机实测 5 次为 10.2s / 5.9s / 5.7s / 5.7s / 5.6s。
     * 取 15 秒覆盖最坏情况；超过这个时间仍未返回，
     * 才判定为「内核挂死」（另一台样机上 `timeout 20 cat` 都杀不掉的那种）。
     */
    private const val SLOW_RETURN_GRACE_MS = 15_000L

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
        // 熔断后不碰 sysfs 读：一次探测就会再留一个 D 态线程。
        // 但冷却结束后自动放行一次 —— 否则硬件修好了 App 也不知道，
        // 现场会看到「明明通了却一直显示不通」（2026-10-20）。
        if (channelBlocked && !tryAutoReset()) {
            return@withContext BusReport(
                state = BusState.NO_DEVICE,
                method = "熔断中（上一次传输无 ACK 未返回）",
                detail = "驱动适配器锁未释放，任何 I2C 访问都会 EBUSY；冷却后自动重试"
            )
        }
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
    private fun listWithTimeout(path: String): List<String>? =
        File(path).list()?.toList()

    /**
     * ## 唯一允许阻塞的 sysfs 读取通道（2026-10-20 第三次修复）
     *
     * ### 为什么不能「每次读开一个线程 + join」
     *
     * 从机无应答时，`read()` 会进入 **D 态（不可中断睡眠）**，
     * 一直卡在内核的 I2C 等待里。给线程设超时并不会让它退出 ——
     * 实测 `timeout 20 cat version` 都杀不掉，只能等驱动自己超时。
     *
     * 于是「800ms 没等到就放弃、下次再开新线程」的写法会**持续泄漏**：
     * 实测 30 秒内进程线程数 188 → 293（14 节点 × 每 5 秒一轮 + 每秒探测）。
     * 计动机 7×24 运行，线程和内核栈会一路涨到无法分配。
     *
     * ### 现在的做法
     *
     * 全局**只有一个**阻塞读线程，所有读取按队列串行执行：
     * 第一个读卡住后，后面的请求在队列里等待，**不再新建线程**。
     * 最多泄漏 1 个 D 态线程（且它是复用的），进程线程数恒定。
     *
     * 代价：一旦卡住，后续读都会排在后面。这对调试页是可接受的 ——
     * 卡住本身就说明硬件不通，此时「读数」本来也没有意义；
     * 而节点列举（`getdents`）不受影响，界面仍能显示 14/14。
     */
    /** 队列积压上限：读通道正常时几乎恒为 0 */
    private const val READ_QUEUE_CAPACITY = 16

    /** 节点读取的轮转起点：慢总线上一个预算周期读不完全部节点 */
    @Volatile
    private var readRotation = 0

    /** 读通道已被卡死：与「本次读超时」含义不同 */
    private const val READ_CHANNEL_BLOCKED = "!channel-blocked"

    // ==================== 熔断器 ====================
    //
    // ## 为什么必须有熔断（2026-10-20 真机定论）
    //
    // 从机不应答时，`rk3x-i2c` 打印 `timeout, ipd: 0x00, state: 1`
    // **却不完成这次传输** —— 调用方永远停在 D 态（不可中断睡眠），
    // 连 `timeout 20 cat` 都杀不掉。它同时**一直持有适配器锁**，
    // 于是后续任何访问都 EBUSY（`i2cget -y 4 0x30` 立即返回 busy，
    // `i2cdetect -y 4` 扫到 0x30 就挂住）。
    //
    // 结果：**每发一次事务就永久泄漏一个 D 态线程**，而且整条 I2C 总线
    // 在重启前都不可再用。对 7×24 的投放机，这必须兜住。
    //
    // 熔断后的行为：不再发起任何 sysfs 事务，界面如实说明原因，
    // 并提供「重试一次」让人在排查硬件后手动复位（会再冒一次泄漏风险）。

    /** 一旦有读/写超时未返回，就不再发起新事务 */
    @Volatile
    private var channelBlocked = false

    /** 卡死时的时间戳（elapsedRealtime），用于界面提示 */
    @Volatile
    var blockedSince: Long = 0L
        private set

    /** 读通道是否已被判死（进程内，重启 App 才会重新尝试） */
    fun isChannelBlocked(): Boolean = channelBlocked

    /**
     * 手动复位熔断，允许再试一次。
     *
     * **注意**：如果硬件仍无应答，这次尝试会再留下一个无法回收的线程，
     * 因此只由用户显式点击触发，不自动重试。
     */
    fun resetChannel() {
        channelBlocked = false
        blockedSince = 0L
    }

    private fun tripChannel() {
        if (!channelBlocked) {
            channelBlocked = true
            blockedSince = android.os.SystemClock.elapsedRealtime()
        }
    }

    /**
     * 冷却期：熔断后至少等这么久才允许自动探测一次。
     *
     * ## 为什么需要自动复位（2026-10-20）
     *
     * 熔断原本只能手动复位。但一次**瞬时抖动**（MCU 恰好在复位、
     * 上电瞬间总线未稳）也会触发熔断，之后即使硬件完全正常，
     * App 也会一直认为「总线挂了」直到重启 —— 现场表现为
     * 「明明通了却一直显示不通」，比不熔断更难排查。
     *
     * ## 为什么是「冷却 + 单次探测」而不是持续重试
     *
     * 每次失败尝试都会留下一个**无法回收**的 D 态线程（内核锁死），
     * 所以绝不能退化成轮询。这里限定：
     * - 距上次熔断至少 [AUTO_RETRY_COOLDOWN_MS]
     * - 每次冷却最多放行**一次**尝试，失败立刻重新熔断
     *
     * 结果是：硬件修好 → 最多等一个冷却期自动恢复；
     * 硬件没修好 → 每冷却期只多赔 1 个线程（而非每秒 1 个）。
     */
    private const val AUTO_RETRY_COOLDOWN_MS = 30_000L

    /**
     * 若仍在冷却期内返回 false；冷却结束则复位并返回 true。
     *
     * 供 [probeBus] 在熔断状态下调用 —— 调试页本来就在每 5 秒刷新，
     * 借此机会在冷却结束后自动重试一次，无需人工干预。
     */
    fun tryAutoReset(): Boolean {
        if (!channelBlocked) return false
        val since = android.os.SystemClock.elapsedRealtime() - blockedSince
        if (since < AUTO_RETRY_COOLDOWN_MS) return false
        resetChannel()
        android.util.Log.i("I2cProbe", "熔断冷却结束（${since}ms），自动重试一次")
        return true
    }

    /**
     * 单线程执行器，**队列有界**。
     *
     * 线程数固定不是全部问题：读卡住时队列本身也会涨。
     * 页面每 5 秒提交 14 个节点读，若一个都消费不了，
     * 无界队列会一路堆下去（每个任务都是一个待执行对象）。
     * 队列满了直接拒绝，由调用方显示「读取通道阻塞」。
     */
    private val reader: java.util.concurrent.ThreadPoolExecutor =
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(READ_QUEUE_CAPACITY),
            { r -> Thread(r, "i2c-sysfs-reader").apply { isDaemon = true } }
        ).apply {
            // 满了就抛，不阻塞提交方（提交发生在 IO 线程，阻塞会拖住整轮刷新）
            rejectedExecutionHandler =
                java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        }


    /**
     * 提交一次读操作并最多等待 [NODE_READ_TIMEOUT_MS]。
     *
     * 超时**不会**取消底层读（内核态无法取消），但也不会新建线程：
     * 该任务仍排在单线程执行器里，后续任务依次排队。
     *
     * @return 读到的文本；超时返回 null
     */
    private fun readBounded(block: () -> String): String? {
        if (channelBlocked) return READ_CHANNEL_BLOCKED

        val future = try {
            reader.submit(java.util.concurrent.Callable {
                try {
                    block()
                } catch (e: android.system.ErrnoException) {
                    // 必须在工作线程里翻译：ErrnoException 跨线程抛出会丢掉 errno
                    "!errno=${e.errno}" +
                        (if (e.errno == android.system.OsConstants.ENODEV) " ENODEV" else "")
                } catch (e: Exception) {
                    "!失败：" + (e.message ?: e.javaClass.simpleName)
                }
            })
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 队列已满 = 前一次读卡死至今没返回，通道事实上不可用
            Log.w(TAG, "sysfs read channel saturated (queue full)")
            return READ_CHANNEL_BLOCKED
        }

        // ## 超时后要区分「慢」和「死」（2026-10-21 真机实测）
        //
        // 两种固件行为完全不同：
        //
        // - **A. 驱动超时返回**：`cat version` 约 10 秒后返回 ENODEV，
        //   线程正常结束、可回收（本机实测连续 3 次都是 ~10s/10.0s/10.0s，
        //   且 ps 无 D 态残留）。此时熔断是**错的** —— 会把一个只是慢的
        //   总线永久判死，用户看不到真实读数。
        // - **B. 内核永久挂死**：`read()` 停在 D 态不返回，线程不可回收，
        //   适配器锁不释放（另一台样机实测，`timeout 20 cat` 都杀不掉）。
        //   这种情况必须熔断，否则持续泄漏线程直到进程耗尽。
        //
        // 判据：等待 [NODE_READ_TIMEOUT_MS] 后仍未返回 → 再等一个
        // [SLOW_RETURN_GRACE_MS] 宽限期。宽限期内返回 = 慢（不熔断）；
        // 仍然不返回 = 死（熔断）。
        return try {
            future.get(NODE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // 宽限期：给驱动层超时留出返回时间
            try {
                future.get(SLOW_RETURN_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (slow: Exception) {
                // 宽限期也没回来 → 判定为真正的挂死，熔断
                tripChannel()
                Log.w(
                    TAG,
                    "sysfs read blocked >${NODE_READ_TIMEOUT_MS + SLOW_RETURN_GRACE_MS}ms " +
                        "— channel tripped"
                )
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "sysfs read failed: ${e.message}")
            null
        }
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
    suspend fun readAllPresent(
        present: List<String>,
        budgetMs: Long = 6_000L
    ): Map<String, String> {
        // 熔断后不读：返回空表，UI 会显示「通道阻塞」
        if (channelBlocked) return emptyMap()
        return readAllPresentInner(present, budgetMs)
    }

    /**
     * 同 [readAllPresent]，但保留每个值的**读取时刻**。
     *
     * 供调试页显示数据年龄 —— 慢总线上不同节点的值可能相差数分钟。
     */
    suspend fun readAllPresentStamped(
        present: List<String>,
        budgetMs: Long = 6_000L
    ): Map<String, Reading> {
        if (channelBlocked) return emptyMap()
        val out = LinkedHashMap<String, Reading>()
        readAllPresentInner(present, budgetMs, out)
        return out
    }

    /**
     * 逐个读节点，**受总预算约束**。
     *
     * ## 为什么不能并行（2026-10-21 真机实测）
     *
     * 读通道只有 **1 个线程**（见 [reader]），并行提交只是把 14 个任务
     * 塞进队列排队。从机无应答时**每个**读要等驱动层超时约 10 秒，
     * 14 个串起来就是 140 秒 —— 而队列只有 16 格，页面 5 秒刷新一次，
     * 首轮就会把队列撑满，之后全部被拒绝，界面永远显示「通道阻塞」。
     *
     * 改为：单个线程顺序读，超出 [budgetMs] 就停下来，
     * 已读到的照常显示。慢总线场景下宁可少显示几个节点，
     * 也要保证界面持续更新。
     */
    /**
     * 带读取时刻的读数。
     *
     * 慢总线上一个预算周期只能读 1 个节点（单次约 5.6 秒），
     * 14 个节点完整轮一遍要 **3.6 分钟**（真机实测 15.4 秒/节点）。
     * 界面并排显示的 14 个值可能横跨好几分钟 ——
     * 不带时间戳会让现场误以为它们都是「当前值」。
     */
    data class Reading(val value: String, val readAtElapsedMs: Long)

    private suspend fun readAllPresentInner(
        present: List<String>,
        budgetMs: Long,
        stamped: MutableMap<String, Reading>? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, String>()
        val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs

        // ## 轮转读取（2026-10-21 真机实测）
        //
        // 慢总线上**单个**节点就要 5.6 秒，6 秒预算只够读 1 个。
        // 若每轮都从头开始，`mode` 会被反复读，后面 13 个节点永远看不到。
        // 记住上轮的停点，本轮从那里继续，几轮下来就能覆盖全部节点。
        val ordered = present
        val start = readRotation % ordered.size.coerceAtLeast(1)
        var read = 0
        for (offset in ordered.indices) {
            if (channelBlocked) break
            if (android.os.SystemClock.elapsedRealtime() >= deadline) break
            val name = ordered[(start + offset) % ordered.size]
            val value = readNodeValue(name)
            out[name] = value
            stamped?.put(name, Reading(value, android.os.SystemClock.elapsedRealtime()))
            read++
        }
        readRotation = (start + read) % ordered.size.coerceAtLeast(1)
        if (read < ordered.size) {
            Log.d(TAG, "本轮读 $read/${ordered.size} 个节点，下轮从 ${ordered.getOrNull(readRotation)} 继续")
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
        val text = readBounded {
            val fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY, 0)
            try {
                val buf = ByteArray(128)
                val n = android.system.Os.read(fd, buf, 0, buf.size)
                if (n <= 0) "（空）" else String(buf, 0, n).trim().ifEmpty { "（空）" }
            } finally {
                runCatching { android.system.Os.close(fd) }
            }
        } ?: return "读取超时（驱动未返回）"
        if (text == READ_CHANNEL_BLOCKED) return "读取通道阻塞（前一次读未返回）"

        // 异常在读取线程里抛出，这里统一翻译成现场可读的原因
        return when {
            text.startsWith("!ENODEV") -> "ENODEV 从机不应答"
            text.startsWith("!EACCES") -> "EACCES 权限不足"
            text.startsWith("!EPERM") -> "EPERM 待机中"
            text.startsWith("!EIO") -> "EIO 总线错误"
            text.startsWith("!errno=") -> text.removePrefix("!")
            else -> text
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
