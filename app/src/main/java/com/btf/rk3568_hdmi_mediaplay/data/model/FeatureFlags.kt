package com.btf.rk3568_hdmi_mediaplay.data.model

/**
 * 功能开关 - 控制UI功能的显示/隐藏
 * Debug版本默认全部开启，Release版本默认精简
 * 可通过U盘配置文件覆盖
 */
data class FeatureFlags(
    // UI功能开关
    val showSettingsButton: Boolean = true,      // 显示设置按钮
    val allowManualFileSelect: Boolean = true,   // 允许手动选择文件
    val allowLocalMediaScan: Boolean = true,     // 允许扫描本地媒体
    val showHelpTip: Boolean = true,             // 显示帮助提示
    val allowLayoutChange: Boolean = true,       // 允许切换布局
    val showDebugInfo: Boolean = false,          // 显示调试信息
    val showPlayerIndex: Boolean = true,         // 显示播放器序号
    val allowVolumeControl: Boolean = true,      // 允许音量控制
    val allowClearCache: Boolean = true,         // 允许清除缓存
    val showUsbStatus: Boolean = true,           // 显示U盘状态
    val allowPlayPauseControl: Boolean = true,   // 允许播放/暂停控制
    val showBottomControlBar: Boolean = true,    // 显示底部控制栏
) {
    companion object {
        /**
         * Debug版本默认值 - 全功能
         */
        fun debugDefaults() = FeatureFlags(
            showSettingsButton = true,
            allowManualFileSelect = true,
            allowLocalMediaScan = true,
            showHelpTip = true,
            allowLayoutChange = true,
            showDebugInfo = true,
            showPlayerIndex = true,
            allowVolumeControl = true,
            allowClearCache = true,
            showUsbStatus = true,
            allowPlayPauseControl = true,
            showBottomControlBar = true
        )

        /**
         * Release版本默认值 - 精简版
         */
        fun releaseDefaults() = FeatureFlags(
            showSettingsButton = false,
            allowManualFileSelect = false,
            allowLocalMediaScan = false,
            showHelpTip = false,
            allowLayoutChange = false,
            showDebugInfo = false,
            showPlayerIndex = true,
            allowVolumeControl = true,
            allowClearCache = false,
            showUsbStatus = true,
            allowPlayPauseControl = true,
            showBottomControlBar = true
        )
    }
}
