package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log

/**
 * 旧 GPIO 按键回退的**唯一**开关与适配层。
 *
 * ## 为什么还留着它
 *
 * 2026-10-19 固件之前的 ROM 没有 I2C 控制节点，只能用 `hdmi_b2` / `hdmi_b3`
 * 模拟按键。现场设备不可能一夜之间全部升级 ROM，因此在新驱动缺失时保留一条
 * **只读回退路径**。
 *
 * ## 约束（防止回退掩盖真实故障）
 *
 * - 仅在 I2C 主节点**不存在**（ENOENT）时可用；
 * - EINVAL / EOPNOTSUPP / EPERM 等来自真实驱动的错误**绝不**回退 ——
 *   那会把「参数写错」伪装成「旧硬件」，问题永远查不出来；
 * - 新固件全面部署后，本文件与 [HdmiGpioController] 一起删除。
 */
sealed interface LegacyFallbackPolicy {

    data object Disabled : LegacyFallbackPolicy

    /**
     * @param maxModeUses 「切换模式」最多可回退多少次；默认不限制。
     *   用于在灰度期限制旧路径使用次数，避免设备长期停留在旧行为。
     */
    data class Enabled(val maxModeUses: Int = Int.MAX_VALUE) : LegacyFallbackPolicy
}

/** 旧按键脉冲的接缝；单测可替换为记录器。 */
interface LegacyKeyBridge {
    /** @return 脉冲是否成功写入（不代表矩阵真的切换了）。 */
    suspend fun press(key: HdmiLegacyKey): Boolean
}

/**
 * 生产实现：调用 [HdmiGpioController] 写 `hdmi_b2` / `hdmi_b3`。
 *
 * 旧控制器内部会 `Thread.sleep` 200ms+，因此这里显式切到 IO 线程。
 */
@Suppress("DEPRECATION")
object HdmiGpioLegacyBridge : LegacyKeyBridge {
    private const val TAG = "HdmiGpioLegacyBridge"

    override suspend fun press(key: HdmiLegacyKey): Boolean {
        val path = when (key) {
            HdmiLegacyKey.MODE -> HardwareConfig.MODE_KEY_PATH
            HdmiLegacyKey.RESOLUTION -> HardwareConfig.RESOLUTION_KEY_PATH
        }
        Log.i(TAG, "Legacy GPIO fallback: pulse $key ($path)")
        return HdmiGpioController.press(path)
    }
}
