package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import java.io.File

/**
 * 文件选择器帮助类
 */
object FilePickerHelper {
    
    // 支持的 MIME 类型
    private val VIDEO_MIME_TYPES = arrayOf(
        "video/mp4",
        "video/x-matroska",  // mkv
        "video/avi",
        "video/quicktime",   // mov
        "video/x-ms-wmv",
        "video/x-flv",
        "video/webm",
        "video/3gpp"
    )
    
    private val IMAGE_MIME_TYPES = arrayOf(
        "image/jpeg",
        "image/png",
        "image/bmp",
        "image/gif",
        "image/webp"
    )
    
    /**
     * 创建选择视频的 Intent
     */
    fun createVideoPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
    
    /**
     * 创建选择图片的 Intent
     */
    fun createImagePickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
    
    /**
     * 创建选择媒体文件的 Intent (视频和图片)
     */
    fun createMediaPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, VIDEO_MIME_TYPES + IMAGE_MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
    
    /**
     * 从 Uri 获取文件路径
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        return try {
            // 尝试获取真实路径
            getRealPathFromUri(context, uri) ?: uri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            uri.toString()
        }
    }
    
    /**
     * 获取真实文件路径
     */
    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        return try {
            when {
                // DocumentProvider
                DocumentsContract.isDocumentUri(context, uri) -> {
                    getDocumentPath(context, uri)
                }
                // MediaStore
                "content".equals(uri.scheme, ignoreCase = true) -> {
                    getMediaStorePath(context, uri)
                }
                // File
                "file".equals(uri.scheme, ignoreCase = true) -> {
                    uri.path
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun getDocumentPath(context: Context, uri: Uri): String? {
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            
            if ("primary".equals(type, ignoreCase = true)) {
                return "${android.os.Environment.getExternalStorageDirectory()}/${split.getOrNull(1) ?: ""}"
            }
            
            // 外部存储
            val externalStorageVolumes = context.getExternalFilesDirs(null)
            for (volume in externalStorageVolumes) {
                val path = volume?.absolutePath
                if (path != null && path.contains(type)) {
                    val basePath = path.substringBefore("/Android")
                    return "$basePath/${split.getOrNull(1) ?: ""}"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun getMediaStorePath(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 从 Uri 创建 MediaItem
     */
    fun createMediaItemFromUri(context: Context, uri: Uri): MediaItem? {
        return try {
            val path = getPathFromUri(context, uri) ?: return null
            val name = uri.lastPathSegment ?: "unknown"
            val type = MediaTypeUtils.getMediaType(name)
            
            // 获取持久化权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 忽略权限错误
            }
            
            MediaItem(
                path = path,
                name = name,
                type = type,
                source = MediaSource.MANUAL
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 从多个 Uri 创建 MediaItem 列表
     */
    fun createMediaItemsFromUris(context: Context, uris: List<Uri>): List<MediaItem> {
        return uris.mapNotNull { uri ->
            createMediaItemFromUri(context, uri)
        }
    }
}
