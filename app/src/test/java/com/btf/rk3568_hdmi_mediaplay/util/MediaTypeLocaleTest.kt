package com.btf.rk3568_hdmi_mediaplay.util

import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 媒体类型识别的 locale 无关性。
 *
 * ## 重要前提（此前我判断错了）
 *
 * Kotlin 的 `String.lowercase()` **无参版本自 1.5 起就用 `Locale.ROOT`**，
 * 与 `java.lang.String.toLowerCase()` 不同。实测（tr-TR 环境下）：
 * ```
 * "AVI".lowercase()                  -> "avi"   ← 安全
 * "AVI".lowercase(Locale.getDefault()) -> "avı" ← 才会出错
 * ```
 *
 * 因此本文件里的测试**不会**因为把 `lowercase(Locale.ROOT)` 写回
 * `lowercase()` 而失败 —— 二者本来就等价。
 *
 * 那这些测试还有什么用？
 * - 防止有人把它改成 `lowercase(Locale.getDefault())`（真正会坏的写法）
 * - 防止 Java 侧的 `toLowerCase()` 被误用（那个确实受 locale 影响）
 *
 * 保留显式 `Locale.ROOT` 是为了让意图写在代码里，而不是依赖"恰好安全"。
 */
class MediaTypeLocaleTest {

    private lateinit var original: Locale

    @Before
    fun setUp() {
        original = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    @Test
    fun `avi is recognized as video under turkish locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        assertEquals(MediaType.VIDEO, MediaTypeUtils.getMediaType("movie.AVI"))
        assertEquals(MediaType.VIDEO, MediaTypeUtils.getMediaType("movie.avi"))
    }

    @Test
    fun `gif and webp are recognized under turkish locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        assertEquals(MediaType.IMAGE, MediaTypeUtils.getMediaType("anim.GIF"))
        assertEquals(MediaType.IMAGE, MediaTypeUtils.getMediaType("pic.WEBP"))
    }

    @Test
    fun `uppercase extensions with capital I are recognized in default locale`() {
        assertEquals(MediaType.VIDEO, MediaTypeUtils.getMediaType("clip.MP4"))
        assertEquals(MediaType.VIDEO, MediaTypeUtils.getMediaType("clip.MKV"))
        assertEquals(MediaType.IMAGE, MediaTypeUtils.getMediaType("shot.JPG"))
        assertEquals(MediaType.IMAGE, MediaTypeUtils.getMediaType("shot.PNG"))
    }

    @Test
    fun `unknown extension stays unknown`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        assertEquals(MediaType.UNKNOWN, MediaTypeUtils.getMediaType("notes.txt"))
        assertEquals(MediaType.UNKNOWN, MediaTypeUtils.getMediaType("noextension"))
    }

    /**
     * 记录 Kotlin 与 Java 大小写转换的语义差异。
     *
     * 这条测试的价值在于：它显式证明了「无参 lowercase 是 ROOT 语义」，
     * 避免后人（包括我）再次误判为缺陷并做无谓的"修复"。
     */
    @Test
    fun `kotlin no-arg lowercase is locale independent but java toLowerCase is not`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        assertEquals("avi", "AVI".lowercase())
        assertEquals("avı", "AVI".lowercase(Locale.getDefault()))

        // Java 的 toLowerCase() 受默认 locale 影响 —— 这才是真正的坑
        assertEquals("avı", "AVI".toLowerCase())
    }
}
