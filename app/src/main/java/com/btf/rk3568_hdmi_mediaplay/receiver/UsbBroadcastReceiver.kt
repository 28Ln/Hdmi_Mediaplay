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
    
    /**
     * 可接受的挂载根前缀（**纵深防御**，不是完整防护）。
     *
     * `MEDIA_MOUNTED` 是隐式广播，任何应用都能伪造。实测攻击路径是
     * `/mnt/media_rw/EVIL` —— 它**在**这个前缀内，因此这层校验拦不住它。
     *
     * 真正的防护是 AndroidManifest 里把静态声明去掉（`exported=false`）：
     * 服务内已有动态注册（RECEIVER_NOT_EXPORTED），外部广播到不了这里。
     *
     * 保留前缀校验是为了挡住 `/data`、`/system` 这类明显越界的伪造路径，
     * 以及在将来若有人重新启用静态声明时提供一层兜底。
     */
    private val ALLOWED_MOUNT_ROOTS = listOf("/mnt/media_rw/", "/storage/")
    
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.data?.path ?: return
        
        Log.d(TAG, "Received action: ${intent.action}, path: $path")

        // 伪造广播防护：路径必须落在已知挂载根之下。
        if (ALLOWED_MOUNT_ROOTS.none { path.startsWith(it) }) {
            Log.w(TAG, "Ignoring broadcast for unexpected path: $path")
            return
        }
        
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
