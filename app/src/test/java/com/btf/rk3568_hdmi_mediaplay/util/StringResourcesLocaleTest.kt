package com.btf.rk3568_hdmi_mediaplay.util

import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `StringResources` 的双语一致性。
 *
 * 该对象是普通单例（非 Compose State），语言由 [StringResources.setLanguage]
 * 全局切换。这里验证的是**同一个属性在不同语言下都有值、且确实不同**。
 *
 * 背景：前台服务通知原本走 `R.string`，按**系统语言**解析，
 * 与应用内语言设置脱节（系统中文 + 应用内英文时通知仍是中文）。
 * 现改为读 `StringResources`，因此必须保证这些属性双语齐备 ——
 * 漏了中文分支会显示英文、漏了英文分支会显示中文，都属"静默错误"。
 */
class StringResourcesLocaleTest {

    private lateinit var original: AppLanguage

    @Before
    fun setUp() {
        original = StringResources.getLanguage()
    }

    @After
    fun tearDown() {
        StringResources.setLanguage(original)
    }

    @Test
    fun `notification strings change with in-app language`() {
        StringResources.setLanguage(AppLanguage.CHINESE)
        val zhChannel = StringResources.notificationChannelName
        val zhTitle = StringResources.notificationTitle
        val zhText = StringResources.notificationText
        val zhDesc = StringResources.notificationChannelDescription

        StringResources.setLanguage(AppLanguage.ENGLISH)
        val enChannel = StringResources.notificationChannelName
        val enTitle = StringResources.notificationTitle
        val enText = StringResources.notificationText
        val enDesc = StringResources.notificationChannelDescription

        // 每个属性在两种语言下都必须不同 —— 相同说明有一条分支没写
        assertNotEquals("通知渠道名未随语言变化", zhChannel, enChannel)
        assertNotEquals("通知标题未随语言变化", zhTitle, enTitle)
        assertNotEquals("通知正文未随语言变化", zhText, enText)
        assertNotEquals("通知渠道描述未随语言变化", zhDesc, enDesc)
    }

    @Test
    fun `notification strings are never blank in either language`() {
        listOf(AppLanguage.CHINESE, AppLanguage.ENGLISH).forEach { lang ->
            StringResources.setLanguage(lang)
            listOf(
                "notificationChannelName" to StringResources.notificationChannelName,
                "notificationTitle" to StringResources.notificationTitle,
                "notificationText" to StringResources.notificationText,
                "notificationChannelDescription" to StringResources.notificationChannelDescription
            ).forEach { (name, value) ->
                assertTrue("$lang 下 $name 为空", value.isNotBlank())
            }
        }
    }

    /**
     * 中文分支不应混入英文单词，反之亦然 —— 常见的复制粘贴错误。
     *
     * 只做粗略检查：中文文案里出现纯 ASCII 单词（长度 >= 4）就可能是漏译。
     * 专有名词（USB、HDMI）是允许的，故设了白名单。
     */
    @Test
    fun `chinese notification strings do not leak english words`() {
        StringResources.setLanguage(AppLanguage.CHINESE)

        val allowed = setOf("USB", "HDMI")
        listOf(
            "notificationTitle" to StringResources.notificationTitle,
            "notificationText" to StringResources.notificationText,
            "notificationChannelDescription" to StringResources.notificationChannelDescription
        ).forEach { (name, value) ->
            val leaked = Regex("[A-Za-z]{4,}").findAll(value)
                .map { it.value }
                .filter { it !in allowed }
                .toList()
            assertTrue("$name 的中文文案疑似混入英文: $leaked（原文：$value）", leaked.isEmpty())
        }
    }

    /**
     * HDMI 矩阵状态文案的双语一致性。
     *
     * 这几个字符串直接决定现场看到「无应答」还是「总线卡死（需重启）」——
     * 处置动作完全不同。漏写一条语言分支会静默显示成另一种语言，
     * 排查时会误以为版本不对。
     */
    @Test
    fun `hdmi matrix status strings are bilingual and distinct`() {
        val pairs = listOf(
            "hdmiMatrixNoDevice" to { StringResources.hdmiMatrixNoDevice },
            "hdmiMatrixBusHung" to { StringResources.hdmiMatrixBusHung },
            "hdmiMatrixBusHungDetail" to { StringResources.hdmiMatrixBusHungDetail },
            "hdmiMatrixRetryOnce" to { StringResources.hdmiMatrixRetryOnce }
        )
        pairs.forEach { (name, getter) ->
            StringResources.setLanguage(AppLanguage.CHINESE)
            val zh = getter()
            StringResources.setLanguage(AppLanguage.ENGLISH)
            val en = getter()

            assertTrue("$name 中文为空", zh.isNotBlank())
            assertTrue("$name 英文为空", en.isNotBlank())
            assertNotEquals("$name 未随语言变化（缺少一条分支）", zh, en)
        }
    }

    @Test
    fun `language switch is reflected immediately by getters`() {
        // 属性是 get() 计算属性，不缓存 —— 切换后必须立刻反映，
        // 否则切换语言需要重建整个对象
        StringResources.setLanguage(AppLanguage.ENGLISH)
        val en = StringResources.notificationTitle
        StringResources.setLanguage(AppLanguage.CHINESE)
        val zh = StringResources.notificationTitle

        assertNotEquals(en, zh)
        assertEquals(AppLanguage.CHINESE, StringResources.getLanguage())
    }
}
