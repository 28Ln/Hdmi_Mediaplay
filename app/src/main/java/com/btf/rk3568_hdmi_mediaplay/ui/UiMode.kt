package com.btf.rk3568_hdmi_mediaplay.ui

import com.btf.rk3568_hdmi_mediaplay.BuildConfig

/**
 * 客户 UI 模式判定（MainScreen / SettingsScreen 共用）。
 *
 * 客户版与开发版的差异（精简设置区块、放大字号内边距、底部栏按钮组合）
 * 完全由构建类型决定：
 *
 * - Release 包：`customerUiMode = true` → 客户精简界面
 * - Debug 包：`customerUiMode = false` → 完整开发界面
 *
 * [FORCE_CUSTOMER_UI_PREVIEW] 是逃生开关：置为 true 可让 Debug 运行
 * 也按客户版渲染，便于在与客户一致的外观下排查界面问题。
 * 正常开发请保持 false——否则 Debug 包会被当成客户版，
 * 既看不到语言/播放/视频/显示/高级等设置区块，
 * 也无法通过界面区分构建类型。
 */
const val FORCE_CUSTOMER_UI_PREVIEW = false

val customerUiMode: Boolean
    get() = FORCE_CUSTOMER_UI_PREVIEW || !BuildConfig.IS_DEBUG_BUILD
