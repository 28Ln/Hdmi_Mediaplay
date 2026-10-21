package com.btf.rk3568_hdmi_mediaplay.hardware

/**
 * 硬件相关配置（集中管理，避免散落在各处硬编码）。
 *
 * 这些值与具体 ROM / 板型绑定，换硬件方案时**只需改这一个文件**。
 * 若后续需要现场可调，可改为从 `btf_config.json` 读取覆盖（见 UsbConfig）。
 */
object HardwareConfig {

    // ============ HDMI 拼接板 I2C 控制节点 ============
    /**
     * 拼接板 I2C 控制器的 sysfs 根目录。
     *
     * 2026-10-19 固件起，控制方式由「GPIO 模拟按键」改为 I2C 寄存器读写：
     * 不再有按键脉冲与 200ms 保持时间，写入即生效并可从同一节点读回。
     *
     * 真值蓝图见 `docs/hdmi-i2c-abi.md`。
     */
    const val I2C_BASE_PATH = "/sys/bus/i2c/devices/4-0030"

    /**
     * 板型：TX3 与 TX4 对镜像（mirror）支持的最低模式不同。
     *
     * 默认 TX4 —— 门槛更高（mode>=5 才允许镜像），
     * 在未确认板型时按更严格的一侧处理，避免发出 MCU 会以 EOPNOTSUPP 拒绝的写入。
     */
    val HDMI_BOARD_VARIANT = HdmiBoardVariant.TX4

    /**
     * hfusion / vfusion **每次写成功都会写 MCU flash**。
     *
     * 拖动滑杆时若逐帧下发，会在数秒内把 flash 写坏（寿命有限）。
     * 因此交互路径必须 stage（仅内存）→ 松手后 commit，且两次 flash 至少间隔该值。
     */
    const val FUSION_FLASH_DEBOUNCE_MS = 300L

    /** EACCES（权限不足）时的重试间隔 */
    const val EACCES_RETRY_DELAY_MS = 500L

    /** EACCES 的最大尝试次数（含首次） */
    const val EACCES_MAX_ATTEMPTS = 3

    /**
     * 单个节点的单次读写超时。
     *
     * ## 必须大于内核 I2C 超时（2026-10-21 实测修正）
     *
     * 原来是 **1500ms**，但实测内核对无应答从机的返回时间是
     * **5.6 ~ 15 秒**（5~11 次驱动重试，每次约 1.04 秒）。
     *
     * 后果很严重：协程 1.5 秒就放弃并**熔断**，但**内核里那次读仍在 D 态
     * 持有适配器锁**。之后所有访问返回 `EBUSY`，
     * 而 App 报告的是「总线挂死」—— 排查方向被完全带偏。
     *
     * 现在取 16 秒，覆盖实测最坏情况（15 秒）+ 余量。
     * 代价是单次失败要等更久，但这比「谎报挂死 + 锁死总线」好得多。
     */
    const val MATRIX_OP_TIMEOUT_MS = 16_000L

    /** EPERM（待机）后写 power=1、等待就绪并重试原操作的预算 */
    const val POWER_RECOVERY_TIMEOUT_MS = 3_000L

    /**
     * EACCES 时是否允许 `su root sh -c` 提权写入。
     *
     * 推荐做法仍是 ROM 侧放开节点权限；su 只是现场兜底。
     */
    const val ALLOW_SU_WRITE = true

    /**
     * 旧 GPIO 按键回退的总开关。
     *
     * 仅在 I2C 根节点不存在（ENOENT，通常是 ROM 尚未加载新驱动）时生效，
     * 绝不能在 EINVAL / EOPNOTSUPP 时回退 —— 那会把真实驱动错误伪装成成功。
     * 新固件全面部署后应连同 [HdmiGpioController] 一并删除。
     */
    const val LEGACY_GPIO_FALLBACK = true

    // ============ 旧版 HDMI 按键节点（已由 2026-10-19 固件移除） ============
    /**
     * @deprecated 2026-10-19 固件起该节点已移除，控制改走 I2C（[I2C_BASE_PATH]）。
     * 仅为旧 ROM 的过渡回退保留；[HdmiGpioController] 删除时一并删除。
     */
    @Deprecated("Removed by the 2026-10-19 firmware change; use I2C_BASE_PATH instead")
    const val MODE_KEY_PATH = "/sys/devices/platform/hdmi-control/hdmi_b2"

    /**
     * @deprecated 2026-10-19 固件起该节点已移除，控制改走 I2C（[I2C_BASE_PATH]）。
     */
    @Deprecated("Removed by the 2026-10-19 firmware change; use I2C_BASE_PATH instead")
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
