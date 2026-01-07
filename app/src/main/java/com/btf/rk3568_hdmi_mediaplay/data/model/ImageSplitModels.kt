package com.btf.rk3568_hdmi_mediaplay.data.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 裁剪布局类型
 */
enum class SplitLayout(
    val rows: Int,
    val cols: Int,
    val displayName: String,
    val description: String
) {
    SPLIT_1X2(1, 2, "1×2", "1行2列，横向2屏"),
    SPLIT_2X1(2, 1, "2×1", "2行1列，纵向2屏"),
    SPLIT_1X3(1, 3, "1×3", "1行3列，横向3屏"),
    SPLIT_3X1(3, 1, "3×1", "3行1列，纵向3屏"),
    SPLIT_2X2(2, 2, "2×2", "2行2列，4屏拼接"),
    SPLIT_1X4(1, 4, "1×4", "1行4列，横向4屏"),
    SPLIT_4X1(4, 1, "4×1", "4行1列，纵向4屏");

    /**
     * 总块数
     */
    val totalParts: Int get() = rows * cols

    /**
     * 理想宽高比 (宽/高)
     */
    val idealAspectRatio: Float get() = cols.toFloat() / rows.toFloat()

    companion object {
        /**
         * 根据图片尺寸推荐最佳布局
         */
        fun recommendLayout(imageWidth: Int, imageHeight: Int): SplitLayout {
            val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

            // 根据宽高比推荐
            return when {
                aspectRatio >= 3.5f -> SPLIT_1X4  // 超宽 4:1
                aspectRatio >= 2.5f -> SPLIT_1X3  // 宽 3:1
                aspectRatio >= 1.8f -> SPLIT_1X2  // 宽 2:1
                aspectRatio >= 0.8f && aspectRatio <= 1.2f -> SPLIT_2X2  // 接近方形
                aspectRatio <= 0.3f -> SPLIT_4X1  // 超高 1:4
                aspectRatio <= 0.4f -> SPLIT_3X1  // 高 1:3
                aspectRatio <= 0.6f -> SPLIT_2X1  // 高 1:2
                else -> SPLIT_2X2  // 默认
            }
        }
    }
}

/**
 * 裁剪配置
 * 
 * 标准分辨率裁剪：每块输出固定为目标分辨率（默认1920x1080）
 * 从原图中裁剪出合适的区域，确保输出全屏无黑边
 */
data class SplitConfig(
    val layout: SplitLayout,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int = 1920,   // 目标分辨率宽度
    val targetHeight: Int = 1080   // 目标分辨率高度
) {
    /**
     * 每块的输出宽度（固定为目标分辨率）
     */
    val partWidth: Int get() = targetWidth

    /**
     * 每块的输出高度（固定为目标分辨率）
     */
    val partHeight: Int get() = targetHeight

    /**
     * 计算从原图裁剪的区域宽度
     * 原图总宽度按列数等分
     */
    val cropWidth: Int get() = sourceWidth / layout.cols

    /**
     * 计算从原图裁剪的区域高度
     * 原图总高度按行数等分
     */
    val cropHeight: Int get() = sourceHeight / layout.rows

    /**
     * 获取指定位置的裁剪区域（从原图裁剪的区域）
     * @param row 行索引 (0开始)
     * @param col 列索引 (0开始)
     */
    fun getPartRect(row: Int, col: Int): Rect {
        val left = col * cropWidth
        val top = row * cropHeight
        val right = left + cropWidth
        val bottom = top + cropHeight
        return Rect(left, top, right, bottom)
    }

    /**
     * 获取指定索引的裁剪区域 (按从左到右、从上到下顺序)
     * @param index 索引 (0开始)
     */
    fun getPartRectByIndex(index: Int): Rect {
        val row = index / layout.cols
        val col = index % layout.cols
        return getPartRect(row, col)
    }

    /**
     * 获取所有裁剪区域
     */
    fun getAllPartRects(): List<Rect> {
        return (0 until layout.totalParts).map { getPartRectByIndex(it) }
    }
}

/**
 * 单块裁剪结果
 */
data class SplitPart(
    val index: Int,          // 索引 (0开始，对应 player1~4)
    val row: Int,            // 行
    val col: Int,            // 列
    val rect: Rect,          // 裁剪区域
    val bitmap: Bitmap?,     // 裁剪后的图片 (可能为null表示裁剪失败)
    val savedPath: String?   // 保存路径 (可能为null表示未保存)
)

/**
 * 裁剪结果
 */
data class SplitResult(
    val success: Boolean,
    val config: SplitConfig,
    val parts: List<SplitPart>,
    val errorMessage: String? = null
) {
    /**
     * 成功裁剪的数量
     */
    val successCount: Int get() = parts.count { it.bitmap != null }

    /**
     * 是否全部成功
     */
    val isAllSuccess: Boolean get() = successCount == config.layout.totalParts
}

/**
 * 图片信息
 */
data class ImageInfo(
    val path: String,
    val name: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    val sizeFormatted: String
        get() {
            val kb = sizeBytes / 1024f
            return if (kb >= 1024) {
                String.format("%.1f MB", kb / 1024)
            } else {
                String.format("%.0f KB", kb)
            }
        }

    val dimensionText: String get() = "${width} × ${height}"
}
