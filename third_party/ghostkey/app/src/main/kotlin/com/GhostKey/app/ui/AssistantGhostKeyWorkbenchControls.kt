package com.GhostKey.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.GhostKey.app.device.DeviceBinding
import com.GhostKey.app.viewmodel.ExploitUiState

/** GhostKey controls embedded into the assistant's shared workbench card. */
@Composable
fun AssistantGhostKeyWorkbenchControls(
    state: ExploitUiState,
    wirelessReady: Boolean,
    onRun: (otaUrl: String) -> Unit,
    onParseLocalBoot: (Uri) -> Unit,
    onStop: () -> Unit,
    onConfirmOutOfRange: () -> Unit,
    onDismissOutOfRange: () -> Unit,
    onDismissInjectRisk: () -> Unit,
    onDismissUsbWarning: () -> Unit,
) {
    var otaUrl by rememberSaveable { mutableStateOf("") }
    val bootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onParseLocalBoot)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = otaUrl,
            onValueChange = { otaUrl = it },
            enabled = wirelessReady && !state.isRunning && !state.isParsingBoot,
            label = { Text("OTA 链接（可选）") },
            placeholder = { Text("留空时自动通过 ROM V2 获取") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f).height(46.dp),
                enabled = wirelessReady && !state.isRunning && !state.isParsingBoot,
                shape = MaterialTheme.shapes.medium,
                onClick = { bootPicker.launch(arrayOf("*/*")) },
            ) {
                Text(if (state.isParsingBoot) "解析中…" else "解析本地 boot")
            }
            if (state.isRunning) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = MaterialTheme.shapes.medium,
                    onClick = onStop,
                ) {
                    Text("停止")
                }
            } else {
                Button(
                    modifier = Modifier.weight(1f).height(46.dp),
                    enabled = wirelessReady && !state.isParsingBoot,
                    shape = MaterialTheme.shapes.medium,
                    onClick = { onRun(otaUrl.trim()) },
                ) {
                    Text("开始提权")
                }
            }
        }
    }

    if (state.showKernelRangeDialog) {
        AlertDialog(
            onDismissRequest = onDismissOutOfRange,
            title = { Text("内核超出已验证范围") },
            text = { Text("当前内核 ${state.kernelRelease} 不在已验证范围 ${DeviceBinding.rangeDescription()} 内，仍要继续吗？") },
            confirmButton = { TextButton(onClick = onConfirmOutOfRange) { Text("仍要继续") } },
            dismissButton = { TextButton(onClick = onDismissOutOfRange) { Text("取消") } },
        )
    }
    if (state.showInjectRiskDialog) {
        AlertDialog(
            onDismissRequest = onDismissInjectRisk,
            title = { Text("正在执行注入阶段") },
            text = { Text("请勿关闭应用或中断进程。此阶段可能导致设备重启。") },
            confirmButton = { TextButton(onClick = onDismissInjectRisk) { Text("知道了") } },
        )
    }
    if (state.showUsbCloseDialog) {
        AlertDialog(
            onDismissRequest = onDismissUsbWarning,
            title = { Text("USB 调试仍处于开启状态") },
            text = { Text("提权完成。若不再需要 ADB，请到系统设置中关闭 USB 调试。") },
            confirmButton = { TextButton(onClick = onDismissUsbWarning) { Text("确定") } },
        )
    }
}
