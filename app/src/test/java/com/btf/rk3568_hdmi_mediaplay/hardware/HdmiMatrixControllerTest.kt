package com.btf.rk3568_hdmi_mediaplay.hardware

import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制器的 JVM 行为测试。
 *
 * 全部基于 [FakeHdmiMatrixNodeIo]，不触碰 Android 框架 ——
 * 因此这些用例可以在 CI 上稳定运行，不需要真机或模拟器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HdmiMatrixControllerTest {

    /**
     * 时钟直接取虚拟时间 [kotlinx.coroutines.test.TestCoroutineScheduler.currentTime]。
     *
     * 这样 `delay(300)` 与「距上次 flash 多久」共享同一个时间轴，
     * 防抖逻辑才能被确定性验证（真实时钟会让断言依赖机器速度）。
     */
    private fun TestScope.controller(
        io: FakeHdmiMatrixNodeIo,
        variant: HdmiBoardVariant = HdmiBoardVariant.TX4,
        fallback: LegacyFallbackPolicy = LegacyFallbackPolicy.Disabled,
        bridge: LegacyKeyBridge = RecordingBridge()
    ) = HdmiMatrixController(
        io = io,
        variant = variant,
        fallbackPolicy = fallback,
        legacyBridge = bridge,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        elapsedMs = { testScheduler.currentTime }
    )

    private class RecordingBridge : LegacyKeyBridge {
        val pressed = mutableListOf<HdmiLegacyKey>()
        var success = true
        override suspend fun press(key: HdmiLegacyKey): Boolean {
            pressed += key
            return success
        }
    }

    // ==================== errno 分类 ====================

    @Test
    fun `errno classification retries only transient errors`() {
        assertTrue(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.EIO))
        assertTrue(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.ETIMEDOUT))
        // 这些重试没有意义：必须由调用方改值 / 上电 / 等驱动
        assertFalse(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.EINVAL))
        assertFalse(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.ERANGE))
        assertFalse(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.EPERM))
        assertFalse(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.EOPNOTSUPP))
        assertFalse(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.EACCES))
        assertFalse(HdmiMatrixErrno.isRetryOnce(HdmiMatrixErrno.ENOENT))
    }

    @Test
    fun `errno maps to the documented error kinds`() {
        assertEquals(HdmiMatrixErrorKind.NOT_AVAILABLE, HdmiMatrixErrno.kindFor(2))
        assertEquals(HdmiMatrixErrorKind.PERMISSION_DENIED, HdmiMatrixErrno.kindFor(13))
        assertEquals(HdmiMatrixErrorKind.DEVICE_STANDBY, HdmiMatrixErrno.kindFor(1))
        assertEquals(HdmiMatrixErrorKind.INVALID_VALUE, HdmiMatrixErrno.kindFor(22))
        assertEquals(HdmiMatrixErrorKind.INVALID_VALUE, HdmiMatrixErrno.kindFor(34))
        assertEquals(HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE, HdmiMatrixErrno.kindFor(95))
        assertEquals(HdmiMatrixErrorKind.TIMEOUT, HdmiMatrixErrno.kindFor(110))
        assertEquals(HdmiMatrixErrorKind.IO_FAILURE, HdmiMatrixErrno.kindFor(5))
        assertEquals(HdmiMatrixErrorKind.UNKNOWN, HdmiMatrixErrno.kindFor(999))
    }

    @Test
    fun `errno parser understands ioexception messages`() {
        assertEquals(13, HdmiMatrixErrno.errnoFromMessage("open failed: EACCES (Permission denied)"))
        assertEquals(2, HdmiMatrixErrno.errnoFromMessage("open failed: ENOENT (No such file or directory)"))
        assertEquals(1, HdmiMatrixErrno.errnoFromMessage("write failed: EPERM (Operation not permitted)"))
        assertEquals(110, HdmiMatrixErrno.errnoFromMessage("read failed: ETIMEDOUT (Connection timed out)"))
        assertEquals(22, HdmiMatrixErrno.errnoFromMessage("EINVAL"))
        assertEquals(5, HdmiMatrixErrno.errnoFromMessage("EIO"))
        assertNull(HdmiMatrixErrno.errnoFromMessage("something else"))
        assertNull(HdmiMatrixErrno.errnoFromMessage(null))
    }

    @Test
    fun `result helpers expose success and failure symmetrically`() {
        val success: HdmiMatrixResult<Int> = HdmiMatrixResult.Success(7)
        assertTrue(success.isSuccess)
        assertEquals(7, success.getOrNull())
        assertNull(success.errorOrNull())

        val failure: HdmiMatrixResult<Int> =
            HdmiMatrixResult.Failure(HdmiMatrixError.NotAvailable())
        assertTrue(failure.isFailure)
        assertNull(failure.getOrNull())
        assertEquals(HdmiMatrixErrorKind.NOT_AVAILABLE, failure.errorOrNull()?.kind)

        assertEquals(14, success.map { it * 2 }.getOrNull())
    }

    @Test
    fun `error kinds carry the ABI errno values`() {
        assertEquals(2, HdmiMatrixError.NotAvailable().errno)
        assertEquals(13, HdmiMatrixError.PermissionDenied().errno)
        assertEquals(1, HdmiMatrixError.DeviceStandby().errno)
        assertEquals(95, HdmiMatrixError.UnsupportedInMode("fusion").errno)
        assertEquals(110, HdmiMatrixError.Timeout().errno)
        assertEquals(5, HdmiMatrixError.IoFailure().errno)
        // 可重试标记只用于「立即原样重试可能成功」
        assertTrue(HdmiMatrixError.Timeout().retryable)
        assertTrue(HdmiMatrixError.IoFailure().retryable)
        assertFalse(HdmiMatrixError.DeviceStandby().retryable)
        assertFalse(HdmiMatrixError.NotAvailable().retryable)
    }

    @Test
    fun `status bitfield decodes all documented bits and keeps high bits`() {
        val status = HdmiMatrixStatus(0x01 or 0x02 or 0x40 or 0x80 or 0x100)
        assertTrue(status.busy)
        assertTrue(status.power)
        assertTrue(status.error)
        assertTrue(status.overflow)
        assertEquals(0x1C3, status.raw)

        val idle = HdmiMatrixStatus(0x02)
        assertFalse(idle.busy)
        assertTrue(idle.power)
        assertFalse(idle.error)
        assertFalse(idle.overflow)
    }

    // ==================== 探测 / 快照 ====================

    @Test
    fun `probe reports online when version is 2`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val result = controller(io).probe()

        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()!!
        assertTrue(snapshot.online)
        assertEquals(2, snapshot.version)
        assertTrue(snapshot.status.power)
        assertEquals(42L, snapshot.cmdCount)
        assertEquals(1, snapshot.rxState)
    }

    @Test
    fun `probe reports not available when the driver is missing`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.VERSION)
        val ctl = controller(io)

        val result = ctl.probe()

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.NOT_AVAILABLE, result.errorOrNull()?.kind)
        assertEquals(HdmiMatrixAvailability.MISSING, ctl.state.value.availability)
    }

    @Test
    fun `probe retries EIO once then succeeds`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.failRead(HdmiMatrixNode.VERSION, HdmiMatrixErrno.EIO)
        val result = controller(io).probe()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.version)
    }

    @Test
    fun `probe does not retry EACCES`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.stickyError(HdmiMatrixNode.VERSION, HdmiMatrixErrno.EACCES)
        val ctl = controller(io)

        val result = ctl.probe()

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.PERMISSION_DENIED, result.errorOrNull()?.kind)
        assertEquals(HdmiMatrixAvailability.PERMISSION_DENIED, ctl.state.value.availability)
        // 只读一次：EACCES 重试在权限问题上是浪费
        assertEquals(1, io.readLog.count { it == HdmiMatrixNode.VERSION })
    }

    @Test
    fun `optional read-only nodes missing yield null instead of failure`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.CMD_COUNT)
        io.removeNode(HdmiMatrixNode.RX_STATE)
        io.removeNode(HdmiMatrixNode.RESULT)

        val result = controller(io).probe()

        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()!!
        assertNull(snapshot.cmdCount)
        assertNull(snapshot.rxState)
        assertNull(snapshot.lastResult)
    }

    // ==================== 写校验与幂等 ====================

    @Test
    fun `setMode rejects out-of-range values without IO`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val result = controller(io).setMode(8)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.INVALID_VALUE, result.errorOrNull()?.kind)
        val error = result.errorOrNull() as HdmiMatrixError.InvalidValue
        assertEquals(0..7, error.allowedRange)
        assertTrue("越界值不得触达硬件", io.writeLog.isEmpty())
    }

    @Test
    fun `setMode skips the write when read-back already matches`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 5)

        val result = controller(io).setMode(5)

        assertTrue(result.isSuccess)
        assertTrue("值相同应跳过写入", io.writesTo(HdmiMatrixNode.MODE).isEmpty())
    }

    @Test
    fun `setMode writes and verifies by read-back`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)

        val result = controller(io).setMode(3)

        assertTrue(result.isSuccess)
        assertEquals(listOf(3), io.writesTo(HdmiMatrixNode.MODE))
        assertEquals(3, io.valueOf(HdmiMatrixNode.MODE))
    }

    @Test
    fun `setMode retries EIO once`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.failWrite(HdmiMatrixNode.MODE, HdmiMatrixErrno.EIO)

        val result = controller(io).setMode(2)

        assertTrue(result.isSuccess)
        // 第一次 EIO 失败 + 一次重试 = 两次写入尝试（日志只记录成功的）
        assertEquals(listOf(2), io.writesTo(HdmiMatrixNode.MODE))
    }

    @Test
    fun `setMode does not retry EOPNOTSUPP`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.stickyError(HdmiMatrixNode.MODE, HdmiMatrixErrno.EOPNOTSUPP)

        val result = controller(io).setMode(2)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE, result.errorOrNull()?.kind)
    }

    @Test
    fun `EPERM triggers one power-on recovery then retries`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.setValue(HdmiMatrixNode.POWER, 0)
        // 第一次写 mode 返回待机；上电后重试即可成功
        io.failWrite(HdmiMatrixNode.MODE, HdmiMatrixErrno.EPERM)

        val result = controller(io).setMode(2)

        assertTrue(result.isSuccess)
        assertEquals(listOf(1), io.writesTo(HdmiMatrixNode.POWER))
        assertEquals(listOf(2), io.writesTo(HdmiMatrixNode.MODE))
    }

    @Test
    fun `persistent EPERM becomes DeviceStandby without looping`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.stickyError(HdmiMatrixNode.MODE, HdmiMatrixErrno.EPERM)

        val result = controller(io).setMode(2)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.DEVICE_STANDBY, result.errorOrNull()?.kind)
    }

    @Test
    fun `EACCES retries 3 times then reports permission denied`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.stickyError(HdmiMatrixNode.MODE, HdmiMatrixErrno.EACCES)
        val ctl = controller(io)

        val result = ctl.setMode(2)

        assertTrue(result.isFailure)
        val error = result.errorOrNull()
        assertEquals(HdmiMatrixErrorKind.PERMISSION_DENIED, error?.kind)
        assertEquals(HardwareConfig.EACCES_MAX_ATTEMPTS, (error as HdmiMatrixError.PermissionDenied).attempts)
        // 500ms × (3-1) 的退避必须真实发生
        assertEquals(
            HardwareConfig.EACCES_RETRY_DELAY_MS * (HardwareConfig.EACCES_MAX_ATTEMPTS - 1),
            testScheduler.currentTime
        )
    }

    @Test
    fun `resolution enum writes the documented raw value`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.RESOLUTION, 0)

        val result = controller(io).setResolution(HdmiResolution.P2160_30)

        assertTrue(result.isSuccess)
        assertEquals(listOf(2), io.writesTo(HdmiMatrixNode.RESOLUTION))
    }

    // ==================== 布局：mode 先于 mirror ====================

    @Test
    fun `applyLayout writes mode before mirror`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 5)
        io.setValue(HdmiMatrixNode.MIRROR, 0)

        val result = controller(io).applyLayout(LayoutMode.GRID_2X1)

        assertTrue(result.isSuccess)
        val sequence = io.writeLog.map { it.first }
        // 硬件值必须是 2（不是 wallModeNumber 5）
        assertEquals(HdmiMatrixNode.MODE to 2, io.writeLog.first { it.first == HdmiMatrixNode.MODE })
        assertTrue(
            "mode 必须在 mirror 之前",
            sequence.indexOf(HdmiMatrixNode.MODE) < sequence.indexOf(HdmiMatrixNode.MIRROR)
        )
    }

    @Test
    fun `applyLayout rewrites mirror because mode write clears it`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 1)
        io.setValue(HdmiMatrixNode.MIRROR, 3)
        val variant = HdmiBoardVariant.TX3

        val result = controller(io, variant).applyLayout(LayoutMode.GRID_2X2, mirrorMask = 3)

        assertTrue(result.isSuccess)
        assertEquals(5, io.valueOf(HdmiMatrixNode.MODE))
        // 假实现按驱动语义在写 mode 时清 mirror，所以必须被重写回来
        assertEquals(3, io.valueOf(HdmiMatrixNode.MIRROR))
    }

    @Test
    fun `applyLayout rejects mirror below board threshold without IO`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        // GRID_2X1 -> mode 2 < TX4 门槛 5
        val result = controller(io).applyLayout(LayoutMode.GRID_2X1, mirrorMask = 1)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE, result.errorOrNull()?.kind)
        assertTrue(io.writeLog.isEmpty())
    }

    @Test
    fun `applyLayout rejects mirror mask above variant max without IO`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val result = controller(io, HdmiBoardVariant.TX3)
            .applyLayout(LayoutMode.GRID_2X2, mirrorMask = 0x0F)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.INVALID_VALUE, result.errorOrNull()?.kind)
        assertTrue(io.writeLog.isEmpty())
    }

    @Test
    fun `applyLayout on a software layout performs zero writes`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val result = controller(io).applyLayout(LayoutMode.PIP)

        assertTrue(result.isFailure)
        val error = result.errorOrNull()
        assertEquals(HdmiMatrixErrorKind.NO_HARDWARE_MODE, error?.kind)
        assertEquals(LayoutMode.PIP, (error as HdmiMatrixError.NoHardwareMode).layoutMode)
        assertTrue(io.writeLog.isEmpty())
    }

    @Test
    fun `applyLayout stops when the mode write fails`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.stickyError(HdmiMatrixNode.MODE, HdmiMatrixErrno.EOPNOTSUPP)

        val result = controller(io).applyLayout(LayoutMode.GRID_2X2)

        assertTrue(result.isFailure)
        assertTrue("mode 失败后绝不能写 mirror", io.writesTo(HdmiMatrixNode.MIRROR).isEmpty())
    }

    @Test
    fun `setMirror rejects when applied mode is below threshold`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 3) // TX4 需要 >= 5

        val result = controller(io).setMirror(1)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE, result.errorOrNull()?.kind)
        assertTrue(io.writesTo(HdmiMatrixNode.MIRROR).isEmpty())
    }

    @Test
    fun `setMirror succeeds when applied mode meets the threshold`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 5)

        val result = controller(io).setMirror(3)

        assertTrue(result.isSuccess)
        assertEquals(listOf(3), io.writesTo(HdmiMatrixNode.MIRROR))
    }

    // ==================== fusion：防抖与合并 ====================

    @Test
    fun `stageFusion performs no IO and validates eagerly`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val ctl = controller(io)

        val ok = ctl.stageFusion(10, 20)
        assertTrue(ok.isSuccess)
        assertTrue("预览不得触达硬件", io.writeLog.isEmpty())

        val bad = ctl.stageFusion(256, 0)
        assertTrue(bad.isFailure)
        assertEquals(HdmiMatrixErrorKind.INVALID_VALUE, bad.errorOrNull()?.kind)
    }

    @Test
    fun `commitFusion writes hfusion then vfusion and updates flash timestamp`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val ctl = controller(io)
        ctl.stageFusion(10, 20)

        val result = ctl.commitFusion()

        assertTrue(result.isSuccess)
        assertEquals(listOf(10), io.writesTo(HdmiMatrixNode.HFUSION))
        assertEquals(listOf(20), io.writesTo(HdmiMatrixNode.VFUSION))
        assertEquals(testScheduler.currentTime, ctl.state.value.lastFusionFlashElapsedMs)
    }

    @Test
    fun `second commit within the debounce window waits 300ms and writes once`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val ctl = controller(io)

        ctl.stageFusion(10, 20)
        assertTrue(ctl.commitFusion().isSuccess)
        val firstWriteTime = testScheduler.currentTime
        io.clearLogs()

        // 时间不推进：第二次提交必须阻塞到防抖窗口结束
        ctl.stageFusion(30, 40)
        val second = ctl.commitFusion()

        assertTrue(second.isSuccess)
        assertEquals(
            "两次 flash 之间至少间隔 FUSION_FLASH_DEBOUNCE_MS",
            firstWriteTime + HardwareConfig.FUSION_FLASH_DEBOUNCE_MS,
            testScheduler.currentTime
        )
        // 只写一次最终值
        assertEquals(listOf(30), io.writesTo(HdmiMatrixNode.HFUSION))
        assertEquals(listOf(40), io.writesTo(HdmiMatrixNode.VFUSION))
    }

    @Test
    fun `commitFusion coalesces newer staged values that arrive during the wait`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val ctl = controller(io)

        ctl.stageFusion(10, 10)
        assertTrue(ctl.commitFusion().isSuccess)
        io.clearLogs()

        ctl.stageFusion(50, 50)
        val job = launch { ctl.commitFusion() }
        advanceTimeBy(100)
        // 等待期间用户仍在拖动：新值必须覆盖旧值
        ctl.stageFusion(77, 88)
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(listOf(77), io.writesTo(HdmiMatrixNode.HFUSION))
        assertEquals(listOf(88), io.writesTo(HdmiMatrixNode.VFUSION))
    }

    @Test
    fun `commitFusion skips when staged values already match read-back`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.HFUSION, 100)
        io.setValue(HdmiMatrixNode.VFUSION, 100)
        val ctl = controller(io)
        ctl.stageFusion(100, 100)

        val result = ctl.commitFusion()

        assertTrue(result.isSuccess)
        assertTrue("与已生效值相同不得写 flash", io.writeLog.isEmpty())
    }

    @Test
    fun `commitFusion with nothing staged is a no-op`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val result = controller(io).commitFusion()

        assertTrue(result.isSuccess)
        assertTrue(io.writeLog.isEmpty())
    }

    @Test
    fun `setFusion throttles instead of failing`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val ctl = controller(io)
        ctl.stageFusion(10, 10)
        assertTrue(ctl.commitFusion().isSuccess)
        io.clearLogs()

        val result = ctl.setFusion(60, 70)

        assertTrue(result.isSuccess)
        assertEquals(listOf(60), io.writesTo(HdmiMatrixNode.HFUSION))
        assertEquals(listOf(70), io.writesTo(HdmiMatrixNode.VFUSION))
    }

    // ==================== 批量 ====================

    @Test
    fun `applySettings applies in the documented order`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)

        val result = controller(io).applySettings(
            HdmiMatrixSettings(
                power = true,
                mode = 5,
                mirror = 3,
                resolution = HdmiResolution.P1080,
                rotate = 1,
                hfusion = 10,
                vfusion = 20,
                mute = true
            )
        )

        assertTrue(result.isSuccess)

        // 断言**相对顺序**，而不是"每个字段都产生一次写"。
        //
        // 写是幂等的：回读值与目标值相同就不写。夹具默认 POWER=1、RESOLUTION=1(1080p)，
        // 而本用例这两个字段的目标值恰好与之相同 —— 因此它们会被正确跳过。
        // 强求 writeLog 等于完整列表，等于要求实现放弃幂等优化，那是错的。
        val order = io.writeLog.map { it.first }
        val expectedRelativeOrder = listOf(
            HdmiMatrixNode.POWER,
            HdmiMatrixNode.MODE,
            HdmiMatrixNode.MIRROR,
            HdmiMatrixNode.RESOLUTION,
            HdmiMatrixNode.ROTATE,
            HdmiMatrixNode.HFUSION,
            HdmiMatrixNode.VFUSION,
            HdmiMatrixNode.MUTE
        ).filter { it in order }
        assertEquals(
            "已写入的节点必须保持文档规定的相对顺序",
            expectedRelativeOrder,
            order
        )
        // 关键约束：写 mode 在先，mirror 在后
        assertTrue(order.indexOf(HdmiMatrixNode.MODE) < order.indexOf(HdmiMatrixNode.MIRROR))
    }

    @Test
    fun `applySettings skips null fields`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MUTE, 0)

        val result = controller(io).applySettings(HdmiMatrixSettings(mute = true))

        assertTrue(result.isSuccess)
        assertEquals(listOf(HdmiMatrixNode.MUTE), io.writeLog.map { it.first })
    }

    @Test
    fun `applySettings stops at the first failure and keeps earlier steps`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 0)
        io.stickyError(HdmiMatrixNode.RESOLUTION, HdmiMatrixErrno.EOPNOTSUPP)

        val result = controller(io).applySettings(
            HdmiMatrixSettings(mode = 5, resolution = HdmiResolution.P1080, mute = true)
        )

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE, result.errorOrNull()?.kind)
        // mode 已生效（硬件没有事务）
        assertEquals(5, io.valueOf(HdmiMatrixNode.MODE))
        // 失败点之后的步骤必须未执行
        assertTrue(io.writesTo(HdmiMatrixNode.MUTE).isEmpty())
    }

    @Test
    fun `applySettings pre-validates before touching the device`() = runTest {
        val io = FakeHdmiMatrixNodeIo()

        val result = controller(io).applySettings(
            HdmiMatrixSettings(mode = 5, rotate = 9)
        )

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.INVALID_VALUE, result.errorOrNull()?.kind)
        assertTrue("预校验失败时不得产生任何写入", io.writeLog.isEmpty())
    }

    @Test
    fun `applySettings rejects mirror below threshold after reading back mode`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 2) // GRID_2X1，TX4 下不支持 mirror

        val result = controller(io).applySettings(HdmiMatrixSettings(mirror = 1))

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.UNSUPPORTED_IN_MODE, result.errorOrNull()?.kind)
        assertTrue(io.writesTo(HdmiMatrixNode.MIRROR).isEmpty())
    }

    // ==================== 等待空闲 / 在线 ====================

    @Test
    fun `waitUntilIdle returns at once when BUSY is clear`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.STATUS, 0x02)

        val result = controller(io).waitUntilIdle()

        assertTrue(result.isSuccess)
    }

    @Test
    fun `waitUntilIdle times out while BUSY stays set`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.STATUS, 0x03) // BUSY | POWER

        val result = controller(io).waitUntilIdle(timeoutMs = 500L, pollMs = 100L)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.TIMEOUT, result.errorOrNull()?.kind)
    }

    @Test
    fun `ensureOnline returns success when version is already 2`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val result = controller(io).ensureOnline(timeoutMs = 1_000L)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `ensureOnline gives up with NotAvailable on a missing node`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.VERSION)

        val result = controller(io).ensureOnline(timeoutMs = 800L, pollMs = 200L)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.NOT_AVAILABLE, result.errorOrNull()?.kind)
    }

    @Test
    fun `ensureOnline does not poll on EACCES`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.stickyError(HdmiMatrixNode.VERSION, HdmiMatrixErrno.EACCES)

        val result = controller(io).ensureOnline(timeoutMs = 5_000L, pollMs = 200L)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.PERMISSION_DENIED, result.errorOrNull()?.kind)
        // 一次就返回：轮询权限问题没有意义
        assertEquals(1, io.readLog.count { it == HdmiMatrixNode.VERSION })
    }

    // ==================== 旧路径回退 ====================

    @Test
    fun `cycleMode advances the hardware mode`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 2)

        val result = controller(io).cycleMode()

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull())
        assertEquals(3, io.valueOf(HdmiMatrixNode.MODE))
    }

    @Test
    fun `cycleMode wraps from 7 back to 0`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.setValue(HdmiMatrixNode.MODE, 7)

        assertEquals(0, controller(io).cycleMode().getOrNull())
    }

    @Test
    fun `cycleMode falls back to the legacy key only on ENOENT`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.MODE)
        val bridge = RecordingBridge()

        val result = controller(
            io,
            fallback = LegacyFallbackPolicy.Enabled(),
            bridge = bridge
        ).cycleMode()

        assertEquals(listOf(HdmiLegacyKey.MODE), bridge.pressed)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cycleMode never falls back on a real driver error`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.stickyError(HdmiMatrixNode.MODE, HdmiMatrixErrno.EOPNOTSUPP)
        val bridge = RecordingBridge()

        val result = controller(
            io,
            fallback = LegacyFallbackPolicy.Enabled(),
            bridge = bridge
        ).cycleMode()

        assertTrue(result.isFailure)
        assertTrue("EOPNOTSUPP 时回退会掩盖真实故障", bridge.pressed.isEmpty())
    }

    @Test
    fun `pressLegacyKey refuses when the I2C node exists`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val bridge = RecordingBridge()

        val result = controller(
            io,
            fallback = LegacyFallbackPolicy.Enabled(),
            bridge = bridge
        ).pressLegacyKey(HdmiLegacyKey.RESOLUTION)

        assertTrue(result.isFailure)
        assertTrue(bridge.pressed.isEmpty())
    }

    @Test
    fun `pressLegacyKey refuses when fallback is disabled`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.VERSION)
        val bridge = RecordingBridge()

        val result = controller(
            io,
            fallback = LegacyFallbackPolicy.Disabled,
            bridge = bridge
        ).pressLegacyKey(HdmiLegacyKey.MODE)

        assertTrue(result.isFailure)
        assertTrue(bridge.pressed.isEmpty())
    }

    @Test
    fun `pressLegacyKey pulses the key when the I2C node is absent`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.VERSION)
        val bridge = RecordingBridge()

        val result = controller(
            io,
            fallback = LegacyFallbackPolicy.Enabled(),
            bridge = bridge
        ).pressLegacyKey(HdmiLegacyKey.MODE)

        assertTrue(result.isSuccess)
        assertEquals(listOf(HdmiLegacyKey.MODE), bridge.pressed)
    }

    @Test
    fun `pressLegacyKey reports permission denied when the pulse fails`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.VERSION)
        val bridge = RecordingBridge().apply { success = false }

        val result = controller(
            io,
            fallback = LegacyFallbackPolicy.Enabled(),
            bridge = bridge
        ).pressLegacyKey(HdmiLegacyKey.RESOLUTION)

        assertTrue(result.isFailure)
        assertEquals(HdmiMatrixErrorKind.PERMISSION_DENIED, result.errorOrNull()?.kind)
    }

    // ==================== 状态发布 ====================

    @Test
    fun `state reflects the last failure`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        io.removeNode(HdmiMatrixNode.VERSION)
        val ctl = controller(io)

        ctl.probe()

        assertEquals(HdmiMatrixAvailability.MISSING, ctl.state.value.availability)
        assertEquals(HdmiMatrixErrorKind.NOT_AVAILABLE, ctl.state.value.lastError?.kind)
    }

    @Test
    fun `staging fusion publishes pendingFusion for the UI`() = runTest {
        val io = FakeHdmiMatrixNodeIo()
        val ctl = controller(io)

        ctl.stageFusion(11, 22)

        assertEquals(11, ctl.state.value.pendingFusion?.hfusion)
        assertEquals(22, ctl.state.value.pendingFusion?.vfusion)
    }
}
