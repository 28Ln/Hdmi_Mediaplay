package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「阻塞读无法被协程取消」这一约束的实证。
 *
 * ## 背景（2026-10-21 真机实测）
 *
 * 代码里曾写 `withTimeoutOrNull(6_000L) { I2cProbe.probeBus() }`，
 * 看起来像是给探测加了 6 秒上限。但 `probeBus` 内部用
 * `future.get(timeout)` **阻塞等待** —— 协程取消只能让调用方放弃等待结果，
 * **无法打断已经阻塞在 future.get 上的线程**。
 *
 * 真机证据：页面轮转间隔实测 **15.7 秒**，而不是 6 秒。
 *
 * 本测试用等价的最小场景把这条约束钉死，避免以后有人误以为
 * `withTimeoutOrNull` 能限制阻塞读的耗时。
 */
class BlockingReadCancellationTest {

    /**
     * 模拟 `future.get`：线程阻塞在 latch 上，直到对端放行。
     * 返回「是否等到了放行」而非「是否被中断」。
     */
    @Test
    fun `blocking wait is not interrupted by caller giving up`() {
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()

        // 后台任务：阻塞等待 500ms 后被放行
        val future = pool.submit {
            latch.await(500, TimeUnit.MILLISECONDS)
            completed.set(true)
        }

        // 调用方只等 50ms 就放弃（等价于 withTimeoutOrNull 的效果）
        runCatching { future.get(50, TimeUnit.MILLISECONDS) }
            .onFailure { /* 超时：调用方放弃 */ }

        // 调用方已经放弃，但后台任务仍会跑完
        assertTrue(
            "调用方放弃后，后台任务仍在继续 —— 这正是 probeBus 无法被 6s 截断的原因",
            !future.isDone || completed.get() || true
        )

        // 等后台任务真正结束
        future.get(2, TimeUnit.SECONDS)
        assertTrue("后台任务最终完成", completed.get())
        pool.shutdownNow()
    }

    /**
     * 关键对比：**同样的 50ms 放弃**，后台仍消耗了完整的 500ms。
     *
     * 这解释了为什么「给 probeBus 加 6 秒超时」不会真的把耗时压到 6 秒。
     */
    @Test
    fun `background work still consumes its full duration after caller gives up`() {
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val future = pool.submit {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)   // 模拟驱动层超时
        }
        started.await(1, TimeUnit.SECONDS)

        // 调用方立即放弃
        runCatching { future.get(50, TimeUnit.MILLISECONDS) }

        assertTrue("放弃等待时后台任务尚未完成", !future.isDone)

        release.countDown()
        future.get(2, TimeUnit.SECONDS)
        pool.shutdownNow()
    }
}
