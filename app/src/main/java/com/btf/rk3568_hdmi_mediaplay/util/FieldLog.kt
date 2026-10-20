package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 现场可回收的运行日志。
 *
 * ## 为什么需要它（2026-10-20）
 *
 * 本机是无人值守投放设备，`enableDebugLog` 在 Release 包**默认关闭**，
 * 而 `MainViewModel.log()` 只在开关打开时才写 logcat。这意味着：
 *
 * - 现场的 U 盘退避、重试、错误态恢复等关键事件**不留任何痕迹**
 * - logcat 重启即丢，出问题后再连 adb 已经看不到
 * - 退避策略曾经写错成「永远 5 秒一次」，这类问题在真机上极难发现，
 *   事后更是完全无从复盘
 *
 * ## 与崩溃日志的区别
 *
 * `MediaPlayApplication.saveCrashLog` 只在崩溃时写，记的是异常；
 * 这里记的是**正常运行期的事件**，用于回答「它当时到底在做什么」。
 * 两者放在同一目录（`files/crash_logs/`），现场一次性取走。
 *
 * ## 写入约束
 *
 * - **绝不抛异常**：日志失败不能影响业务
 * - **同步追加**：量很小（只记关键事件），不需要队列
 * - **滚动保留**：超过 [MAX_BYTES] 就轮转一份，总占用有上限
 * - **不记敏感内容**：只记事件与数值，不记完整文件路径清单
 */
object FieldLog {

    private const val TAG = "FieldLog"
    private const val DIR = "crash_logs"
    private const val FILE = "field.log"

    /** 单文件上限：约 1 万行，足够覆盖数天的关键事件 */
    private const val MAX_BYTES = 512 * 1024L

    /**
     * 只在 writer 线程使用。
     *
     * `SimpleDateFormat` **不是线程安全的**，而 `write()` 会被主线程和 IO 线程
     * 并发调用；共享同一个实例会产生错乱时间戳甚至抛
     * `ArrayIndexOutOfBoundsException`。因此格式化一律放到单线程 writer 里做。
     */
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    /**
     * 单线程异步写入。
     *
     * `MainViewModel.log()` 有相当一部分是在**主线程**调用的
     * （`viewModelScope.launch` 默认就是 Main），而同步 `appendText`
     * 在慢速 eMMC 上可能阻塞数毫秒。投放机对卡顿敏感，
     * 日志绝不能拖住 UI。
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "field-log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 记一条现场日志。
     *
     * @param tag 事件类别，便于 grep（如 `USB`、`SYNC`、`HDMI`）
     */
    fun write(tag: String, message: String) {
        // logcat 照常输出，便于接 adb 时实时看
        Log.d(tag, message)

        val ctx = appContext ?: return
        // 时间戳在此刻取（体现「什么时候发生的」），格式化留到 writer 线程
        val atMs = System.currentTimeMillis()
        runCatching {
            writer.execute { appendLine(ctx, atMs, tag, message) }
        }.onFailure {
            // 线程池已关闭等极端情况：绝不能影响业务
            Log.w(TAG, "field log enqueue failed: ${it.message}")
        }
    }

    /** 真正的落盘，只在 writer 线程执行 */
    private fun appendLine(ctx: Context, atMs: Long, tag: String, message: String) {
        try {
            // 拼装放在 writer 线程：timeFormat 不是线程安全的
            val line = timeFormat.format(Date(atMs)) + " [$tag] $message\n"
            val dir = File(ctx.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE)

            if (file.length() > MAX_BYTES) {
                val rotated = File(dir, "$FILE.1")
                rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(line)
        } catch (e: Exception) {
            // 日志失败绝不能影响业务
            Log.w(TAG, "field log write failed: ${e.message}")
        }
    }

    /** 供调试页/远程诊断展示最近若干行 */
    fun tail(maxLines: Int = 200): List<String> {
        val ctx = appContext ?: return emptyList()
        return try {
            val file = File(File(ctx.filesDir, DIR), FILE)
            if (!file.exists()) emptyList()
            else file.readLines().takeLast(maxLines)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
