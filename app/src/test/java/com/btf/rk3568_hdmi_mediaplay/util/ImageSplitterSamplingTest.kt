package com.btf.rk3568_hdmi_mediaplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片解码采样率计算（`ImageSplitter.calculateInSampleSize`）。
 *
 * 该函数是 P0-10 防 OOM 修复的核心：整图全量解码在 4K/8K 素材下必然 OOM，
 * 而拼接墙场景常见的正是大图。它是纯算术，完全可在 JVM 上验证。
 *
 * 约束（函数注释所承诺的）：
 * 1. 采样后的宽高**不小于**目标最小尺寸（否则画面糊）
 * 2. 采样率尽量大（解码尽量小，省内存）
 * 3. 采样率是 2 的幂
 */
class ImageSplitterSamplingTest {

    private fun sample(w: Int, h: Int, minW: Int, minH: Int) =
        ImageSplitter.calculateInSampleSize(w, h, minW, minH)

    @Test
    fun `small image is not downsampled`() {
        assertEquals(1, sample(800, 600, 1920, 1080))
    }

    @Test
    fun `exactly matching size is not downsampled`() {
        assertEquals(1, sample(1920, 1080, 1920, 1080))
    }

    @Test
    fun `4K image downsamples to fit 1080p target`() {
        // 3840x2160 至少是目标的 2 倍，但不满足 4 倍
        assertEquals(2, sample(3840, 2160, 1920, 1080))
    }

    @Test
    fun `8K image downsamples more aggressively`() {
        // 7680x4320 = 目标的 4 倍
        assertEquals(4, sample(7680, 4320, 1920, 1080))
    }

    @Test
    fun `result is always a power of two`() {
        for (w in listOf(1000, 3000, 5000, 9000)) {
            val s = sample(w, w, 800, 600)
            assertTrue("sampleSize $s 不是 2 的幂", s > 0 && (s and (s - 1)) == 0)
        }
    }

    @Test
    fun `downsampled dimensions never fall below the target`() {
        val cases = listOf(
            Triple(3840, 2160, 1920 to 1080),
            Triple(7680, 4320, 1920 to 1080),
            Triple(5000, 3000, 1280 to 720)
        )
        for ((w, h, target) in cases) {
            val s = sample(w, h, target.first, target.second)
            assertTrue(
                "$w×$h 采样 $s 后宽 ${w / s} 小于目标 ${target.first}",
                w / s >= target.first
            )
            assertTrue(
                "$w×$h 采样 $s 后高 ${h / s} 小于目标 ${target.second}",
                h / s >= target.second
            )
        }
    }

    @Test
    fun `zero or negative target falls back to no downsampling`() {
        assertEquals(1, sample(4000, 3000, 0, 1080))
        assertEquals(1, sample(4000, 3000, 1920, 0))
        assertEquals(1, sample(4000, 3000, -1, -1))
    }

    @Test
    fun `very wide panorama is limited by height`() {
        // 宽远超目标但高很小：不应继续放大采样率
        val s = sample(8000, 1200, 1920, 1080)
        assertTrue("高 1200/2=600 < 1080，不应再采样", 1200 / s >= 1080)
    }
}
