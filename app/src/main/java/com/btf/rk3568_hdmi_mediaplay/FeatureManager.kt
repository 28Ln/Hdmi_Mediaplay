package com.btf.rk3568_hdmi_mediaplay

import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 功能管理器 - 单例
 * 管理功能开关状态，支持通过U盘配置覆盖
 */
object FeatureManager {

    private const val TAG = "FeatureManager"

    // 是否为Debug版本
    private var isDebugBuild: Boolean = true

    // 功能开关状态
    private val _featureFlags = MutableStateFlow(FeatureFlags.debugDefaults())
    val featureFlags: StateFlow<FeatureFlags> = _featureFlags.asStateFlow()

    // 当前加载的U盘配置
    private var currentUsbConfig: UsbConfig? = null

    /**
     * 初始化 - 在Application中调用
     * @param isDebug 是否为Debug版本
     */
    fun init(isDebug: Boolean) {
        isDebugBuild = isDebug
        Log.i(TAG, "FeatureManager initialized, isDebug=$isDebug")

        // 设置默认值
        _featureFlags.value = if (isDebug) {
            FeatureFlags.debugDefaults()
        } else {
            FeatureFlags.releaseDefaults()
        }

        Log.d(TAG, "Default features: ${_featureFlags.value}")
    }

    /**
     * 应用U盘配置
     * @param config U盘配置
     */
    fun applyUsbConfig(config: UsbConfig?) {
        currentUsbConfig = config

        if (config == null) {
            // 恢复默认值
            _featureFlags.value = if (isDebugBuild) {
                FeatureFlags.debugDefaults()
            } else {
                FeatureFlags.releaseDefaults()
            }
            Log.i(TAG, "USB config cleared, restored defaults")
            return
        }

        // 应用功能开关覆盖
        val baseFlags = if (isDebugBuild) {
            FeatureFlags.debugDefaults()
        } else {
            FeatureFlags.releaseDefaults()
        }

        val newFlags = config.features?.applyTo(baseFlags) ?: baseFlags
        _featureFlags.value = newFlags

        Log.i(TAG, "USB config applied: $newFlags")
    }

    /**
     * 获取当前U盘配置的设置覆盖
     */
    fun getSettingsOverride(): UsbConfigSettings? {
        return currentUsbConfig?.settings
    }

    /**
     * 检查是否有U盘配置
     */
    fun hasUsbConfig(): Boolean {
        return currentUsbConfig != null
    }

    /**
     * 获取当前功能开关（非Flow方式）
     */
    fun getCurrentFlags(): FeatureFlags {
        return _featureFlags.value
    }

    /**
     * 是否为Debug版本
     */
    fun isDebug(): Boolean = isDebugBuild
}
