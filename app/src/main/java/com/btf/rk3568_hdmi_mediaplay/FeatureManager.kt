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

    // 功能开关状态 - 初始化为 release 默认值，确保安全
    // 注意：init() 会根据实际 build type 重新设置正确的值
    private val _featureFlags = MutableStateFlow(FeatureFlags.releaseDefaults())
    val featureFlags: StateFlow<FeatureFlags> = _featureFlags.asStateFlow()

    // 当前加载的U盘配置
    private var currentUsbConfig: UsbConfig? = null

    /**
     * 初始化 - 在Application中调用
     * @param isDebug 是否为Debug版本（已忽略，强制使用Release配置）
     */
    fun init(isDebug: Boolean) {
        // 【重要】强制使用 Release 配置，不管是 Debug 还是 Release 构建
        // 如需测试 Debug 全功能模式，将下面的 false 改为 true
        val forceDebugMode = true
        
        isDebugBuild = forceDebugMode
        Log.i(TAG, "FeatureManager initialized, forceDebugMode=$forceDebugMode (buildConfig.isDebug=$isDebug)")

        // 设置默认值
        _featureFlags.value = if (forceDebugMode) {
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
