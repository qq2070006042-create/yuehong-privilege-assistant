@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package `in`.hridayan.ashell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import `in`.hridayan.ashell.R
import `in`.hridayan.ashell.shell.EscalationResult
import `in`.hridayan.ashell.shell.EscalationStage
import `in`.hridayan.ashell.shell.PrivilegeEscalator
import `in`.hridayan.ashell.shell.ShizukuShellController
import `in`.hridayan.ashell.shell.ShizukuStatus

@Composable
fun LocalAdbScreen(
    controller: ShizukuShellController,
    escalator: PrivilegeEscalator,
) {
    var command by rememberSaveable { mutableStateOf("") }
    var showDeviceInfo by remember { mutableStateOf(false) }
    var showEscalationResult by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val escalationRunning = escalator.stage !in setOf(
        EscalationStage.Idle,
        EscalationStage.Done,
    )

    // 流程结束（成功或失败）后弹出结果对话框
    LaunchedEffect(escalator.result) {
        showEscalationResult = escalator.result !is EscalationResult.Idle
    }

    LaunchedEffect(controller.output.size) {
        if (controller.output.isNotEmpty()) {
            listState.scrollToItem(controller.output.lastIndex)
        }
    }

    LaunchedEffect(controller.commandStartToken) {
        if (controller.commandStartToken > 0) command = ""
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                title = { Text(stringResource(R.string.local_adb)) },
                actions = {
                    IconButton(onClick = { showDeviceInfo = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = stringResource(R.string.open_device_info),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShizukuStatusCard(
                status = controller.status,
                onAuthorize = controller::requestPermission,
                onRefresh = controller::refreshStatus,
            )

            EscalationCard(
                escalator = escalator,
                enabled = !controller.isRunning,
                onStart = escalator::start,
                onReset = escalator::reset,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.output),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = controller::clear,
                            enabled = !controller.isRunning &&
                                !escalationRunning &&
                                controller.output.isNotEmpty(),
                        ) {
                            Icon(
                                modifier = Modifier.size(18.dp),
                                painter = painterResource(R.drawable.ic_clear),
                                contentDescription = null,
                            )
                            Text(
                                modifier = Modifier.padding(start = 5.dp),
                                text = stringResource(R.string.clear),
                            )
                        }
                    }

                    if (controller.output.isEmpty()) {
                        Text(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            text = stringResource(R.string.no_output),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(controller.output) { line ->
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = when {
                                            line.isError -> MaterialTheme.colorScheme.error
                                            line.text.startsWith("$ ") -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = command,
                onValueChange = { command = it },
                enabled = !controller.isRunning,
                label = { Text(stringResource(R.string.command_hint)) },
                minLines = 1,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        controller.execute(command)
                    },
                ),
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (controller.isRunning) {
                        controller.stop()
                    } else {
                        controller.execute(command)
                    }
                },
                enabled = controller.isRunning || command.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(
                        if (controller.isRunning) R.drawable.ic_stop else R.drawable.ic_play,
                    ),
                    contentDescription = null,
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(if (controller.isRunning) R.string.stop else R.string.run),
                )
            }
        }
    }

    if (showDeviceInfo) {
        DeviceInfoDialog(onDismiss = { showDeviceInfo = false })
    }

    if (controller.permissionRequiredNotice) {
        AlertDialog(
            onDismissRequest = controller::dismissPermissionRequiredNotice,
            title = { Text(stringResource(R.string.shizuku_permission_title)) },
            text = { Text(stringResource(R.string.shizuku_permission_required_message)) },
            confirmButton = {
                TextButton(onClick = controller::dismissPermissionRequiredNotice) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showEscalationResult) {
        EscalationResultDialog(
            result = escalator.result,
            onDismiss = {
                showEscalationResult = false
                escalator.reset()
            },
        )
    }
}

@Composable
private fun ShizukuStatusCard(
    status: ShizukuStatus,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
) {
    val text = when (status) {
        ShizukuStatus.Granted -> stringResource(R.string.shizuku_ready)
        ShizukuStatus.NeedsPermission -> stringResource(R.string.shizuku_needs_permission)
        ShizukuStatus.Unavailable -> stringResource(R.string.shizuku_unavailable)
        ShizukuStatus.Checking -> stringResource(R.string.active_shizuku)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status == ShizukuStatus.Granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            when (status) {
                ShizukuStatus.NeedsPermission -> FilledTonalButton(onClick = onAuthorize) {
                    Text(stringResource(R.string.grant_permission))
                }

                ShizukuStatus.Unavailable -> TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun EscalationCard(
    escalator: PrivilegeEscalator,
    enabled: Boolean,
    onStart: () -> Unit,
    onReset: () -> Unit,
) {
    val running = escalator.stage !in setOf(
        EscalationStage.Idle,
        EscalationStage.Done,
    )
    val stageText = escalationStageLabel(escalator.stage)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stageText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (escalator.result is EscalationResult.Failure) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !running,
                    onClick = onStart,
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = null,
                        )
                    }
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.start_escalation),
                    )
                }

                if (escalator.result !is EscalationResult.Idle) {
                    OutlinedButton(onClick = onReset) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun escalationStageLabel(stage: EscalationStage): String = when (stage) {
    EscalationStage.Idle -> stringResource(R.string.escalation_idle)
    EscalationStage.CheckingPermission -> stringResource(R.string.escalation_checking_permission)
    EscalationStage.CollectingDeviceInfo -> stringResource(R.string.escalation_collecting_device_info)
    EscalationStage.ReportingToServer -> stringResource(R.string.escalation_reporting_to_server)
    EscalationStage.DownloadingPayload -> stringResource(R.string.escalation_downloading_payload)
    EscalationStage.VerifyingChecksum -> stringResource(R.string.escalation_verifying_checksum)
    EscalationStage.ExecutingPayload -> stringResource(R.string.escalation_executing_payload)
    EscalationStage.CheckingRoot -> stringResource(R.string.escalation_checking_root)
    EscalationStage.Done -> stringResource(R.string.escalation_done)
}

@Composable
private fun EscalationResultDialog(
    result: EscalationResult,
    onDismiss: () -> Unit,
) {
    when (result) {
        is EscalationResult.Success -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.escalation_success_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.adb_escalated_to_root))
                    if (result.output.isNotBlank()) {
                        SelectionContainer { Text(result.output) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            },
        )

        is EscalationResult.Failure -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.escalation_failure_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.adb_escalation_failed))
                    SelectionContainer { Text(result.reason) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            },
        )

        EscalationResult.Idle -> Unit
    }
}
