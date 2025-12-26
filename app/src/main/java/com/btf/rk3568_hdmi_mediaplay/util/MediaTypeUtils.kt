package com.btf.rk3568_hdmi_mediaplay.util

import com.btf.rk3568_hdmi_mediaplay.data.model.MediaType
import java.io.File

object MediaTypeUtils {
    
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", 
        "webm", "m4v", "3gp", "ts", "mpg", "mpeg"
    )
    
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "bmp", "gif", "webp", "heic"
    )
    
    /**
     * 根据文件扩展名获取媒体类型
     */
    fun getMediaType(fileName: String): MediaType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            VIDEO_EXTENSIONS.contains(extension) -> MediaType.VIDEO
            IMAGE_EXTENSIONS.contains(extension) -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }
    
    /**
     * 根据文件获取媒体类型
     */
    fun getMediaType(file: File): MediaType = getMediaType(file.name)
    
    /**
     * 判断是否为视频文件
     */
    fun isVideo(fileName: String): Boolean = getMediaType(fileName) == MediaType.VIDEO
    
    /**
     * 判断是否为图片文件
     */
    fun isImage(fileName: String): Boolean = getMediaType(fileName) == MediaType.IMAGE
    
    /**
     * 判断是否为支持的媒体文件
     */
    fun isSupportedMedia(fileName: String): Boolean = getMediaType(fileName) != MediaType.UNKNOWN
    
    /**
     * 获取支持的视频扩展名
     */
    fun getSupportedVideoExtensions(): Set<String> = VIDEO_EXTENSIONS
    
    /**
     * 获取支持的图片扩展名
     */
    fun getSupportedImageExtensions(): Set<String> = IMAGE_EXTENSIONS
}
