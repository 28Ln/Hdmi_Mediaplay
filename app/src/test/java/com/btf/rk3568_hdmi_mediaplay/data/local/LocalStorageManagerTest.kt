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

    private class TestContext(private val rootFilesDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = rootFilesDir
    }
}
