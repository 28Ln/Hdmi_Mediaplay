package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 图片裁剪工具
 */
object ImageSplitter {

    private const val TAG = "ImageSplitter"

    /**
     * 获取图片信息（不加载完整图片，只读取尺寸）
     */
    suspend fun getImageInfo(context: Context, uri: Uri): ImageInfo? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "Invalid image dimensions")
                return@withContext null
            }

            // 获取文件名
            val name = FilePickerHelper.getFileName(context, uri)

            // 获取文件大小
            val sizeBytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
            } catch (e: Exception) {
                0L
            }

            ImageInfo(
                path = uri.toString(),
                name = name,
                width = options.outWidth,
                height = options.outHeight,
                sizeBytes = sizeBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting image info: ${e.message}", e)
            null
        }
    }

    /**
     * 加载图片（用于裁剪），按目标总尺寸降采样以避免 OOM。
     *
     * 此前直接 `BitmapFactory.decodeStream` 全尺寸解码：一张 8K 图（约 132MB ARGB_8888）
     * 再加各分块的中间位图，会直接 OOM（`largeHeap` 只是把崩溃点往后推）。
     * 这里先读边界算出所需 `inSampleSize`，使解码后的像素量不超过
     * 「分块数 × 目标分辨率」的量级。
     *
     * @param minWidth  期望的解码最小宽度（通常 = cols × targetWidth）
     * @param minHeight 期望的解码最小高度（通常 = rows × targetHeight）
     */
    suspend fun loadBitmap(
        context: Context,
        uri: Uri,
        minWidth: Int = 0,
        minHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 第一步：只读边界，不解码像素
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "Invalid image bounds: ${bounds.outWidth}x${bounds.outHeight}")
                return@withContext null
            }

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, minWidth, minHeight)
            Log.d(TAG, "Decoding ${bounds.outWidth}x${bounds.outHeight} with inSampleSize=$sampleSize")

            // 第二步：按采样率解码
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: OutOfMemoryError) {
            // OOM 是 Error 而非 Exception，必须单独捕获，否则进程直接崩溃
            Log.e(TAG, "OutOfMemoryError decoding bitmap", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${e.message}", e)
            null
        }
    }

    /**
     * 计算 2 的幂次采样率，使解码结果不小于 [minWidth]×[minHeight]，
     * 同时尽量接近它（避免解码过大导致 OOM）。
     */
    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        minWidth: Int,
        minHeight: Int
    ): Int {
        if (minWidth <= 0 || minHeight <= 0) return 1
        var sampleSize = 1
        // 每次放大 2 倍采样率，直到再放大就会小于所需尺寸
        while (width / (sampleSize * 2) >= minWidth && height / (sampleSize * 2) >= minHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 执行图片裁剪
     * @param context 上下文
     * @param uri 图片URI
     * @param layout 裁剪布局
     * @param targetWidth 目标分辨率宽度（默认1920）
     * @param targetHeight 目标分辨率高度（默认1080）
     */
    suspend fun splitImage(
        context: Context,
        uri: Uri,
        layout: SplitLayout,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080
    ): SplitResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting split: uri=$uri, layout=$layout, target=${targetWidth}x${targetHeight}")

            // 加载图片：按「分块数 × 目标分辨率」降采样，避免大图 OOM。
            // 裁切区域按解码后的实际宽高计算，最终每块仍缩放到 targetWidth×targetHeight，
            // 因此降采样不改变输出尺寸与裁切比例。
            val sourceBitmap = loadBitmap(
                context,
                uri,
                minWidth = layout.cols * targetWidth,
                minHeight = layout.rows * targetHeight
            )
            if (sourceBitmap == null) {
                return@withContext SplitResult(
                    success = false,
                    config = SplitConfig(layout, 0, 0, targetWidth, targetHeight),
                    parts = emptyList(),
                    errorMessage = "无法加载图片"
                )
            }

            val config = SplitConfig(
                layout = layout,
                sourceWidth = sourceBitmap.width,
                sourceHeight = sourceBitmap.height,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )

            Log.d(TAG, "Source: ${config.sourceWidth}x${config.sourceHeight}, " +
                    "Crop: ${config.cropWidth}x${config.cropHeight}, " +
                    "Output: ${config.partWidth}x${config.partHeight}")

            // 裁剪每一块
            val parts = mutableListOf<SplitPart>()

            for (index in 0 until layout.totalParts) {
                val row = index / layout.cols
                val col = index % layout.cols
                val rect = config.getPartRectByIndex(index)

                Log.d(TAG, "Splitting part $index: row=$row, col=$col, rect=$rect -> ${targetWidth}x${targetHeight}")

                val partBitmap = try {
                    cropAndScaleBitmap(sourceBitmap, rect, targetWidth, targetHeight)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cropping part $index: ${e.message}", e)
                    null
                }

                parts.add(
                    SplitPart(
                        index = index,
                        row = row,
                        col = col,
                        rect = rect,
                        bitmap = partBitmap,
                        savedPath = null
                    )
                )
            }

            // 释放源图片
            sourceBitmap.recycle()

            SplitResult(
                success = parts.all { it.bitmap != null },
                config = config,
                parts = parts
            )
        } catch (e: OutOfMemoryError) {
            // OOM 是 Error 而非 Exception：不单独捕获会直接崩溃进程。
            // 降采样后仍 OOM 说明源图确实超出设备能力，返回可读的失败信息。
            Log.e(TAG, "OutOfMemoryError splitting image", e)
            SplitResult(
                success = false,
                config = SplitConfig(layout, 0, 0, targetWidth, targetHeight),
                parts = emptyList(),
                errorMessage = "图片过大，内存不足，裁剪失败"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error splitting image: ${e.message}", e)
            SplitResult(
                success = false,
                config = SplitConfig(layout, 0, 0, targetWidth, targetHeight),
                parts = emptyList(),
                errorMessage = e.message ?: "裁剪失败"
            )
        }
    }

    /**
     * 裁剪图片的指定区域，并缩放到目标分辨率
     * @param source 源图片
     * @param rect 裁剪区域
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     */
    private fun cropAndScaleBitmap(
        source: Bitmap, 
        rect: Rect, 
        targetWidth: Int, 
        targetHeight: Int
    ): Bitmap {
        // 确保裁剪区域在图片范围内
        val safeRect = Rect(
            rect.left.coerceIn(0, source.width),
            rect.top.coerceIn(0, source.height),
            rect.right.coerceIn(0, source.width),
            rect.bottom.coerceIn(0, source.height)
        )

        val cropWidth = safeRect.width()
        val cropHeight = safeRect.height()

        if (cropWidth <= 0 || cropHeight <= 0) {
            throw IllegalArgumentException("Invalid crop rect: $safeRect")
        }

        // 先裁剪
        val croppedBitmap = Bitmap.createBitmap(source, safeRect.left, safeRect.top, cropWidth, cropHeight)
        
        // 如果裁剪后的尺寸与目标尺寸相同，直接返回
        if (cropWidth == targetWidth && cropHeight == targetHeight) {
            return croppedBitmap
        }
        
        // 缩放到目标分辨率
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)
        
        // 释放中间的裁剪图片
        if (scaledBitmap != croppedBitmap) {
            croppedBitmap.recycle()
        }
        
        return scaledBitmap
    }

    /**
     * 裁剪图片的指定区域（保持原始尺寸，不缩放）
     */
    private fun cropBitmap(source: Bitmap, rect: Rect): Bitmap {
        // 确保裁剪区域在图片范围内
        val safeRect = Rect(
            rect.left.coerceIn(0, source.width),
            rect.top.coerceIn(0, source.height),
            rect.right.coerceIn(0, source.width),
            rect.bottom.coerceIn(0, source.height)
        )

        val width = safeRect.width()
        val height = safeRect.height()

        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Invalid crop rect: $safeRect")
        }

        return Bitmap.createBitmap(source, safeRect.left, safeRect.top, width, height)
    }

    /**
     * 保存裁剪结果到指定目录
     * @param result 裁剪结果
     * @param outputDir 输出目录
     * @param fileNamePrefix 文件名前缀
     * @return 更新后的裁剪结果（包含保存路径）
     */
    suspend fun saveSplitResult(
        result: SplitResult,
        outputDir: File,
        fileNamePrefix: String = "split"
    ): SplitResult = withContext(Dispatchers.IO) {
        if (!result.success) return@withContext result

        // 确保输出目录存在
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val updatedParts = result.parts.map { part ->
            if (part.bitmap == null) return@map part

            val fileName = "${fileNamePrefix}_${part.index + 1}.jpg"
            val outputFile = File(outputDir, fileName)

            try {
                FileOutputStream(outputFile).use { out ->
                    part.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Log.d(TAG, "Saved part ${part.index} to ${outputFile.absolutePath}")
                part.copy(savedPath = outputFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving part ${part.index}: ${e.message}", e)
                part
            }
        }

        result.copy(parts = updatedParts)
    }

    /**
     * 保存裁剪结果到各播放器目录
     * @param result 裁剪结果
     * @param baseDir 基础目录 (包含 player1, player2 等子目录)
     * @param fileName 文件名
     */
    suspend fun saveSplitResultToPlayerDirs(
        result: SplitResult,
        baseDir: File,
        fileName: String = "split.jpg"
    ): SplitResult = withContext(Dispatchers.IO) {
        if (!result.success) return@withContext result

        val updatedParts = result.parts.map { part ->
            if (part.bitmap == null) return@map part

            val playerDir = File(baseDir, "player${part.index + 1}")
            if (!playerDir.exists()) {
                playerDir.mkdirs()
            }

            val outputFile = File(playerDir, fileName)

            try {
                FileOutputStream(outputFile).use { out ->
                    part.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Log.d(TAG, "Saved part ${part.index} to ${outputFile.absolutePath}")
                part.copy(savedPath = outputFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving part ${part.index}: ${e.message}", e)
                part
            }
        }

        result.copy(parts = updatedParts)
    }

    /**
     * 释放裁剪结果中的 Bitmap 资源
     */
    fun recycleSplitResult(result: SplitResult) {
        result.parts.forEach { part ->
            part.bitmap?.recycle()
        }
    }
}
