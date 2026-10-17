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

class LocalStorageManagerTest {

    @Test
    fun `copyAllFromUsb copies supported media into internal storage`() = runBlocking {
        val filesDir = Files.createTempDirectory("local-storage-test").toFile()
        val context = TestContext(filesDir)
        val manager = LocalStorageManager(context)
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        val usbRoot = Files.createTempDirectory("usb-root").toFile()
        val playerDir = File(usbRoot, "media/player1").apply { mkdirs() }
        File(playerDir, "demo.mp4").writeText("video-content")

        val success = manager.copyAllFromUsb(usbRoot, "media")

        assertTrue(success)
        val copiedFile = File(filesDir, "media/player1/demo.mp4")
        assertTrue(copiedFile.exists())
        assertEquals(1, manager.getLocalMediaFiles(0).size)
    }

    @Test
    fun `copyAllFromUsb preserves existing cache when usb has no supported media`() = runBlocking {
        val filesDir = Files.createTempDirectory("local-storage-rollback").toFile()
        val context = TestContext(filesDir)
        val manager = LocalStorageManager(context)
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        val existingDir = File(filesDir, "media/player1").apply { mkdirs() }
        val existingFile = File(existingDir, "existing.mp4").apply { writeText("cached-content") }

        val usbRoot = Files.createTempDirectory("usb-empty").toFile()
        File(usbRoot, "media/player1").apply { mkdirs() }
        File(usbRoot, "media/player1/ignore.txt").writeText("not-supported")

        val success = manager.copyAllFromUsb(usbRoot, "media")

        assertFalse(success)
        assertTrue(existingFile.exists())
        assertEquals("cached-content", existingFile.readText())
        assertEquals(1, manager.getLocalMediaFiles(0).size)
    }

    /**
     * 回归防线（P0）：陈旧备份必须被清理，否则拷贝会永久失败。
     *
     * 故障链：某次拷贝在 promote 之后、清理之前被中断（断电 / 进程被杀），
     * 会留下 `.playerN_backup` 与 `playerN` 同时存在。下一次拷贝时
     * `localPlayerDir.renameTo(backupDir)` **无法覆盖已存在的目录**，返回 false
     * → `backup_prepare_failed`，此后每次拷贝都失败，必须手工删隐藏目录才能恢复。
     *
     * 曾经的错误实现是 `if (!backupDir.exists()) backupDir.deleteRecursively()`，
     * 条件写反使清理恒为空操作，恰好制造出上述状态。该缺陷编译通过、无告警，
     * 只有真实中断过才会暴露 —— 故用测试固化此场景。
     */
    @Test
    fun `copyAllFromUsb recovers when a stale backup dir is left behind`() = runBlocking {
        val filesDir = Files.createTempDirectory("local-storage-stale-backup").toFile()
        val context = TestContext(filesDir)
        val manager = LocalStorageManager(context)
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        val playerDir = File(filesDir, "media/player1").apply { mkdirs() }
        val existingFile = File(playerDir, "existing.mp4").apply { writeText("old-content") }

        // 模拟「上次拷贝被中断」：backup 与 player 同时存在
        val staleBackup = File(filesDir, "media/.player1_backup").apply { mkdirs() }
        File(staleBackup, "stale.mp4").writeText("stale-content")

        val usbRoot = Files.createTempDirectory("usb-stale-backup").toFile()
        val usbPlayer = File(usbRoot, "media/player1").apply { mkdirs() }
        File(usbPlayer, "new.mp4").writeText("new-content")

        val success = manager.copyAllFromUsb(usbRoot, "media")

        assertTrue("残留备份不应导致拷贝永久失败", success)
        assertTrue("新内容应已就位", File(playerDir, "new.mp4").exists())
        assertFalse("旧内容应被替换", existingFile.exists())
        assertFalse("陈旧备份应被清理，否则下次仍会失败", staleBackup.exists())
    }

    /**
     * 回归防线（P0）：孤儿备份还原失败时**绝不能**删除它。
     *
     * 该状态下 `localPlayerDir` 不存在、数据只存在于 backup 中。
     * 若还原失败后继续执行清理，就会删掉唯一一份数据。
     */
    @Test
    fun `copyAllFromUsb keeps orphaned backup intact when it cannot be restored`() = runBlocking {
        val filesDir = Files.createTempDirectory("local-storage-orphan").toFile()
        val context = TestContext(filesDir)
        val manager = LocalStorageManager(context)
        manager.updateStorageSettings(StorageLocation.INTERNAL)

        // 孤儿备份：player1 不存在，数据只在备份里
        val backupDir = File(filesDir, "media/.player1_backup").apply { mkdirs() }
        val onlyCopy = File(backupDir, "precious.mp4").apply { writeText("only-copy") }

        val usbRoot = Files.createTempDirectory("usb-orphan").toFile()
        File(usbRoot, "media/player1").apply { mkdirs() }

        manager.copyAllFromUsb(usbRoot, "media")

        // 无论还原是否成功，这份唯一数据都不能被删
        assertTrue("孤儿备份中唯一的数据不能被删除", onlyCopy.exists())
        assertEquals("only-copy", onlyCopy.readText())
    }

    private class TestContext(private val rootFilesDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = rootFilesDir
    }
}
