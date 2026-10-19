package com.btf.rk3568_hdmi_mediaplay.receiver

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 伪造挂载广播的防护（2026-10-20）。
 *
 * 背景：`MEDIA_MOUNTED` 是隐式广播，任何应用都能 `sendBroadcast` 伪造。
 * 实测在 RK3568 真机上，一个 root shell 发出的
 * `am broadcast -a android.intent.action.MEDIA_MOUNTED -d file:///mnt/media_rw/EVIL`
 * 能让本应用对任意路径走完整 U 盘处理链（扫描、复制、弹框）。
 *
 * 修复分两层：
 * 1. Manifest 里的静态声明改为 `exported=false`（服务内已有动态注册，
 *    静态声明本就是冗余攻击面）；
 * 2. `onReceive` 校验路径必须落在已知挂载根之下。
 *
 * 本测试覆盖第 2 层的**判定逻辑**（纯字符串前缀判断，不需要 Android 运行时）。
 */
class UsbBroadcastPathGuardTest {

    // 与服务实现保持一致的允许前缀
    private val allowed = listOf("/mnt/media_rw/", "/storage/")

    private fun accepts(path: String): Boolean = allowed.any { path.startsWith(it) }

    @Test
    fun `real usb mount paths are accepted`() {
        assertTrue(accepts("/mnt/media_rw/1922-2112"))
        assertTrue(accepts("/mnt/media_rw/FAKEUSB"))
        assertTrue(accepts("/storage/1922-2112"))
    }

    @Test
    fun `forged paths outside mount roots are rejected`() {
        assertFalse(accepts("/data/local/tmp/evil"))
        assertFalse(accepts("/sdcard/Download"))
        assertFalse(accepts("/system"))
        assertFalse(accepts(""))
    }

    /**
     * **诚实记录一个限制**：前缀校验挡不住挂载根**内部**的伪造路径。
     *
     * 实测攻击用的正是 `/mnt/media_rw/EVIL`，它在允许前缀内，因此这一层
     * 放行了。真正的防护是 Manifest 的 `exported=false` —— 去掉静态声明后，
     * 外部广播根本到不了这个 receiver（服务内动态注册的是 NOT_EXPORTED）。
     *
     * 保留前缀校验的价值：挡住 `/data`、`/system`、`/sdcard` 这类明显越界的
     * 伪造路径（例如未来若重新启用静态声明）。不要宣称它解决了全部问题。
     */
    @Test
    fun `prefix guard alone does not stop in-root forgery`() {
        assertTrue(
            "在挂载根内部伪造的路径仍会通过前缀校验 —— 该层只做纵深防御",
            accepts("/mnt/media_rw/EVIL")
        )
    }

    @Test
    fun `prefix must include the trailing slash to avoid sibling-prefix bypass`() {
        // 若只判断 startsWith("/mnt/media_rw")，"/mnt/media_rw_evil" 会漏过
        assertFalse(accepts("/mnt/media_rw_evil/x"))
        assertFalse(accepts("/storage_evil/x"))
    }
}
