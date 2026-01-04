package com.btf.rk3568_hdmi_mediaplay.ui.split

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btf.rk3568_hdmi_mediaplay.data.local.LocalStorageManager
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import com.btf.rk3568_hdmi_mediaplay.util.ImageSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片裁剪 ViewModel
 */
class ImageSplitViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ImageSplitViewModel"
    }

    private val localStorageManager = LocalStorageManager(application)

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
                if (savedCount == result.config.layout.totalParts) {
                    onComplete(true, "已保存 $savedCount 块到播放器目录")
                } else {
                    onComplete(false, "部分保存失败，成功 $savedCount/${result.config.layout.totalParts}")
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
