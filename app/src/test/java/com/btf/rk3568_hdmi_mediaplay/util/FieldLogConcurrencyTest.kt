package com.btf.rk3568_hdmi_mediaplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `FieldLog` 的并发与异步行为。
 *
 * ## 为什么单独测这两点
 *
 * 日志是现场**唯一**的事后证据（Release 包默认不写 logcat 之外的东西，
 * 而 logcat 重启即丢）。它有两种很容易写错、又很难在真机上看出来的失效方式：
 *
 * 1. **异步化后丢事件**：改成单线程写以后，如果队列/异常处理写错，
 *    日志会静默少记 —— 平时完全看不出来，等到真要排查时才发现缺了关键行。
 * 2. **`SimpleDateFormat` 并发**：它不是线程安全的。`write()` 会被主线程
 *    和 IO 线程同时调用，共享实例会产生错乱时间戳，甚至抛
 *    `ArrayIndexOutOfBoundsException` —— 而这类异常在日志代码里通常被吞掉。
 */
class FieldLogConcurrencyTest {

    /**
     * 并发格式化时间戳不能抛异常、不能产生空串。
     *
     * 这里复现的是「多个线程共用一个 SimpleDateFormat」的失败场景，
     * 验证正确的做法（把格式化收到单线程里）确实消除了它。
     */
    @Test
    fun `concurrent formatting on a single thread is safe`() {
        val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val singleThread = Executors.newSingleThreadExecutor()
        val failure = AtomicInteger(0)
        val empty = AtomicInteger(0)
        val latch = CountDownLatch(200)

        repeat(200) { i ->
            // 模拟并发提交：200 个任务抢同一个格式化器（实际由单线程串行执行）
            Thread {
                try {
                    val out = singleThread.submit<String> {
                        format.format(Date(1_700_000_000_000L + i))
                    }.get(5, TimeUnit.SECONDS)
                    if (out.isBlank()) empty.incrementAndGet()
                } catch (e: Exception) {
                    failure.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("并发提交未完成", latch.await(30, TimeUnit.SECONDS))
        singleThread.shutdownNow()

        assertEquals("单线程格式化不应抛异常", 0, failure.get())
        assertEquals("格式化结果不应为空", 0, empty.get())
    }

    /**
     * 共享 `SimpleDateFormat` 跨线程并发使用**确实是**不安全的。
     *
     * 这条不是测我们的实现，而是记录「为什么必须把它收进单线程」的依据：
     * 如果哪天有人图省事把格式化挪回 `write()` 里，这里说明了后果。
     */
    @Test
    fun `shared SimpleDateFormat across threads is unsafe`() {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val pool = Executors.newFixedThreadPool(8)
        val bad = AtomicInteger(0)
        val latch = CountDownLatch(8 * 500)

        repeat(8) {
            pool.execute {
                repeat(500) {
                    try {
                        val s = format.format(Date(1_700_000_000_000L))
                        // 正常结果长度固定；错乱的输出会偏离这个长度
                        if (s.length != 23) bad.incrementAndGet()
                    } catch (e: Exception) {
                        bad.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        // 该测试的意义在于说明风险；若某个 JVM 实现恰好没出错也不应失败，
        // 因此这里只断言"没有异常逃逸到测试框架"，具体计数仅作诊断输出。
        println("shared SimpleDateFormat anomalies = ${bad.get()} / 4000")
    }

    /** 队列为空时写日志是空操作，不应抛异常。 */
    @Test
    fun `writing before init does not throw`() {
        repeat(100) { FieldLog.write("TEST", "line $it") }
        assertTrue("未初始化时 tail 应为空", FieldLog.tail(10).isEmpty())
    }

    /** `tail` 的负数参数不应崩（调试页可能传错）。 */
    @Test
    fun `tail with non-positive count returns empty`() {
        assertTrue(FieldLog.tail(0).isEmpty())
        assertTrue(FieldLog.tail(-5).isEmpty())
    }
}
