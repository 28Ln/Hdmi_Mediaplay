package com.btf.rk3568_hdmi_mediaplay.hardware

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
 * 每次操作仍包一层 [HardwareConfig.MATRIX_OP_TIMEOUT_MS] 超时，
 * 防止 I2C 总线被 MCU 占用时协程永远挂住。
 */
class SysfsMatrixNodeIo(
    private val baseDir: String = HardwareConfig.I2C_BASE_PATH,
    private val suFallbackEnabled: Boolean = HardwareConfig.ALLOW_SU_WRITE
) : HdmiMatrixNodeIo {

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

    private suspend fun readRaw(node: HdmiMatrixNode): String =
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
