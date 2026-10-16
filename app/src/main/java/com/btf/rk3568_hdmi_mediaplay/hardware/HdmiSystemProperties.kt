package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log

/**
 * RK HDMI 旋转全屏属性。
 * 对应 `setprop persist.sys.rotation.efull-1 true|false`。
 */
object HdmiSystemProperties {
    private const val TAG = "HdmiSystemProperties"
    /** 控制 HDMI 旋转全屏的系统属性（见 HardwareConfig） */
    const val EFULL_KEY = HardwareConfig.EFULL_PROPERTY
    private const val COMMAND_TIMEOUT_MS = HardwareConfig.PROPERTY_TIMEOUT_MS

    fun isEfullEnabled(): Boolean = parseBoolean(get(EFULL_KEY))

    fun setEfullEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "true" else "false"
        val wrote = set(EFULL_KEY, value)
        val current = get(EFULL_KEY)
        val ok = wrote && parseBoolean(current) == enabled
        if (!ok) {
            Log.e(TAG, "Failed to set $EFULL_KEY=$value, readBack=$current")
        } else {
            Log.i(TAG, "Set $EFULL_KEY=$value")
        }
        return ok
    }

    fun get(key: String): String {
        getViaSystemProperties(key)?.let { return it }
        val shell = runCommand("getprop", key)
        if (shell != null) return shell
        return runCommand("su", "-c", "getprop $key").orEmpty()
    }

    fun set(key: String, value: String): Boolean {
        if (setViaSystemProperties(key, value)) return true
        if (runCommand("setprop", key, value) != null) return true
        return runCommand("su", "-c", "setprop $key $value") != null
    }

    fun parseBoolean(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return normalized == "true" || normalized == "1"
    }

    private fun getViaSystemProperties(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            (method.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "SystemProperties.get unavailable: ${e.message}")
            null
        }
    }

    private fun setViaSystemProperties(key: String, value: String): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("set", String::class.java, String::class.java)
            method.invoke(null, key, value)
            true
        } catch (e: Exception) {
            Log.d(TAG, "SystemProperties.set unavailable: ${e.message}")
            false
        }
    }

    private fun runCommand(vararg args: String): String? {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(
                COMMAND_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "Command timed out: ${args.joinToString(" ")}")
                return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                Log.e(TAG, "Command failed (${process.exitValue()}): ${args.joinToString(" ")} $output")
                null
            } else {
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${args.joinToString(" ")}", e)
            null
        }
    }
}
