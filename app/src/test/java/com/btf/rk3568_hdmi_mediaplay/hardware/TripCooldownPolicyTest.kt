package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 熔断冷却期语义（`SysfsMatrixNodeIo` / `I2cProbe` 共用的策略）。
 *
 * ## 为什么值得单独测
 *
 * 熔断是为了防止「每次失败都永久泄漏一个 D 态线程」，但它有个反向风险：
 * **一次瞬时抖动就会把正常机器永久判死**。现场表现为
 * 「I2C 明明通了，App 却一直显示不通」——比不熔断更难排查。
 *
 * 自动恢复又不能退化成轮询，否则熔断的意义就没了。
 * 这个类把两边的约束都钉死成可执行的断言。
 *
 * 这里用一个纯逻辑替身复现同一套判据：真实实现依赖
 * `elapsedRealtime()` 与 sysfs，无法在 JVM 上跑。
 */
class TripCooldownPolicyTest {

    /** 与生产实现一致的冷却策略 */
    private class Policy(private val cooldownMs: Long) {
        var tripped = false
            private set
        var trippedAt = 0L
            private set

        fun trip(nowMs: Long) {
            if (!tripped) {
                tripped = true
                trippedAt = nowMs
            }
        }

        fun tryAutoReset(nowMs: Long): Boolean {
            if (!tripped) return false
            if (nowMs - trippedAt < cooldownMs) return false
            tripped = false
            return true
        }

        fun manualReset() {
            tripped = false
            trippedAt = 0L
        }
    }

    private val cooldown = 30_000L

    @Test
    fun `auto reset is denied during cooldown`() {
        val p = Policy(cooldown)
        p.trip(nowMs = 1_000L)

        assertFalse("冷却期内不得自动重试", p.tryAutoReset(1_001L))
        assertFalse(p.tryAutoReset(15_000L))
        assertFalse(p.tryAutoReset(30_999L))
        assertTrue("冷却期内必须仍处于熔断态", p.tripped)
    }

    @Test
    fun `auto reset is allowed exactly at the cooldown boundary`() {
        val p = Policy(cooldown)
        p.trip(nowMs = 1_000L)

        assertTrue("满 30s 应放行一次", p.tryAutoReset(31_000L))
        assertFalse("复位后不再处于熔断态", p.tripped)
    }

    /**
     * 核心约束：**不能退化成轮询**。
     *
     * 复位只放行一次；若这次又失败必须重新熔断并刷新时刻，
     * 于是下一次放行还要再等一个完整冷却期。
     */
    @Test
    fun `failing again re-trips and restarts the cooldown`() {
        val p = Policy(cooldown)
        p.trip(nowMs = 0L)
        assertTrue(p.tryAutoReset(30_000L))

        // 这次尝试又失败 → 重新熔断
        p.trip(nowMs = 30_100L)

        assertFalse("刚重新熔断，冷却未满", p.tryAutoReset(30_500L))
        assertFalse(p.tryAutoReset(60_000L))
        assertTrue("应在一个完整冷却期后放行", p.tryAutoReset(60_100L))
    }

    /** 未熔断时不应产生任何「复位」动作（避免误清状态）。 */
    @Test
    fun `auto reset is a no-op when not tripped`() {
        val p = Policy(cooldown)
        assertFalse(p.tryAutoReset(100_000L))
    }

    /** 手动复位必须立即生效，不等冷却。 */
    @Test
    fun `manual reset bypasses cooldown`() {
        val p = Policy(cooldown)
        p.trip(nowMs = 1_000L)

        p.manualReset()

        assertFalse(p.tripped)
        assertFalse("已复位，不能再触发一次自动复位", p.tryAutoReset(1_001L))
    }

    /**
     * 长时间停机后的首次调用应直接放行。
     *
     * 场景：设备关机一夜后开机，熔断状态早已过了冷却期，
     * 不该再多等 30 秒。
     */
    @Test
    fun `long gap after trip allows immediate auto reset`() {
        val p = Policy(cooldown)
        p.trip(nowMs = 0L)

        assertTrue(p.tryAutoReset(nowMs = 3_600_000L))
    }
}
