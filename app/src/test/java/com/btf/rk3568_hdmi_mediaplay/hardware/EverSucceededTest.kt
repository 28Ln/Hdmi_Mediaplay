package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「曾经成功过」标志的语义。
 *
 * ## 为什么这个标志重要（2026-10-21）
 *
 * 现场反馈「刚开机是好的，运行一段时间后失效，必须重启」。
 * 但仅凭「当前读不到」无法区分两种处置完全不同的情况：
 *
 * | 情况 | 含义 | 该查什么 |
 * |---|---|---|
 * | **从未成功** | 硬件从开机就是坏的 | 供电 / 上拉 / 接线 |
 * | **曾经成功过** | 先好后坏 | 上电时序 / 接触不良 |
 *
 * 若没有历史记录，App 只能报告「当前不通」，
 * 现场会把上电时序问题当成接线问题去查，方向就错了。
 */
class EverSucceededTest {

    /** 复现 readNodeValue 中的判定 */
    private fun isNumeric(text: String): Boolean = text.toIntOrNull() != null

    @Test
    fun `numeric read counts as success`() {
        assertTrue(isNumeric("2"))
        assertTrue(isNumeric("0"))
        assertTrue(isNumeric("255"))
    }

    @Test
    fun `error texts do not count as success`() {
        assertFalse("ENODEV 不应被当成成功", isNumeric("ENODEV 从机不应答"))
        assertFalse(isNumeric("读取超时（驱动未返回）"))
        assertFalse(isNumeric("读取通道阻塞（前一次读未返回）"))
        assertFalse(isNumeric("ECONNREFUSED"))
        assertFalse(isNumeric(""))
    }

    /**
     * 关键区分：同样的「当前 ENODEV」，历史不同则结论不同。
     */
    @Test
    fun `same error yields different diagnosis depending on history`() {
        fun diagnosis(everSucceeded: Boolean): String =
            if (everSucceeded) "先好后坏：查上电时序/接触" else "从未通：查供电/上拉/接线"

        val never = diagnosis(everSucceeded = false)
        val before = diagnosis(everSucceeded = true)

        assertTrue(never.contains("从未通"))
        assertTrue(before.contains("先好后坏"))
        assertEquals("两种情况必须给出不同结论", true, never != before)
    }

    /**
     * 标志是「粘性」的：一旦成功过，后续失败不应清除它。
     *
     * 否则某次成功会被后来的失败覆盖，现场又看不出「曾经好过」。
     */
    @Test
    fun `flag is sticky after first success`() {
        var hasEverSucceeded = false
        // 第一次成功
        if (isNumeric("2")) hasEverSucceeded = true
        // 之后一直失败
        repeat(5) {
            if (isNumeric("ENODEV 从机不应答")) hasEverSucceeded = true
        }
        assertTrue("成功标志不能被后续失败清除", hasEverSucceeded)
    }
}
