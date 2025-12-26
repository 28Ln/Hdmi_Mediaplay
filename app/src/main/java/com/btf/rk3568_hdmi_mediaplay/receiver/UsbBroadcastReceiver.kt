package com.btf.rk3568_hdmi_mediaplay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * USB/存储设备广播接收器
 * 监听U盘的插入和拔出事件
 */
class UsbBroadcastReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "UsbBroadcastReceiver"
        
        // 事件回调接口
        var onUsbMounted: ((String) -> Unit)? = null
        var onUsbUnmounted: ((String) -> Unit)? = null
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.data?.path ?: return
        
        Log.d(TAG, "Received action: ${intent.action}, path: $path")
        
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> {
                Log.i(TAG, "USB mounted: $path")
                onUsbMounted?.invoke(path)
            }
            
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_EJECT -> {
                Log.i(TAG, "USB unmounted: $path")
                onUsbUnmounted?.invoke(path)
            }
        }
    }
}
