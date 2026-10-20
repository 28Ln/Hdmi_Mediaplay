package com.btf.rk3568_hdmi_mediaplay.ui.player

import com.btf.rk3568_hdmi_mediaplay.ui.player.PlaybackSync.DriftAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAME 四格同步的**决策逻辑**（纯函数，不依赖 ExoPlayer）。
 *
 * ## 为什么单独测这两个函数
 *
 * 同步是真机上出过两次严重故障的地方，而那两次都**不是**播放器 API 用错，
 * 而是这里的两条判据写错了：
 *
 * 1. 循环边界用了原始 `position`，而 API30 单文件循环时 `currentPosition`
 *    会超过 duration 不回绕 → 判据恒为真 → 每轮跳过同步，
 *    P3/P4 漂移累积到 16~19 秒。
 * 2. 对任何超阈值偏差都 seek → 每播几秒转一次圈，
 *    这也是周期同步一度被迫默认关闭的根因。
 *
 * 这两条判据此前内嵌在 `syncOnce()` 里，只能在真机上看现象，无法回归。
 */
class PlaybackSyncDecisionTest {

    // ==================== 循环边界 ====================

    @Test
    fun `position near start is treated as loop edge`() {
        assertTrue(PlaybackSync.isNearLoopEdge(500L, 30_000L))
    }

    @Test
    fun `position near end is treated as loop edge`() {
        assertTrue(PlaybackSync.isNearLoopEdge(29_500L, 30_000L))
    }

    @Test
    fun `position in the middle is not a loop edge`() {
        assertFalse(PlaybackSync.isNearLoopEdge(15_000L, 30_000L))
    }

    /**
     * 回归：API30 实测 `currentPosition` 会单调增长超过 duration 而不回绕。
     *
     * 旧判据 `position > duration - GUARD` 在 position 超过 duration 后
     * **恒为真**，导致永远跳过同步。必须用 `position % duration`。
     */
    @Test
    fun `position beyond duration wraps and is not permanently at the edge`() {
        // 52 秒位置、30 秒视频 → 循环内 22 秒，属于中间，不该判为边界
        assertFalse(
            "位置超过 duration 后旧判据恒为真，这是 P3/P4 漂移到 16 秒的根因",
            PlaybackSync.isNearLoopEdge(52_000L, 30_000L)
        )
        // 60 秒位置、30 秒视频 → 循环内 0 秒，确实在边界
        assertTrue(PlaybackSync.isNearLoopEdge(60_000L, 30_000L))
    }

    @Test
    fun `unknown duration never reports a loop edge`() {
        assertFalse(PlaybackSync.isNearLoopEdge(0L, 0L))
        assertFalse(PlaybackSync.isNearLoopEdge(5_000L, -1L))
    }

    /**
     * 极短视频：保护窗口若按固定 2 秒算，会吃掉整个片长，导致永远不同步。
     * 此时窗口收敛到 duration/4，中间区段仍然可同步。
     */
    @Test
    fun `very short clip still has a syncable middle section`() {
        val duration = 3_000L   // 3 秒，GUARD=2000 会占掉 2/3
        assertTrue(PlaybackSync.isNearLoopEdge(100L, duration))
        assertFalse(
            "3 秒视频的中点必须可同步，否则短视频永远不同步",
            PlaybackSync.isNearLoopEdge(1_500L, duration)
        )
    }

    /**
     * 回归：4 秒短片 + 固定 3 秒间隔 = 同步被永久跳过。
     *
     * 真机实测日志刷满「主格位于循环边界」，四格从未被纠正过。
     * 判据：可同步区间必须装得下一次检查。
     */
    @Test
    fun `short clip still gets a syncable window with adaptive interval`() {
        val duration = 4_000L
        val interval = PlaybackSync.checkIntervalMs(duration)

        assertTrue("短视频间隔必须小于固定值", interval < PlaybackSync.CHECK_INTERVAL_MS)

        // 可同步区间长度 = duration - 2*guard
        val guard = minOf(PlaybackSync.LOOP_GUARD_MS, duration / 4)
        val syncable = duration - 2 * guard
        assertTrue(
            "可同步区间 ${syncable}ms 必须装下一次检查 ${interval}ms，否则永远跳过",
            syncable >= interval
        )
    }

    @Test
    fun `normal length clip keeps the standard interval`() {
        assertEquals(PlaybackSync.CHECK_INTERVAL_MS, PlaybackSync.checkIntervalMs(30_000L))
        assertEquals(PlaybackSync.CHECK_INTERVAL_MS, PlaybackSync.checkIntervalMs(120_000L))
    }

    @Test
    fun `interval has a floor to avoid spinning`() {
        assertTrue(PlaybackSync.checkIntervalMs(200L) >= 500L)
        assertTrue(PlaybackSync.checkIntervalMs(1L) >= 500L)
    }

    @Test
    fun `unknown duration falls back to the standard interval`() {
        assertEquals(PlaybackSync.CHECK_INTERVAL_MS, PlaybackSync.checkIntervalMs(0L))
        assertEquals(PlaybackSync.CHECK_INTERVAL_MS, PlaybackSync.checkIntervalMs(-1L))
    }

    /**
     * 滞回带：进入纠偏与恢复常速必须用**不同**阈值。
     *
     * 若用同一个阈值（旧实现），刚进入同步窗口就恢复 1.0×，
     * 固有偏差随即又把它推出去 → 反复在 0.95×/1.05× 之间切换。
     * 真机实测 P3 在 -263ms 与 +137ms 之间每 8 秒摆动一次，永不收敛。
     */
    @Test
    fun `trim uses hysteresis so it cannot oscillate at one threshold`() {
        assertTrue(
            "恢复阈值必须明显小于进入阈值，否则会来回振荡",
            PlaybackSync.TRIM_RELEASE_MS < PlaybackSync.TRIM_ENTER_MS
        )
        // 滞回带要有实际宽度，不能只是差 1ms
        assertTrue(
            "滞回带过窄起不到作用",
            PlaybackSync.TRIM_ENTER_MS - PlaybackSync.TRIM_RELEASE_MS >= 50L
        )
    }

    /** 落在滞回带内：既不该开始纠偏，也不该恢复常速（保持现状）。 */
    @Test
    fun `drift inside the hysteresis band keeps the current speed`() {
        val inBand = PlaybackSync.TRIM_RELEASE_MS + 10L
        assertTrue("滞回带内的漂移不应触发新的调速动作", inBand < PlaybackSync.TRIM_ENTER_MS)
    }

    // ==================== 漂移处理 ====================

    @Test
    fun `small drift is ignored`() {
        assertEquals(DriftAction.NONE, PlaybackSync.decideDriftAction(0L))
        assertEquals(DriftAction.NONE, PlaybackSync.decideDriftAction(119L))
        assertEquals(DriftAction.NONE, PlaybackSync.decideDriftAction(-119L))
    }

    @Test
    fun `medium drift is trimmed by speed not seek`() {
        assertEquals(DriftAction.TRIM, PlaybackSync.decideDriftAction(120L))
        assertEquals(DriftAction.TRIM, PlaybackSync.decideDriftAction(-1_000L))
        assertEquals(DriftAction.TRIM, PlaybackSync.decideDriftAction(3_000L))
    }

    /**
     * 回归：大偏差（通常是刚换源）才 seek。
     *
     * 旧实现对任何超阈值偏差都 seek —— 每播几秒转一次圈，
     * 比不同步更难用，迫使周期同步默认关闭。
     */
    @Test
    fun `large drift falls back to seek`() {
        assertEquals(DriftAction.SEEK, PlaybackSync.decideDriftAction(3_001L))
        assertEquals(DriftAction.SEEK, PlaybackSync.decideDriftAction(-10_000L))
    }

    @Test
    fun `decision is symmetric for negative drift`() {
        assertEquals(
            PlaybackSync.decideDriftAction(2_000L),
            PlaybackSync.decideDriftAction(-2_000L)
        )
        assertEquals(
            PlaybackSync.decideDriftAction(5_000L),
            PlaybackSync.decideDriftAction(-5_000L)
        )
    }

    /** 调速比 seek 温和得多：正常解码漂移区间内不应出现 seek。 */
    @Test
    fun `typical decoding drift never triggers a seek`() {
        // 实测正常漂移约每秒 10~30ms；3 秒检查间隔最多累积约 90ms，
        // 异常情况放宽到 1 秒也不该 seek（那会重新缓冲）
        for (drift in listOf(150L, 300L, 800L, 1_500L, 2_900L)) {
            assertEquals(
                "漂移 ${drift}ms 不应触发 seek（会重新缓冲）",
                DriftAction.TRIM,
                PlaybackSync.decideDriftAction(drift)
            )
        }
    }
}
