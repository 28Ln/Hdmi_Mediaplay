package com.btf.rk3568_hdmi_mediaplay.data.model

/**
 * 功能开关配置类
 * ==================
 * 
 * 用于控制应用中各个功能模块的显示/隐藏状态。
 * 
 * 【使用场景】
 * - Debug 版本：全功能开启，方便开发调试
 * - Release 版本：精简模式，只保留必要功能
 * - U盘配置覆盖：可通过 btf_config.json 动态修改
 * 
 * 【配置优先级】
 * U盘配置 > 版本默认值
 * 
 * 【U盘配置示例】btf_config.json
 * ```json
 * {
 *   "features": {
 *     "showSettingsButton": true,
 *     "showImageSplitTool": true,
 *     "showDebugInfo": true
 *   }
 * }
 * ```
 */
data class FeatureFlags(
    
    // ============ 底部控制栏相关 ============
    
    /** 显示底部控制栏 - 触摸屏幕底部弹出的工具栏 */
    val showBottomControlBar: Boolean = true,
    
    /** 显示设置按钮 - 底部栏的⚙图标，点击进入设置页面 */
    val showSettingsButton: Boolean = true,
    
    /** 显示图片裁剪工具 - 底部栏的✂️图标，用于将大图裁剪分配到多个播放器 */
    val showImageSplitTool: Boolean = true,  // Release默认关闭
    
    /** 允许播放/暂停控制 - 底部栏的▶/⏸按钮 */
    val allowPlayPauseControl: Boolean = true,
    
    // ============ 播放器交互相关 ============
    
    /** 允许手动选择文件 - 长按播放器弹出菜单中的"选择文件"选项 */
    val allowManualFileSelect: Boolean = false,  // Release默认关闭
    
    /** 允许扫描本地媒体 - 长按播放器弹出菜单中的"扫描本地"选项 */
    val allowLocalMediaScan: Boolean = false,  // Release默认关闭
    
    /** 允许音量控制 - 长按播放器弹出菜单中的音量调节 */
    val allowVolumeControl: Boolean = true,
    
    /** 允许切换布局 - 设置页面中的布局模式选项(单屏/双屏/四屏等) */
    val allowLayoutChange: Boolean = false,  // Release默认关闭
    
    // ============ 界面显示相关 ============
    
    /** 显示帮助提示 - 首次启动时左上角的操作说明气泡 */
    val showHelpTip: Boolean = false,  // Release默认关闭
    
    /** 显示播放器序号 - 每个播放器窗口角落的 P1/P2/P3/P4 标识 */
    val showPlayerIndex: Boolean = true,
    
    /** 显示U盘状态 - 右上角的U盘连接状态指示器 */
    val showUsbStatus: Boolean = true,
    
    /** 显示调试信息 - 开发用，显示额外的调试日志和状态信息 */
    val showDebugInfo: Boolean = false,
    
    // ============ 高级功能相关 ============
    
    /** 允许清除缓存 - 设置页面中的"清除缓存"按钮 */
    val allowClearCache: Boolean = false,  // Release默认关闭
    
    // ============ 设置页面区块控制 ============
    
    /** 显示语言设置 */
    val showLanguageSetting: Boolean = false,  // Release默认关闭
    
    /** 显示音频输出设置 */
    val showAudioOutputSetting: Boolean = false,  // Release默认关闭
    
    /** 显示播放设置区块 */
    val showPlaybackSettings: Boolean = false,  // Release默认关闭
    
    /** 显示视频设置区块 */
    val showVideoSettings: Boolean = false,  // Release默认关闭
    
    /** 显示图片设置区块 */
    val showImageSettings: Boolean = true,
    
    /** 显示U盘设置区块 */
    val showUsbSettings: Boolean = true,
    
    /** 显示显示设置区块 */
    val showDisplaySettings: Boolean = true,
    
    /** 显示高级设置区块 */
    val showAdvancedSettings: Boolean = false,  // Release默认关闭
    
    // ============ 其他控制 ============
    
    /** 显示存储设置区块 */
    val showStorageSettings: Boolean = false,  // Release默认关闭
    
    /** 显示HDMI控制区块 */
    val showHdmiControl: Boolean = false,  // Release默认关闭
    
    /** 显示使用说明 */
    val showHelpSection: Boolean = false,  // Release默认关闭
    
    /** 显示扫描U盘按钮 */
    val showScanUsbButton: Boolean = true,  // 保留，用户需要手动触发扫描
    
) {
    companion object {
        
        /**
         * Debug 版本默认配置
         * ------------------
         * 全功能开启，适合开发调试阶段使用。
         * 
         * 编译命令: ./gradlew assembleDebug
         */
        fun debugDefaults() = FeatureFlags(
            // 底部控制栏 - 全部显示
            showBottomControlBar = true,
            showSettingsButton = true,
            showImageSplitTool = true,
            allowPlayPauseControl = true,
            // 播放器交互 - 全部允许
            allowManualFileSelect = true,
            allowLocalMediaScan = true,
            allowVolumeControl = true,
            allowLayoutChange = true,
            // 界面显示 - 全部显示(含调试)
            showHelpTip = true,
            showPlayerIndex = true,
            showUsbStatus = true,
            showDebugInfo = true,
            // 高级功能 - 全部允许
            allowClearCache = true,
            // 设置页面 - 全部显示
            showLanguageSetting = true,
            showAudioOutputSetting = true,
            showPlaybackSettings = true,
            showVideoSettings = true,
            showImageSettings = true,
            showUsbSettings = true,
            showDisplaySettings = true,
            showAdvancedSettings = true,
            // 其他控制 - 全部显示
            showStorageSettings = true,
            showHdmiControl = true,
            showHelpSection = true,
            showScanUsbButton = true
        )

        /**
         * Release 版本默认配置
         * --------------------
         * 精简模式，隐藏开发/调试相关功能，
         * 只保留终端用户需要的核心功能。
         * 
         * 编译命令: ./gradlew assembleRelease
         */
        fun releaseDefaults() = FeatureFlags(
            // 底部控制栏 - 保留基本控制
            showBottomControlBar = true,
            showSettingsButton = true,
            showImageSplitTool = true,
            allowPlayPauseControl = true,
            // 播放器交互 - 限制手动操作
            allowManualFileSelect = false,
            allowLocalMediaScan = false,
            allowVolumeControl = true,
            allowLayoutChange = false,
            // 界面显示 - 精简
            showHelpTip = false,
            showPlayerIndex = true,
            showUsbStatus = true,
            showDebugInfo = false,
            // 高级功能 - 限制
            allowClearCache = false,
            // 设置页面 - 按需求控制
            showLanguageSetting = false,
            showAudioOutputSetting = false,
            showPlaybackSettings = false,
            showVideoSettings = false,
            showImageSettings = true,
            showUsbSettings = true,
            showDisplaySettings = true,
            showAdvancedSettings = false,
            // 其他控制 - 精简
            showStorageSettings = false,
            showHdmiControl = true,  // HDMI控制保留
            showHelpSection = false,
            showScanUsbButton = true  // 扫描U盘保留
        )
    }
}
