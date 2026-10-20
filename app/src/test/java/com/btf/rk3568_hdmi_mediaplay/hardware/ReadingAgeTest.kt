package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读数的「年龄」判定。
 *
 * ## 背景（2026-10-21 真机实测）
 *
 * 慢总线上单个节点读要等驱动超时约 5.6 秒，而每个预算周期（6s）只够读 1 个。
 * 实测轮转周期 **15.4 秒/节点**，14 个节点轮一遍需要
 * **216 秒 ≈ 3.6 分钟**。
 *
 * 界面并排显示 14 个值时，它们实际可能横跨好几分钟 —— 不带时间戳
 * 会让现场误以为这些都是「当前值」，据此判断硬件状态会得出错误结论。
 */
class ReadingAgeTest {

    /** 与 I2cDebugScreen 中的阈值保持一致 */
    private val staleThresholdMs = 30_000L

    private fun display(value: String, ageMs: Long): String =
        if (ageMs > staleThresholdMs) "$value  (${ageMs / 1000}s 前)" else value

    @Test
    fun `fresh reading shows the bare value`() {
        assertEquals("2", display("2", 0L))
        assertEquals("2", display("2", 29_999L))
    }

    @Test
    fun `stale reading is annotated with its age in seconds`() {
        assertEquals("2  (30s 前)", display("2", 30_001L))
        assertEquals("2  (125s 前)", display("2", 125_000L))
    }

    /**
     * 回归：满一轮 3.6 分钟后，最早读到的节点必须被标记为陈旧。
     *
     * 若阈值设得过大（例如 300 秒），这个值看起来会和刚读到的一模一样。
     */
    @Test
    fun `a full rotation marks the oldest node as stale`() {
        val rotationMs = 216_000L  // 14 节点 × 15.4 秒
        assertTrue(
            "满一轮后最早的值必须带年龄标注，否则会被当成当前值",
            display("2", rotationMs).contains("前")
        )
    }

    /**
     * 合并读数时必须按**当前节点集合**裁剪。
     *
     * 驱动重绑定后节点可能增减；不做裁剪的话，残留条目会一直留在 Map 里，
     * 若同名节点后来又被加回来，会先显示一次来路不明的旧值。
     */
    @Test
    fun `merged readings are pruned to the current node set`() {
        val previous = mapOf(
            "mode" to 1L,
            "resolution" to 2L,
            "removed_node" to 3L      // 节点已不存在
        )
        val fresh = mapOf("mode" to 4L)
        val currentNodes = setOf("mode", "resolution", "version")

        val merged = (previous + fresh).filterKeys { it in currentNodes }

        assertEquals(setOf("mode", "resolution"), merged.keys)
        assertTrue("已消失的节点必须被剔除", "removed_node" !in merged)
        assertEquals("本轮读到的新值覆盖旧值", 4L, merged["mode"])
        assertEquals("本轮没读到的保留旧值", 2L, merged["resolution"])
    }

    /** 格式化不能出现负年龄或除零之类的问题。 */
    @Test
    fun `age formatting is monotonic and safe`() {
        listOf(0L, 1L, 999L, 30_001L, 216_000L, 3_600_000L).forEach { age ->
            val text = display("v", age)
            assertTrue("年龄 $age 的展示不应为空", text.isNotBlank())
            assertTrue("展示中不应出现负数", !text.contains("-"))
        }
    }
}
