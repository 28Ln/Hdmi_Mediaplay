package com.btf.rk3568_hdmi_mediaplay.data.model

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.Locale

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

    /**
     * 该裁剪布局对应的播放器布局。
     *
     * 裁剪把一张图切成 N 块写入 player1..N，必须同时把播放器切到对应的
     * 分屏布局，否则用户裁完看不到拼接效果（例如 2×2 裁完但当前是单屏，
     * 只有一格显示），会以为功能没生效。
     *
     * 映射依据：两者的行列数一一对应，**全部 7 种裁剪布局都有对应模式**，
     * 因此 [matchingLayoutMode] 不会返回 null（返回类型保留可空是为了
     * 未来新增裁剪布局时不破坏调用方）。
     */
    val matchingLayoutMode: LayoutMode?
        get() = when (this) {
            SPLIT_1X2 -> LayoutMode.GRID_1X2
            SPLIT_2X1 -> LayoutMode.GRID_2X1
            SPLIT_1X3 -> LayoutMode.GRID_1X3
            SPLIT_3X1 -> LayoutMode.GRID_3X1
            SPLIT_2X2 -> LayoutMode.GRID_2X2
            SPLIT_1X4 -> LayoutMode.ROW_1X4
            SPLIT_4X1 -> LayoutMode.COLUMN_4X1
        }

    companion object {
        /**
         * 裁剪页**允许用户选择**的布局。
         *
         * 排除 1×4 / 4×1：它们没有拼接板编号，因此不出现在设置页的模式列表
         * （`LayoutMode.customerModes`）里。若裁剪后切到这两个模式，
         * 用户会发现设置页找不到它们、**无法切回来** —— 形成"能进不能出"。
         *
         * 若后续确定要支持 4 路单排/单列，正确做法是同时把它们加入
         * `customerModes`，而不是只放开裁剪入口。
         */
        val selectableLayouts: List<SplitLayout> = listOf(
            SPLIT_1X2, SPLIT_2X1, SPLIT_1X3, SPLIT_3X1, SPLIT_2X2
        )

        /**
         * 根据图片尺寸推荐最佳布局。
         *
         * 只会返回 [selectableLayouts] 中的值 —— 推荐值必须是用户能选到的，
         * 否则默认选中项会是一个界面上不存在的卡片。
         */
        fun recommendLayout(imageWidth: Int, imageHeight: Int): SplitLayout {
            val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

            return when {
                aspectRatio >= 2.5f -> SPLIT_1X3   // 宽 3:1 及以上
                aspectRatio >= 1.8f -> SPLIT_1X2   // 宽 2:1
                aspectRatio >= 0.8f && aspectRatio <= 1.2f -> SPLIT_2X2  // 接近方形
                aspectRatio <= 0.4f -> SPLIT_3X1   // 高 1:3 及以上
                aspectRatio <= 0.6f -> SPLIT_2X1   // 高 1:2
                else -> SPLIT_2X2                  // 默认
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
                String.format(Locale.ROOT, "%.1f MB", kb / 1024)
            } else {
                String.format(Locale.ROOT, "%.0f KB", kb)
            }
        }

    val dimensionText: String get() = "${width} × ${height}"
}
