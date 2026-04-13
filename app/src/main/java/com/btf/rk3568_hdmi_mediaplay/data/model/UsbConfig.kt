package com.btf.rk3568_hdmi_mediaplay.data.model

import org.json.JSONObject

/**
 * U盘配置文件数据结构
 * 对应 btf_config.json
 */
data class UsbConfig(
    val version: Int = 1,
    val settings: UsbConfigSettings? = null,
    val features: UsbConfigFeatures? = null,
    val validationWarnings: List<String> = emptyList()
) {
    companion object {
        const val CONFIG_FILE_NAME = "btf_config.json"
        private const val LATEST_SUPPORTED_VERSION = 1
        private val SUPPORTED_VERSIONS = setOf(1)
        private val TOP_LEVEL_KEYS = setOf("version", "settings", "features")

        /**
         * 从JSON字符串解析配置
         */
        fun fromJson(jsonString: String): UsbConfig? {
            return try {
                val warnings = mutableListOf<String>()
                val json = JSONObject(jsonString)
                warnings += findUnknownKeys(json, TOP_LEVEL_KEYS, "root")

                val parsedVersion = parseOptionalInt(json, "version", warnings, "root") ?: 1
                if (parsedVersion !in SUPPORTED_VERSIONS) {
                    warnings += "Unsupported config version: $parsedVersion (supported=${SUPPORTED_VERSIONS.joinToString()}); best-effort parsing will use v$LATEST_SUPPORTED_VERSION rules"
                }

                UsbConfig(
                    version = parsedVersion,
                    settings = json.optJSONObject("settings")?.let {
                        UsbConfigSettings.fromJson(it, warnings)
                    },
                    features = json.optJSONObject("features")?.let {
                        UsbConfigFeatures.fromJson(it, warnings)
                    },
                    validationWarnings = warnings
                )
            } catch (e: Exception) {
                null
            }
        }

        internal fun parseOptionalBoolean(
            json: JSONObject,
            key: String,
            warnings: MutableList<String>,
            scope: String
        ): Boolean? {
            if (!json.has(key)) return null
            return when (val value = json.opt(key)) {
                is Boolean -> value
                else -> {
                    warnings += "Type mismatch for $scope.$key: expected Boolean, got ${value?.javaClass?.simpleName ?: "null"}"
                    null
                }
            }
        }

        internal fun parseOptionalInt(
            json: JSONObject,
            key: String,
            warnings: MutableList<String>,
            scope: String
        ): Int? {
            if (!json.has(key)) return null
            return when (val value = json.opt(key)) {
                is Int -> value
                is Long -> value.toInt()
                else -> {
                    warnings += "Type mismatch for $scope.$key: expected Int, got ${value?.javaClass?.simpleName ?: "null"}"
                    null
                }
            }
        }

        internal fun parseOptionalString(
            json: JSONObject,
            key: String,
            warnings: MutableList<String>,
            scope: String
        ): String? {
            if (!json.has(key)) return null
            return when (val value = json.opt(key)) {
                is String -> value
                else -> {
                    warnings += "Type mismatch for $scope.$key: expected String, got ${value?.javaClass?.simpleName ?: "null"}"
                    null
                }
            }
        }

        internal fun validateEnumValue(
            value: String?,
            allowedValues: Set<String>,
            warnings: MutableList<String>,
            scope: String,
            fieldName: String
        ): String? {
            if (value == null) return null
            val normalized = value.uppercase()
            return if (normalized in allowedValues) {
                normalized
            } else {
                warnings += "Invalid enum value for $scope.$fieldName: $value (allowed=${allowedValues.joinToString()})"
                null
            }
        }

        internal fun validateAliasValue(
            value: String?,
            allowedValues: Set<String>,
            warnings: MutableList<String>,
            scope: String,
            fieldName: String
        ): String? {
            if (value == null) return null
            val normalized = value.lowercase()
            return if (normalized in allowedValues) {
                normalized
            } else {
                warnings += "Invalid value for $scope.$fieldName: $value (allowed=${allowedValues.joinToString()})"
                null
            }
        }

        internal fun validateIntRange(
            value: Int?,
            min: Int,
            max: Int,
            warnings: MutableList<String>,
            scope: String,
            fieldName: String
        ): Int? {
            if (value == null) return null
            return if (value in min..max) {
                value
            } else {
                warnings += "Out of range for $scope.$fieldName: $value (expected $min..$max)"
                null
            }
        }

        internal fun validateHexColor(
            value: String?,
            warnings: MutableList<String>,
            scope: String,
            fieldName: String
        ): String? {
            if (value == null) return null
            val normalized = value.trim()
            val isValid = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(normalized)
            return if (isValid) {
                normalized.uppercase()
            } else {
                warnings += "Invalid color for $scope.$fieldName: $value (expected #RRGGBB or #AARRGGBB)"
                null
            }
        }

        internal fun validateNonBlankString(
            value: String?,
            warnings: MutableList<String>,
            scope: String,
            fieldName: String
        ): String? {
            if (value == null) return null
            val normalized = value.trim()
            return if (normalized.isNotEmpty()) {
                normalized
            } else {
                warnings += "Blank value is not allowed for $scope.$fieldName"
                null
            }
        }

        internal fun findUnknownKeys(
            json: JSONObject,
            allowedKeys: Set<String>,
            scope: String
        ): List<String> {
            val warnings = mutableListOf<String>()
            val iterator = json.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key !in allowedKeys) {
                    warnings += "Unknown key in $scope: $key"
                }
            }
            return warnings
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
        private val LAYOUT_MODE_VALUES = LayoutMode.entries.map { it.name }.toSet()
        private val IMAGE_TRANSITION_VALUES = ImageTransition.entries.map { it.name }.toSet()
        private val LOOP_MODE_VALUES = LoopMode.entries.map { it.name }.toSet()
        private val VIDEO_SCALE_MODE_VALUES = VideoScaleMode.entries.map { it.name }.toSet()
        private val LANGUAGE_VALUES = setOf("zh", "chinese", "en", "english")
        private val SETTINGS_KEYS = setOf(
            "layoutMode",
            "language",
            "backgroundColor",
            "defaultVolume",
            "defaultMuted",
            "imageIntervalSeconds",
            "imageTransition",
            "loopMode",
            "videoScaleMode",
            "autoPlayOnStart",
            "autoPlayAfterCopy",
            "keepScreenOn",
            "usbScanFolderName",
            "showOverwriteConfirm"
        )

        fun fromJson(json: JSONObject, warnings: MutableList<String>): UsbConfigSettings {
            warnings += UsbConfig.findUnknownKeys(json, SETTINGS_KEYS, "settings")
            return UsbConfigSettings(
                layoutMode = UsbConfig.validateEnumValue(
                    UsbConfig.parseOptionalString(json, "layoutMode", warnings, "settings"),
                    LAYOUT_MODE_VALUES,
                    warnings,
                    "settings",
                    "layoutMode"
                ),
                language = UsbConfig.validateAliasValue(
                    UsbConfig.parseOptionalString(json, "language", warnings, "settings"),
                    LANGUAGE_VALUES,
                    warnings,
                    "settings",
                    "language"
                ),
                backgroundColor = UsbConfig.validateHexColor(
                    UsbConfig.parseOptionalString(json, "backgroundColor", warnings, "settings"),
                    warnings,
                    "settings",
                    "backgroundColor"
                ),
                defaultVolume = UsbConfig.validateIntRange(
                    UsbConfig.parseOptionalInt(json, "defaultVolume", warnings, "settings"),
                    0,
                    100,
                    warnings,
                    "settings",
                    "defaultVolume"
                ),
                defaultMuted = UsbConfig.parseOptionalBoolean(json, "defaultMuted", warnings, "settings"),
                imageIntervalSeconds = UsbConfig.validateIntRange(
                    UsbConfig.parseOptionalInt(json, "imageIntervalSeconds", warnings, "settings"),
                    1,
                    60,
                    warnings,
                    "settings",
                    "imageIntervalSeconds"
                ),
                imageTransition = UsbConfig.validateEnumValue(
                    UsbConfig.parseOptionalString(json, "imageTransition", warnings, "settings"),
                    IMAGE_TRANSITION_VALUES,
                    warnings,
                    "settings",
                    "imageTransition"
                ),
                loopMode = UsbConfig.validateEnumValue(
                    UsbConfig.parseOptionalString(json, "loopMode", warnings, "settings"),
                    LOOP_MODE_VALUES,
                    warnings,
                    "settings",
                    "loopMode"
                ),
                videoScaleMode = UsbConfig.validateEnumValue(
                    UsbConfig.parseOptionalString(json, "videoScaleMode", warnings, "settings"),
                    VIDEO_SCALE_MODE_VALUES,
                    warnings,
                    "settings",
                    "videoScaleMode"
                ),
                autoPlayOnStart = UsbConfig.parseOptionalBoolean(json, "autoPlayOnStart", warnings, "settings"),
                autoPlayAfterCopy = UsbConfig.parseOptionalBoolean(json, "autoPlayAfterCopy", warnings, "settings"),
                keepScreenOn = UsbConfig.parseOptionalBoolean(json, "keepScreenOn", warnings, "settings"),
                usbScanFolderName = UsbConfig.validateNonBlankString(
                    UsbConfig.parseOptionalString(json, "usbScanFolderName", warnings, "settings"),
                    warnings,
                    "settings",
                    "usbScanFolderName"
                ),
                showOverwriteConfirm = UsbConfig.parseOptionalBoolean(json, "showOverwriteConfirm", warnings, "settings")
            )
        }
    }
}

/**
 * 功能开关覆盖
 */
data class UsbConfigFeatures(
    val showBottomControlBar: Boolean? = null,
    val showSettingsButton: Boolean? = null,
    val showImageSplitTool: Boolean? = null,
    val allowPlayPauseControl: Boolean? = null,
    val allowManualFileSelect: Boolean? = null,
    val allowLocalMediaScan: Boolean? = null,
    val allowVolumeControl: Boolean? = null,
    val allowLayoutChange: Boolean? = null,
    val showHelpTip: Boolean? = null,
    val showPlayerIndex: Boolean? = null,
    val showUsbStatus: Boolean? = null,
    val showDebugInfo: Boolean? = null,
    val allowClearCache: Boolean? = null,
    val showLanguageSetting: Boolean? = null,
    val showAudioOutputSetting: Boolean? = null,
    val showPlaybackSettings: Boolean? = null,
    val showVideoSettings: Boolean? = null,
    val showImageSettings: Boolean? = null,
    val showUsbSettings: Boolean? = null,
    val showDisplaySettings: Boolean? = null,
    val showAdvancedSettings: Boolean? = null,
    val showStorageSettings: Boolean? = null,
    val showHdmiControl: Boolean? = null,
    val showHelpSection: Boolean? = null,
    val showScanUsbButton: Boolean? = null
) {
    @Suppress("LongParameterList")
    constructor(
        showSettingsButton: Boolean? = null,
        allowManualFileSelect: Boolean? = null,
        allowLocalMediaScan: Boolean? = null,
        showHelpTip: Boolean? = null,
        allowLayoutChange: Boolean? = null,
        showDebugInfo: Boolean? = null,
        showPlayerIndex: Boolean? = null,
        allowVolumeControl: Boolean? = null,
        allowClearCache: Boolean? = null,
        showUsbStatus: Boolean? = null,
        allowPlayPauseControl: Boolean? = null,
        showBottomControlBar: Boolean? = null
    ) : this(
        showBottomControlBar = showBottomControlBar,
        showSettingsButton = showSettingsButton,
        showImageSplitTool = null,
        allowPlayPauseControl = allowPlayPauseControl,
        allowManualFileSelect = allowManualFileSelect,
        allowLocalMediaScan = allowLocalMediaScan,
        allowVolumeControl = allowVolumeControl,
        allowLayoutChange = allowLayoutChange,
        showHelpTip = showHelpTip,
        showPlayerIndex = showPlayerIndex,
        showUsbStatus = showUsbStatus,
        showDebugInfo = showDebugInfo,
        allowClearCache = allowClearCache,
        showLanguageSetting = null,
        showAudioOutputSetting = null,
        showPlaybackSettings = null,
        showVideoSettings = null,
        showImageSettings = null,
        showUsbSettings = null,
        showDisplaySettings = null,
        showAdvancedSettings = null,
        showStorageSettings = null,
        showHdmiControl = null,
        showHelpSection = null,
        showScanUsbButton = null
    )

    companion object {
        private val FEATURE_KEYS = setOf(
            "showBottomControlBar",
            "showSettingsButton",
            "showImageSplitTool",
            "allowPlayPauseControl",
            "allowManualFileSelect",
            "allowLocalMediaScan",
            "allowVolumeControl",
            "allowLayoutChange",
            "showHelpTip",
            "showPlayerIndex",
            "showUsbStatus",
            "showDebugInfo",
            "allowClearCache",
            "showLanguageSetting",
            "showAudioOutputSetting",
            "showPlaybackSettings",
            "showVideoSettings",
            "showImageSettings",
            "showUsbSettings",
            "showDisplaySettings",
            "showAdvancedSettings",
            "showStorageSettings",
            "showHdmiControl",
            "showHelpSection",
            "showScanUsbButton"
        )

        fun fromJson(json: JSONObject, warnings: MutableList<String>): UsbConfigFeatures {
            warnings += UsbConfig.findUnknownKeys(json, FEATURE_KEYS, "features")
            return UsbConfigFeatures(
                showBottomControlBar = UsbConfig.parseOptionalBoolean(json, "showBottomControlBar", warnings, "features"),
                showSettingsButton = UsbConfig.parseOptionalBoolean(json, "showSettingsButton", warnings, "features"),
                showImageSplitTool = UsbConfig.parseOptionalBoolean(json, "showImageSplitTool", warnings, "features"),
                allowPlayPauseControl = UsbConfig.parseOptionalBoolean(json, "allowPlayPauseControl", warnings, "features"),
                allowManualFileSelect = UsbConfig.parseOptionalBoolean(json, "allowManualFileSelect", warnings, "features"),
                allowLocalMediaScan = UsbConfig.parseOptionalBoolean(json, "allowLocalMediaScan", warnings, "features"),
                allowVolumeControl = UsbConfig.parseOptionalBoolean(json, "allowVolumeControl", warnings, "features"),
                allowLayoutChange = UsbConfig.parseOptionalBoolean(json, "allowLayoutChange", warnings, "features"),
                showHelpTip = UsbConfig.parseOptionalBoolean(json, "showHelpTip", warnings, "features"),
                showPlayerIndex = UsbConfig.parseOptionalBoolean(json, "showPlayerIndex", warnings, "features"),
                showUsbStatus = UsbConfig.parseOptionalBoolean(json, "showUsbStatus", warnings, "features"),
                showDebugInfo = UsbConfig.parseOptionalBoolean(json, "showDebugInfo", warnings, "features"),
                allowClearCache = UsbConfig.parseOptionalBoolean(json, "allowClearCache", warnings, "features"),
                showLanguageSetting = UsbConfig.parseOptionalBoolean(json, "showLanguageSetting", warnings, "features"),
                showAudioOutputSetting = UsbConfig.parseOptionalBoolean(json, "showAudioOutputSetting", warnings, "features"),
                showPlaybackSettings = UsbConfig.parseOptionalBoolean(json, "showPlaybackSettings", warnings, "features"),
                showVideoSettings = UsbConfig.parseOptionalBoolean(json, "showVideoSettings", warnings, "features"),
                showImageSettings = UsbConfig.parseOptionalBoolean(json, "showImageSettings", warnings, "features"),
                showUsbSettings = UsbConfig.parseOptionalBoolean(json, "showUsbSettings", warnings, "features"),
                showDisplaySettings = UsbConfig.parseOptionalBoolean(json, "showDisplaySettings", warnings, "features"),
                showAdvancedSettings = UsbConfig.parseOptionalBoolean(json, "showAdvancedSettings", warnings, "features"),
                showStorageSettings = UsbConfig.parseOptionalBoolean(json, "showStorageSettings", warnings, "features"),
                showHdmiControl = UsbConfig.parseOptionalBoolean(json, "showHdmiControl", warnings, "features"),
                showHelpSection = UsbConfig.parseOptionalBoolean(json, "showHelpSection", warnings, "features"),
                showScanUsbButton = UsbConfig.parseOptionalBoolean(json, "showScanUsbButton", warnings, "features")
            )
        }
    }

    /**
     * 应用到基础FeatureFlags，只覆盖非null的值
     */
    fun applyTo(base: FeatureFlags): FeatureFlags {
        return base.copy(
            showBottomControlBar = showBottomControlBar ?: base.showBottomControlBar,
            showSettingsButton = showSettingsButton ?: base.showSettingsButton,
            showImageSplitTool = showImageSplitTool ?: base.showImageSplitTool,
            allowPlayPauseControl = allowPlayPauseControl ?: base.allowPlayPauseControl,
            allowManualFileSelect = allowManualFileSelect ?: base.allowManualFileSelect,
            allowLocalMediaScan = allowLocalMediaScan ?: base.allowLocalMediaScan,
            allowVolumeControl = allowVolumeControl ?: base.allowVolumeControl,
            allowLayoutChange = allowLayoutChange ?: base.allowLayoutChange,
            showHelpTip = showHelpTip ?: base.showHelpTip,
            showPlayerIndex = showPlayerIndex ?: base.showPlayerIndex,
            showUsbStatus = showUsbStatus ?: base.showUsbStatus,
            showDebugInfo = showDebugInfo ?: base.showDebugInfo,
            allowClearCache = allowClearCache ?: base.allowClearCache,
            showLanguageSetting = showLanguageSetting ?: base.showLanguageSetting,
            showAudioOutputSetting = showAudioOutputSetting ?: base.showAudioOutputSetting,
            showPlaybackSettings = showPlaybackSettings ?: base.showPlaybackSettings,
            showVideoSettings = showVideoSettings ?: base.showVideoSettings,
            showImageSettings = showImageSettings ?: base.showImageSettings,
            showUsbSettings = showUsbSettings ?: base.showUsbSettings,
            showDisplaySettings = showDisplaySettings ?: base.showDisplaySettings,
            showAdvancedSettings = showAdvancedSettings ?: base.showAdvancedSettings,
            showStorageSettings = showStorageSettings ?: base.showStorageSettings,
            showHdmiControl = showHdmiControl ?: base.showHdmiControl,
            showHelpSection = showHelpSection ?: base.showHelpSection,
            showScanUsbButton = showScanUsbButton ?: base.showScanUsbButton
        )
    }
}
