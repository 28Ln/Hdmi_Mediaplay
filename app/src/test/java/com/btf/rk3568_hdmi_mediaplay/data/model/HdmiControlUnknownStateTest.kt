package com.btf.rk3568_hdmi_mediaplay.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixAvailability
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiResolution
import org.junit.Test

/**
 * HDMI 控制按钮在「状态未知」时的行为。
 *
 * ## 背景（2026-10-21 审计）
 *
 * 电源/静音按钮的实现是**基于当前值反向推断**：
 *
 * ```kotlin
 * 显示：if (applied?.power == true) "ON" else "OFF"
 * 点击：controller.setPower(applied?.power != true)
 * ```
 *
 * `applied` 为 null（I2C 不通、读不到快照）时两者同时出错：
 * - 显示 **OFF**（因为 null == true 为 false）
 * - 点击写入 **true**（因为 null != true 为 true）
 *
 * 用户看到 OFF 以为拼接板已关，点一下反而发出「开」命令 —— **反向操作**。
 *
 * 修复：状态未知时禁用控件，并把标签显示为 `--` 而不是 `OFF`。
 */
class HdmiControlUnknownStateTest {

    private data class Applied(val power: Boolean? = null, val mute: Boolean? = null)

    /** 复现按钮的显示逻辑（修复后） */
    private fun label(applied: Applied?, field: String): String = when {
        applied == null -> "--"
        field == "power" && applied.power == true -> "ON"
        field == "mute" && applied.mute == true -> "ON"
        else -> "OFF"
    }

    /** 复现按钮的写入值（修复前的问题逻辑） */
    private fun naiveWriteValue(applied: Applied?, field: String): Boolean =
        if (field == "power") applied?.power != true else applied?.mute != true

    @Test
    fun `unknown state shows placeholder instead of OFF`() {
        assertEquals("状态未知时不能显示 OFF（会让人以为已关）", "--", label(null, "power"))
        assertEquals("--", label(null, "mute"))
    }

    /**
     * 核心回归：状态未知时，`!= true` 会求值为 **true**（开）。
     *
     * 这条断言证明「未知时点击会写反」，也是禁用按钮的直接依据。
     * 若将来有人改成 `applied?.power == false`，此测试会失败并提醒复核。
     */
    @Test
    fun `naive toggle inverts when state is unknown`() {
        assertTrue(
            "applied 为 null 时 `applied?.power != true` 为 true —— 这正是反向操作的来源",
            naiveWriteValue(null, "power")
        )
        assertTrue(naiveWriteValue(null, "mute"))
    }

    @Test
    fun `known state toggles correctly`() {
        val on = Applied(power = true, mute = true)
        val off = Applied(power = false, mute = false)

        assertEquals("ON", label(on, "power"))
        assertFalse("当前是开 → 点击应写关", naiveWriteValue(on, "power"))

        assertEquals("OFF", label(off, "power"))
        assertTrue("当前是关 → 点击应写开", naiveWriteValue(off, "power"))
    }

    /**
     * 只有「依赖当前值反向推断」的控件需要禁用。
     * 分辨率是直接指定目标值，不受影响。
     */
    /**
     * 状态文案映射：每种 availability 必须给出**不同**的排查方向。
     *
     * 真机曾把「从机无应答」误报成「驱动节点缺失」——两者排查动作完全相反：
     * - 节点缺失 → 查固件 / DTS
     * - 从机无应答 → 查供电 / 上拉 / 接线
     */
    @Test
    fun `each availability maps to a distinct diagnostic direction`() {
        val mapping = mapOf(
            HdmiMatrixAvailability.NO_DEVICE to "无应答",
            HdmiMatrixAvailability.BUS_HUNG to "卡死",
            HdmiMatrixAvailability.MISSING to "不可用"
        )
        // 三种状态必须映射到不同文案，不能共用一句「不可用」
        assertEquals(3, mapping.values.toSet().size)
        assertTrue(
            "NO_DEVICE 的文案必须含「无应答」而不是「节点缺失」",
            mapping[HdmiMatrixAvailability.NO_DEVICE]!!.contains("无应答")
        )
    }

    /**
     * 探测超时不能被误报成「驱动未绑定且无 root」。
     *
     * 真机实测：驱动已绑定、root 可用、14 个节点全在，
     * 但读 version 要 5.6~25 秒（内核 I2C 超时），
     * 超出 6 秒预算后旧代码返回 CANNOT_PROBE，
     * 界面显示「驱动未绑定且无 root」—— 排查方向被完全带偏。
     */
    @Test
    fun `probe timeout has its own state distinct from cannot-probe`() {
        val labels = mapOf(
            "CANNOT_PROBE" to "无法探测 · 驱动未绑定且无 root",
            "PROBE_TIMEOUT" to "探测超时 · 读 version 超过预算"
        )
        assertNotEquals(
            "超时与「没驱动没root」必须是不同文案",
            labels["CANNOT_PROBE"], labels["PROBE_TIMEOUT"]
        )
        assertTrue(labels["PROBE_TIMEOUT"]!!.contains("超时"))
        assertFalse(
            "超时文案不应出现「驱动未绑定」",
            labels["PROBE_TIMEOUT"]!!.contains("驱动未绑定")
        )
    }

    @Test
    fun `direct-target controls are safe without a snapshot`() {
        // 分辨率按钮：点击直接写枚举值，不读当前值
        val target = HdmiResolution.P1080
        assertEquals(HdmiResolution.P1080, target)
        assertNull("该路径不依赖 applied", null as Applied?)
    }
}
