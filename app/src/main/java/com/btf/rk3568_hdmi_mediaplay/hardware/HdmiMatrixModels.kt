package com.btf.rk3568_hdmi_mediaplay.hardware

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode

/**
 * HDMI 拼接板 I2C 控制的数据模型。
 *
 * ## 为什么单独放一个文件
 *
 * 本文件**刻意不引用任何 Android API**（`Log`、`Context`、`SystemClock` 都不出现），
 * 因此整个错误分类 / 快照 / 枚举都能在普通 JVM 单测里真实执行。
 * 控制器与 UI 只依赖这些类型，错误文案由 `StringResources` 负责翻译 ——
 * 控制器永远不构造用户可见文本。
 */

// ==================== 结果 ====================

/**
 * 所有公开 IO 调用的返回类型。
 *
 * 不再返回裸 `Boolean`：失败时调用方需要知道「能不能重试」「是哪个节点」「errno 是多少」，
 * 否则只能把 EINVAL（改值）和 EACCES（重试/提权）混为一谈。
 */
sealed interface HdmiMatrixResult<out T> {

    data class Success<T>(
        val value: T,
        val snapshot: HdmiMatrixSnapshot? = null
    ) : HdmiMatrixResult<T>

    data class Failure(
        val error: HdmiMatrixError,
        val snapshot: HdmiMatrixSnapshot? = null
    ) : HdmiMatrixResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): HdmiMatrixError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): HdmiMatrixResult<R> = when (this) {
        is Success -> Success(transform(value), snapshot)
        is Failure -> this
    }
}

// ==================== 错误 ====================

/** UI 层据此选择 zh/en 文案；控制器只产出 key，不产出文本。 */
enum class HdmiMatrixErrorKind {
    NOT_AVAILABLE,
    PERMISSION_DENIED,
    DEVICE_STANDBY,
    INVALID_VALUE,
    UNSUPPORTED_IN_MODE,
    TIMEOUT,
    IO_FAILURE,
    BUSY,
    NO_HARDWARE_MODE,
    THROTTLED,
    UNKNOWN
}

/**
 * 结构化错误。
 *
 * `retryable` 的含义是「**立即**原样重试可能成功」，不是「以后可能恢复」：
 * ENOENT（驱动没加载）在启动阶段可能稍后出现，但立刻重试没有意义，
 * 应由 `ensureOnline` 轮询而不是在单次调用里死循环。
 */
sealed class HdmiMatrixError {

    abstract val kind: HdmiMatrixErrorKind
    abstract val errno: Int?
    abstract val node: HdmiMatrixNode?
    open val retryable: Boolean = false
    abstract val cause: Throwable?

    /** 驱动未加载 / 节点不存在（ENOENT=2）。 */
    data class NotAvailable(
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.NOT_AVAILABLE
        override val errno: Int = HdmiMatrixErrno.ENOENT
    }

    /** 文件权限不足（EACCES=13），已按策略重试 [attempts] 次。 */
    data class PermissionDenied(
        val attempts: Int = 1,
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.PERMISSION_DENIED
        override val errno: Int = HdmiMatrixErrno.EACCES
    }

    /** MCU 处于待机（EPERM=1），必须先写 `power=1`。 */
    data class DeviceStandby(
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.DEVICE_STANDBY
        override val errno: Int = HdmiMatrixErrno.EPERM
    }

    /** 值越界（EINVAL=22 / ERANGE=34）；**不要重试**，改值。 */
    data class InvalidValue(
        val field: String,
        val value: Int,
        val allowedRange: IntRange? = null,
        override val errno: Int = HdmiMatrixErrno.EINVAL,
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.INVALID_VALUE
    }

    /** 当前模式下不允许该操作（EOPNOTSUPP=95），例如非拼接模式写 fusion。 */
    data class UnsupportedInMode(
        val field: String,
        val hardwareMode: Int? = null,
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE
        override val errno: Int = HdmiMatrixErrno.EOPNOTSUPP
    }

    data class Timeout(
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.TIMEOUT
        override val errno: Int = HdmiMatrixErrno.ETIMEDOUT
        override val retryable: Boolean = true
    }

    data class IoFailure(
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.IO_FAILURE
        override val errno: Int = HdmiMatrixErrno.EIO
        override val retryable: Boolean = true
    }

    /** status 的 bit0 长时间不归零。 */
    data class Busy(val statusRaw: Int) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.BUSY
        override val errno: Int? = null
        override val node: HdmiMatrixNode? = HdmiMatrixNode.STATUS
        override val cause: Throwable? = null
        override val retryable: Boolean = true
    }

    /** 软件布局（SINGLE / 竖屏 / 同画面 / PIP 等）没有硬件模式，**不是故障**。 */
    data class NoHardwareMode(val layoutMode: LayoutMode) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.NO_HARDWARE_MODE
        override val errno: Int? = null
        override val node: HdmiMatrixNode? = null
        override val cause: Throwable? = null
    }

    /** flash 写入过于频繁，调用方应改为 stage + commit。 */
    data object Throttled : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.THROTTLED
        override val errno: Int? = null
        override val node: HdmiMatrixNode? = null
        override val cause: Throwable? = null
        override val retryable: Boolean = true
    }

    data class Unknown(
        override val errno: Int?,
        val message: String? = null,
        override val node: HdmiMatrixNode? = null,
        override val cause: Throwable? = null
    ) : HdmiMatrixError() {
        override val kind: HdmiMatrixErrorKind = HdmiMatrixErrorKind.UNKNOWN
    }
}

// ==================== 快照 ====================

/** `status` 节点的位域；未定义的高位保留在 [raw] 中，不做丢弃。 */
data class HdmiMatrixStatus(val raw: Int) {
    val busy: Boolean get() = raw and 0x01 != 0
    val power: Boolean get() = raw and 0x02 != 0
    val error: Boolean get() = raw and 0x40 != 0
    val overflow: Boolean get() = raw and 0x80 != 0
}

/** 从 sysfs **读回**的已生效值；字段为 null 表示该节点本次未读取。 */
data class HdmiMatrixApplied(
    val mode: Int? = null,
    val resolution: HdmiResolution? = null,
    val mirror: Int? = null,
    val rotate: Int? = null,
    val hfusion: Int? = null,
    val vfusion: Int? = null,
    val mute: Boolean? = null,
    val power: Boolean? = null
)

/**
 * 一次性读到的只读面板。
 *
 * [online] 由 `version == 2` 推导，**不缓存为真值**：MCU 重启后版本号会变化，
 * 任何缓存都会让 UI 显示一个已经不存在的在线状态。
 */
data class HdmiMatrixSnapshot(
    val online: Boolean,
    val version: Int?,
    val status: HdmiMatrixStatus,
    val lastResult: Int?,
    val cmdCount: Long?,
    val rxState: Int?,
    val applied: HdmiMatrixApplied?,
    val readAtElapsedMs: Long
)

/** 只读状态面板的可观测状态。 */
data class HdmiMatrixState(
    val availability: HdmiMatrixAvailability = HdmiMatrixAvailability.UNKNOWN,
    val lastSnapshot: HdmiMatrixSnapshot? = null,
    val lastError: HdmiMatrixError? = null,
    val lastFusionFlashElapsedMs: Long = 0L,
    val pendingFusion: FusionStage? = null
)

enum class HdmiMatrixAvailability {
    UNKNOWN,
    ONLINE,
    MISSING,
    PERMISSION_DENIED
}

/** [stageFusion] 暂存在内存里的预览值；[revision] 用于提交时检测是否已有更新的值。 */
data class FusionStage(
    val hfusion: Int,
    val vfusion: Int,
    val revision: Long
)

/** 批量下发；null 表示「不要动这个节点」。 */
data class HdmiMatrixSettings(
    val power: Boolean? = null,
    val mode: Int? = null,
    val mirror: Int? = null,
    val resolution: HdmiResolution? = null,
    val rotate: Int? = null,
    val hfusion: Int? = null,
    val vfusion: Int? = null,
    val mute: Boolean? = null
)

/** `resolution` 只接受枚举，避免调用方把 0/1/2 写错位。 */
enum class HdmiResolution(val sysfsValue: Int) {
    P720(0),
    P1080(1),
    P2160_30(2);

    companion object {
        fun fromSysfsValue(value: Int): HdmiResolution? =
            entries.firstOrNull { it.sysfsValue == value }
    }
}

// ==================== 节点 ====================

/**
 * 节点表：路径、可写性、取值范围。
 *
 * 路径相对 [HardwareConfig.I2C_BASE_PATH]；节点表是 ABI 的唯一代码来源，
 * 文档 `docs/hdmi-i2c-abi.md` 必须与本表逐项一致。
 */
enum class HdmiMatrixNode(
    val relativePath: String,
    val writable: Boolean,
    val allowedRange: IntRange?
) {
    MODE("mode", true, 0..7),
    RESOLUTION("resolution", true, 0..2),
    MIRROR("mirror", true, 0..15),
    ROTATE("rotate", true, 0..3),
    HFUSION("hfusion", true, 0..255),
    VFUSION("vfusion", true, 0..255),
    MUTE("mute", true, 0..1),
    POWER("power", true, 0..1),

    /** 兼容旧按键语义；新代码不得依赖。 */
    KEY("key", true, null),

    VERSION("version", false, null),
    STATUS("status", false, null),
    RESULT("result", false, null),
    CMD_COUNT("cmd_count", false, null),
    RX_STATE("rx_state", false, null);

    val absolutePath: String get() = "${HardwareConfig.I2C_BASE_PATH}/$relativePath"
}

/** 板型差异：TX4 对镜像模式的门槛更高，默认取 TX4（更严格）。 */
enum class HdmiBoardVariant(
    val minHardwareModeForMirror: Int,
    val maxMirrorMask: Int
) {
    TX3(minHardwareModeForMirror = 3, maxMirrorMask = 0x07),
    TX4(minHardwareModeForMirror = 5, maxMirrorMask = 0x0F)
}

/** 旧按键回退（2026-10-19 固件前的 ROM）。 */
enum class HdmiLegacyKey {
    MODE,
    RESOLUTION
}

// ==================== errno ====================

/**
 * errno 语义的**唯一**分类点。
 *
 * 把「值错误」和「权限错误」区分开是最重要的：前者重试一万次也不会成功，
 * 后者重试或提权才可能成功。分类函数是纯函数，便于 JVM 单测覆盖每个分支。
 */
object HdmiMatrixErrno {
    const val EPERM = 1
    const val ENOENT = 2
    const val EIO = 5
    const val EACCES = 13
    const val EINVAL = 22
    const val ERANGE = 34
    const val EOPNOTSUPP = 95
    const val ETIMEDOUT = 110

    /** 立即重试一次可能成功（写没写进去，或总线偶发超时）。 */
    fun isRetryOnce(errno: Int): Boolean = errno == EIO || errno == ETIMEDOUT

    /** 只有 EPERM 需要先 `power=1`。 */
    fun requiresPowerRecovery(errno: Int): Boolean = errno == EPERM

    fun kindFor(errno: Int): HdmiMatrixErrorKind = when (errno) {
        ENOENT -> HdmiMatrixErrorKind.NOT_AVAILABLE
        EACCES -> HdmiMatrixErrorKind.PERMISSION_DENIED
        EPERM -> HdmiMatrixErrorKind.DEVICE_STANDBY
        EINVAL, ERANGE -> HdmiMatrixErrorKind.INVALID_VALUE
        EOPNOTSUPP -> HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE
        ETIMEDOUT -> HdmiMatrixErrorKind.TIMEOUT
        EIO -> HdmiMatrixErrorKind.IO_FAILURE
        else -> HdmiMatrixErrorKind.UNKNOWN
    }

    /**
     * 从异常文本里尽量解析 errno。
     *
     * 生产路径用 `android.system.Os` 的 [android.system.ErrnoException] 拿到精确值；
     * 这个函数只用于「拿不到 ErrnoException」的兜底（例如 su 子进程的错误输出），
     * 以及 JVM 单测里构造的普通异常。
     */
    fun errnoFromMessage(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val upper = message.uppercase()
        return when {
            "EACCES" in upper || "PERMISSION DENIED" in upper -> EACCES
            "ENOENT" in upper || "NO SUCH FILE" in upper -> ENOENT
            "EPERM" in upper || "OPERATION NOT PERMITTED" in upper -> EPERM
            "EOPNOTSUPP" in upper || "ENOTSUP" in upper || "NOT SUPPORTED" in upper -> EOPNOTSUPP
            "ETIMEDOUT" in upper || "TIMED OUT" in upper || "TIMEOUT" in upper -> ETIMEDOUT
            "ERANGE" in upper -> ERANGE
            "EINVAL" in upper || "INVALID ARGUMENT" in upper -> EINVAL
            "EIO" in upper -> EIO
            else -> null
        }
    }
}

/**
 * 节点 IO 抛出的内部异常；公开 API 在边界处把它翻译成 [HdmiMatrixError]。
 *
 * 用独立异常而不是直接返回 Result，是为了让「读若干节点组成快照」这种
 * 多步逻辑可以用普通 try/catch 表达，不必层层解包。
 */
class MatrixNodeException(
    val errno: Int,
    message: String? = null,
    val node: HdmiMatrixNode? = null,
    val attempts: Int = 1,
    cause: Throwable? = null
) : Exception(message, cause)
