package com.btf.rk3568_hdmi_mediaplay.service

import com.btf.rk3568_hdmi_mediaplay.data.model.UsbRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U 盘状态机的决策规则。
 *
 * 这些规则此前只存在于我的推理里，靠人工检查代码来守。
 * 结果在一个迭代周期内连续三次踩坑：
 *
 * 1. 手动扫描按钮无效（被发现时已发布）
 * 2. 错误态自动重试永远恢复不了（同上）
 * 3. 挂载广播可能被"内容未变"吞掉
 *
 * 共同根因都是「某个调用方该不该绕过签名闸门」判断错误。
 * 现在把判断抽成纯函数并用测试固化，任何改动只要破坏规则 CI 就会红。
 */
class UsbStateMachineTest {

    // ---------- shouldCheckOnPoll ----------

    @Test
    fun `poll re-enters check when disconnected`() {
        assertTrue(UsbMonitorService.shouldCheckOnPoll(UsbRuntimeState.Disconnected))
    }

    @Test
    fun `poll re-enters check when connected`() {
        // 已连接也要检查：用户可能往 U 盘里拷了新文件
        val state = UsbRuntimeState.Connected(File("/mnt/usb"), hasMediaContent = true)
        assertTrue(UsbMonitorService.shouldCheckOnPoll(state))
    }

    @Test
    fun `poll re-enters check when in error`() {
        // 错误态必须能重入，否则永远没有恢复路径
        assertTrue(UsbMonitorService.shouldCheckOnPoll(UsbRuntimeState.Error("空间不足")))
    }

    @Test
    fun `poll does not re-enter while scanning`() {
        // 上一轮还没结束时不叠加，避免并发扫描同一目录
        val state = UsbRuntimeState.Scanning(File("/mnt/usb"))
        assertFalse(UsbMonitorService.shouldCheckOnPoll(state))
    }

    // ---------- mustBypassSignatureGate ----------

    @Test
    fun `poll must not bypass the signature gate`() {
        // 轮询是例行巡检，靠闸门去重；绕过就会每 3 秒打扰用户
        assertFalse(
            UsbMonitorService.mustBypassSignatureGate(UsbMonitorService.Companion.TriggerSource.POLL)
        )
    }

    @Test
    fun `mount broadcast must bypass the signature gate`() {
        // 挂载是"新设备就绪"的权威信号。若被闸门挡住，
        // 重挂同一分区（部分 ROM 不发 UNMOUNTED）时插入会被忽略。
        assertTrue(
            UsbMonitorService.mustBypassSignatureGate(UsbMonitorService.Companion.TriggerSource.MOUNT_BROADCAST)
        )
    }

    @Test
    fun `manual scan and retry must bypass the signature gate`() {
        // 手动扫描与错误重试的意图就是"再试一次"。
        // 若被闸门挡住，两个功能都会静默失效（已真实发生过）。
        assertTrue(
            UsbMonitorService.mustBypassSignatureGate(UsbMonitorService.Companion.TriggerSource.MANUAL_OR_RETRY)
        )
    }

    @Test
    fun `only poll skips the gate among all sources`() {
        // 兜底：穷举所有来源，确保只有 POLL 不绕过。
        // 新增 TriggerSource 时这条会失败，提醒作者显式决定其语义。
        val bypassing = UsbMonitorService.Companion.TriggerSource.values()
            .filter { UsbMonitorService.mustBypassSignatureGate(it) }
            .toSet()

        assertFalse(
            "POLL 不应绕过闸门",
            UsbMonitorService.Companion.TriggerSource.POLL in bypassing
        )
        assertTrue(
            "除 POLL 外的来源都应绕过闸门，实际绕过的是 $bypassing",
            bypassing.size == UsbMonitorService.Companion.TriggerSource.values().size - 1
        )
    }
}
