package com.btf.rk3568_hdmi_mediaplay.util

import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.UsbConfig
import java.io.File

/**
 * U盘配置文件加载器
 */
object UsbConfigLoader {

    private const val TAG = "UsbConfigLoader"

    /**
     * 从U盘路径加载配置文件
     * @param usbPath U盘根目录
     * @return 配置对象，如果不存在或解析失败返回null
     */
    fun loadConfig(usbPath: File): UsbConfig? {
        return try {
            val configFile = File(usbPath, UsbConfig.CONFIG_FILE_NAME)

            if (!configFile.exists()) {
                Log.d(TAG, "Config file not found: ${configFile.absolutePath}")
                return null
            }

            if (!configFile.canRead()) {
                Log.w(TAG, "Config file not readable: ${configFile.absolutePath}")
                return null
            }

            val jsonString = configFile.readText(Charsets.UTF_8)
            Log.d(TAG, "Config file content: $jsonString")

            val config = UsbConfig.fromJson(jsonString)
            if (config != null) {
                Log.i(TAG, "Config loaded successfully: version=${config.version}")
            } else {
                Log.w(TAG, "Failed to parse config file")
            }

            config
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config: ${e.message}", e)
            null
        }
    }

    /**
     * 检查U盘是否包含配置文件
     */
    fun hasConfigFile(usbPath: File): Boolean {
        val configFile = File(usbPath, UsbConfig.CONFIG_FILE_NAME)
        return configFile.exists() && configFile.canRead()
    }
}
