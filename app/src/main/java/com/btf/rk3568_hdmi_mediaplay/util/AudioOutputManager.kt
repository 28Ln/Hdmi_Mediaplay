package com.btf.rk3568_hdmi_mediaplay.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.btf.rk3568_hdmi_mediaplay.data.model.AudioOutput

/**
 * 音频输出管理器
 * 管理音频输出设备切换
 */
object AudioOutputManager {
    
    private const val TAG = "AudioOutputManager"
    
    /**
     * 获取可用的音频输出设备
     */
    fun getAvailableOutputs(context: Context): List<AudioDeviceInfo> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get audio outputs", e)
            emptyList()
        }
    }
    
    /**
     * 检查是否有 HDMI 音频输出
     */
    fun hasHdmiOutput(context: Context): Boolean {
        return getAvailableOutputs(context).any { device ->
            device.type == AudioDeviceInfo.TYPE_HDMI ||
            device.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            device.type == AudioDeviceInfo.TYPE_HDMI_EARC
        }
    }
    
    /**
     * 检查是否有扬声器输出
     */
    fun hasSpeakerOutput(context: Context): Boolean {
        return getAvailableOutputs(context).any { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }
    }
    
    /**
     * 设置音频输出
     * 注意: Android 标准 API 不支持直接切换音频输出
     * 这里提供的是一个框架，实际实现可能需要系统级权限或厂商 API
     */
    fun setAudioOutput(context: Context, output: AudioOutput): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            when (output) {
                AudioOutput.AUTO -> {
                    // 自动模式 - 使用系统默认
                    audioManager.isSpeakerphoneOn = false
                    Log.i(TAG, "Audio output set to AUTO")
                    true
                }
                
                AudioOutput.HDMI -> {
                    // HDMI 输出
                    // 注意: 标准 Android API 不支持直接路由到 HDMI
                    // RK3568 可能需要使用 Rockchip 特定 API
                    audioManager.isSpeakerphoneOn = false
                    Log.i(TAG, "Audio output set to HDMI (may require system API)")
                    true
                }
                
                AudioOutput.SPEAKER -> {
                    // 扬声器输出
                    audioManager.isSpeakerphoneOn = true
                    Log.i(TAG, "Audio output set to SPEAKER")
                    true
                }
                
                AudioOutput.ALL -> {
                    // 全部输出 - 需要系统级支持
                    Log.i(TAG, "Audio output set to ALL (may require system API)")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio output", e)
            false
        }
    }
    
    /**
     * 获取当前音频输出设备信息
     */
    fun getCurrentOutputInfo(context: Context): String {
        return try {
            val outputs = getAvailableOutputs(context)
            val outputNames = outputs.map { getDeviceTypeName(it.type) }
            outputNames.joinToString(", ")
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * 获取设备类型名称
     */
    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Out"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Out"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            else -> "Unknown ($type)"
        }
    }
}
