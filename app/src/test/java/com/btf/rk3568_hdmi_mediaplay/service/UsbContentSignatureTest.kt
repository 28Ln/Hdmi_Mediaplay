package com.btf.rk3568_hdmi_mediaplay.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 内容签名是「拔插刷新」的核心闸门：内容没变就不能重复通知上层，
 * 否则每 3 秒弹一次覆盖确认框，插件无法使用；内容变了又必须通知，
 * 否则用户往 U 盘加了文件却看不到更新。
 */
class UsbContentSignatureTest {

    private fun newUsbRoot(): File = Files.createTempDirectory("usb-sig").toFile()

    private fun writeMedia(usbRoot: File, player: String, name: String, content: String) {
        val dir = File(usbRoot, "media/$player").apply { mkdirs() }
        File(dir, name).writeText(content)
    }

    @Test
    fun `signature is stable when content does not change`() {
        val usb = newUsbRoot()
        writeMedia(usb, "player1", "a.mp4", "aaa")

        val first = UsbMonitorService.contentSignatureForTest(usb, "media")
        val second = UsbMonitorService.contentSignatureForTest(usb, "media")

        assertEquals("内容未变时签名必须一致，否则会反复通知上层", first, second)
    }

    @Test
    fun `signature changes when a file is added`() {
        val usb = newUsbRoot()
        writeMedia(usb, "player1", "a.mp4", "aaa")
        val before = UsbMonitorService.contentSignatureForTest(usb, "media")

        writeMedia(usb, "player1", "b.mp4", "bbb")
        val after = UsbMonitorService.contentSignatureForTest(usb, "media")

        assertNotEquals("新增文件必须改变签名", before, after)
    }

    @Test
    fun `signature changes when file size changes`() {
        val usb = newUsbRoot()
        writeMedia(usb, "player1", "a.mp4", "aaa")
        val before = UsbMonitorService.contentSignatureForTest(usb, "media")

        // 用不同长度内容模拟替换
        writeMedia(usb, "player1", "a.mp4", "aaaaaaaaaa")
        val after = UsbMonitorService.contentSignatureForTest(usb, "media")

        assertNotEquals("文件大小变化必须改变签名", before, after)
    }

    @Test
    fun `signature changes when config file appears`() {
        val usb = newUsbRoot()
        writeMedia(usb, "player1", "a.mp4", "aaa")
        val before = UsbMonitorService.contentSignatureForTest(usb, "media")

        File(usb, "btf_config.json").writeText("""{"version":1}""")
        val after = UsbMonitorService.contentSignatureForTest(usb, "media")

        assertNotEquals("配置变化必须触发重扫", before, after)
    }

    @Test
    fun `signature differs across usb roots`() {
        val a = newUsbRoot()
        val b = newUsbRoot()
        writeMedia(a, "player1", "x.mp4", "same")
        writeMedia(b, "player1", "x.mp4", "same")

        assertNotEquals(
            "路径不同的两个U盘不能产生同一签名",
            UsbMonitorService.contentSignatureForTest(a, "media"),
            UsbMonitorService.contentSignatureForTest(b, "media")
        )
    }

    @Test
    fun `signature is stable when media dir is missing`() {
        val usb = newUsbRoot()

        val first = UsbMonitorService.contentSignatureForTest(usb, "media")
        val second = UsbMonitorService.contentSignatureForTest(usb, "media")

        // 关键：必须是稳定值。若每次不同（如用 nanoTime），
        // 「内容未变」永远不成立 → 每 3 秒通知一次 → 反复弹框。
        assertEquals("目录不存在时也要返回稳定值", first, second)
    }
}
