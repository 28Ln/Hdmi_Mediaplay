package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 节点读写的接缝（seam）。
 *
 * 生产实现是 [SysfsMatrixNodeIo]，JVM 单测用内存假实现替换，
 * 因此控制器的重试/排序/防抖逻辑可以在**不碰 Android 框架**的情况下被验证。
 */
interface HdmiMatrixNodeIo {

    /** 读取节点并解析为整数；失败抛 [MatrixNodeException]（errno 精确）。 */
    suspend fun read(node: HdmiMatrixNode): Int

    /**
     * 读取可能超过 Int 范围的计数器（cmd_count）。
     *
     * 默认实现足以覆盖普通节点；生产实现会按 Long 解析，避免计数器上千亿时溢出。
     */
    suspend fun readLong(node: HdmiMatrixNode): Long = read(node).toLong()

    /** 写入节点；失败抛 [MatrixNodeException]。 */
    suspend fun write(node: HdmiMatrixNode, value: Int)

    /** 节点是否存在。仅用于快速判断，不作为可写性依据。 */
    fun isPresent(node: HdmiMatrixNode): Boolean
}

/**
 * sysfs 生产实现。
 *
 * ## 为什么用 `android.system.Os` 而不是 `File.readText/writeText`
 *
 * `File` 抛出的 `IOException` 只有一句 "Permission denied"，
 * 需要**解析字符串**才能知道 errno，而文案随 ROM/语言变化并不可靠。
 * `Os.open/read/write/close` 抛出的 `ErrnoException.errno` 是内核原始值，
 * 控制器的重试策略（EACCES 重试、EPERM 唤醒、EINVAL 不重试）才能准确。
 *
 * ## 线程
 *
 * 这些方法会阻塞在 sysfs 上，**必须在 IO 线程调用**（控制器负责 withContext）。
 *
 * ## `withTimeout` 对 D 态读**无效**（2026-10-20 真机修正）
 *
 * 原注释写「包一层超时，防止协程永远挂住」——**这是错的**。
 * 从机无应答时 `Os.read` 进入 D 态（不可中断睡眠），
 * 协程超时只会让调用方放弃等待，**内核里的那次读依然占着线程**，
 * 实测连 `timeout 20 cat` 都杀不掉，且驱动适配器锁一直不释放，
 * 之后所有访问 EBUSY。
 *
 * 因此：
 * - 超时后**必须熔断**，不能按 250ms 轮询继续重试
 *   （20 秒的 `ensureOnline` 会泄漏最多 80 个 D 态线程）
 * - 熔断状态是进程级的，硬件修好前重启 App 也不会自愈
 * - 熔断由 `HdmiMatrixController` 转成 `NoDevice` → UI 隐藏控制入口
 */
class SysfsMatrixNodeIo(
    private val baseDir: String = HardwareConfig.I2C_BASE_PATH,
    private val suFallbackEnabled: Boolean = HardwareConfig.ALLOW_SU_WRITE
) : HdmiMatrixNodeIo {

    /**
     * 一旦某次 sysfs 操作超时，就不再发起新操作。
     *
     * 每次超时都意味着一个**无法回收**的 D 态线程和一把不释放的适配器锁；
     * 继续重试只会把小问题放大成进程级资源耗尽。
     */
    private companion object {
        private const val TAG = "SysfsMatrixNodeIo"

        /** 熔断后的冷却期：期满自动重试一次，硬件修好可自愈 */
        private const val AUTO_RETRY_COOLDOWN_MS = 30_000L
    }

    @Volatile
    private var tripped = false

    /** 熔断发生的时刻（elapsedRealtime），用于冷却判定 */
    @Volatile
    private var trippedAt = 0L

    /** 冷却期：熔断后至少等这么久才允许自动重试一次 */
    private val autoRetryCooldownMs: Long = AUTO_RETRY_COOLDOWN_MS

    /** 供控制器/UI 查询：总线是否已判死 */
    fun isTripped(): Boolean = tripped

    /**
     * 冷却结束后自动复位一次。
     *
     * ## 为什么需要（2026-10-20）
     *
     * 熔断是保守的：一次**瞬时抖动**（MCU 恰在复位、上电瞬间总线未稳）
     * 也会触发。若只能手动复位，即使硬件随后完全正常，
     * App 也会一直认为「总线挂了」—— 现场表现为「明明通了却一直不通」，
     * 比不熔断更难排查。
     *
     * ## 为什么不会退化成轮询
     *
     * - 距上次熔断必须满 [AUTO_RETRY_COOLDOWN_MS]
     * - 每次冷却只放行一次；失败立刻重新熔断并刷新时刻
     *
     * 硬件正常 → 最多等一个冷却期自动恢复；
     * 硬件仍坏 → 每冷却期只多赔 1 个线程，而不是每秒 1 个。
     */
    fun tryAutoReset(): Boolean {
        if (!tripped) return false
        val since = android.os.SystemClock.elapsedRealtime() - trippedAt
        if (since < autoRetryCooldownMs) return false
        tripped = false
        Log.i(TAG, "拼接板熔断冷却结束（${since}ms），自动重试一次")
        return true
    }

    /**
     * 手动复位熔断。
     *
     * 熔断是**保守**的：一次瞬时抖动（比如 MCU 恰好在复位）也会触发。
     * 若没有任何复位入口，正常机器会被永久判死，只能杀进程。
     * 因此提供显式复位，只由「用户主动重试」或 `ensureOnline` 的首次调用触发。
     *
     * 每次复位最多再试**一次**，失败立即重新熔断 —— 不会退化成轮询。
     */
    fun resetTrip() {
        tripped = false
        trippedAt = 0L
    }

    private fun pathOf(node: HdmiMatrixNode): String = "$baseDir/${node.relativePath}"

    override fun isPresent(node: HdmiMatrixNode): Boolean = try {
        File(pathOf(node)).exists()
    } catch (e: Exception) {
        false
    }

    override suspend fun read(node: HdmiMatrixNode): Int {
        val raw = readRaw(node)
        val trimmed = raw.trim()
        // sysfs 可能输出十进制，也可能输出 0x 前缀；两者都接受，避免误判 EINVAL
        return try {
            if (trimmed.startsWith("0x", ignoreCase = true)) {
                trimmed.substring(2).toLong(16).toInt()
            } else {
                trimmed.toLong().toInt()
            }
        } catch (e: NumberFormatException) {
            throw MatrixNodeException(
                errno = HdmiMatrixErrno.EINVAL,
                message = "Unparsable value at ${pathOf(node)}: '$trimmed'",
                node = node,
                cause = e
            )
        }
    }

    override suspend fun readLong(node: HdmiMatrixNode): Long {
        val trimmed = readRaw(node).trim()
        return try {
            if (trimmed.startsWith("0x", ignoreCase = true)) {
                trimmed.substring(2).toLong(16)
            } else {
                trimmed.toLong()
            }
        } catch (e: NumberFormatException) {
            throw MatrixNodeException(
                errno = HdmiMatrixErrno.EINVAL,
                message = "Unparsable value at ${pathOf(node)}: '$trimmed'",
                node = node,
                cause = e
            )
        }
    }

    private suspend fun readRaw(node: HdmiMatrixNode): String {
        // 冷却结束后自动放行一次，避免硬件修好后仍被判死
        tryAutoReset()
        if (tripped) throw blockedException(node)
        return try {
            readRawTimed(node)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            trip()
            throw blockedException(node)
        }
    }

    /** 熔断并记录时刻，供冷却判定使用 */
    private fun trip() {
        if (!tripped) {
            tripped = true
            trippedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun blockedException(node: HdmiMatrixNode) = MatrixNodeException(
        errno = HdmiMatrixErrno.ENODEV,
        message = "I2C 传输无应答且未返回（驱动 D 态），已熔断",
        node = node
    )

    private suspend fun readRawTimed(node: HdmiMatrixNode): String =
        withTimeout(HardwareConfig.MATRIX_OP_TIMEOUT_MS) {
            val path = pathOf(node)
            val fd = try {
                Os.open(path, OsConstants.O_RDONLY, 0)
            } catch (e: ErrnoException) {
                throw toNodeException(e, node)
            }
            try {
                val buffer = ByteArray(64)
                val count = try {
                    Os.read(fd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    throw toNodeException(e, node)
                }
                if (count <= 0) {
                    throw MatrixNodeException(
                        errno = HdmiMatrixErrno.EIO,
                        message = "Empty read from $path",
                        node = node
                    )
                }
                String(buffer, 0, count, Charsets.US_ASCII)
            } finally {
                runCatching { Os.close(fd) }
            }
        }

    override suspend fun write(node: HdmiMatrixNode, value: Int) {
        writeRaw(node, value.toString())
    }

    private suspend fun writeRaw(node: HdmiMatrixNode, value: String) {
        tryAutoReset()
        if (tripped) throw blockedException(node)
        try {
            writeRawTimed(node, value)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            trip()
            throw blockedException(node)
        }
    }

    private suspend fun writeRawTimed(node: HdmiMatrixNode, value: String) {
        withTimeout(HardwareConfig.MATRIX_OP_TIMEOUT_MS) {
            val path = pathOf(node)
            try {
                writeDirect(path, value)
            } catch (e: MatrixNodeException) {
                // 只有权限不足才值得提权；ENOENT（驱动没加载）提权也解决不了
                if (e.errno == HdmiMatrixErrno.EACCES && suFallbackEnabled && writeViaSu(path, value)) {
                    return@withTimeout
                }
                throw e
            }
        }
    }

    private fun writeDirect(path: String, value: String) {
        val fd = try {
            Os.open(path, OsConstants.O_WRONLY, 0)
        } catch (e: ErrnoException) {
            throw MatrixNodeException(
                errno = e.errno,
                message = e.message,
                cause = e
            )
        }
        try {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            val written = try {
                Os.write(fd, bytes, 0, bytes.size)
            } catch (e: ErrnoException) {
                throw MatrixNodeException(
                    errno = e.errno,
                    message = e.message,
                    cause = e
                )
            }
            if (written != bytes.size) {
                throw MatrixNodeException(
                    errno = HdmiMatrixErrno.EIO,
                    message = "Short write to $path ($written/${bytes.size})"
                )
            }
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    /**
     * 通过 su 写入。
     *
     * 命令语法：本设备的 su 是 `su WHO COMMAND` 形式，不支持 `su -c`
     * （会报 invalid uid/gid）。因此用 `su root sh -c`，由 sh 解析重定向。
     * 路径来自 [HdmiMatrixNode] 常量，非外部输入；仍做白名单校验。
     */
    private fun writeViaSu(path: String, value: String): Boolean {
        if (!path.startsWith("/sys/")) return false
        val command = "echo $value > $path"
        return try {
            val process = ProcessBuilder(*HardwareConfig.SU_PREFIX, command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(HardwareConfig.SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            // 无 su / 用户拒绝授权
            false
        }
    }

    private fun toNodeException(e: ErrnoException, node: HdmiMatrixNode): MatrixNodeException =
        MatrixNodeException(errno = e.errno, message = e.message, node = node, cause = e)
}
