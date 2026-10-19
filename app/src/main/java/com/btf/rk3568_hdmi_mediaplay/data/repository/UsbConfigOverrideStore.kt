package com.btf.rk3568_hdmi_mediaplay.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.AppSettings
import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage
import com.btf.rk3568_hdmi_mediaplay.data.model.ImageTransition
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.LoopMode
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import kotlinx.coroutines.flow.first

/**
 * U 盘配置对 [AppSettings] 的**可逆覆盖**。
 *
 * ## 为什么需要它（2026-10-19 审计 03 · 缺陷 C1）
 *
 * U 盘里的 `btf_config.json` 会把 settings 写进 DataStore（`applyUsbConfigSettings`），
 * 但拔盘时**没有任何逆操作**：
 * - `FeatureManager.applyUsbConfig(null)` 只恢复**功能开关**；
 * - `AppSettings` 里的 `layoutMode` / `defaultVolume` / `usbScanFolderName` 等**永久保留**。
 *
 * 后果：客户用 U 盘下发一次 `layoutMode=GRID_1X2`、`defaultVolume=0`，
 * 之后拔盘重启，设备**永远**停在那套配置上；现场无法区分
 * 「这是 U 盘设的还是运维设的」，只能整机重置设置。
 *
 * ## 做法
 *
 * 应用覆盖前，把「被覆盖的键 + 原值」存成快照；拔盘/下次启动无 U 盘时，
 * 只回滚**仍然等于 U 盘设的值**的那些键 —— 运维在设置页手动改过的键不回滚，
 * 否则会覆盖人工作出的决定。
 *
 * 快照持久化在 DataStore 的独立 key 中，因此进程被杀后仍能回滚。
 */
object UsbConfigOverrideStore {

    private const val TAG = "UsbConfigOverride"

    private val KEY_SNAPSHOT = stringPreferencesKey("usb_config_override_snapshot_v1")

    /**
     * 被 U 盘覆盖的单个键：键名 + U 盘写入的值 + 覆盖前的原值。
     *
     * 用字符串编码（`name\nvalue\noriginal` 逐行）而不是新增依赖：
     * 项目已有 org.json，但这里只需要保存少量标量，手写编码更省事且可测。
     */
    data class Entry(val name: String, val original: String, val applied: String)

    // ---- 每个受支持键的取值/赋值（纯函数，便于单测） ----

    internal fun readKey(settings: AppSettings, name: String): String? = when (name) {
        "layoutMode" -> settings.layoutMode.name
        "language" -> settings.language.name
        "backgroundColor" -> settings.backgroundColor.toString()
        "defaultVolume" -> settings.defaultVolume.toString()
        "defaultMuted" -> settings.defaultMuted.toString()
        "imageIntervalSeconds" -> settings.imageIntervalSeconds.toString()
        "imageTransition" -> settings.imageTransition.name
        "loopMode" -> settings.loopMode.name
        "videoScaleMode" -> settings.videoScaleMode.name
        "autoPlayOnStart" -> settings.autoPlayOnStart.toString()
        "autoPlayAfterCopy" -> settings.autoPlayAfterCopy.toString()
        "bootAutoStart" -> settings.bootAutoStart.toString()
        "keepScreenOn" -> settings.keepScreenOn.toString()
        "usbScanFolderName" -> settings.usbScanFolderName
        "showOverwriteConfirm" -> settings.showOverwriteConfirm.toString()
        "audioFallbackSilent" -> settings.audioFallbackSilent.toString()
        else -> null
    }

    /** 受支持键的全集（与 [readKey] 保持一致，供测试校验） */
    internal val SUPPORTED_KEYS = listOf(
        "layoutMode", "language", "backgroundColor", "defaultVolume", "defaultMuted",
        "imageIntervalSeconds", "imageTransition", "loopMode", "videoScaleMode",
        "autoPlayOnStart", "autoPlayAfterCopy", "bootAutoStart", "keepScreenOn", "usbScanFolderName",
        "showOverwriteConfirm", "audioFallbackSilent"
    )

    /**
     * 把字符串值写回设置。值非法时原样返回（不抛异常）。
     */
    internal fun writeKey(settings: AppSettings, name: String, value: String): AppSettings {
        return try {
            when (name) {
                "layoutMode" -> settings.copy(layoutMode = LayoutMode.valueOf(value))
                "language" -> settings.copy(language = AppLanguage.valueOf(value))
                "backgroundColor" -> settings.copy(backgroundColor = value.toLong())
                "defaultVolume" -> settings.copy(defaultVolume = value.toInt())
                "defaultMuted" -> settings.copy(defaultMuted = value.toBooleanStrict())
                "imageIntervalSeconds" -> settings.copy(imageIntervalSeconds = value.toInt())
                "imageTransition" -> settings.copy(imageTransition = ImageTransition.valueOf(value))
                "loopMode" -> settings.copy(loopMode = LoopMode.valueOf(value))
                "videoScaleMode" -> settings.copy(videoScaleMode = VideoScaleMode.valueOf(value))
                "autoPlayOnStart" -> settings.copy(autoPlayOnStart = value.toBooleanStrict())
                "autoPlayAfterCopy" -> settings.copy(autoPlayAfterCopy = value.toBooleanStrict())
                "bootAutoStart" -> settings.copy(bootAutoStart = value.toBooleanStrict())
                "keepScreenOn" -> settings.copy(keepScreenOn = value.toBooleanStrict())
                "usbScanFolderName" -> settings.copy(usbScanFolderName = value)
                "showOverwriteConfirm" -> settings.copy(showOverwriteConfirm = value.toBooleanStrict())
                "audioFallbackSilent" -> settings.copy(audioFallbackSilent = value.toBooleanStrict())
                else -> settings
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeKey($name=$value) failed: ${e.message}")
            settings
        }
    }

    /**
     * 计算「应用 U 盘覆盖后」的设置与需要记录的条目。
     *
     * 只记录**确实发生变化**的键 —— 值本来就相同的键不需要回滚。
     */
    internal fun applyOverrides(
        before: AppSettings,
        overrides: Map<String, String>
    ): Pair<AppSettings, List<Entry>> {
        var current = before
        val entries = mutableListOf<Entry>()
        for ((name, applied) in overrides) {
            val original = readKey(current, name) ?: continue
            val next = writeKey(current, name, applied)
            val actual = readKey(next, name) ?: continue
            if (actual != original) {
                entries += Entry(name, original, actual)
                current = next
            }
        }
        return current to entries
    }

    /**
     * 回滚：仅当当前值**仍等于 U 盘写入的值**时才还原。
     *
     * 运维手动改过的键保持原样（否则会覆盖人工作出的决定）。
     */
    internal fun revert(
        current: AppSettings,
        entries: List<Entry>
    ): Pair<AppSettings, List<String>> {
        var result = current
        val reverted = mutableListOf<String>()
        for (entry in entries) {
            val now = readKey(result, entry.name) ?: continue
            if (now == entry.applied) {
                result = writeKey(result, entry.name, entry.original)
                reverted += entry.name
            }
        }
        return result to reverted
    }

    // ---- 快照编码（纯字符串，便于单测） ----

    internal fun encode(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.name}\t${it.original}\t${it.applied}" }

    internal fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size == 3) Entry(parts[0], parts[1], parts[2]) else null
        }
    }

    // ---- DataStore 读写 ----

    suspend fun loadSnapshot(store: DataStore<Preferences>): List<Entry> = try {
        decode(store.data.first()[KEY_SNAPSHOT])
    } catch (e: Exception) {
        Log.w(TAG, "loadSnapshot failed: ${e.message}")
        emptyList()
    }

    suspend fun saveSnapshot(store: DataStore<Preferences>, entries: List<Entry>) {
        try {
            store.edit { prefs ->
                if (entries.isEmpty()) prefs.remove(KEY_SNAPSHOT)
                else prefs[KEY_SNAPSHOT] = encode(entries)
            }
        } catch (e: Exception) {
            Log.w(TAG, "saveSnapshot failed: ${e.message}")
        }
    }
}
