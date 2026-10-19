package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HDMI 拼接板按键控制（**已废弃，仅过渡期回退**）。
 *
 * 通过向 sysfs 节点写入电平来模拟物理按键（按下 → 保持 → 松开）。
 *
 * **废弃原因**：2026-10-19 固件起，拼接板改由 I2C 寄存器控制
 * （见 [HdmiMatrixController] / `docs/hdmi-i2c-abi.md`），
 * `hdmi_b2` / `hdmi_b3` 节点已从固件中移除。新代码一律使用 I2C 路径；
 * 本类只在新驱动缺失（ENOENT）的旧 ROM 上由 [HdmiMatrixLegacyFallback] 调用。
 *
 * **权限说明**：控制节点默认属 root，普通应用直接写入会被拒（EACCES）。
 * 处理顺序：
 * 1. 直接写（ROM 已放开节点权限时可用）
 * 2. 失败则回退到 `su root sh -c` 写入（设备已 root 时可用）
 *
 * 若两者都失败，返回 false，由调用方给出用户可见的失败提示。
 */
@Deprecated(
    "Fallback window only; removed with the 2026-10-19 firmware change. Use HdmiMatrixController."
)
object HdmiGpioController {
    private const val TAG = "HdmiGpioController"

    /**
     * 旧「切换模式」节点。
     *
     * 路径不再从 HardwareConfig 导出：新代码不应引用它，
     * 保留为私有常量只为让旧 ROM 的回退路径可用。
     */
    @Suppress("DEPRECATION")
    private const val MODE_KEY = HardwareConfig.MODE_KEY_PATH

    /** 旧「切换分辨率」节点。 */
    @Suppress("DEPRECATION")
    private const val RESOLUTION_KEY = HardwareConfig.RESOLUTION_KEY_PATH

    /**
     * 按下并松开按键。
     *
     * @param path      控制节点路径（用 [MODE_KEY] / [RESOLUTION_KEY]）
     * @param activeLow 是否低电平有效
     * @param holdMs    按下保持时长
     * @return 是否成功；失败时已记录日志，调用方应给出用户提示
     */
    @Suppress("DEPRECATION")
    fun press(
        path: String,
        activeLow: Boolean = HardwareConfig.KEY_ACTIVE_LOW,
        holdMs: Long = HardwareConfig.KEY_HOLD_MS
    ): Boolean {
        val idle = if (activeLow) "1" else "0"
        val active = if (activeLow) "0" else "1"

        // 松开态 → 按下态 → 保持 → 松开态
        if (!write(path, idle)) return false
        if (!sleep(HardwareConfig.KEY_SETTLE_MS)) return false
        if (!write(path, active)) return false
        if (!sleep(holdMs)) return false
        // 无论中途是否出错，都要尽量回到松开态，避免按键卡住
        return write(path, idle)
    }

    /**
     * 写入节点。先直接写，失败后按配置回退到 su。
     */
    private fun write(path: String, value: String): Boolean {
        if (writeDirect(path, value)) return true
        if (!HardwareConfig.ALLOW_SU_WRITE) return false
        Log.i(TAG, "Direct write failed, retrying via su: $path=$value")
        return writeViaSu(path, value)
    }

    /** 直接写节点（需要节点对应用可写） */
    private fun writeDirect(path: String, value: String): Boolean {
        val file = File(path)
        return try {
            file.writeText(value)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct write failed: $path=$value, exists=${file.exists()}, canWrite=${file.canWrite()}")
            false
        }
    }

    /**
     * 通过 su 写入。
     *
     * 命令语法：本设备的 su 是 su WHO COMMAND 形式，
     * 不支持常见的 su -c 写法（会报 invalid uid/gid）。
     * 因此用 su root sh -c，由 sh 负责解析重定向。
     */
    private fun writeViaSu(path: String, value: String): Boolean {
        // 路径来自本对象常量，非外部输入；仍做一次白名单校验以防被误用
        if (!path.startsWith("/sys/")) {
            Log.e(TAG, "Refusing to write outside /sys: $path")
            return false
        }
        val command = "echo $value > $path"
        return try {
            val process = ProcessBuilder(*HardwareConfig.SU_PREFIX, command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(HardwareConfig.SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "su timed out: $command")
                return false
            }

            val exit = process.exitValue()
            if (exit != 0) {
                val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
                Log.e(TAG, "su failed (exit=$exit): $command $out")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            // 设备无 su、或用户拒绝授权时走到这里
            Log.e(TAG, "su unavailable: ${e.message}")
            false
        }
    }

    private fun sleep(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
