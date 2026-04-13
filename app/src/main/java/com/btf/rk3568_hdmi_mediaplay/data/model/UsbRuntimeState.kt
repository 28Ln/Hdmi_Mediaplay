package com.btf.rk3568_hdmi_mediaplay.data.model

import java.io.File

sealed class UsbRuntimeState {
    data object Disconnected : UsbRuntimeState()
    data class Scanning(val path: File) : UsbRuntimeState()
    data class Connected(
        val path: File,
        val hasMediaContent: Boolean,
        val hasConfig: Boolean = false
    ) : UsbRuntimeState()
    data class Error(val message: String) : UsbRuntimeState()
}
