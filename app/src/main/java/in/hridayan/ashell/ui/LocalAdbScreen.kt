@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package `in`.hridayan.ashell.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.hridayan.ashell.R
import `in`.hridayan.ashell.shell.EscalationResult
import `in`.hridayan.ashell.shell.EscalationStage
import `in`.hridayan.ashell.shell.OutputLine
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
    val escalationRunning = escalator.stage !in setOf(EscalationStage.Idle, EscalationStage.Done)

    LaunchedEffect(escalator.result) {
        showEscalationResult = escalator.result !is EscalationResult.Idle
    }

    LaunchedEffect(controller.output.size) {
        if (controller.output.isNotEmpty()) {
            listState.animateScrollToItem(controller.output.lastIndex)
        }
    }

    LaunchedEffect(controller.commandStartToken) {
        if (controller.commandStartToken > 0) command = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            LocalAdbHeader(
                status = controller.status,
                onDeviceInfo = { showDeviceInfo = true },
            )
            EscalationCard(
                escalator = escalator,
                enabled = !controller.isRunning && controller.status == ShizukuStatus.Granted,
                onStart = escalator::start,
                onReset = escalator::reset,
            )
            TerminalOutputCard(
                modifier = Modifier.weight(1f),
                output = controller.output,
                listState = listState,
                canClear = !controller.isRunning && !escalationRunning,
                onClear = controller::clear,
            )
            CommandComposer(
                command = command,
                onCommandChange = { command = it },
                isRunning = controller.isRunning,
                onRunOrStop = {
                    if (controller.isRunning) controller.stop() else controller.execute(command)
                },
            )
        }
    }

    if (showDeviceInfo) {
        DeviceInfoDialog(onDismiss = { showDeviceInfo = false })
    }

    if (showEscalationResult) {
        EscalationResultDialog(
            result = escalator.result,
            onActivateKernelSu = {
                showEscalationResult = false
                escalator.activateKernelSu()
            },
            onDismiss = {
                showEscalationResult = false
                escalator.reset()
            },
        )
    }
}

@Composable
private fun LocalAdbHeader(
    status: ShizukuStatus,
    onDeviceInfo: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.local_adb),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.local_adb_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedContent(
                targetState = status,
                transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
                label = "local-adb-status",
            ) { activeStatus ->
                val ready = activeStatus == ShizukuStatus.Granted
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        text = stringResource(
                            if (ready) R.string.shizuku_connected_short else R.string.shizuku_disconnected_short,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (ready) MaterialTheme.colorScheme.primary else
                            MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onDeviceInfo) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.open_device_info),
                )
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
    val running = escalator.stage !in setOf(EscalationStage.Idle, EscalationStage.Done)
    val failed = escalator.result is EscalationResult.Failure ||
        escalator.result is EscalationResult.KernelSuActivationFailure
    val accent by animateColorAsState(
        targetValue = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "escalation-accent",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.privilege_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    AnimatedContent(
                        targetState = escalator.stage,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith
                                (fadeOut(tween(140)) + slideOutVertically { -it / 3 })
                        },
                        label = "escalation-stage",
                    ) { stage ->
                        Text(
                            text = escalationStageLabel(stage),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        text = stringResource(if (running) R.string.status_running else R.string.status_ready),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }

            AnimatedVisibility(visible = running, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MotionButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !running,
                    onClick = onStart,
                ) {
                    AnimatedContent(
                        targetState = running,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "escalation-button",
                    ) { active ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (active) {
                                CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(painter = painterResource(R.drawable.ic_play), contentDescription = null)
                            }
                            Text(
                                modifier = Modifier.padding(start = 9.dp),
                                text = stringResource(
                                    if (active) R.string.escalation_in_progress else R.string.start_escalation,
                                ),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = escalator.result !is EscalationResult.Idle,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    OutlinedButton(
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        onClick = onReset,
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalOutputCard(
    output: List<OutputLine>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    canClear: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        MaterialTheme.colorScheme.error,
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.primary,
                    ).forEach { color -> Surface(Modifier.size(7.dp), CircleShape, color) {} }
                }
                Text(
                    modifier = Modifier.padding(start = 10.dp),
                    text = stringResource(R.string.terminal_output_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    modifier = Modifier.padding(start = 8.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        text = output.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClear, enabled = canClear && output.isNotEmpty()) {
                    Icon(
                        modifier = Modifier.size(19.dp),
                        painter = painterResource(R.drawable.ic_clear),
                        contentDescription = stringResource(R.string.clear),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            AnimatedContent(
                modifier = Modifier.weight(1f),
                targetState = output.isEmpty(),
                transitionSpec = {
                    (fadeIn(tween(280)) + scaleIn(initialScale = 0.985f)) togetherWith fadeOut(tween(180))
                },
                label = "terminal-output-state",
            ) { empty ->
                if (empty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.no_output),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.no_output_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                        }
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 15.dp, vertical = 10.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            itemsIndexed(
                                items = output,
                                key = { index, line -> "$index-${line.text.hashCode()}" },
                            ) { _, line ->
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
    }
}

@Composable
private fun CommandComposer(
    command: String,
    onCommandChange: (String) -> Unit,
    isRunning: Boolean,
    onRunOrStop: () -> Unit,
) {
    val suggestions = listOf("id", "uname -r", "getprop ro.product.model")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.command_console_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.quick_command_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(suggestions) { suggestion ->
                    AssistChip(
                        enabled = !isRunning,
                        onClick = { onCommandChange(suggestion) },
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = command,
                onValueChange = onCommandChange,
                enabled = !isRunning,
                placeholder = { Text(stringResource(R.string.command_hint)) },
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(17.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (command.isNotBlank()) onRunOrStop() }),
            )
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = isRunning || command.isNotBlank(),
                onClick = onRunOrStop,
            ) {
                AnimatedContent(
                    targetState = isRunning,
                    transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
                    label = "run-stop-button",
                ) { running ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
                            contentDescription = null,
                        )
                        Text(
                            modifier = Modifier.padding(start = 9.dp),
                            text = stringResource(if (running) R.string.stop else R.string.run),
                            fontWeight = FontWeight.Bold,
                        )
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
    EscalationStage.ValidatingCommand -> stringResource(R.string.escalation_validating_command)
    EscalationStage.ExecutingPayload -> stringResource(R.string.escalation_executing_payload)
    EscalationStage.CheckingRoot -> stringResource(R.string.escalation_checking_root)
    EscalationStage.ActivatingKernelSu -> stringResource(R.string.escalation_activating_kernelsu)
    EscalationStage.Done -> stringResource(R.string.escalation_done)
}

@Composable
private fun EscalationResultDialog(
    result: EscalationResult,
    onActivateKernelSu: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (result) {
        is EscalationResult.RootConfirmed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.escalation_success_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.adb_escalated_to_root))
                    Text(stringResource(R.string.kernelsu_activation_prompt))
                    if (result.output.isNotBlank()) SelectionContainer { Text(result.output) }
                }
            },
            confirmButton = {
                TextButton(onClick = onActivateKernelSu) {
                    Text(stringResource(R.string.activate_kernelsu))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.do_not_activate)) }
            },
        )

        is EscalationResult.KernelSuActivated -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.kernelsu_activation_success_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.kernelsu_activation_success_message))
                    if (result.output.isNotBlank()) SelectionContainer { Text(result.output) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            },
        )

        is EscalationResult.KernelSuActivationFailure -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.kernelsu_activation_failure_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.kernelsu_activation_failure_message))
                    SelectionContainer { Text(result.reason) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
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
