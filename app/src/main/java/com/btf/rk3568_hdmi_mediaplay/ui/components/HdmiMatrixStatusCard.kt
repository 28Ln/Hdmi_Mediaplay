package com.btf.rk3568_hdmi_mediaplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btf.rk3568_hdmi_mediaplay.data.model.AppLanguage
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixAvailability
import com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixController
import com.btf.rk3568_hdmi_mediaplay.util.StringResources
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 拼接板状态卡（可复用）。
 *
 * 只 **collect** [HdmiMatrixController.state]，不在重组时发起任何 sysfs 读 ——
 * 重组可能每帧发生，而一次读在 I2C 忙时会阻塞数百毫秒。
 * 刷新只能由用户显式点击触发（或页面首次进入时触发一次）。
 *
 * 用 `collectAsState()` 而不是 `collectAsStateWithLifecycle()`：
 * 后者需要 lifecycle-runtime-compose 依赖，本项目未引入；
 * 而这里的 StateFlow 本身不产生 IO，停订阅没有收益。
 */
@Composable
fun HdmiMatrixStatusCard(
    controller: HdmiMatrixController = HdmiMatrixController.getInstance(),
    modifier: Modifier = Modifier,
    language: AppLanguage = StringResources.getLanguage(),
    showFusionSliders: Boolean = true
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 切语言时重新取文案（StringResources 不是 State）
    @Suppress("UNUSED_VARIABLE")
    val langKey = language

    val snapshot = state.lastSnapshot
    val applied = snapshot?.applied

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        busy = true
        controller.probe()
        controller.readApplied()
        busy = false
    }

    fun refresh() {
        scope.launch {
            busy = true
            controller.probe()
            controller.readApplied()
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), MaterialTheme.shapes.medium)
            .padding(16.dp)
    ) {
        Text(
            text = "🖧 ${StringResources.hdmiMatrix}",
            color = Color.Cyan,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(8.dp))

        // ---- 在线状态 ----
        val availability = state.availability
        val (statusText, statusColor) = when {
            busy -> StringResources.loading to Color(0xFFB0BEC5)
            availability == HdmiMatrixAvailability.MISSING ->
                StringResources.hdmiMatrixUnavailable to Color(0xFFFF8A80)
            availability == HdmiMatrixAvailability.PERMISSION_DENIED ->
                StringResources.hdmiMatrixErrorText(
                    com.btf.rk3568_hdmi_mediaplay.hardware.HdmiMatrixErrorKind.PERMISSION_DENIED
                ) to Color(0xFFFF8A80)
            snapshot?.online == true ->
                "${StringResources.hdmiMatrixOnline} · v${snapshot.version}" to Color(0xFF81C784)
            snapshot != null ->
                "${StringResources.hdmiMatrixOffline} · v${snapshot.version}" to Color(0xFFFFD54F)
            else -> StringResources.hdmiMatrixOffline to Color(0xFFB0BEC5)
        }
        Text(statusText, color = statusColor, fontSize = 13.sp)

        snapshot?.let { snap ->
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(StringResources.hdmiMatrixBusy, snap.status.busy, Color(0xFFFFB74D))
                Badge(StringResources.hdmiMatrixPower, snap.status.power, Color(0xFF81C784))
                Badge(StringResources.hdmiMatrixErrorFlag, snap.status.error, Color(0xFFE57373))
                Badge(StringResources.hdmiMatrixOverflow, snap.status.overflow, Color(0xFFBA68C8))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${StringResources.hdmiMatrixAppliedMode}: ${applied?.mode ?: "-"} · " +
                    "${StringResources.hdmiMatrixResolution}: ${applied?.resolution?.name ?: "-"} · " +
                    "${StringResources.hdmiMatrixMirror}: ${applied?.mirror ?: "-"}",
                color = Color(0xFFB0BEC5),
                fontSize = 11.sp
            )
        }

        if (showFusionSliders) {
            Spacer(Modifier.height(12.dp))
            var hFusion by remember(applied?.hfusion) {
                mutableFloatStateOf((applied?.hfusion ?: 0).toFloat())
            }
            var vFusion by remember(applied?.vfusion) {
                mutableFloatStateOf((applied?.vfusion ?: 0).toFloat())
            }

            Text(
                "${StringResources.hdmiMatrixHorizontal} ${hFusion.toInt()} / " +
                    "${StringResources.hdmiMatrixVertical} ${vFusion.toInt()}",
                color = Color.White,
                fontSize = 12.sp
            )
            // 拖动帧只 stageFusion（零 IO）；松手才 commitFusion（写 flash，带 300ms 防抖）
            Slider(
                value = hFusion,
                onValueChange = {
                    hFusion = it
                    controller.stageFusion(it.toInt(), vFusion.toInt())
                },
                onValueChangeFinished = {
                    scope.launch {
                        val result = controller.commitFusion()
                        result.errorOrNull()?.let {
                            message = StringResources.hdmiMatrixErrorDetail(it)
                        }
                    }
                },
                valueRange = 0f..255f,
                enabled = !busy
            )
            Slider(
                value = vFusion,
                onValueChange = {
                    vFusion = it
                    controller.stageFusion(hFusion.toInt(), it.toInt())
                },
                onValueChangeFinished = {
                    scope.launch {
                        val result = controller.commitFusion()
                        result.errorOrNull()?.let {
                            message = StringResources.hdmiMatrixErrorDetail(it)
                        }
                    }
                },
                valueRange = 0f..255f,
                enabled = !busy
            )
            Text(
                StringResources.hdmiMatrixFusionHint,
                color = Color.Gray,
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { refresh() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
            ) {
                Text(StringResources.hdmiMatrixRefresh, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    scope.launch {
                        val on = applied?.power != true
                        val result = controller.setPower(on)
                        result.errorOrNull()?.let {
                            message = StringResources.hdmiMatrixErrorDetail(it)
                        }
                        refresh()
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (applied?.power == true) Color(0xFF2E7D32) else Color(0xFF455A64)
                )
            ) {
                Text(
                    "${StringResources.hdmiMatrixPower}: ${if (applied?.power == true) "ON" else "OFF"}",
                    fontSize = 12.sp
                )
            }
        }

        // 错误：带 errno 与节点名，便于现场截图排查
        (message ?: state.lastError?.let { StringResources.hdmiMatrixErrorDetail(it) })?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Badge(label: String, active: Boolean, activeColor: Color) {
    Text(
        text = label,
        color = if (active) Color.Black else Color(0xFF90A4AE),
        fontSize = 10.sp,
        modifier = Modifier
            .background(
                if (active) activeColor else Color(0xFF263238),
                MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
