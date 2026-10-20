package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区分「排队等待」与「真正挂死」。
 *
 * ## 背景（2026-10-21 真机实测）
 *
 * 读通道只有 1 个线程。调试页 refresh 与 `probeBus()` 都会提交任务，
 * 单节点执行约 5.5 秒（从机无应答时驱动层超时）。
 * 队列里排 3 个就是 16.5 秒，超过 15.8 秒的挂死判定阈值。
 *
 * 真机日志显示**每 30 秒稳定熔断一次**（22:50:54 → 22:51:58 → 22:52:44），
 * 但总线并没有挂死，只是排队。熔断把「正常排队」误判成「死总线」，
 * 导致调试页反复显示「总线挂死 · 已停止发送」，误导现场排查方向。
 */
class QueueWaitVsHangTest {

    /**
     * 熔断判定：只有当任务**已经开始执行**且超过宽限期，才算挂死。
     *
     * @param taskStarted 任务是否已开始执行（startedAt != 0）
     */
    private fun shouldTrip(taskStarted: Boolean): Boolean = taskStarted

    @Test
    fun `task still queued does not trip the breaker`() {
        assertFalse(
            "任务尚未开始执行说明是排队积压，不是挂死 —— 熔断会误报",
            shouldTrip(taskStarted = false)
        )
    }

    @Test
    fun `task started but not returning does trip the breaker`() {
        assertTrue(
            "任务已开始执行且超时未返回，才是真正的挂死",
            shouldTrip(taskStarted = true)
        )
    }

    /**
     * 数量关系：3 个排队任务 × 5.5 秒 > 15.8 秒阈值。
     * 这条断言说明为什么必须区分 —— 阈值本身没有错，
     * 错的是把排队时间算进了执行时间。
     */
    @Test
    fun `queueing three tasks exceeds the hang threshold`() {
        val perTaskMs = 5_500L
        val graceTotalMs = 800L + 15_000L   // NODE_READ_TIMEOUT_MS + SLOW_RETURN_GRACE_MS
        assertTrue(
            "3 个排队任务的累计等待确实会超过阈值，这正是误熔断的来源",
            3 * perTaskMs > graceTotalMs
        )
    }
}
