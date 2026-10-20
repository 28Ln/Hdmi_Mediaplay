package com.btf.rk3568_hdmi_mediaplay.hardware

import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HDMI 拼接板 I2C 控制器。
 *
 * ## 设计要点（每一条都对应一个真实故障模式）
 *
 * 1. **所有写操作串行化**（[mutex]）。MCU 一次只处理一条命令，
 *    并发写会让「先写 mode 再写 mirror」的顺序失效 —— mode 写会把 mirror 清零，
 *    交错的 mirror 会落到旧 mode 上。
 * 2. **绝不在主线程阻塞**。一次写可能要几百毫秒到数秒，全部经 [ioDispatcher]。
 * 3. **fusion 不做连续写**。每成功一次就写 MCU flash，滑杆逐帧下发会写坏 flash。
 *    交互路径必须 [stageFusion]（纯内存）→ [commitFusion]（松手时一次）。
 * 4. **errno 决定是否重试**：EINVAL/ERANGE 改值、EPERM 先上电、
 *    EOPNOTSUPP 不重试、EIO/ETIMEDOUT 重试一次、EACCES 500ms×3。
 * 5. **读回即真值**。成功以回读为准，绝不乐观地把「写调用没抛异常」当成生效。
 *
 * 对外只返回 [HdmiMatrixResult]，不抛异常；错误用 [HdmiMatrixError] 表达，
 * 文案留给 `StringResources`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HdmiMatrixController(
    private val io: HdmiMatrixNodeIo = SysfsMatrixNodeIo(),
    private val variant: HdmiBoardVariant = HardwareConfig.HDMI_BOARD_VARIANT,
    private val fallbackPolicy: LegacyFallbackPolicy = if (HardwareConfig.LEGACY_GPIO_FALLBACK) {
        LegacyFallbackPolicy.Enabled()
    } else {
        LegacyFallbackPolicy.Disabled
    },
    private val legacyBridge: LegacyKeyBridge = HdmiGpioLegacyBridge,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 单调时钟（毫秒）。注入是为了让 flash 防抖在 JVM 单测里可确定性地验证。 */
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    /** 读改写共用一把锁：保证「读 mode → 校验 → 写 mirror」不被插队。 */
    private val mutex = Mutex()

    private val _state = MutableStateFlow(HdmiMatrixState())
    val state: StateFlow<HdmiMatrixState> = _state.asStateFlow()

    /** 已 stage 的 fusion 值；用不可变对象 + revision 做无锁预览。 */
    @Volatile
    private var stagedFusion: FusionStage? = null

    private val revisionCounter = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var lastFusionFlashElapsedMs: Long = NO_FUSION_FLASH_YET

    /** 上一次成功读到的快照；失败时随 Failure 返回，便于现场判断「卡在哪一步」。 */
    @Volatile
    private var lastSnapshot: HdmiMatrixSnapshot? = null

    // ==================== 只读探测 ====================

    /**
     * 廉价存活探测：读 version/status/result/cmd_count/rx_state。
     *
     * 不重试（除 EIO/ETIMEDOUT 的一次），失败即返回；
     * 启动阶段要等驱动加载请用 [ensureOnline]。
     */
    suspend fun probe(): HdmiMatrixResult<HdmiMatrixSnapshot> =
        withContext(ioDispatcher) {
            mutex.withLock {
                val result = doReadSnapshot()
                publish(result, markAvailability = true)
                result
            }
        }

    /**
     * 等待 MCU 就绪（version == 2）。
     *
     * 开机时节点可能**短暂不存在**（驱动尚未 probe），这不是永久故障；
     * 但 EACCES 轮询没有意义，立即返回。
     *
     * 每次轮询单独取锁，长等待不会卡住其他写入。
     */
    suspend fun ensureOnline(
        timeoutMs: Long = 20_000L,
        pollMs: Long = 250L
    ): HdmiMatrixResult<Unit> = withContext(ioDispatcher) {
        val deadlineResult = withTimeoutOrNull(timeoutMs) {
            var done: HdmiMatrixResult<Unit>? = null
            while (done == null) {
                val attempt = mutex.withLock { tryReadVersion() }
                when {
                    attempt.error == null && attempt.version == ONLINE_VERSION -> {
                        _state.update { it.copy(availability = HdmiMatrixAvailability.ONLINE) }
                        done = HdmiMatrixResult.Success(Unit)
                    }
                    attempt.error is HdmiMatrixError.PermissionDenied -> {
                        publishError(attempt.error)
                        done = HdmiMatrixResult.Failure(attempt.error)
                    }
                    attempt.error is HdmiMatrixError.NotAvailable -> {
                        // 驱动未加载：启动阶段可能稍后出现，继续轮询
                        _state.update { it.copy(availability = HdmiMatrixAvailability.MISSING) }
                    }
                    attempt.error is HdmiMatrixError.NoDevice -> {
                        // 从机不应答：重试无意义（硬件未接/未上电），立即结束。
                        // 若是熔断导致（传输卡死未返回），还要标明「须重启设备」。
                        val hung = (io as? SysfsMatrixNodeIo)?.isTripped() == true
                        _state.update {
                            it.copy(
                                availability = if (hung) {
                                    HdmiMatrixAvailability.BUS_HUNG
                                } else {
                                    HdmiMatrixAvailability.NO_DEVICE
                                }
                            )
                        }
                        publishError(attempt.error)
                        done = HdmiMatrixResult.Failure(attempt.error)
                    }
                    // version 读到了但不是 2：还在启动，继续等
                }
                if (done == null) delay(pollMs)
            }
            done
        }
        deadlineResult ?: run {
            val error = HdmiMatrixError.NotAvailable(node = HdmiMatrixNode.VERSION)
            publishError(error)
            HdmiMatrixResult.Failure(error)
        }
    }

    /**
     * 读 version 并最多重试 [IS_ONLINE_ATTEMPTS] 次（每次间隔 200ms）。
     *
     * 与 [probe] 的区别：只关心「现在是否在线」，适合 UI 进入页面时的一次性判断。
     */
    suspend fun isOnline(): Boolean = withContext(ioDispatcher) {
        var result = false
        var decided = false
        for (index in 0 until IS_ONLINE_ATTEMPTS) {
            val version = mutex.withLock { tryReadVersion() }
            if (version.error == null && version.version == ONLINE_VERSION) {
                _state.update { it.copy(availability = HdmiMatrixAvailability.ONLINE) }
                result = true
                decided = true
            } else if (version.error is HdmiMatrixError.PermissionDenied) {
                publishError(version.error)
                decided = true
            }
            if (decided) break
            if (index < IS_ONLINE_ATTEMPTS - 1) delay(IS_ONLINE_POLL_MS)
        }
        result
    }

    /** 一次 version 读；失败已翻译成错误，不抛异常。 */
    private suspend fun tryReadVersion(): VersionAttempt = try {
        VersionAttempt(readNode(HdmiMatrixNode.VERSION), null)
    } catch (e: MatrixNodeException) {
        VersionAttempt(null, errorFor(e))
    } catch (e: Exception) {
        VersionAttempt(null, HdmiMatrixError.Unknown(null, e.message, HdmiMatrixNode.VERSION, e))
    }

    private data class VersionAttempt(val version: Int?, val error: HdmiMatrixError?)

    /**
     * 读完整只读面板。
     *
     * version/status 是必需节点；result/cmd_count/rx_state 在 ROM 省略时给 null，
     * 不把「少一个可选节点」当成设备故障。
     */
    suspend fun readSnapshot(): HdmiMatrixResult<HdmiMatrixSnapshot> =
        withContext(ioDispatcher) {
            mutex.withLock {
                val result = doReadSnapshot()
                publish(result, markAvailability = true)
                result
            }
        }

    /** 读回全部可写节点的**已生效值**（不做任何缓存/乐观更新）。 */
    suspend fun readApplied(): HdmiMatrixResult<HdmiMatrixApplied> =
        withContext(ioDispatcher) {
            mutex.withLock {
                val result = doReadApplied()
                if (result is HdmiMatrixResult.Failure) publishError(result.error)
                result
            }
        }

    // ==================== 单节点写 ====================

    /**
     * 待机解锁：写 power。
     *
     * 其他写操作遇到 EPERM 时会自动调用一次（见 [doWriteVerified]），
     * 但调用方也可以显式先上电。
     */
    suspend fun setPower(on: Boolean): HdmiMatrixResult<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val result = doWriteVerified(
                node = HdmiMatrixNode.POWER,
                value = if (on) 1 else 0,
                allowPowerRecovery = false
            )
            publish(result)
            result
        }
    }

    /**
     * 应用布局：**mode 先写，mirror 后写**。
     *
     * mode 写会把 mirror 清零，因此即使 mirror 值没变也必须重写。
     * 软件布局（SINGLE/竖屏/同画面/PIP）没有硬件模式，直接返回
     * [HdmiMatrixError.NoHardwareMode]，**零次 IO**，由 UI 保留软件布局。
     */
    suspend fun applyLayout(
        layoutMode: LayoutMode,
        mirrorMask: Int = 0
    ): HdmiMatrixResult<Unit> {
        val hardwareMode = HdmiMatrixModeMapping.hardwareModeFor(layoutMode)
            ?: return failNoHardwareMode(layoutMode)

        val mirrorRange = HdmiMatrixModeMapping.mirrorMaskError(mirrorMask, variant)
        if (mirrorRange != null) {
            return failInvalidValue("mirror", mirrorMask, mirrorRange)
        }
        // 非零镜像要求当前（即将写入的）模式达到板型门槛，提前拒绝，零次 IO
        if (mirrorMask != 0 && !HdmiMatrixModeMapping.mirrorAllowedForMode(hardwareMode, variant)) {
            return failUnsupported("mirror", hardwareMode)
        }

        return withContext(ioDispatcher) {
            mutex.withLock {
                val result = doApplyLayout(hardwareMode, mirrorMask)
                publish(result)
                result
            }
        }
    }

    /**
     * 直接写硬件模式（诊断 / 无布局模型的调用方）。
     *
     * 注意副作用：写 mode 会清空 mirror。需要镜像请用 [applyLayout]。
     */
    suspend fun setMode(hardwareMode: Int): HdmiMatrixResult<Unit> {
        val range = HdmiMatrixNode.MODE.allowedRange
        if (range != null && hardwareMode !in range) {
            return failInvalidValue("mode", hardwareMode, range)
        }
        return withContext(ioDispatcher) {
            mutex.withLock {
                val result = doWriteVerified(HdmiMatrixNode.MODE, hardwareMode)
                publish(result)
                result
            }
        }
    }

    /**
     * 只写 mirror。
     *
     * 先读当前 mode 并校验门槛 —— mirror 的合法性取决于 mode，
     * 但本函数**不会**替调用方改 mode（静默改布局是最难排查的一类问题）。
     */
    suspend fun setMirror(mirrorMask: Int): HdmiMatrixResult<Unit> {
        val mirrorRange = HdmiMatrixModeMapping.mirrorMaskError(mirrorMask, variant)
        if (mirrorRange != null) {
            return failInvalidValue("mirror", mirrorMask, mirrorRange)
        }
        return withContext(ioDispatcher) {
            mutex.withLock {
                val result = doSetMirror(mirrorMask)
                publish(result)
                result
            }
        }
    }

    /** 分辨率只接受枚举，调用方无法误传裸 int。 */
    suspend fun setResolution(resolution: HdmiResolution): HdmiMatrixResult<Unit> =
        withContext(ioDispatcher) {
            mutex.withLock {
                val result = doWriteVerified(
                    HdmiMatrixNode.RESOLUTION,
                    resolution.sysfsValue
                )
                publish(result)
                result
            }
        }

    /** 硬件旋转 0..3；与竖屏布局的 Android 层旋转是两件事。 */
    suspend fun setRotate(quarterTurns: Int): HdmiMatrixResult<Unit> {
        val range = HdmiMatrixNode.ROTATE.allowedRange
        if (range != null && quarterTurns !in range) {
            return failInvalidValue("rotate", quarterTurns, range)
        }
        return withContext(ioDispatcher) {
            mutex.withLock {
                val result = doWriteVerified(HdmiMatrixNode.ROTATE, quarterTurns)
                publish(result)
                result
            }
        }
    }

    suspend fun setMute(mute: Boolean): HdmiMatrixResult<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val result = doWriteVerified(HdmiMatrixNode.MUTE, if (mute) 1 else 0)
            publish(result)
            result
        }
    }

    // ==================== fusion（flash 写保护） ====================

    /**
     * 滑杆拖动帧：只更新内存预览，**零 IO**。
     *
     * 这是「绝不连续写 hfusion/vfusion」的强制执行点：
     * 唯一会写 flash 的路径是 [commitFusion] / [setFusion]。
     */
    fun stageFusion(horizontal: Int, vertical: Int): HdmiMatrixResult<FusionStage> {
        val hRange = HdmiMatrixNode.HFUSION.allowedRange
        if (hRange != null && horizontal !in hRange) {
            return HdmiMatrixResult.Failure(
                HdmiMatrixError.InvalidValue("hfusion", horizontal, hRange),
                lastSnapshot
            )
        }
        val vRange = HdmiMatrixNode.VFUSION.allowedRange
        if (vRange != null && vertical !in vRange) {
            return HdmiMatrixResult.Failure(
                HdmiMatrixError.InvalidValue("vfusion", vertical, vRange),
                lastSnapshot
            )
        }
        val stage = FusionStage(horizontal, vertical, revisionCounter.incrementAndGet())
        stagedFusion = stage
        _state.update { it.copy(pendingFusion = stage) }
        return HdmiMatrixResult.Success(stage)
    }

    /**
     * 松手提交：等待 ≥300ms flash 预算，合并最新 staged 值后写一次。
     *
     * 等待期间若有更新的 stage（revision 变化），取最新值重新计时 ——
     * 保证「一次松手最多一次 flash」，且写的是用户最终停留的位置。
     */
    suspend fun commitFusion(): HdmiMatrixResult<Unit> = withContext(ioDispatcher) {
        // 注意：debounce 的等待**不持锁**。
        // 若持锁 delay(300ms)，同进程内其他设置写入（电源/分辨率）会被无谓地卡 300ms。
        // 释放锁期间可能有新的 stage 抵达；下面的 revision 检查会把它们合并，
        // 且真正写 flash 前会重新取锁，写入本身仍然是串行的。
        val initial = stagedFusion ?: return@withContext HdmiMatrixResult.Success(Unit)

        var target = initial
        while (true) {
            val wait = fusionWaitMs()
            if (wait > 0) delay(wait)

            val latest = stagedFusion ?: return@withContext HdmiMatrixResult.Success(Unit)
            if (latest.revision != target.revision) {
                target = latest
                continue
            }
            break
        }

        val result = mutex.withLock { doCommitFusion(target) }
        publish(result)
        if (result.isSuccess && stagedFusion?.revision == target.revision) {
            // 只有真正写成功（或本就相等）才清除预览；失败保留给 UI 显示
            stagedFusion = null
            _state.update { it.copy(pendingFusion = null) }
        }
        result
    }

    /**
     * 一次性 fusion 写（开机恢复 / U 盘配置 / 远控）。
     *
     * 与 [commitFusion] 共用 300ms flash 预算：这里的等待是**节流**而不是失败，
     * 连续调用不会连写 flash。滑杆代码不得使用本函数。
     */
    suspend fun setFusion(horizontal: Int, vertical: Int): HdmiMatrixResult<Unit> {
        val hRange = HdmiMatrixNode.HFUSION.allowedRange
        if (hRange != null && horizontal !in hRange) {
            return failInvalidValue("hfusion", horizontal, hRange)
        }
        val vRange = HdmiMatrixNode.VFUSION.allowedRange
        if (vRange != null && vertical !in vRange) {
            return failInvalidValue("vfusion", vertical, vRange)
        }
        return withContext(ioDispatcher) {
            mutex.withLock {
                val wait = fusionWaitMs()
                if (wait > 0) delay(wait)
                val result = doWriteFusion(horizontal, vertical)
                publish(result)
                result
            }
        }
    }

    /** 距离下一次允许写 flash 还需等待的毫秒数（<=0 表示可以立即写）。 */
    private fun fusionWaitMs(): Long {
        if (lastFusionFlashElapsedMs == NO_FUSION_FLASH_YET) return 0L
        return HardwareConfig.FUSION_FLASH_DEBOUNCE_MS -
            (elapsedMs() - lastFusionFlashElapsedMs)
    }

    /** 提交 staged 值；相等则跳过（避免无意义的 flash 磨损）。 */
    private suspend fun doCommitFusion(stage: FusionStage): HdmiMatrixResult<Unit> {
        val applied = doReadApplied()
        if (applied is HdmiMatrixResult.Success) {
            val a = applied.value
            if (a.hfusion == stage.hfusion && a.vfusion == stage.vfusion) {
                return HdmiMatrixResult.Success(Unit)
            }
        } else if (applied.errorOrNull() !is HdmiMatrixError.NotAvailable) {
            // 读不到就无法判断是否需要写；除节点缺失外不冒险盲写
            return HdmiMatrixResult.Failure(applied.errorOrNull()!!, lastSnapshot)
        }
        return doWriteFusion(stage.hfusion, stage.vfusion)
    }

    /** hfusion 然后 vfusion；两次都成功后才更新 flash 时间戳。 */
    private suspend fun doWriteFusion(horizontal: Int, vertical: Int): HdmiMatrixResult<Unit> {
        doWriteVerified(HdmiMatrixNode.HFUSION, horizontal, field = "hfusion").let {
            if (it is HdmiMatrixResult.Failure) return it
        }
        doWriteVerified(HdmiMatrixNode.VFUSION, vertical, field = "vfusion").let {
            if (it is HdmiMatrixResult.Failure) return it
        }
        val now = elapsedMs()
        lastFusionFlashElapsedMs = now
        _state.update { it.copy(lastFusionFlashElapsedMs = now) }
        return HdmiMatrixResult.Success(Unit)
    }

    // ==================== 批量 ====================

    /**
     * 有序部分批量（开机 / U 盘配置恢复）。
     *
     * 固定顺序 power → mode → mirror → resolution → rotate → fusion → mute。
     * 先做客户端预校验，避免「前三项已生效、第四项才发现越界」。
     *
     * 硬件没有事务：中途失败时前面的步骤保持生效。每一步都是幂等的，
     * 因此调用方可以安全地整体重放。
     */
    suspend fun applySettings(settings: HdmiMatrixSettings): HdmiMatrixResult<Unit> {
        // ---- 预校验（零 IO） ----
        settings.mode?.let { mode ->
            val range = HdmiMatrixNode.MODE.allowedRange
            if (range != null && mode !in range) return failInvalidValue("mode", mode, range)
        }
        settings.mirror?.let { mask ->
            val range = HdmiMatrixModeMapping.mirrorMaskError(mask, variant)
            if (range != null) return failInvalidValue("mirror", mask, range)
        }
        settings.rotate?.let { turns ->
            val range = HdmiMatrixNode.ROTATE.allowedRange
            if (range != null && turns !in range) return failInvalidValue("rotate", turns, range)
        }
        settings.hfusion?.let { h ->
            val range = HdmiMatrixNode.HFUSION.allowedRange
            if (range != null && h !in range) return failInvalidValue("hfusion", h, range)
        }
        settings.vfusion?.let { v ->
            val range = HdmiMatrixNode.VFUSION.allowedRange
            if (range != null && v !in range) return failInvalidValue("vfusion", v, range)
        }

        return withContext(ioDispatcher) {
            mutex.withLock {
                val result = doApplySettings(settings)
                publish(result)
                result
            }
        }
    }

    private suspend fun doApplySettings(s: HdmiMatrixSettings): HdmiMatrixResult<Unit> {
        var currentMode: Int? = s.mode

        s.power?.let { on ->
            val r = doWriteVerified(
                HdmiMatrixNode.POWER,
                if (on) 1 else 0,
                allowPowerRecovery = false
            )
            if (r is HdmiMatrixResult.Failure) return r
        }

        s.mode?.let { mode ->
            val r = doWriteVerified(HdmiMatrixNode.MODE, mode)
            if (r is HdmiMatrixResult.Failure) return r
        }

        s.mirror?.let { mask ->
            // mirror 的合法性依赖 mode：未显式给出时读回当前 mode
            val mode = currentMode ?: run {
                val read = try {
                    readNode(HdmiMatrixNode.MODE)
                } catch (e: MatrixNodeException) {
                    return failureFor(e)
                } catch (e: Exception) {
                    return failureUnknown(e, HdmiMatrixNode.MODE)
                }
                read
            }
            currentMode = mode
            if (!HdmiMatrixModeMapping.mirrorAllowedForMode(mode, variant)) {
                return HdmiMatrixResult.Failure(
                    HdmiMatrixError.UnsupportedInMode("mirror", mode, HdmiMatrixNode.MIRROR),
                    lastSnapshot
                )
            }
            // 同 applyLayout：本次若显式写了 mode，固件会清空镜像，
            // 回读不可信，必须强制重写。
            val r = doWriteVerified(
                HdmiMatrixNode.MIRROR, mask,
                field = "mirror", forceWrite = s.mode != null
            )
            if (r is HdmiMatrixResult.Failure) return r
        }

        s.resolution?.let {
            val r = doWriteVerified(HdmiMatrixNode.RESOLUTION, it.sysfsValue, field = "resolution")
            if (r is HdmiMatrixResult.Failure) return r
        }

        s.rotate?.let {
            val r = doWriteVerified(HdmiMatrixNode.ROTATE, it, field = "rotate")
            if (r is HdmiMatrixResult.Failure) return r
        }

        // hfusion/vfusion 合并为一个 flash 序列：只更新实际给出的那一边
        if (s.hfusion != null || s.vfusion != null) {
            val wait = fusionWaitMs()
            if (wait > 0) delay(wait)

            s.hfusion?.let {
                val r = doWriteVerified(HdmiMatrixNode.HFUSION, it, field = "hfusion")
                if (r is HdmiMatrixResult.Failure) return r
            }
            s.vfusion?.let {
                val r = doWriteVerified(HdmiMatrixNode.VFUSION, it, field = "vfusion")
                if (r is HdmiMatrixResult.Failure) return r
            }
            val now = elapsedMs()
            lastFusionFlashElapsedMs = now
            _state.update { it.copy(lastFusionFlashElapsedMs = now) }
        }

        s.mute?.let {
            val r = doWriteVerified(HdmiMatrixNode.MUTE, if (it) 1 else 0, field = "mute")
            if (r is HdmiMatrixResult.Failure) return r
        }

        return HdmiMatrixResult.Success(Unit)
    }

    // ==================== 等待空闲 ====================

    /**
     * 轮询 status.bit0（BUSY）直到清零。
     *
     * 在延迟敏感序列（applyLayout 紧跟 setMode）前调用，
     * 避免把新命令排到 MCU 还在执行的旧命令后面。
     */
    suspend fun waitUntilIdle(
        timeoutMs: Long = 2_000L,
        pollMs: Long = 100L
    ): HdmiMatrixResult<Unit> = withContext(ioDispatcher) {
        val completed: HdmiMatrixResult<Unit>? = withTimeoutOrNull(timeoutMs) {
            var outcome: HdmiMatrixResult<Unit>? = null
            while (outcome == null) {
                val raw = try {
                    mutex.withLock { readNode(HdmiMatrixNode.STATUS) }
                } catch (e: MatrixNodeException) {
                    outcome = failureFor(e)
                    break
                } catch (e: Exception) {
                    outcome = failureUnknown(e, HdmiMatrixNode.STATUS)
                    break
                }
                if (!HdmiMatrixStatus(raw).busy) {
                    outcome = HdmiMatrixResult.Success(Unit)
                    break
                }
                delay(pollMs)
            }
            outcome
        }
        completed ?: run {
            val error = HdmiMatrixError.Timeout(HdmiMatrixNode.STATUS)
            publishError(error)
            HdmiMatrixResult.Failure(error)
        }
    }

    // ==================== 旧路径（过渡期） ====================

    /**
     * 底部栏「切换模式」的过渡实现：硬件模式 +1（模 8）。
     *
     * @deprecated 新 UI 应调用 [applyLayout]（按布局映射，不会切到 App 不展示的 1X4/4X1）。
     * 这里保留是为了在迁移期间保持按钮行为。
     */
    @Deprecated("Fallback window only; use applyLayout instead")
    suspend fun cycleMode(): HdmiMatrixResult<Int> {
        val result: HdmiMatrixResult<Int> = withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val current = readNode(HdmiMatrixNode.MODE)
                    val next = (current + 1) % 8
                    when (val write = doWriteVerified(HdmiMatrixNode.MODE, next)) {
                        is HdmiMatrixResult.Success -> HdmiMatrixResult.Success(next)
                        is HdmiMatrixResult.Failure -> write
                    }
                } catch (e: MatrixNodeException) {
                    // 只有「节点不存在」才允许回退；其他 errno 如实上报
                    if (e.errno == HdmiMatrixErrno.ENOENT) {
                        legacyFallback(HdmiLegacyKey.MODE) ?: failureFor(e)
                    } else {
                        failureFor(e)
                    }
                } catch (e: Exception) {
                    failureUnknown(e, HdmiMatrixNode.MODE)
                }
            }
        }
        publish(result)
        return result
    }

    /**
     * 紧急兼容路径：脉冲旧 `hdmi_b2` / `hdmi_b3` 按键。
     *
     * 仅在 [LegacyFallbackPolicy] 允许**且** I2C 主节点确实不存在时执行。
     * 成功只代表脉冲写出去了，不代表矩阵真的切换 —— 调用方应在新节点可用后读回。
     *
     * @deprecated 与 [HdmiGpioController] 一同在新固件全面部署后删除。
     */
    @Deprecated("Fallback window only; remove with HdmiGpioController")
    suspend fun pressLegacyKey(key: HdmiLegacyKey): HdmiMatrixResult<Unit> {
        // I2C 主节点存在 => 走主路径，绝不回退（回退会掩盖真实驱动错误）
        if (io.isPresent(HdmiMatrixNode.VERSION)) {
            val error = HdmiMatrixError.NotAvailable(HdmiMatrixNode.VERSION)
            publishError(error)
            return HdmiMatrixResult.Failure(error, lastSnapshot)
        }
        val policy = fallbackPolicy
        if (policy !is LegacyFallbackPolicy.Enabled) {
            val error = HdmiMatrixError.NotAvailable(HdmiMatrixNode.VERSION)
            publishError(error)
            return HdmiMatrixResult.Failure(error, lastSnapshot)
        }
        val ok = withContext(ioDispatcher) { legacyBridge.press(key) }
        return if (ok) {
            HdmiMatrixResult.Success(Unit, lastSnapshot)
        } else {
            val error = HdmiMatrixError.PermissionDenied(node = HdmiMatrixNode.VERSION)
            publishError(error)
            HdmiMatrixResult.Failure(error, lastSnapshot)
        }
    }

    /** 回退是异步 IO（旧实现内部 sleep 200ms+），返回 null 表示不可用。 */
    @Suppress("DEPRECATION")
    private suspend fun legacyFallback(key: HdmiLegacyKey): HdmiMatrixResult<Int>? {
        if (fallbackPolicy !is LegacyFallbackPolicy.Enabled) return null
        val ok = legacyBridge.press(key)
        if (!ok) return null
        // 旧路径无法直接得知切换后的 mode；读一次，读不到就返回 -1
        val newMode = try {
            readNode(HdmiMatrixNode.MODE)
        } catch (e: Exception) {
            -1
        }
        return HdmiMatrixResult.Success(newMode)
    }

    // ==================== 内部：读 ====================

    private suspend fun doReadSnapshot(): HdmiMatrixResult<HdmiMatrixSnapshot> {
        val version: Int
        val statusRaw: Int
        try {
            version = readNode(HdmiMatrixNode.VERSION)
            statusRaw = readNode(HdmiMatrixNode.STATUS)
        } catch (e: MatrixNodeException) {
            return failureFor(e)
        } catch (e: Exception) {
            return failureUnknown(e, HdmiMatrixNode.VERSION)
        }

        val lastResult = readOptionalInt(HdmiMatrixNode.RESULT)
        val cmdCount = readOptionalLong(HdmiMatrixNode.CMD_COUNT)
        val rxState = readOptionalInt(HdmiMatrixNode.RX_STATE)

        val snapshot = HdmiMatrixSnapshot(
            online = version == ONLINE_VERSION,
            version = version,
            status = HdmiMatrixStatus(statusRaw),
            lastResult = lastResult,
            cmdCount = cmdCount,
            rxState = rxState,
            applied = null,
            readAtElapsedMs = elapsedMs()
        )
        lastSnapshot = snapshot
        return HdmiMatrixResult.Success(snapshot, snapshot)
    }

    private suspend fun doReadApplied(): HdmiMatrixResult<HdmiMatrixApplied> {
        val applied = try {
            val mode = readNode(HdmiMatrixNode.MODE)
            val resolutionRaw = readNode(HdmiMatrixNode.RESOLUTION)
            val resolution = HdmiResolution.fromSysfsValue(resolutionRaw)
                ?: throw MatrixNodeException(
                    errno = HdmiMatrixErrno.EINVAL,
                    message = "Unsupported resolution value: $resolutionRaw",
                    node = HdmiMatrixNode.RESOLUTION
                )
            HdmiMatrixApplied(
                mode = mode,
                resolution = resolution,
                mirror = readNode(HdmiMatrixNode.MIRROR),
                rotate = readNode(HdmiMatrixNode.ROTATE),
                hfusion = readNode(HdmiMatrixNode.HFUSION),
                vfusion = readNode(HdmiMatrixNode.VFUSION),
                mute = readNode(HdmiMatrixNode.MUTE) != 0,
                power = readNode(HdmiMatrixNode.POWER) != 0
            )
        } catch (e: MatrixNodeException) {
            return failureFor(e)
        } catch (e: Exception) {
            return failureUnknown(e, null)
        }
        return HdmiMatrixResult.Success(applied, lastSnapshot)
    }

    /** 可选节点：缺失或不可读都给 null —— 少一个可选面板字段不构成设备故障。 */
    private suspend fun readOptionalInt(node: HdmiMatrixNode): Int? = try {
        readNode(node)
    } catch (e: MatrixNodeException) {
        Log.d(TAG, "Optional node $node unavailable (errno=${e.errno})")
        null
    } catch (e: Exception) {
        Log.d(TAG, "Optional node $node unreadable: ${e.message}")
        null
    }

    private suspend fun readOptionalLong(node: HdmiMatrixNode): Long? = try {
        readLongNode(node)
    } catch (e: MatrixNodeException) {
        Log.d(TAG, "Optional node $node unavailable (errno=${e.errno})")
        null
    } catch (e: Exception) {
        Log.d(TAG, "Optional node $node unreadable: ${e.message}")
        null
    }

    private suspend fun readNode(node: HdmiMatrixNode): Int {
        var retried = false
        while (true) {
            try {
                return io.read(node)
            } catch (e: MatrixNodeException) {
                if (!retried && HdmiMatrixErrno.isRetryOnce(e.errno)) {
                    retried = true
                    continue
                }
                throw e
            }
        }
    }

    private suspend fun readLongNode(node: HdmiMatrixNode): Long {
        var retried = false
        while (true) {
            try {
                return io.readLong(node)
            } catch (e: MatrixNodeException) {
                if (!retried && HdmiMatrixErrno.isRetryOnce(e.errno)) {
                    retried = true
                    continue
                }
                throw e
            }
        }
    }

    // ==================== 内部：写 ====================

    /**
     * 写一个节点并读回校验。
     *
     * 重试策略（**调用方持有 [mutex]**）：
     * - 客户端先做范围校验 → InvalidValue，零次 IO
     * - 回读等于目标值 → 直接成功（幂等，避免磨损）
     * - EACCES → 500ms 间隔最多 3 次（su 提权在 IO 层完成）
     * - EIO / ETIMEDOUT → 原样重试一次
     * - EPERM → 写 power=1 一次，再重试原写一次；仍 EPERM 则 DeviceStandby
     * - EOPNOTSUPP / EINVAL / ERANGE / ENOENT → 不重试
     */
    private suspend fun doWriteVerified(
        node: HdmiMatrixNode,
        value: Int,
        field: String = node.relativePath,
        allowPowerRecovery: Boolean = true,
        /**
         * 跳过「回读一致就不写」的幂等优化，强制下发一次。
         *
         * 只有一种情况需要：[doApplyLayout] 刚写完 mode —— 固件规定「写 mode
         * 会清除所有镜像」，因此即使回读到的 mirror 仍是旧值（例如 0），
         * 也不能据此认为目标状态已达成。必须显式重写 mirror，
         * 否则 TX 镜像在切模式后会静默丢失。
         */
        forceWrite: Boolean = false
    ): HdmiMatrixResult<Unit> {
        val range = node.allowedRange
        if (range != null && value !in range) {
            return failInvalidValue(field, value, range)
        }

        // 幂等：回读一致就不写。读失败只在 ENOENT 时中止，其余继续尝试写。
        if (!forceWrite && node.writable && node != HdmiMatrixNode.KEY) {
            try {
                if (readNode(node) == value) {
                    return HdmiMatrixResult.Success(Unit, lastSnapshot)
                }
            } catch (e: MatrixNodeException) {
                if (e.errno == HdmiMatrixErrno.ENOENT) return failureFor(e, field, value)
                // EACCES / EIO：写操作自身的重试策略更完整，交给下面
            } catch (e: Exception) {
                // 忽略回读异常，继续写
            }
        }

        var eaccesAttempts = 0
        var ioRetries = 0
        var powerRecovered = false

        while (true) {
            try {
                io.write(node, value)
                break
            } catch (e: MatrixNodeException) {
                when {
                    e.errno == HdmiMatrixErrno.EACCES &&
                        eaccesAttempts + 1 < HardwareConfig.EACCES_MAX_ATTEMPTS -> {
                        eaccesAttempts++
                        Log.w(TAG, "EACCES on $node=$value, attempt ${eaccesAttempts + 1} in " +
                            "${HardwareConfig.EACCES_RETRY_DELAY_MS}ms")
                        delay(HardwareConfig.EACCES_RETRY_DELAY_MS)
                    }

                    HdmiMatrixErrno.isRetryOnce(e.errno) && ioRetries < 1 -> {
                        ioRetries++
                        Log.w(TAG, "Transient errno ${e.errno} on $node=$value, retrying once")
                    }

                    e.errno == HdmiMatrixErrno.EPERM && allowPowerRecovery && !powerRecovered -> {
                        powerRecovered = true
                        Log.i(TAG, "MCU standby (EPERM), powering on before retrying $node=$value")
                        val powered = doWriteVerified(
                            node = HdmiMatrixNode.POWER,
                            value = 1,
                            allowPowerRecovery = false
                        )
                        if (powered is HdmiMatrixResult.Failure) return powered
                        // 给 MCU 一点启动时间，再读一次 version 确认（失败不阻断重试）
                        runCatching { readNode(HdmiMatrixNode.VERSION) }
                    }

                    else -> {
                        val error = errorFor(e, field, value).let {
                            if (e.errno == HdmiMatrixErrno.EACCES && it is HdmiMatrixError.PermissionDenied) {
                                it.copy(attempts = eaccesAttempts + 1)
                            } else {
                                it
                            }
                        }
                        return HdmiMatrixResult.Failure(error, lastSnapshot)
                    }
                }
            } catch (e: Exception) {
                return failureUnknown(e, node)
            }
        }

        // 读回校验：写调用返回不等于真的生效
        if (node.writable && node != HdmiMatrixNode.KEY) {
            val actual = try {
                readNode(node)
            } catch (e: MatrixNodeException) {
                return failureFor(e, field, value)
            } catch (e: Exception) {
                return failureUnknown(e, node)
            }
            if (actual != value) {
                val error = HdmiMatrixError.IoFailure(node)
                Log.e(TAG, "Read-back mismatch on $node: wrote $value, read $actual")
                return HdmiMatrixResult.Failure(error, lastSnapshot)
            }
        }
        return HdmiMatrixResult.Success(Unit, lastSnapshot)
    }

    /** applyLayout 的内部实现；**调用方持有锁**。 */
    private suspend fun doApplyLayout(
        hardwareMode: Int,
        mirrorMask: Int
    ): HdmiMatrixResult<Unit> {
        val currentMode: Int = try {
            readNode(HdmiMatrixNode.MODE)
        } catch (e: MatrixNodeException) {
            return failureFor(e)
        } catch (e: Exception) {
            return failureUnknown(e, HdmiMatrixNode.MODE)
        }

        val modeChanged = currentMode != hardwareMode
        if (modeChanged) {
            // 先写 mode（该写会把 mirror 清零）。必须走 doWriteVerified，
            // 才能获得 EPERM 上电恢复 / EACCES 重试 / 读回校验。
            val modeResult = doWriteVerified(HdmiMatrixNode.MODE, hardwareMode, field = "mode")
            if (modeResult is HdmiMatrixResult.Failure) return modeResult
        }

        // mode 变更后 mirror **必然被固件清零**，因此即使回读仍是 0 也必须重写：
        // 回读值在这里不可信（它反映的是写 mode 之前的状态）。
        // 未变更时才允许幂等跳过。
        val mirrorResult = doWriteVerified(
            HdmiMatrixNode.MIRROR, mirrorMask,
            field = "mirror", forceWrite = modeChanged
        )
        if (mirrorResult is HdmiMatrixResult.Failure) return mirrorResult
        return HdmiMatrixResult.Success(Unit, lastSnapshot)
    }

    /** setMirror 的内部实现；**调用方持有锁**。 */
    private suspend fun doSetMirror(mirrorMask: Int): HdmiMatrixResult<Unit> {
        val mode = try {
            readNode(HdmiMatrixNode.MODE)
        } catch (e: MatrixNodeException) {
            return failureFor(e)
        } catch (e: Exception) {
            return failureUnknown(e, HdmiMatrixNode.MODE)
        }
        if (!HdmiMatrixModeMapping.mirrorAllowedForMode(mode, variant)) {
            return HdmiMatrixResult.Failure(
                HdmiMatrixError.UnsupportedInMode("mirror", mode, HdmiMatrixNode.MIRROR),
                lastSnapshot
            )
        }
        return doWriteVerified(HdmiMatrixNode.MIRROR, mirrorMask, field = "mirror")
    }

    // ==================== 错误翻译 ====================

    private fun failureFor(
        e: MatrixNodeException,
        field: String? = null,
        value: Int? = null
    ): HdmiMatrixResult.Failure {
        val error = errorFor(e, field, value)
        return HdmiMatrixResult.Failure(error, lastSnapshot)
    }

    /**
     * errno → 结构化错误。
     *
     * 这里是「错误码语义表」在代码中的唯一落点，UI 只根据 [HdmiMatrixErrorKind] 取文案。
     */
    private fun errorFor(
        e: MatrixNodeException,
        field: String? = null,
        value: Int? = null
    ): HdmiMatrixError {
        val node = e.node
        return when (e.errno) {
            HdmiMatrixErrno.ENOENT -> HdmiMatrixError.NotAvailable(node, e)
            // 节点在、从机不应答 → 硬件问题，与「驱动没加载」必须分开
            HdmiMatrixErrno.ENODEV -> HdmiMatrixError.NoDevice(node, e)
            HdmiMatrixErrno.EACCES -> HdmiMatrixError.PermissionDenied(
                attempts = e.attempts,
                node = node,
                cause = e
            )
            HdmiMatrixErrno.EPERM -> HdmiMatrixError.DeviceStandby(node, e)
            HdmiMatrixErrno.EINVAL, HdmiMatrixErrno.ERANGE -> HdmiMatrixError.InvalidValue(
                field = field ?: node?.relativePath ?: "node",
                value = value ?: 0,
                allowedRange = node?.allowedRange,
                errno = e.errno,
                node = node,
                cause = e
            )
            HdmiMatrixErrno.EOPNOTSUPP -> HdmiMatrixError.UnsupportedInMode(
                field = field ?: node?.relativePath ?: "node",
                hardwareMode = null,
                node = node,
                cause = e
            )
            HdmiMatrixErrno.ETIMEDOUT -> HdmiMatrixError.Timeout(node, e)
            HdmiMatrixErrno.EIO -> HdmiMatrixError.IoFailure(node, e)
            else -> HdmiMatrixError.Unknown(e.errno, e.message, node, e)
        }
    }

    private fun failInvalidValue(field: String, value: Int, range: IntRange): HdmiMatrixResult.Failure {
        val error = HdmiMatrixError.InvalidValue(field, value, range)
        Log.w(TAG, "Rejected $field=$value, allowed $range")
        return HdmiMatrixResult.Failure(error, lastSnapshot)
    }

    private fun failUnsupported(field: String, hardwareMode: Int): HdmiMatrixResult.Failure {
        val error = HdmiMatrixError.UnsupportedInMode(field, hardwareMode)
        Log.w(TAG, "Rejected $field for hardware mode $hardwareMode (below ${variant.minHardwareModeForMirror})")
        return HdmiMatrixResult.Failure(error, lastSnapshot)
    }

    private fun failNoHardwareMode(layoutMode: LayoutMode): HdmiMatrixResult.Failure =
        HdmiMatrixResult.Failure(
            HdmiMatrixError.NoHardwareMode(layoutMode),
            lastSnapshot
        )

    private fun failureUnknown(e: Exception, node: HdmiMatrixNode?): HdmiMatrixResult.Failure {
        Log.e(TAG, "Unexpected failure on ${node ?: "controller"}: ${e.message}")
        return HdmiMatrixResult.Failure(
            HdmiMatrixError.Unknown(null, e.message, node, e),
            lastSnapshot
        )
    }

    // ==================== 状态发布 ====================

    /**
     * 把结果同步到 [state]，让 Compose 无需在重组时发起 IO。
     *
     * 注意：快照带 [HdmiMatrixSnapshot.readAtElapsedMs]，UI 可据此标注「数据已过期」。
     */
    private fun <T> publish(
        result: HdmiMatrixResult<T>,
        markAvailability: Boolean = false
    ) {
        when (result) {
            is HdmiMatrixResult.Success -> {
                val snapshot = (result.value as? HdmiMatrixSnapshot) ?: result.snapshot
                if (snapshot != null) lastSnapshot = snapshot
                _state.update {
                    it.copy(
                        lastSnapshot = snapshot ?: it.lastSnapshot,
                        lastError = null,
                        availability = when {
                            snapshot?.version == null -> it.availability
                            snapshot.online -> HdmiMatrixAvailability.ONLINE
                            else -> it.availability
                        }
                    )
                }
            }

            is HdmiMatrixResult.Failure -> publishError(result.error)
        }
    }

    private fun publishError(error: HdmiMatrixError) {
        _state.update {
            it.copy(
                lastError = error,
                availability = when (error) {
                    is HdmiMatrixError.NotAvailable -> HdmiMatrixAvailability.MISSING
                    // 熔断后的 ENODEV 含义不同：不是「没接线」，而是「总线卡死、必须重启」
                    is HdmiMatrixError.NoDevice ->
                        if ((io as? SysfsMatrixNodeIo)?.isTripped() == true) {
                            HdmiMatrixAvailability.BUS_HUNG
                        } else {
                            HdmiMatrixAvailability.NO_DEVICE
                        }
                    is HdmiMatrixError.PermissionDenied -> HdmiMatrixAvailability.PERMISSION_DENIED
                    else -> it.availability
                }
            )
        }
    }

    companion object {
        private const val TAG = "HdmiMatrixController"

        /** version == 2 表示 MCU 在线 */
        private const val ONLINE_VERSION = 2

        /** isOnline() 的轮询参数：启动瞬间驱动可能尚未 probe 完成 */
        private const val IS_ONLINE_ATTEMPTS = 5
        private const val IS_ONLINE_POLL_MS = 200L

        /**
         * 「从未写过 flash」的哨兵值。
         *
         * 不能用 0：若时钟恰好从小值开始（单测注入的假时钟、或 nanoTime 回绕后的
         * 相对值），`now - 0 < 300` 会把**第一次**提交也延迟 300ms。
         * 用极小值保证首次永远不等待。
         */
        private const val NO_FUSION_FLASH_YET = Long.MIN_VALUE / 2

        /**
         * 进程内单例。UI / ViewModel 共用同一个 StateFlow 与同一把写锁 ——
         * 每个调用点各建一个实例会让锁形同虚设。
         */
        @Volatile
        private var instance: HdmiMatrixController? = null

        fun getInstance(): HdmiMatrixController =
            instance ?: synchronized(this) {
                instance ?: HdmiMatrixController().also { instance = it }
            }

        /** 测试/诊断用：丢弃单例，下一次 [getInstance] 重新创建。 */
        @Suppress("UNUSED")
        fun resetInstanceForTest() {
            synchronized(this) { instance = null }
        }
    }
}
