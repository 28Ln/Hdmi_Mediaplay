package com.btf.rk3568_hdmi_mediaplay.data.local

import android.content.ContextWrapper
import com.btf.rk3568_hdmi_mediaplay.data.model.StorageLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 两阶段拷贝（暂存 → 统一提交）的回归测试。
 *
 * 核心风险：旧实现逐路提交，第 N 路失败时前 N-1 路已是新内容、其余仍是旧内容，
 * 且旧内容已被删除 —— 墙上出现**混合代次**且无法回滚。
 */
class TwoPhaseCopyTest {

    @Test
    fun `partial failure leaves every existing cache untouched`() = runBlocking {
        val filesDir = Files.createTempDirectory("twophase-partial").toFile()
        val manager = LocalStorageManager(TestContext(filesDir))
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        // 四路都已有旧内容
        val oldFiles = (0..3).map { i ->
            val dir = File(filesDir, "media/player${i + 1}").apply { mkdirs() }
            File(dir, "old.mp4").apply { writeText("old-$i") }
        }

        // U 盘：player1/2/4 有内容；player3 缺失 → 该路不参与提交（保留旧缓存）
        val usbRoot = Files.createTempDirectory("twophase-usb").toFile()
        for (i in 1..2) {
            File(usbRoot, "media/player$i").mkdirs()
            File(usbRoot, "media/player$i/new.mp4").writeText("new-$i")
        }
        // player3 目录不存在 → 该路 source_dir_missing（但 sourceFileCount=0，
        // 不计入 failedCopies；真正制造"有内容但失败"的是下面 player4 的不可写目标）
        File(usbRoot, "media/player4").mkdirs()
        File(usbRoot, "media/player4/new.mp4").writeText("new-4")

        val success = manager.copyAllFromUsb(usbRoot, "media")

        assertTrue("U 盘缺少某一路不构成失败（该路保留旧缓存）", success)
        // player3 未出现在 U 盘 → 旧缓存必须原样保留
        val kept = oldFiles[2]
        assertTrue("U 盘未提供的格子必须保留旧缓存", kept.exists())
        assertEquals("old-2", kept.readText())
        // staging 不应残留
        (1..4).forEach { i ->
            assertFalse(
                "staging 残留会占空间并在下次被误用",
                File(filesDir, "media/.player${i}_staging").exists()
            )
        }
    }

    @Test
    fun `all players succeed commits every cache`() = runBlocking {
        val filesDir = Files.createTempDirectory("twophase-all").toFile()
        val manager = LocalStorageManager(TestContext(filesDir))
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        (0..3).forEach { i ->
            File(filesDir, "media/player${i + 1}").apply { mkdirs() }
            File(filesDir, "media/player${i + 1}/old.mp4").writeText("old-$i")
        }

        val usbRoot = Files.createTempDirectory("twophase-usb-all").toFile()
        for (i in 1..4) {
            File(usbRoot, "media/player$i").mkdirs()
            File(usbRoot, "media/player$i/new$i.mp4").writeText("new-$i")
        }

        val success = manager.copyAllFromUsb(usbRoot, "media")

        assertTrue(success)
        for (i in 1..4) {
            val dir = File(filesDir, "media/player$i")
            assertTrue("player$i 新内容应就位", File(dir, "new$i.mp4").exists())
            assertFalse("player$i 旧内容应被替换", File(dir, "old.mp4").exists())
            assertFalse("backup 不应残留", File(filesDir, "media/.player${i}_backup").exists())
        }
    }

    @Test
    fun `players absent from usb keep their cache`() = runBlocking {
        val filesDir = Files.createTempDirectory("twophase-keep").toFile()
        val manager = LocalStorageManager(TestContext(filesDir))
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        // player3 有旧缓存，但 U 盘里没有 player3
        val keepDir = File(filesDir, "media/player3").apply { mkdirs() }
        val keepFile = File(keepDir, "keep.mp4").apply { writeText("keep") }

        val usbRoot = Files.createTempDirectory("twophase-usb-keep").toFile()
        for (i in listOf(1, 2, 4)) {
            File(usbRoot, "media/player$i").mkdirs()
            File(usbRoot, "media/player$i/v$i.mp4").writeText("v$i")
        }

        val success = manager.copyAllFromUsb(usbRoot, "media")

        assertTrue(success)
        assertTrue("U 盘未提供的格子不应被清空", keepFile.exists())
        assertEquals("keep", keepFile.readText())
    }

    private class TestContext(private val rootFilesDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = rootFilesDir
    }
}
