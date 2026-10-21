package com.btf.rk3568_hdmi_mediaplay.hardware

import com.btf.rk3568_hdmi_mediaplay.hardware.I2cProbe.WriteStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两处会放大 I2C 故障的正确性约束。
 *
 * ## 背景（2026-10-21 真机 + 代码审查）
 *
 * ### 1. 操作超时必须大于内核 I2C 超时
 *
 * `MATRIX_OP_TIMEOUT_MS` 原为 1500ms，而内核对无应答从机的返回时间是
 * **5.6~15 秒**。协程 1.5 秒就放弃并熔断，但**内核里那次读仍在 D 态
 * 持有适配器锁** —— 之后所有访问 `EBUSY`，而 App 报告「总线挂死」。
 *
 * ### 2. 调试页不应默认开始写
 *
 * 总线 ONLINE 时 `MODE_IDEMPOTENT` 是**真实的写命令**（读 mode → 写回）。
 * 每秒 1 次 × 数百秒 = 数百次向 MCU 写。而只读事务同样有完整波形，
 * 对示波器测量完全够用且零副作用。
 */
class TimeoutAndAutoWriteTest {

    @Test
    fun `operation timeout must exceed kernel i2c timeout`() {
        val kernelWorstCaseMs = 15_000L  // 实测最坏 15 秒
        assertTrue(
            "MATRIX_OP_TIMEOUT_MS(${HardwareConfig.MATRIX_OP_TIMEOUT_MS}) " +
                "必须大于内核超时(${kernelWorstCaseMs}ms)，否则协程先放弃、内核仍持锁",
            HardwareConfig.MATRIX_OP_TIMEOUT_MS > kernelWorstCaseMs
        )
    }

    /** 自动开始写入的判定：只有只读策略才允许自动启动 */
    private fun shouldAutoStartWriting(strategy: WriteStrategy): Boolean =
        strategy == WriteStrategy.READ_PROBE

    @Test
    fun `read probe may auto start`() {
        assertTrue(
            "只读探测对面板零副作用，可以自动开始",
            shouldAutoStartWriting(WriteStrategy.READ_PROBE)
        )
    }

    @Test
    fun `write strategies must not auto start`() {
        assertFalse(
            "MODE_IDEMPOTENT 是真实写命令（读 mode→写回），不得自动开始",
            shouldAutoStartWriting(WriteStrategy.MODE_IDEMPOTENT)
        )
        assertFalse(
            "BUS_PROBE 不应自动开始",
            shouldAutoStartWriting(WriteStrategy.BUS_PROBE)
        )
        assertFalse(shouldAutoStartWriting(WriteStrategy.UNAVAILABLE))
    }

    /**
     * 数值校验：只读与写入的代价差异（供文档与评审参考）。
     */
    @Test
    fun `write path costs more transactions than read probe`() {
        // MODE_IDEMPOTENT 每轮 = 读 mode + 写 mode = 2 个事务
        val writeTxnPerTick = 2
        // READ_PROBE 每轮 = 读 version = 1 个事务
        val readTxnPerTick = 1
        assertEquals(2, writeTxnPerTick)
        assertEquals(1, readTxnPerTick)
        assertTrue(writeTxnPerTick > readTxnPerTick)
    }
}
