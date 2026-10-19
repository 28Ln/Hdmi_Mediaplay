package com.btf.rk3568_hdmi_mediaplay.hardware

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode

/**
 * App 布局 <-> 拼接板硬件模式 的**唯一**映射表。
 *
 * ## 为什么必须单独一张表（历史缺陷）
 *
 * `LayoutMode.wallModeNumber` 是**拼接板示意图上的编号**，不是硬件寄存器值：
 *
 * | LayoutMode | wallModeNumber（示意图） | 硬件 mode |
 * |---|---|---|
 * | GRID_1X2  | 1 | 1 |
 * | GRID_1X3  | 2 | 3 |
 * | GRID_2X1  | **5** | **2** |
 * | GRID_3X1  | 6 | 4 |
 * | GRID_2X2  | 7 | 5 |
 * | GRID_1X2_PORTRAIT | 3 | 无 |
 * | GRID_1X3_PORTRAIT | 4 | 无 |
 *
 * 直接复用 `wallModeNumber` 会把「横屏 2×1」写成硬件模式 5（实际是 2×2），
 * 画面切分与用户选择完全不符，而且**不会报错** —— MCU 照单全收。
 * 因此本文件**禁止读取 `wallModeNumber`**，并用独立单测锁死每条映射。
 *
 * 硬件模式取值（ABI）：0=1X1, 1=1X2, 2=2X1, 3=1X3, 4=3X1, 5=2X2, 6=1X4, 7=4X1。
 */
object HdmiMatrixModeMapping {

    /**
     * 表驱动，避免 `when` 分支漏项（新增布局时编译器不会提醒 when 漏了谁，
     * 但 `Map` 的完整性由单测逐项校验）。
     *
     * null = 没有硬件模式，调用方必须跳过硬件 IO（返回 NoHardwareMode），
     * **不能猜一个值**。
     */
    private val LAYOUT_TO_HARDWARE: Map<LayoutMode, Int?> = mapOf(
        LayoutMode.SINGLE to null,
        LayoutMode.GRID_1X2 to 1,
        // 关键：2，不是 wallModeNumber 的 5
        LayoutMode.GRID_2X1 to 2,
        LayoutMode.GRID_2X2 to 5,
        LayoutMode.GRID_1X3 to 3,
        LayoutMode.GRID_3X1 to 4,
        LayoutMode.GRID_1X2_PORTRAIT to null,
        LayoutMode.GRID_1X3_PORTRAIT to null,
        LayoutMode.GRID_2X2_SAME to null,
        LayoutMode.ROW_1X4 to 6,
        LayoutMode.COLUMN_4X1 to 7,
        LayoutMode.PIP to null
    )

    /** 反向表：硬件模式 -> App 布局。0（1X1）刻意不映射。 */
    private val HARDWARE_TO_LAYOUT: Map<Int, LayoutMode> = buildMap {
        LAYOUT_TO_HARDWARE.forEach { (layout, hardware) ->
            if (hardware != null && hardware != 0) {
                put(hardware, layout)
            }
        }
    }

    /** 供单测/文档核对使用（只读）。 */
    val layoutMapping: Map<LayoutMode, Int?> get() = LAYOUT_TO_HARDWARE

    /**
     * App 布局 -> 硬件模式。
     *
     * 纯函数、无 IO、永不抛异常；软件布局返回 null。
     * **GRID_2X1 -> 2**（本函数存在的理由）。
     */
    fun hardwareModeFor(layoutMode: LayoutMode): Int? = LAYOUT_TO_HARDWARE[layoutMode]

    /**
     * 硬件模式 -> App 布局（用于读回后校正 UI）。
     *
     * 0（1X1）返回 null：App 不提供 1X1 布局，若 MCU 处于 1X1
     * （例如别的客户端改过），应如实显示「无对应布局」而不是强行套用。
     */
    fun layoutModeFor(hardwareMode: Int): LayoutMode? = HARDWARE_TO_LAYOUT[hardwareMode]

    /** 该硬件模式是否允许写 mirror。 */
    fun mirrorAllowedForMode(
        hardwareMode: Int,
        variant: HdmiBoardVariant = HardwareConfig.HDMI_BOARD_VARIANT
    ): Boolean = hardwareMode >= variant.minHardwareModeForMirror

    /**
     * UI 预检：当前布局能不能提供镜像控制。
     *
     * 提前返回 false 比让用户点了再收到 EOPNOTSUPP 更好 ——
     * 前者是「这个布局不支持」，后者看起来像故障。
     */
    fun supportsMirror(
        layoutMode: LayoutMode,
        variant: HdmiBoardVariant = HardwareConfig.HDMI_BOARD_VARIANT
    ): Boolean {
        val mode = hardwareModeFor(layoutMode) ?: return false
        return mirrorAllowedForMode(mode, variant)
    }

    /** 不合法时返回 null；调用方据此构造 InvalidValue，避免把越界值写进 sysfs。 */
    fun validate(node: HdmiMatrixNode, value: Int): IntRange? {
        val range = node.allowedRange ?: return null
        return if (value in range) null else range
    }

    /** mirror 还要受板型上限约束：TX3 只有 3 路，0x0F 会越界。 */
    fun mirrorMaskError(
        mirrorMask: Int,
        variant: HdmiBoardVariant = HardwareConfig.HDMI_BOARD_VARIANT
    ): IntRange? {
        val max = minOf(0x0F, variant.maxMirrorMask)
        val allowed = 0..max
        return if (mirrorMask in allowed) null else allowed
    }
}
