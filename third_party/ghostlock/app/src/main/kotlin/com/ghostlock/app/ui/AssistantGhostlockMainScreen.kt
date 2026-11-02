package com.ghostlock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import com.ghostlock.app.domain.model.ShizukuStatus

@Composable
internal fun AssistantGhostlockMainScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
        var otaUrl by rememberSaveable { mutableStateOf("") }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        val canRun = !state.kernelSupported || state.profileInvalidPaths.isEmpty()
        val deviceSummary = listOf(state.deviceName, state.socName)
            .filter(String::isNotBlank)
            .joinToString(" · ")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = deviceSummary.ifBlank { stringResource(R.string.device_label) },
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.kernelRelease.ifBlank { "-" },
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        enabled = !state.running,
                        onClick = { showSettings = true },
                    ) {
                        Text(stringResource(R.string.assistant_home_settings))
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assistant_home_action_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.assistant_home_action_body),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                enabled = canRun && !state.running,
                                shape = MaterialTheme.shapes.small,
                                contentPadding = ButtonDefaults.ContentPadding,
                                onClick = { actions.onRun(otaUrl.trim()) },
                            ) {
                                Text(
                                    text = stringResource(
                                        if (state.running) R.string.action_running
                                        else R.string.assistant_home_start,
                                    ),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                            if (!canRun && !state.running) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable(role = Role.Button) { actions.onProfileInvalid() },
                                )
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            enabled = !state.running,
                            shape = MaterialTheme.shapes.small,
                            contentPadding = ButtonDefaults.ContentPadding,
                            onClick = actions::onParseImage,
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_home_parse_local),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = otaUrl,
                        onValueChange = { otaUrl = it },
                        enabled = !state.running,
                        placeholder = {
                            Text(stringResource(R.string.assistant_home_ota_url_hint))
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }

            AssistantGhostlockLogPanel(
                lines = state.logLines,
                onCopy = actions::onCopyLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        if (showSettings) {
            AssistantGhostlockSettingsDialog(
                state = state,
                onCpuPairSelected = actions::onCpuPairSelected,
                onSafeModeChanged = actions::onSafeModeChanged,
                onShizukuChanged = actions::onShizukuChanged,
                onDismiss = { showSettings = false },
            )
        }
}

/** Compact GhostLock operation controls for the assistant's single workbench page. */
@Composable
fun AssistantGhostlockWorkbenchControls(
    state: GhostlockUiState,
    wirelessPrivilegeReady: Boolean,
    onRun: (String) -> Unit,
    onParseImage: () -> Unit,
    onCpuPairSelected: (Int) -> Unit,
    onSafeModeChanged: (Boolean) -> Unit,
    onShizukuChanged: (Boolean) -> Unit,
    onDialogItemSelected: (Int) -> Unit,
    onDialogDismiss: () -> Unit,
    onDialogDismissFinished: () -> Unit,
    onOverwriteConfirm: () -> Unit,
    onOverwriteDismiss: () -> Unit,
) {
        var otaUrl by rememberSaveable { mutableStateOf("") }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        val profileCanRun = !state.kernelSupported || state.profileInvalidPaths.isEmpty()
        // Keep GhostLock's operation controls usable; when Shizuku is selected
        // but unavailable, the ViewModel reports the failure without switching routes.
        val canRun = profileCanRun
        val runBlockedMessage = when {
            state.kernelSupported && state.profileInvalidPaths.isNotEmpty() ->
                "配置参数无效：${state.profileInvalidPaths.joinToString("、")}"
            else -> null
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "OTA 链接",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = !state.running,
                    onClick = { showSettings = true },
                ) {
                    Text(stringResource(R.string.assistant_home_settings))
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = otaUrl,
                onValueChange = { otaUrl = it },
                enabled = !state.running,
                placeholder = { Text("粘贴 OTA 下载链接…") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f).height(46.dp),
                    enabled = !state.running && canRun,
                    shape = MaterialTheme.shapes.small,
                    onClick = { onRun(otaUrl.trim()) },
                ) {
                    Text(
                        text = stringResource(if (state.running) R.string.action_running else R.string.assistant_home_start),
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).height(46.dp),
                    enabled = !state.running,
                    shape = MaterialTheme.shapes.small,
                    onClick = onParseImage,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_home_parse_local),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (runBlockedMessage != null) {
                Text(
                    text = runBlockedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.dialogVisible) {
            val dialogText = when {
                state.dialogMessageRes != 0 -> stringResource(state.dialogMessageRes)
                state.dialogMessage.isNotBlank() -> state.dialogMessage
                else -> ""
            }
            AlertDialog(
                onDismissRequest = {
                    onDialogDismiss()
                    onDialogDismissFinished()
                },
                title = {
                    if (state.dialogTitleRes != 0) Text(stringResource(state.dialogTitleRes))
                },
                text = {
                    if (state.dialogType == DialogType.LIST) {
                        val dialogItems = state.dialogItems.ifEmpty {
                            state.dialogItemResIds.map { stringResource(it) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            dialogItems.forEachIndexed { index, item ->
                                TextButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        onDialogItemSelected(index)
                                        onDialogDismissFinished()
                                    },
                                ) {
                                    Text(
                                        text = item,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                    )
                                }
                            }
                        }
                    } else if (dialogText.isNotBlank()) {
                        Text(dialogText)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDialogDismiss()
                            onDialogDismissFinished()
                        },
                    ) {
                        Text(
                            stringResource(
                                if (state.dialogType == DialogType.LIST) R.string.cancel
                                else R.string.dialog_dismiss,
                            ),
                        )
                    }
                },
            )
        }

        if (state.overwriteDialogVisible) {
            AlertDialog(
                onDismissRequest = onOverwriteDismiss,
                title = { Text(stringResource(R.string.overwrite_title)) },
                text = { Text(stringResource(R.string.overwrite_message, state.overwriteMessage)) },
                confirmButton = {
                    TextButton(onClick = onOverwriteConfirm) {
                        Text(stringResource(R.string.overwrite_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onOverwriteDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (showSettings) {
            AssistantGhostlockSettingsDialog(
                state = state,
                wirelessPrivilegeReady = wirelessPrivilegeReady,
                onCpuPairSelected = onCpuPairSelected,
                onSafeModeChanged = onSafeModeChanged,
                onShizukuChanged = onShizukuChanged,
                onDismiss = { showSettings = false },
            )
        }
}

@Composable
private fun AssistantGhostlockLogPanel(
    lines: List<GhostlockLogLine>,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color(0xFF19191C),
        border = BorderStroke(1.dp, Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.log_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC7C5CC),
                )
                TextButton(onClick = onCopy) {
                    Text(
                        text = stringResource(R.string.action_copy),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFC7C5CC),
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_home_log_empty),
                        color = Color(0xFF9B9BA2),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 6.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line.text.trimEnd('\r', '\n'),
                                color = assistantLogLineColor(line.color),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun assistantLogLineColor(color: Int): Color = when (color) {
    0xFFFF6B6B.toInt() -> Color(0xFFFF6B6B)
    0xFF5FD68A.toInt() -> Color(0xFF5FD68A)
    0xFFFFC94D.toInt() -> Color(0xFFFFC94D)
    0xFF60A5FA.toInt() -> Color(0xFF60A5FA)
    else -> Color(0xFFE5E1E6)
}

@Composable
private fun AssistantGhostlockSettingsDialog(
    state: GhostlockUiState,
    wirelessPrivilegeReady: Boolean? = null,
    onCpuPairSelected: (Int) -> Unit,
    onSafeModeChanged: (Boolean) -> Unit,
    onShizukuChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var cpuMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        title = { Text(stringResource(R.string.assistant_home_settings)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.cpuPairLabels.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            onClick = { cpuMenuExpanded = true },
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(stringResource(R.string.cpu_pair_label))
                                Text(
                                    text = state.customCpuPair?.let {
                                        stringResource(
                                            R.string.cpu_pair_custom,
                                            "${it.primary}, ${it.consumer}",
                                        )
                                    } ?: state.cpuPairLabels.getOrElse(state.cpuPairIndex) { "-" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = cpuMenuExpanded,
                            onDismissRequest = { cpuMenuExpanded = false },
                        ) {
                            state.cpuPairLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        cpuMenuExpanded = false
                                        onCpuPairSelected(index)
                                    },
                                )
                            }
                        }
                    }
                }

                AssistantSettingSwitch(
                    title = stringResource(R.string.safe_mode_label),
                    summary = stringResource(R.string.safe_mode_summary),
                    checked = state.safeModeEnabled,
                    onCheckedChange = onSafeModeChanged,
                )

                AssistantSettingSwitch(
                    title = stringResource(R.string.shizuku_label),
                    summary = if (!state.shizukuEnabled && wirelessPrivilegeReady == false) {
                        "激活无线调试后才能启用 Shizuku；关闭状态下 GhostLock 使用基础路线"
                    } else {
                        stringResource(
                            when {
                                !state.shizukuEnabled -> R.string.shizuku_summary
                                state.shizukuStatus == ShizukuStatus.READY -> R.string.shizuku_status_ready
                                state.shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED ->
                                    R.string.shizuku_status_permission_required
                                state.shizukuStatus == ShizukuStatus.NOT_RUNNING ->
                                    R.string.shizuku_status_not_running
                                else -> R.string.shizuku_status_checking
                            },
                        )
                    },
                    checked = state.shizukuEnabled,
                    onCheckedChange = onShizukuChanged,
                    enabled = state.shizukuEnabled || wirelessPrivilegeReady != false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AssistantSettingSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
