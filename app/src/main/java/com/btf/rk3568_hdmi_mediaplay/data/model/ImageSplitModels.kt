package com.btf.rk3568_hdmi_mediaplay.data.model

import android.graphics.Bitmap
import android.net.Uri

/**
 * 分割布局类型
 */
enum class SplitLayout(
    val rows: Int,
    val cols: Int,
    val displayName: String,
    val displayNameEn: String
) {
    LAYOUT_1X2(1, 2, "1行2列", "1x2"),
    LAYOUT_2X1(2, 1, "2行1列", "2x1"),
    LAYOUT_1X3(1, 3, "1行3列", "1x3"),
    LAYOUT_3X1(3, 1, "3行1列", "3x1"),
    LAYOUT_2X2(2, 2, "2行2列", "2x2"),
    LAYOUT_1X4(1, 4, "1行4列", "1x4"),
    LAYOUT_4X1(4, 1, "4行1列", "4x1");

    /**
     * 总块数
     */
    val totalParts: Int get() = rows * cols

    /**
     * 获取显示名称
     */
    fun getDisplayName(isChinese: Boolean): String {
        return if (isChinese) displayName else displayNameEn
    }

    companion object {
        /**
         * 根据图片比例推荐最佳布局
         */
        fun recommendLayout(imageWidth: Int, imageHeight: Int): SplitLayout {
            val ratio = imageWidth.toFloat() / imageHeight.toFloat()
            return when {
                ratio >= 3.5f -> LAYOUT_1X4  // 超宽图 (如 7680x1080 = 7.1)
                ratio >= 2.5f -> LAYOUT_1X3  // 宽图 (如 5760x1080 = 5.3)
                ratio >= 1.5f -> LAYOUT_1X2  // 较宽图 (如 3840x1080 = 3.5)
                ratio <= 0.3f -> LAYOUT_4X1  // 超高图
                ratio <= 0.4f -> LAYOUT_3X1  // 高图
                ratio <= 0.7f -> LAYOUT_2X1  // 较高图
                else -> LAYOUT_2X2           // 接近正方形
            }
        }
    }
}

/**
 * 分割配置
 */
data class SplitConfig(
    val sourceUri: Uri,              // 源图片URI
    val sourceWidth: Int,            // 源图片宽度
    val sourceHeight: Int,           // 源图片高度
    val layout: SplitLayout,         // 分割布局
    val saveToFile: Boolean = false, // 是否保存到文件
    val outputQuality: Int = 90      // 输出质量 (1-100)
) {
    /**
     * 计算每块的宽度
     */
    val partWidth: Int get() = sourceWidth / layout.cols

    /**
     * 计算每块的高度
     */
    val partHeight: Int get() = sourceHeight / layout.rows

    /**
     * 获取指定位置的裁剪区域
     * @param index 块索引 (0-based)
     * @return Pair(x, y) 左上角坐标
     */
    fun getPartPosition(index: Int): Pair<Int, Int> {
        val row = index / layout.cols
        val col = index % layout.cols
        val x = col * partWidth
        val y = row * partHeight
        return Pair(x, y)
    }

    /**
     * 获取指定位置对应的播放器索引
     * @param index 块索引 (0-based)
     * @return 播放器索引 (0-3)
     */
    fun getPlayerIndex(index: Int): Int {
        // 直接映射，最多4个播放器
        return index.coerceIn(0, 3)
    }
}

/**
 * 单块分割结果
 */
data class SplitPart(
    val index: Int,           // 块索引
    val playerIndex: Int,     // 对应播放器索引
    val bitmap: Bitmap?,      // 分割后的位图（内存中）
    val savedPath: String?,   // 保存的文件路径（如果保存了）
    val x: Int,               // 在原图中的X坐标
    val y: Int,               // 在原图中的Y坐标
    val width: Int,           // 宽度
    val height: Int           // 高度
)

/**
 * 分割结果
 */
data class SplitResult(
    val success: Boolean,
    val config: SplitConfig,
    val parts: List<SplitPart>,
    val errorMessage: String? = null
) {
    /**
     * 获取所有成功分割的部分
     */
    fun getSuccessfulParts(): List<SplitPart> {
        return parts.filter { it.bitmap != null || it.savedPath != null }
    }

    /**
     * 释放所有Bitmap资源
     */
    fun recycle() {
        parts.forEach { part ->
            part.bitmap?.recycle()
        }
    }
}

/**
 * 图片信息
 */
data class ImageInfo(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val fileSize: Long
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    fun getRecommendedLayout(): SplitLayout {
        return SplitLayout.recommendLayout(width, height)
    }

    fun getSizeDescription(): String {
        return "${width} x ${height}"
    }

    fun getFileSizeDescription(): String {
        return when {
            fileSize >= 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            fileSize >= 1024 -> "${fileSize / 1024} KB"
            else -> "$fileSize B"
        }
    }
}
