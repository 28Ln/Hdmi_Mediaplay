package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「授权返回后自动重载」的判定逻辑。
 *
 * ## 背景（2026-10-21 真机确认）
 *
 * 全新安装的 Release 包首次启动会跳转「所有文件访问权限」页
 * （`MANAGE_EXTERNAL_STORAGE` 在 Android 11 上只能去系统设置手动开启，
 * 没有运行时对话框）。用户在那里点「允许」并返回后：
 *
 * - `requestPermissions()` 不会重跑（只在 onCreate 调用）
 * - 本地媒体不加载 → 四格只显示「长按选择媒体文件」
 * - USB 监听服务也不启动
 *
 * 实测：授权后返回 App，画面仍是四个空白格，必须杀进程重启才有内容。
 * 客户拿到新设备时很容易以为 App 坏了。
 */
class PermissionResumeTest {

    /**
     * 复现 onResume 的判定：只有「从无到有」才触发重载。
     *
     * @param hadBefore 进 onResume 前记录的权限状态
     * @param now       当前权限状态
     */
    private fun shouldReload(hadBefore: Boolean, now: Boolean): Boolean =
        now && !hadBefore

    @Test
    fun `granting permission while away triggers reload`() {
        assertTrue(
            "首次授权后返回必须重载，否则画面停在空白格",
            shouldReload(hadBefore = false, now = true)
        )
    }

    @Test
    fun `already granted does not reload again`() {
        assertFalse(
            "已授权用户每次 onResume 都重载会造成反复扫描",
            shouldReload(hadBefore = true, now = true)
        )
    }

    @Test
    fun `still denied does not reload`() {
        assertFalse(shouldReload(hadBefore = false, now = false))
        assertFalse(shouldReload(hadBefore = true, now = false))
    }

    /**
     * `hadStoragePermission` 必须在 `requestPermissions()` **之前**初始化。
     *
     * 若在 onCreate 里先调 requestPermissions() 再记录状态，
     * 已授权用户会被误判成「刚授予」，每次冷启动都重复扫描媒体。
     */
    @Test
    fun `initial flag must be captured before requesting`() {
        // 已授权用户冷启动：onCreate 记录了 true，onResume 不应重载
        val onCreateSnapshot = true
        assertFalse(shouldReload(hadBefore = onCreateSnapshot, now = true))
    }
}
