package com.btf.rk3568_hdmi_mediaplay.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        // 基础设置
        private val SHOW_OVERWRITE_CONFIRM = booleanPreferencesKey("show_overwrite_confirm")
        private val AUTO_PLAY_ON_START = booleanPreferencesKey("auto_play_on_start")
        private val BOOT_AUTO_START = booleanPreferencesKey("boot_auto_start")
        private val LOOP_MODE = stringPreferencesKey("loop_mode")
        
        // 视频设置
        private val DEFAULT_VOLUME = intPreferencesKey("default_volume")
        private val DEFAULT_MUTED = booleanPreferencesKey("default_muted")
        private val VIDEO_SCALE_MODE = stringPreferencesKey("video_scale_mode")
        private val USE_HARDWARE_DECODE = booleanPreferencesKey("use_hardware_decode")
        
        // 图片设置
        private val IMAGE_INTERVAL_SECONDS = intPreferencesKey("image_interval_seconds")
        private val IMAGE_TRANSITION = stringPreferencesKey("image_transition")
        
        // U盘设置
        private val USB_DETECTION_ENABLED = booleanPreferencesKey("usb_detection_enabled")
        private val USB_SCAN_FOLDER_NAME = stringPreferencesKey("usb_scan_folder_name")
        private val AUTO_PLAY_AFTER_COPY = booleanPreferencesKey("auto_play_after_copy")
        private val SHOW_COPY_PROGRESS = booleanPreferencesKey("show_copy_progress")
        
        // 显示设置
        private val LAYOUT_MODE = stringPreferencesKey("layout_mode")
        private val SHOW_PLAYER_INDEX = booleanPreferencesKey("show_player_index")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val BACKGROUND_COLOR = longPreferencesKey("background_color")
        
        // 高级设置
        private val MAX_CACHE_SIZE_MB = intPreferencesKey("max_cache_size_mb")
        private val ENABLE_DEBUG_LOG = booleanPreferencesKey("enable_debug_log")
    }
    
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            showOverwriteConfirm = preferences[SHOW_OVERWRITE_CONFIRM] ?: true,
            autoPlayOnStart = preferences[AUTO_PLAY_ON_START] ?: true,
            bootAutoStart = preferences[BOOT_AUTO_START] ?: false,
            loopMode = preferences[LOOP_MODE]?.let { LoopMode.valueOf(it) } ?: LoopMode.LIST,
            
            defaultVolume = preferences[DEFAULT_VOLUME] ?: 100,
            defaultMuted = preferences[DEFAULT_MUTED] ?: false,
            videoScaleMode = preferences[VIDEO_SCALE_MODE]?.let { VideoScaleMode.valueOf(it) } ?: VideoScaleMode.FIT,
            useHardwareDecode = preferences[USE_HARDWARE_DECODE] ?: true,
            
            imageIntervalSeconds = preferences[IMAGE_INTERVAL_SECONDS] ?: 5,
            imageTransition = preferences[IMAGE_TRANSITION]?.let { ImageTransition.valueOf(it) } ?: ImageTransition.FADE,
            
            usbDetectionEnabled = preferences[USB_DETECTION_ENABLED] ?: true,
            usbScanFolderName = preferences[USB_SCAN_FOLDER_NAME] ?: "media",
            autoPlayAfterCopy = preferences[AUTO_PLAY_AFTER_COPY] ?: true,
            showCopyProgress = preferences[SHOW_COPY_PROGRESS] ?: true,
            
            layoutMode = preferences[LAYOUT_MODE]?.let { LayoutMode.valueOf(it) } ?: LayoutMode.GRID_2X2,
            showPlayerIndex = preferences[SHOW_PLAYER_INDEX] ?: true,
            keepScreenOn = preferences[KEEP_SCREEN_ON] ?: true,
            backgroundColor = preferences[BACKGROUND_COLOR] ?: 0xFF000000,
            
            maxCacheSizeMB = preferences[MAX_CACHE_SIZE_MB] ?: 2048,
            enableDebugLog = preferences[ENABLE_DEBUG_LOG] ?: false
        )
    }
    
    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_OVERWRITE_CONFIRM] = settings.showOverwriteConfirm
            preferences[AUTO_PLAY_ON_START] = settings.autoPlayOnStart
            preferences[BOOT_AUTO_START] = settings.bootAutoStart
            preferences[LOOP_MODE] = settings.loopMode.name
            
            preferences[DEFAULT_VOLUME] = settings.defaultVolume
            preferences[DEFAULT_MUTED] = settings.defaultMuted
            preferences[VIDEO_SCALE_MODE] = settings.videoScaleMode.name
            preferences[USE_HARDWARE_DECODE] = settings.useHardwareDecode
            
            preferences[IMAGE_INTERVAL_SECONDS] = settings.imageIntervalSeconds
            preferences[IMAGE_TRANSITION] = settings.imageTransition.name
            
            preferences[USB_DETECTION_ENABLED] = settings.usbDetectionEnabled
            preferences[USB_SCAN_FOLDER_NAME] = settings.usbScanFolderName
            preferences[AUTO_PLAY_AFTER_COPY] = settings.autoPlayAfterCopy
            preferences[SHOW_COPY_PROGRESS] = settings.showCopyProgress
            
            preferences[LAYOUT_MODE] = settings.layoutMode.name
            preferences[SHOW_PLAYER_INDEX] = settings.showPlayerIndex
            preferences[KEEP_SCREEN_ON] = settings.keepScreenOn
            preferences[BACKGROUND_COLOR] = settings.backgroundColor
            
            preferences[MAX_CACHE_SIZE_MB] = settings.maxCacheSizeMB
            preferences[ENABLE_DEBUG_LOG] = settings.enableDebugLog
        }
    }
    
    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}
