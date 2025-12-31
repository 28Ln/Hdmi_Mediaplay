package com.btf.rk3568_hdmi_mediaplay.data.model

import org.json.JSONObject

/**
 * U盘配置文件数据结构
 * 对应 btf_config.json
 */
data class UsbConfig(
    val version: Int = 1,
    val settings: UsbConfigSettings? = null,
    val features: UsbConfigFeatures? = null
) {
    companion object {
        const val CONFIG_FILE_NAME = "btf_config.json"

        /**
         * 从JSON字符串解析配置
         */
        fun fromJson(jsonString: String): UsbConfig? {
            return try {
                val json = JSONObject(jsonString)
                UsbConfig(
                    version = json.optInt("version", 1),
                    settings = json.optJSONObject("settings")?.let {
                        UsbConfigSettings.fromJson(it)
                    },
                    features = json.optJSONObject("features")?.let {
                        UsbConfigFeatures.fromJson(it)
                    }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 设置覆盖
 */
data class UsbConfigSettings(
    val layoutMode: String? = null,
    val language: String? = null,
    val backgroundColor: String? = null,
    val defaultVolume: Int? = null,
    val defaultMuted: Boolean? = null,
    val imageIntervalSeconds: Int? = null,
    val imageTransition: String? = null,
    val loopMode: String? = null,
    val videoScaleMode: String? = null,
    val autoPlayOnStart: Boolean? = null,
    val autoPlayAfterCopy: Boolean? = null,
    val keepScreenOn: Boolean? = null,
    val usbScanFolderName: String? = null,
    val showOverwriteConfirm: Boolean? = null
) {
    companion object {
        fun fromJson(json: JSONObject): UsbConfigSettings {
            return UsbConfigSettings(
                layoutMode = json.optString("layoutMode", null),
                language = json.optString("language", null),
                backgroundColor = json.optString("backgroundColor", null),
                defaultVolume = if (json.has("defaultVolume")) json.optInt("defaultVolume") else null,
                defaultMuted = if (json.has("defaultMuted")) json.optBoolean("defaultMuted") else null,
                imageIntervalSeconds = if (json.has("imageIntervalSeconds")) json.optInt("imageIntervalSeconds") else null,
                imageTransition = json.optString("imageTransition", null),
                loopMode = json.optString("loopMode", null),
                videoScaleMode = json.optString("videoScaleMode", null),
                autoPlayOnStart = if (json.has("autoPlayOnStart")) json.optBoolean("autoPlayOnStart") else null,
                autoPlayAfterCopy = if (json.has("autoPlayAfterCopy")) json.optBoolean("autoPlayAfterCopy") else null,
                keepScreenOn = if (json.has("keepScreenOn")) json.optBoolean("keepScreenOn") else null,
                usbScanFolderName = json.optString("usbScanFolderName", null),
                showOverwriteConfirm = if (json.has("showOverwriteConfirm")) json.optBoolean("showOverwriteConfirm") else null
            )
        }
    }
}

/**
 * 功能开关覆盖
 */
data class UsbConfigFeatures(
    val showSettingsButton: Boolean? = null,
    val allowManualFileSelect: Boolean? = null,
    val allowLocalMediaScan: Boolean? = null,
    val showHelpTip: Boolean? = null,
    val allowLayoutChange: Boolean? = null,
    val showDebugInfo: Boolean? = null,
    val showPlayerIndex: Boolean? = null,
    val allowVolumeControl: Boolean? = null,
    val allowClearCache: Boolean? = null,
    val showUsbStatus: Boolean? = null,
    val allowPlayPauseControl: Boolean? = null,
    val showBottomControlBar: Boolean? = null
) {
    companion object {
        fun fromJson(json: JSONObject): UsbConfigFeatures {
            return UsbConfigFeatures(
                showSettingsButton = if (json.has("showSettingsButton")) json.optBoolean("showSettingsButton") else null,
                allowManualFileSelect = if (json.has("allowManualFileSelect")) json.optBoolean("allowManualFileSelect") else null,
                allowLocalMediaScan = if (json.has("allowLocalMediaScan")) json.optBoolean("allowLocalMediaScan") else null,
                showHelpTip = if (json.has("showHelpTip")) json.optBoolean("showHelpTip") else null,
                allowLayoutChange = if (json.has("allowLayoutChange")) json.optBoolean("allowLayoutChange") else null,
                showDebugInfo = if (json.has("showDebugInfo")) json.optBoolean("showDebugInfo") else null,
                showPlayerIndex = if (json.has("showPlayerIndex")) json.optBoolean("showPlayerIndex") else null,
                allowVolumeControl = if (json.has("allowVolumeControl")) json.optBoolean("allowVolumeControl") else null,
                allowClearCache = if (json.has("allowClearCache")) json.optBoolean("allowClearCache") else null,
                showUsbStatus = if (json.has("showUsbStatus")) json.optBoolean("showUsbStatus") else null,
                allowPlayPauseControl = if (json.has("allowPlayPauseControl")) json.optBoolean("allowPlayPauseControl") else null,
                showBottomControlBar = if (json.has("showBottomControlBar")) json.optBoolean("showBottomControlBar") else null
            )
        }
    }

    /**
     * 应用到基础FeatureFlags，只覆盖非null的值
     */
    fun applyTo(base: FeatureFlags): FeatureFlags {
        return base.copy(
            showSettingsButton = showSettingsButton ?: base.showSettingsButton,
            allowManualFileSelect = allowManualFileSelect ?: base.allowManualFileSelect,
            allowLocalMediaScan = allowLocalMediaScan ?: base.allowLocalMediaScan,
            showHelpTip = showHelpTip ?: base.showHelpTip,
            allowLayoutChange = allowLayoutChange ?: base.allowLayoutChange,
            showDebugInfo = showDebugInfo ?: base.showDebugInfo,
            showPlayerIndex = showPlayerIndex ?: base.showPlayerIndex,
            allowVolumeControl = allowVolumeControl ?: base.allowVolumeControl,
            allowClearCache = allowClearCache ?: base.allowClearCache,
            showUsbStatus = showUsbStatus ?: base.showUsbStatus,
            allowPlayPauseControl = allowPlayPauseControl ?: base.allowPlayPauseControl,
            showBottomControlBar = showBottomControlBar ?: base.showBottomControlBar
        )
    }
}
