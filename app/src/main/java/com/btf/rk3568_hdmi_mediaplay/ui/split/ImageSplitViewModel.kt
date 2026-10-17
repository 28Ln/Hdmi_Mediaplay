package com.btf.rk3568_hdmi_mediaplay.ui.split

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.repository.SettingsRepository
import com.btf.rk3568_hdmi_mediaplay.data.model.LayoutMode
import com.btf.rk3568_hdmi_mediaplay.data.model.VideoScaleMode
import com.btf.rk3568_hdmi_mediaplay.data.model.ImageInfo
import com.btf.rk3568_hdmi_mediaplay.data.model.SplitConfig
import com.btf.rk3568_hdmi_mediaplay.data.model.SplitLayout
import com.btf.rk3568_hdmi_mediaplay.data.model.SplitResult
import com.btf.rk3568_hdmi_mediaplay.util.ImageSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 图片裁剪 ViewModel
 */
class ImageSplitViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ImageSplitViewModel"
    }

    private val localStorageManager = LocalStorageManager(application)
    private val settingsRepository = SettingsRepository(application)

    // 当前选择的图片 URI
    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    // 图片信息
    private val _imageInfo = MutableStateFlow<ImageInfo?>(null)
    val imageInfo: StateFlow<ImageInfo?> = _imageInfo.asStateFlow()

    // 选择的布局
    private val _selectedLayout = MutableStateFlow(SplitLayout.SPLIT_2X2)
    val selectedLayout: StateFlow<SplitLayout> = _selectedLayout.asStateFlow()

    // 推荐的布局
    private val _recommendedLayout = MutableStateFlow<SplitLayout?>(null)
    val recommendedLayout: StateFlow<SplitLayout?> = _recommendedLayout.asStateFlow()

    // 裁剪配置
    private val _splitConfig = MutableStateFlow<SplitConfig?>(null)
    val splitConfig: StateFlow<SplitConfig?> = _splitConfig.asStateFlow()

    // 裁剪结果
    private val _splitResult = MutableStateFlow<SplitResult?>(null)
    val splitResult: StateFlow<SplitResult?> = _splitResult.asStateFlow()

    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 错误消息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 成功消息
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    /**
     * 选择图片
     */
    fun selectImage(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _splitResult.value = null

            try {
                _selectedImageUri.value = uri

                // 获取图片信息
                val info = ImageSplitter.getImageInfo(getApplication(), uri)
                if (info == null) {
                    _errorMessage.value = "无法读取图片信息"
                    _isLoading.value = false
                    return@launch
                }

                _imageInfo.value = info
                Log.d(TAG, "Image selected: ${info.name}, ${info.dimensionText}")

                // 推荐布局
                val recommended = SplitLayout.recommendLayout(info.width, info.height)
                _recommendedLayout.value = recommended
                _selectedLayout.value = recommended

                // 更新配置
                updateSplitConfig()

            } catch (e: Exception) {
                Log.e(TAG, "Error selecting image: ${e.message}", e)
                _errorMessage.value = "选择图片失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 选择布局
     */
    fun selectLayout(layout: SplitLayout) {
        _selectedLayout.value = layout
        updateSplitConfig()
    }

    /**
     * 更新裁剪配置
     */
    private fun updateSplitConfig() {
        val info = _imageInfo.value ?: return
        _splitConfig.value = SplitConfig(
            layout = _selectedLayout.value,
            sourceWidth = info.width,
            sourceHeight = info.height
        )
    }

    /**
     * 执行裁剪
     */
    fun executeSplit() {
        val uri = _selectedImageUri.value ?: return
        val layout = _selectedLayout.value

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _successMessage.value = null

            try {
                Log.d(TAG, "Executing split: layout=$layout")

                val result = ImageSplitter.splitImage(getApplication(), uri, layout)

                if (result.success) {
                    _splitResult.value = result
                    _successMessage.value = "裁剪完成，共 ${result.successCount} 块"
                } else {
                    _errorMessage.value = result.errorMessage ?: "裁剪失败"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing split: ${e.message}", e)
                _errorMessage.value = "裁剪失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 保存并应用到播放器
     */
    fun saveAndApply(onComplete: (Boolean, String) -> Unit) {
        val result = _splitResult.value
        if (result == null || !result.success) {
            onComplete(false, "请先执行裁剪")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true

            try {
                // 获取存储目录
                val baseDir = withContext(Dispatchers.IO) {
                    localStorageManager.getPlayerBaseDir()
                }

                if (baseDir == null) {
                    onComplete(false, "无法获取存储目录")
                    _isLoading.value = false
                    return@launch
                }

                // 生成文件名
                val fileName = "split_${System.currentTimeMillis()}.jpg"

                // 保存到各播放器目录
                val savedResult = ImageSplitter.saveSplitResultToPlayerDirs(
                    result = result,
                    baseDir = baseDir,
                    fileName = fileName
                )

                // 释放 Bitmap 资源
                ImageSplitter.recycleSplitResult(result)
                _splitResult.value = savedResult

                val savedCount = savedResult.parts.count { it.savedPath != null }
                val totalParts = result.config.layout.totalParts

                if (savedCount == totalParts) {
                    // 裁剪各块写入 player1..N，必须同时把播放器切到对应分屏布局，
                    // 否则用户看不到拼接效果（例如 2×2 裁完但当前是单屏，只显示一格）。
                    val layoutApplied = applyLayoutForSplit(result.config.layout)
                    if (layoutApplied) {
                        onComplete(true, "已保存 $savedCount 块并切换到对应布局")
                    } else {
                        // 图片已保存成功，只是布局没能切换 —— 必须如实告诉用户，
                        // 否则他会以为看到的是拼接效果，实际只是单格。
                        onComplete(
                            true,
                            "已保存 $savedCount 块，但未能自动切换布局，请手动选择对应模式"
                        )
                    }
                } else {
                    onComplete(false, "部分保存失败，成功 $savedCount/$totalParts")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error saving split result: ${e.message}", e)
                onComplete(false, "保存失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 把播放器切到与裁剪布局匹配的分屏模式。
     *
     * 逐块裁剪的结果分别写入 player1..N，只有播放器也切到对应分屏
     * 才能拼成完整画面。同时把缩放模式设为拉伸，避免每格留黑边拼不回原图。
     *
     * 若当前是「2×2 同画面」（四格播同一路），裁剪结果会四格显示同一块，
     * 因此强制切回独立四宫格。
     */
    private suspend fun applyLayoutForSplit(layout: SplitLayout): Boolean {
        val target = layout.matchingLayoutMode
        if (target == null) {
            Log.w(TAG, "布局 $layout 无对应播放模式，跳过自动切换")
            return false
        }
        return try {
            val current = settingsRepository.settingsFlow.first()
            val updated = current.copy(
                layoutMode = target,
                videoScaleMode = VideoScaleMode.STRETCH
            )
            if (updated == current) {
                // 已经是目标布局，无需切换
                return true
            }
            settingsRepository.updateSettings(updated)
            // 写入后回读确认：updateSettings 内部会吞掉异常（只 printStackTrace），
            // 不能仅凭"它没抛"就认为写入成功。
            // 此前毫无校验就提示"已切换到对应布局"，用户可能看到成功提示、
            // 画面却仍是旧布局。
            val persisted = settingsRepository.settingsFlow.first().layoutMode
            val ok = persisted == target
            if (ok) {
                Log.i(TAG, "裁剪完成：布局切到 $target，缩放设为 STRETCH")
            } else {
                Log.e(TAG, "自动切换布局未生效：期望 $target，实际 $persisted")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "自动切换布局失败", e)
            false
        }
    }

    /**
     * 清除状态
     */
    fun clear() {
        // 释放 Bitmap 资源
        _splitResult.value?.let { ImageSplitter.recycleSplitResult(it) }

        _selectedImageUri.value = null
        _imageInfo.value = null
        _splitConfig.value = null
        _splitResult.value = null
        _errorMessage.value = null
        _successMessage.value = null
        _selectedLayout.value = SplitLayout.SPLIT_2X2
        _recommendedLayout.value = null
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun dismissSuccess() {
        _successMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // 释放资源
        _splitResult.value?.let { ImageSplitter.recycleSplitResult(it) }
    }
}
