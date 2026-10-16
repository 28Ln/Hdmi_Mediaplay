package com.btf.rk3568_hdmi_mediaplay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiSystemProperties
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HdmiEfullUiState(
    val enabled: Boolean,
    val busy: Boolean,
    val message: String?,
    val toggle: () -> Unit
)

@Composable
fun rememberHdmiEfullUiState(): HdmiEfullUiState {
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        enabled = withContext(Dispatchers.IO) {
            HdmiSystemProperties.isEfullEnabled()
        }
    }

    val toggle = {
        if (!busy) {
            busy = true
            message = null
            scope.launch(Dispatchers.IO) {
                val next = !enabled
                val ok = HdmiSystemProperties.setEfullEnabled(next)
                val current = HdmiSystemProperties.isEfullEnabled()
                withContext(Dispatchers.Main) {
                    enabled = current
                    busy = false
                    message = if (ok) null else StringResources.hdmiEfullFailed
                }
            }
        }
    }

    return HdmiEfullUiState(
        enabled = enabled,
        busy = busy,
        message = message,
        toggle = toggle
    )
}
