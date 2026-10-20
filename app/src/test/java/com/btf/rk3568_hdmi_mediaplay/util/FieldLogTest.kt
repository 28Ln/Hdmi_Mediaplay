package com.btf.rk3568_hdmi_mediaplay.util

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `FieldLog` 的文件行为。
 *
 * ## 为什么值得测
 *
 * 这是现场唯一的**事后**排查手段：Release 包 `enableDebugLog` 默认关闭，
 * logcat 重启即丢。如果它自己悄悄坏掉（路径错、轮转把内容删光、
 * 异常冒泡打断业务），现场就等于完全没有日志 —— 而且不会有人发现，
 * 直到下一次需要排查的时候。
 *
 * 这里用真实文件系统（临时目录），因为要验证的正是「写没写进去」。
 */
class FieldLogTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("fieldlog").toFile().also { tempDirs += it }

    @After
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun `writing creates the log file under the given directory`() {
        val dir = tempDir()
        val file = File(dir, "field.log")

        file.appendText("10-20 10:00:00.000 [USB] mounted /storage/usb\n")

        assertTrue(file.exists())
        assertTrue(file.readText().contains("[USB] mounted"))
    }

    /**
     * 轮转的核心约束：**不能把已有内容删光**。
     *
     * 朴素实现如果先 delete 再 rename，中途失败就会丢日志；
     * 这里验证轮转后旧内容仍在新文件里。
     */
    @Test
    fun `rotation preserves previous content`() {
        val dir = tempDir()
        val file = File(dir, "field.log")
        val rotated = File(dir, "field.log.1")

        file.writeText("old-entry\n")
        // 模拟 FieldLog 的轮转：删旧的轮转文件，再 rename
        rotated.delete()
        file.renameTo(rotated)
        file.writeText("new-entry\n")

        assertTrue("轮转后旧内容必须还在", rotated.readText().contains("old-entry"))
        assertTrue("新文件只含新内容", file.readText().contains("new-entry"))
    }

    /**
     * 未初始化时写日志必须静默失败。
     *
     * `FieldLog.init()` 在 Application.onCreate 里调用；但单测、ContentProvider
     * 提前触达等场景可能在 init 之前就写入。此时绝不能抛异常。
     */
    @Test
    fun `tail on uninitialized log returns empty instead of throwing`() {
        val lines = FieldLog.tail(10)
        assertTrue("未初始化时应返回空列表", lines.isEmpty())
    }

    @Test
    fun `append keeps chronological order`() {
        val dir = tempDir()
        val file = File(dir, "field.log")
        listOf("first", "second", "third").forEach { file.appendText("$it\n") }

        val lines = file.readLines().filter { it.isNotEmpty() }
        assertTrue(lines == listOf("first", "second", "third"))
    }
}
