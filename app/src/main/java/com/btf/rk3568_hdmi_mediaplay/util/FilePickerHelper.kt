package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaItem
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaSource
import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType

/**
 * 文件选择器帮助类
 */
object FilePickerHelper {
    
    private const val TAG = "FilePickerHelper"
    
    /**
     * 从 Uri 获取文件名
     */
    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown"
        
        try {
            // 方法1: 从 ContentResolver 查询
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get name from cursor: ${e.message}")
        }
        
        // 方法2: 从路径提取
        if (name == "unknown") {
            val path = uri.path
            if (path != null) {
                name = path.substringAfterLast('/')
            }
        }
        
        // 方法3: 从 lastPathSegment
        if (name == "unknown" || name.isEmpty()) {
            name = uri.lastPathSegment ?: "unknown"
        }
        
        Log.d(TAG, "getFileName: uri=$uri, name=$name")
        return name
    }
    
    /**
     * 从 Uri 获取 MIME 类型
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 根据 MIME 类型判断媒体类型
     */
    fun getMediaTypeFromMime(mimeType: String?): MediaType {
        return when {
            mimeType == null -> MediaType.UNKNOWN
            mimeType.startsWith("video/") -> MediaType.VIDEO
            mimeType.startsWith("image/") -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }
    
    /**
     * 从 Uri 创建 MediaItem
     */
    fun createMediaItemFromUri(context: Context, uri: Uri): MediaItem? {
        return try {
            // 获取文件名
            val name = getFileName(context, uri)
            
            // 获取 MIME 类型
            val mimeType = getMimeType(context, uri)
            
            // 判断媒体类型 - 优先使用 MIME 类型，其次使用文件扩展名
            var type = getMediaTypeFromMime(mimeType)
            if (type == MediaType.UNKNOWN) {
                type = MediaTypeUtils.getMediaType(name)
            }
            
            Log.d(TAG, "createMediaItemFromUri: uri=$uri, name=$name, mime=$mimeType, type=$type")
            
            // 如果还是未知类型，跳过
            if (type == MediaType.UNKNOWN) {
                Log.w(TAG, "Unsupported media type: $name, mime=$mimeType")
                return null
            }
            
            // 获取持久化权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to take persistable permission: ${e.message}")
            }
            
            // 使用 Uri 字符串作为路径（content:// URI 可以直接被 ExoPlayer 播放）
            MediaItem(
                path = uri.toString(),
                name = name,
                type = type,
                source = MediaSource.MANUAL
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaItem from Uri: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 从多个 Uri 创建 MediaItem 列表
     */
    fun createMediaItemsFromUris(context: Context, uris: List<Uri>): List<MediaItem> {
        Log.d(TAG, "createMediaItemsFromUris: ${uris.size} uris")
        return uris.mapNotNull { uri ->
            createMediaItemFromUri(context, uri)
        }.also {
            Log.d(TAG, "Created ${it.size} MediaItems")
        }
    }
}
