package com.btf.rk3568_hdmi_mediaplay.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    /**
     * 供 [UsbConfigOverrideStore] 持久化「U 盘配置覆盖快照」。
     *
     * 复用同一个 DataStore：快照与设置必须在同一次事务语义下才能保持一致
     * （分开存会出现「设置回滚了、快照没了」或反之）。key 是独立的，互不影响。
     */
    internal val preferenceStore: DataStore<Preferences> get() = context.dataStore
    
    companion object {
        // 基础设置
        private val SHOW_OVERWRITE_CONFIRM = booleanPreferencesKey("show_overwrite_confirm")
        private val AUTO_PLAY_ON_START = booleanPreferencesKey("auto_play_on_start")
        private val BOOT_AUTO_START = booleanPreferencesKey("boot_auto_start")
        private val LOOP_MODE = stringPreferencesKey("loop_mode")
        private val LANGUAGE = stringPreferencesKey("language")
        
        // 视频设置
        private val DEFAULT_VOLUME = intPreferencesKey("default_volume")
        private val DEFAULT_MUTED = booleanPreferencesKey("default_muted")
        private val VIDEO_SCALE_MODE = stringPreferencesKey("video_scale_mode")
        private val USE_HARDWARE_DECODE = booleanPreferencesKey("use_hardware_decode")
        
        // 音频设置
        private val AUDIO_OUTPUT = stringPreferencesKey("audio_output")
        
        // 图片设置
        private val IMAGE_INTERVAL_SECONDS = intPreferencesKey("image_interval_seconds")
        private val IMAGE_TRANSITION = stringPreferencesKey("image_transition")
        
        // U盘设置
        private val USB_DETECTION_ENABLED = booleanPreferencesKey("usb_detection_enabled")
        private val USB_SCAN_FOLDER_NAME = stringPreferencesKey("usb_scan_folder_name")
        private val AUTO_PLAY_AFTER_COPY = booleanPreferencesKey("auto_play_after_copy")
        private val SHOW_COPY_PROGRESS = booleanPreferencesKey("show_copy_progress")
        
        // 存储设置
        private val STORAGE_LOCATION = stringPreferencesKey("storage_location")
        private val CUSTOM_STORAGE_PATH = stringPreferencesKey("custom_storage_path")
        
        // 显示设置
        private val LAYOUT_MODE = stringPreferencesKey("layout_mode")
        private val SHOW_PLAYER_INDEX = booleanPreferencesKey("show_player_index")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val BACKGROUND_COLOR = longPreferencesKey("background_color")
        
        // 高级设置
        private val MAX_CACHE_SIZE_MB = intPreferencesKey("max_cache_size_mb")
        private val ENABLE_DEBUG_LOG = booleanPreferencesKey("enable_debug_log")
        private val AUDIO_FALLBACK_SILENT = booleanPreferencesKey("audio_fallback_silent")

        /**
         * 纯函数入口，仅供 JVM 单测调用。
         *
         * `applyDelta` 本身不依赖 Context/DataStore，但它是实例方法；
         * 测试没有真实 DataStore，因此从这里暴露一个静态转发。
         */
        internal fun applyDeltaForTest(
            base: AppSettings,
            previous: AppSettings,
            desired: AppSettings
        ): AppSettings = SettingsRepository.deltaOf(base, previous, desired)

        internal fun deltaOf(
            base: AppSettings,
            previous: AppSettings,
            desired: AppSettings
        ): AppSettings {
            fun <T> pick(before: T, after: T, current: T): T = if (after != before) after else current
            return base.copy(
                showOverwriteConfirm = pick(previous.showOverwriteConfirm, desired.showOverwriteConfirm, base.showOverwriteConfirm),
                autoPlayOnStart = pick(previous.autoPlayOnStart, desired.autoPlayOnStart, base.autoPlayOnStart),
                bootAutoStart = pick(previous.bootAutoStart, desired.bootAutoStart, base.bootAutoStart),
                loopMode = pick(previous.loopMode, desired.loopMode, base.loopMode),
                language = pick(previous.language, desired.language, base.language),
                defaultVolume = pick(previous.defaultVolume, desired.defaultVolume, base.defaultVolume),
                defaultMuted = pick(previous.defaultMuted, desired.defaultMuted, base.defaultMuted),
                videoScaleMode = pick(previous.videoScaleMode, desired.videoScaleMode, base.videoScaleMode),
                useHardwareDecode = pick(previous.useHardwareDecode, desired.useHardwareDecode, base.useHardwareDecode),
                audioOutput = pick(previous.audioOutput, desired.audioOutput, base.audioOutput),
                imageIntervalSeconds = pick(previous.imageIntervalSeconds, desired.imageIntervalSeconds, base.imageIntervalSeconds),
                imageTransition = pick(previous.imageTransition, desired.imageTransition, base.imageTransition),
                usbDetectionEnabled = pick(previous.usbDetectionEnabled, desired.usbDetectionEnabled, base.usbDetectionEnabled),
                usbScanFolderName = pick(previous.usbScanFolderName, desired.usbScanFolderName, base.usbScanFolderName),
                autoPlayAfterCopy = pick(previous.autoPlayAfterCopy, desired.autoPlayAfterCopy, base.autoPlayAfterCopy),
                showCopyProgress = pick(previous.showCopyProgress, desired.showCopyProgress, base.showCopyProgress),
                storageLocation = pick(previous.storageLocation, desired.storageLocation, base.storageLocation),
                customStoragePath = pick(previous.customStoragePath, desired.customStoragePath, base.customStoragePath),
                layoutMode = pick(previous.layoutMode, desired.layoutMode, base.layoutMode),
                showPlayerIndex = pick(previous.showPlayerIndex, desired.showPlayerIndex, base.showPlayerIndex),
                keepScreenOn = pick(previous.keepScreenOn, desired.keepScreenOn, base.keepScreenOn),
                backgroundColor = pick(previous.backgroundColor, desired.backgroundColor, base.backgroundColor),
                maxCacheSizeMB = pick(previous.maxCacheSizeMB, desired.maxCacheSizeMB, base.maxCacheSizeMB),
                enableDebugLog = pick(previous.enableDebugLog, desired.enableDebugLog, base.enableDebugLog),
                audioFallbackSilent = pick(previous.audioFallbackSilent, desired.audioFallbackSilent, base.audioFallbackSilent)
            )
        }
    }
    
    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            // 处理读取错误，返回默认值
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> readSettings(preferences) }

    /**
     * 把 Preferences 解析成 [AppSettings]。
     *
     * 抽成独立函数的原因：[updateSettings] 需要在 `edit {}` 事务内
     * 用**同一份**解析逻辑读取最新值做合并，避免两处规则漂移。
     */
    private fun readSettings(preferences: Preferences): AppSettings =
        try {
            AppSettings(
                showOverwriteConfirm = preferences[SHOW_OVERWRITE_CONFIRM] ?: true,
                autoPlayOnStart = preferences[AUTO_PLAY_ON_START] ?: true,
                bootAutoStart = preferences[BOOT_AUTO_START] ?: false,
                loopMode = safeEnumValueOf(preferences[LOOP_MODE], LoopMode.LIST),
                language = safeEnumValueOf(preferences[LANGUAGE], AppLanguage.CHINESE),

                defaultVolume = preferences[DEFAULT_VOLUME] ?: 100,
                defaultMuted = preferences[DEFAULT_MUTED] ?: false,
                videoScaleMode = safeEnumValueOf(preferences[VIDEO_SCALE_MODE], VideoScaleMode.STRETCH),
                useHardwareDecode = preferences[USE_HARDWARE_DECODE] ?: true,

                audioOutput = safeEnumValueOf(preferences[AUDIO_OUTPUT], AudioOutput.AUTO),

                imageIntervalSeconds = preferences[IMAGE_INTERVAL_SECONDS] ?: 5,
                imageTransition = safeEnumValueOf(preferences[IMAGE_TRANSITION], ImageTransition.FADE),

                usbDetectionEnabled = preferences[USB_DETECTION_ENABLED] ?: true,
                usbScanFolderName = preferences[USB_SCAN_FOLDER_NAME] ?: "media",
                autoPlayAfterCopy = preferences[AUTO_PLAY_AFTER_COPY] ?: true,
                showCopyProgress = preferences[SHOW_COPY_PROGRESS] ?: true,

                storageLocation = safeEnumValueOf(preferences[STORAGE_LOCATION], StorageLocation.SDCARD),
                customStoragePath = preferences[CUSTOM_STORAGE_PATH] ?: "",

                layoutMode = safeEnumValueOf(preferences[LAYOUT_MODE], LayoutMode.GRID_2X2),
                showPlayerIndex = preferences[SHOW_PLAYER_INDEX] ?: true,
                keepScreenOn = preferences[KEEP_SCREEN_ON] ?: true,
                backgroundColor = preferences[BACKGROUND_COLOR] ?: 0xFF000000,

                maxCacheSizeMB = preferences[MAX_CACHE_SIZE_MB] ?: 2048,
                enableDebugLog = preferences[ENABLE_DEBUG_LOG] ?: false,
                audioFallbackSilent = preferences[AUDIO_FALLBACK_SILENT] ?: true
            )
        } catch (e: Exception) {
            // 如果解析失败，返回默认设置
            AppSettings()
        }

    /**
     * 写入设置。
     *
     * @param desired  调用方想要的目标状态
     * @param previous 调用方生成 [desired] 时看到的基线；用于计算「本次真正改了什么」。
     *                 传 null 表示整份覆盖（旧行为，仅限确实需要全量写的场景）。
     *
     * ## 为什么需要它（2026-10-19 修复：丢失更新）
     *
     * 旧实现把整份快照写回。调用方基于 `settings.value` 生成快照，而落盘是
     * `safeLaunch` 异步执行的，于是两条并发链路会互相覆盖：
     *
     * ```
     * T0 用户改音量      -> desired {layout:2x2, vol:70}, 基线 {2x2, 50}
     * T1 U 盘下发布局    -> desired {layout:1x2, vol:50}, 基线 {2x2, 50}
     * T2 T0 落盘 -> {2x2, 70}
     * T3 T1 落盘 -> {1x2, 50}   ← 音量 70 被静默覆盖回 50
     * ```
     *
     * 现在在 `edit {}` 事务内**先重读磁盘最新值**，再把「基线→目标」这段**增量**
     * 应用上去。T3 会读到 `{2x2, 70}`，只覆盖 layout，音量保持 70。
     *
     * U 盘配置下发与用户在设置页操作正是这条链路上最容易撞车的两个来源。
     */
    suspend fun updateSettings(
        desired: AppSettings,
        previous: AppSettings? = null
    ) {
        try {
            context.dataStore.edit { preferences ->
                val base = readSettings(preferences)
                val merged = if (previous == null) desired else applyDelta(base, previous, desired)

                preferences[SHOW_OVERWRITE_CONFIRM] = merged.showOverwriteConfirm
                preferences[AUTO_PLAY_ON_START] = merged.autoPlayOnStart
                preferences[BOOT_AUTO_START] = merged.bootAutoStart
                preferences[LOOP_MODE] = merged.loopMode.name
                preferences[LANGUAGE] = merged.language.name

                preferences[DEFAULT_VOLUME] = merged.defaultVolume
                preferences[DEFAULT_MUTED] = merged.defaultMuted
                preferences[VIDEO_SCALE_MODE] = merged.videoScaleMode.name
                preferences[USE_HARDWARE_DECODE] = merged.useHardwareDecode

                preferences[AUDIO_OUTPUT] = merged.audioOutput.name

                preferences[IMAGE_INTERVAL_SECONDS] = merged.imageIntervalSeconds
                preferences[IMAGE_TRANSITION] = merged.imageTransition.name

                preferences[USB_DETECTION_ENABLED] = merged.usbDetectionEnabled
                preferences[USB_SCAN_FOLDER_NAME] = merged.usbScanFolderName
                preferences[AUTO_PLAY_AFTER_COPY] = merged.autoPlayAfterCopy
                preferences[SHOW_COPY_PROGRESS] = merged.showCopyProgress

                preferences[STORAGE_LOCATION] = merged.storageLocation.name
                preferences[CUSTOM_STORAGE_PATH] = merged.customStoragePath

                preferences[LAYOUT_MODE] = merged.layoutMode.name
                preferences[SHOW_PLAYER_INDEX] = merged.showPlayerIndex
                preferences[KEEP_SCREEN_ON] = merged.keepScreenOn
                preferences[BACKGROUND_COLOR] = merged.backgroundColor

                preferences[MAX_CACHE_SIZE_MB] = merged.maxCacheSizeMB
                preferences[ENABLE_DEBUG_LOG] = merged.enableDebugLog
                preferences[AUDIO_FALLBACK_SILENT] = merged.audioFallbackSilent
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 把「[previous] → [desired]」的字段差异应用到 [base]（磁盘最新值）上。
     *
     * 只有调用方**确实改动过**的字段才覆盖；其余字段保留 [base] 的并发写入，
     * 从而避免丢失更新。
     */
    internal fun applyDelta(
        base: AppSettings,
        previous: AppSettings,
        desired: AppSettings
    ): AppSettings {
        fun <T> pick(before: T, after: T, current: T): T = if (after != before) after else current

        return base.copy(
            showOverwriteConfirm = pick(previous.showOverwriteConfirm, desired.showOverwriteConfirm, base.showOverwriteConfirm),
            autoPlayOnStart = pick(previous.autoPlayOnStart, desired.autoPlayOnStart, base.autoPlayOnStart),
            bootAutoStart = pick(previous.bootAutoStart, desired.bootAutoStart, base.bootAutoStart),
            loopMode = pick(previous.loopMode, desired.loopMode, base.loopMode),
            language = pick(previous.language, desired.language, base.language),

            defaultVolume = pick(previous.defaultVolume, desired.defaultVolume, base.defaultVolume),
            defaultMuted = pick(previous.defaultMuted, desired.defaultMuted, base.defaultMuted),
            videoScaleMode = pick(previous.videoScaleMode, desired.videoScaleMode, base.videoScaleMode),
            useHardwareDecode = pick(previous.useHardwareDecode, desired.useHardwareDecode, base.useHardwareDecode),

            audioOutput = pick(previous.audioOutput, desired.audioOutput, base.audioOutput),

            imageIntervalSeconds = pick(previous.imageIntervalSeconds, desired.imageIntervalSeconds, base.imageIntervalSeconds),
            imageTransition = pick(previous.imageTransition, desired.imageTransition, base.imageTransition),

            usbDetectionEnabled = pick(previous.usbDetectionEnabled, desired.usbDetectionEnabled, base.usbDetectionEnabled),
            usbScanFolderName = pick(previous.usbScanFolderName, desired.usbScanFolderName, base.usbScanFolderName),
            autoPlayAfterCopy = pick(previous.autoPlayAfterCopy, desired.autoPlayAfterCopy, base.autoPlayAfterCopy),
            showCopyProgress = pick(previous.showCopyProgress, desired.showCopyProgress, base.showCopyProgress),

            storageLocation = pick(previous.storageLocation, desired.storageLocation, base.storageLocation),
            customStoragePath = pick(previous.customStoragePath, desired.customStoragePath, base.customStoragePath),

            layoutMode = pick(previous.layoutMode, desired.layoutMode, base.layoutMode),
            showPlayerIndex = pick(previous.showPlayerIndex, desired.showPlayerIndex, base.showPlayerIndex),
            keepScreenOn = pick(previous.keepScreenOn, desired.keepScreenOn, base.keepScreenOn),
            backgroundColor = pick(previous.backgroundColor, desired.backgroundColor, base.backgroundColor),

            maxCacheSizeMB = pick(previous.maxCacheSizeMB, desired.maxCacheSizeMB, base.maxCacheSizeMB),
            enableDebugLog = pick(previous.enableDebugLog, desired.enableDebugLog, base.enableDebugLog),
            audioFallbackSilent = pick(previous.audioFallbackSilent, desired.audioFallbackSilent, base.audioFallbackSilent)
        )
    }

    suspend fun resetToDefaults() {
        try {
            context.dataStore.edit { it.clear() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 安全的枚举值解析
     */
    private inline fun <reified T : Enum<T>> safeEnumValueOf(value: String?, default: T): T {
        return try {
            value?.let { enumValueOf<T>(it) } ?: default
        } catch (e: Exception) {
            default
        }
    }
}
