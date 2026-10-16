package com.btf.rk3568_hdmi_mediaplay.hardware

/**
 * 硬件相关配置（集中管理，避免散落在各处硬编码）。
 *
 * 这些值与具体 ROM / 板型绑定，换硬件方案时**只需改这一个文件**。
 * 若后续需要现场可调，可改为从 `btf_config.json` 读取覆盖（见 UsbConfig）。
 */
object HardwareConfig {

    // ============ HDMI 拼接板控制节点 ============
    /** 「切换模式」按键对应的 sysfs 节点 */
    const val MODE_KEY_PATH = "/sys/devices/platform/hdmi-control/hdmi_b2"

    /** 「切换分辨率」按键对应的 sysfs 节点 */
    const val RESOLUTION_KEY_PATH = "/sys/devices/platform/hdmi-control/hdmi_b3"

    /**
     * 按键盘是否低电平有效。
     * 本板实测为低电平有效：写 0 = 按下，写 1 = 松开。
     */
    const val KEY_ACTIVE_LOW = true

    /** 按下保持时长（毫秒）。过短按键芯片可能识别不到，过长会影响响应。 */
    const val KEY_HOLD_MS = 200L

    /** 按下前、松开后的短暂延时（毫秒），避免电平切换过快 */
    const val KEY_SETTLE_MS = 20L

    /**
     * 无写权限时是否尝试通过 su 提权写入。
     *
     * 现场 ROM 若未放开节点权限（节点属 root），普通应用写入会被拒（EACCES）。
     * 设备已 root 时可通过 su 完成同样操作。
     * 置 false 可完全禁用提权行为（用于对安全性敏感的场景）。
     */
    const val ALLOW_SU_WRITE = true

    /**
     * su 命令的前缀参数。
     *
     * 本设备的 su 用法为 su WHO COMMAND 形式，不支持 su -c
     * （传 -c 会报 invalid uid/gid）。故用 su root sh -c，由 sh 解析重定向。
     * 若换到支持 -c 的 ROM，此处改为 arrayOf("su", "-c") 即可。
     */
    val SU_PREFIX = arrayOf("su", "root", "sh", "-c")

    /** 执行 su 命令的超时（毫秒） */
    const val SU_TIMEOUT_MS = 3_000L

    // ============ HDMI 全屏（efull）属性 ============
    /** 控制 HDMI 旋转全屏的系统属性 */
    const val EFULL_PROPERTY = "persist.sys.rotation.efull-1"

    /** 属性读写命令超时（毫秒） */
    const val PROPERTY_TIMEOUT_MS = 3_000L
}
