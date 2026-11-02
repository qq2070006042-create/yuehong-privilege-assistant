@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package roro.stellar.yuehong.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.EscalationResult
import roro.stellar.yuehong.shell.EscalationStage
import roro.stellar.yuehong.shell.BundledPayload
import roro.stellar.yuehong.shell.BundledPayloadCatalog
import roro.stellar.yuehong.shell.CachedLocalPayloadInfo
import roro.stellar.yuehong.shell.OutputLine
import roro.stellar.yuehong.shell.PrivilegeEscalator
import roro.stellar.yuehong.shell.StellarShellController
import roro.stellar.yuehong.shell.StellarStatus

private val LOCAL_PRIVILEGE_COMMAND_PRESETS = listOf(
    "LD_PRELOAD=/data/local/tmp/preload.so sh",
    "LD_PRELOAD=/data/local/tmp/preload.so /system/bin/id",
    "LD_PRELOAD=/data/local/tmp/preload.so /system/bin/true",
    "/data/local/tmp/preload.so --no-proxy --force",
)

@Composable
fun LocalAdbScreen(
    controller: StellarShellController,
    escalator: PrivilegeEscalator,
    embedded: Boolean = false,
) {
    var command by rememberSaveable { mutableStateOf("") }
    var localPrivilegeCommand by rememberSaveable { mutableStateOf("") }
    var pendingLocalPayloadUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPayloadPicker by remember { mutableStateOf(false) }
    var cachedLocalPayload by remember { mutableStateOf<CachedLocalPayloadInfo?>(null) }
    var showCachedPayloadPrompt by rememberSaveable { mutableStateOf(false) }
    var showEscalationResult by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val bundledPayloads = remember(context) {
        runCatching { BundledPayloadCatalog.load(context) }.getOrDefault(emptyList())
    }
    val terminalScrollState = rememberScrollState()
    val escalationRunning = escalator.stage !in setOf(EscalationStage.Idle, EscalationStage.Done)
    val localPayloadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            localPrivilegeCommand = ""
            pendingLocalPayloadUri = uri
        }
    }

    LaunchedEffect(escalator.result) {
        showEscalationResult = escalator.result !is EscalationResult.Idle
    }

    LaunchedEffect(controller.output.size) {
        if (controller.output.isNotEmpty()) {
            runCatching { terminalScrollState.animateScrollTo(terminalScrollState.maxValue) }
        }
    }

    LaunchedEffect(controller.commandStartToken) {
        if (controller.commandStartToken > 0) command = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .padding(
                    horizontal = if (embedded) 12.dp else 16.dp,
                    vertical = if (embedded) 8.dp else 14.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (embedded) 8.dp else 11.dp),
        ) {
            LocalAdbHeader(
                status = controller.status,
                compact = embedded,
            )
            EscalationCard(
                escalator = escalator,
                enabled = !controller.isRunning && controller.status == StellarStatus.Granted,
                onSelectBundledPayload = { showPayloadPicker = true },
                onSelectLocalPayload = {
                    val cached = escalator.cachedLocalPayloadInfo()
                    if (cached == null) {
                        localPayloadPicker.launch(arrayOf("*/*"))
                    } else {
                        cachedLocalPayload = cached
                        showCachedPayloadPrompt = true
                    }
                },
                compact = embedded,
            )
            TerminalOutputCard(
                modifier = Modifier.weight(1f),
                output = controller.output,
                scrollState = terminalScrollState,
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
                compact = embedded,
            )
        }
    }

    pendingLocalPayloadUri?.let { uri ->
        LocalPayloadCommandDialog(
            command = localPrivilegeCommand,
            onCommandChange = { localPrivilegeCommand = it },
            onConfirm = {
                val selectedCommand = localPrivilegeCommand.trim()
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
                escalator.startWithLocalPayload(uri, selectedCommand)
            },
            onDismiss = {
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
            },
        )
    }

    val cachedPayloadPrompt = cachedLocalPayload
    if (showCachedPayloadPrompt && cachedPayloadPrompt != null) {
        AlertDialog(
            onDismissRequest = { showCachedPayloadPrompt = false },
            title = { Text(stringResource(R.string.local_payload_cache_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.local_payload_cache_message,
                            cachedPayloadPrompt.displayName,
                            cachedPayloadPrompt.sizeBytes,
                        ),
                    )
                    Text(
                        cachedPayloadPrompt.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCachedPayloadPrompt = false
                        if (!escalator.startWithCachedLocalPayload()) {
                            localPayloadPicker.launch(arrayOf("*/*"))
                        }
                    },
                ) {
                    Text(stringResource(R.string.local_payload_use_cached))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCachedPayloadPrompt = false
                        localPayloadPicker.launch(arrayOf("*/*"))
                    },
                ) {
                    Text(stringResource(R.string.local_payload_choose_new))
                }
            },
        )
    }

    if (showPayloadPicker) {
        BundledPayloadDialog(
            payloads = bundledPayloads,
            onSelect = {
                showPayloadPicker = false
                escalator.startWithBundledPayload(it)
            },
            onDismiss = { showPayloadPicker = false },
        )
    }

    if (showEscalationResult) {
        EscalationResultDialog(
            result = escalator.result,
            onActivateJailbreak = {
                showEscalationResult = false
                escalator.activateJailbreak()
            },
            onDismiss = {
                showEscalationResult = false
                escalator.reset()
            },
        )
    }
}

/** The Stellar privilege actions used inside the unified assistant workbench. */
@Composable
fun StellarPrivilegeWorkbenchControls(
    controller: StellarShellController,
    escalator: PrivilegeEscalator,
) {
    var localPrivilegeCommand by rememberSaveable { mutableStateOf("") }
    var pendingLocalPayloadUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPayloadPicker by remember { mutableStateOf(false) }
    var cachedLocalPayload by remember { mutableStateOf<CachedLocalPayloadInfo?>(null) }
    var showCachedPayloadPrompt by rememberSaveable { mutableStateOf(false) }
    var showEscalationResult by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val bundledPayloads = remember(context) {
        runCatching { BundledPayloadCatalog.load(context) }.getOrDefault(emptyList())
    }
    val escalationRunning = escalator.stage !in setOf(EscalationStage.Idle, EscalationStage.Done)
    val localPayloadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            localPrivilegeCommand = ""
            pendingLocalPayloadUri = uri
        }
    }

    LaunchedEffect(escalator.result) {
        showEscalationResult = escalator.result !is EscalationResult.Idle
    }

    val canEscalate = !controller.isRunning && !escalationRunning &&
        controller.status in setOf(StellarStatus.Granted, StellarStatus.NeedsPermission)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MotionButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = canEscalate,
            onClick = { showPayloadPicker = true },
        ) {
            Text(
                text = stringResource(
                    if (escalationRunning) R.string.escalation_in_progress else R.string.start_escalation,
                ),
                fontWeight = FontWeight.Bold,
            )
        }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = canEscalate,
            onClick = {
                val cached = escalator.cachedLocalPayloadInfo()
                if (cached == null) {
                    localPayloadPicker.launch(arrayOf("*/*"))
                } else {
                    cachedLocalPayload = cached
                    showCachedPayloadPrompt = true
                }
            },
        ) {
            Text(stringResource(R.string.use_local_payload), fontWeight = FontWeight.SemiBold)
        }
    }

    pendingLocalPayloadUri?.let { uri ->
        LocalPayloadCommandDialog(
            command = localPrivilegeCommand,
            onCommandChange = { localPrivilegeCommand = it },
            onConfirm = {
                val selectedCommand = localPrivilegeCommand.trim()
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
                escalator.startWithLocalPayload(uri, selectedCommand)
            },
            onDismiss = {
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
            },
        )
    }

    val cachedPayloadPrompt = cachedLocalPayload
    if (showCachedPayloadPrompt && cachedPayloadPrompt != null) {
        AlertDialog(
            onDismissRequest = { showCachedPayloadPrompt = false },
            title = { Text(stringResource(R.string.local_payload_cache_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.local_payload_cache_message,
                            cachedPayloadPrompt.displayName,
                            cachedPayloadPrompt.sizeBytes,
                        ),
                    )
                    Text(
                        cachedPayloadPrompt.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCachedPayloadPrompt = false
                        if (!escalator.startWithCachedLocalPayload()) {
                            localPayloadPicker.launch(arrayOf("*/*"))
                        }
                    },
                ) { Text(stringResource(R.string.local_payload_use_cached)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCachedPayloadPrompt = false
                        localPayloadPicker.launch(arrayOf("*/*"))
                    },
                ) { Text(stringResource(R.string.local_payload_choose_new)) }
            },
        )
    }

    if (showPayloadPicker) {
        BundledPayloadDialog(
            payloads = bundledPayloads,
            onSelect = {
                showPayloadPicker = false
                escalator.startWithBundledPayload(it)
            },
            onDismiss = { showPayloadPicker = false },
        )
    }

    if (showEscalationResult) {
        EscalationResultDialog(
            result = escalator.result,
            onActivateJailbreak = {
                showEscalationResult = false
                escalator.activateJailbreak()
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
    status: StellarStatus,
    compact: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = if (compact) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 12.dp else 16.dp,
                    vertical = if (compact) 8.dp else 13.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.local_adb),
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!compact) {
                    Text(
                        text = stringResource(R.string.local_adb_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedContent(
                targetState = status,
                transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
                label = "local-adb-status",
            ) { activeStatus ->
                val ready = activeStatus == StellarStatus.Granted
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                    ),
                ) {
                    Text(
                        modifier = Modifier.padding(
                            horizontal = if (compact) 8.dp else 10.dp,
                            vertical = if (compact) 4.dp else 6.dp,
                        ),
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
        }
    }
}

@Composable
private fun EscalationCard(
    escalator: PrivilegeEscalator,
    enabled: Boolean,
    onSelectBundledPayload: () -> Unit,
    onSelectLocalPayload: () -> Unit,
    compact: Boolean,
) {
    val running = escalator.stage !in setOf(EscalationStage.Idle, EscalationStage.Done)
    val failed = escalator.result is EscalationResult.Failure ||
        escalator.result is EscalationResult.JailbreakActivationFailure
    val accent by animateColorAsState(
        targetValue = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "escalation-accent",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = if (compact) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (compact) Color.Transparent else MaterialTheme.colorScheme.surface,
        ),
        border = if (compact) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 12.dp else 16.dp,
                    vertical = if (compact) 9.dp else 14.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.privilege_card_title),
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                    ),
                ) {
                    Text(
                        modifier = Modifier.padding(
                            horizontal = if (compact) 8.dp else 10.dp,
                            vertical = if (compact) 4.dp else 6.dp,
                        ),
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
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MotionButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !running,
                    onClick = onSelectBundledPayload,
                ) {
                    AnimatedContent(
                        targetState = running,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "escalation-button",
                    ) { active ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (active) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(if (compact) 16.dp else 19.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    modifier = Modifier.size(if (compact) 17.dp else 24.dp),
                                    painter = painterResource(R.drawable.ic_play),
                                    contentDescription = null,
                                )
                            }
                            Text(
                                modifier = Modifier.padding(start = if (compact) 6.dp else 9.dp),
                                text = stringResource(
                                    if (active) R.string.escalation_in_progress else R.string.start_escalation,
                                ),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                MotionButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !running,
                    onClick = onSelectLocalPayload,
                ) {
                    Icon(
                        modifier = Modifier.size(if (compact) 17.dp else 24.dp),
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = null,
                    )
                    Text(
                        modifier = Modifier.padding(start = if (compact) 6.dp else 9.dp),
                        text = stringResource(R.string.use_local_payload),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun BundledPayloadDialog(
    payloads: List<BundledPayload>,
    onSelect: (BundledPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = payloads.filter { payload ->
        query.isBlank() ||
            payload.displayName.contains(query, ignoreCase = true) ||
            payload.library.contains(query, ignoreCase = true) ||
            payload.vendor.contains(query, ignoreCase = true) ||
            payload.kernelVersion.orEmpty().contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bundled_payload_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bundled_payload_search)) },
                )
                Text(
                    text = stringResource(R.string.bundled_payload_count, filtered.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 0.dp, max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(filtered) { payload ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelect(payload) },
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = payload.label,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun LocalPayloadCommandDialog(
    command: String,
    onCommandChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_payload_command_dialog_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 0.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.local_payload_command_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.local_payload_command_presets),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(LOCAL_PRIVILEGE_COMMAND_PRESETS) { preset ->
                    FilterChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = command == preset,
                        onClick = { onCommandChange(preset) },
                        label = {
                            Text(
                                text = preset,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = command,
                        onValueChange = onCommandChange,
                        singleLine = true,
                        label = { Text(stringResource(R.string.local_payload_custom_command)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = command.isNotBlank(),
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.start_escalation))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TerminalOutputCard(
    output: List<OutputLine>,
    scrollState: androidx.compose.foundation.ScrollState,
    canClear: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 3.dp),
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
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                    ),
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
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            output.forEach { line ->
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
    compact: Boolean,
) {
    val suggestions = listOf("id", "uname -r", "getprop ro.product.marketname")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = if (compact) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 10.dp else 14.dp,
                    vertical = if (compact) 8.dp else 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
        ) {
            if (!compact) {
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
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = command,
                onValueChange = onCommandChange,
                enabled = !isRunning,
                placeholder = { Text(stringResource(R.string.command_hint)) },
                minLines = 1,
                maxLines = if (compact) 2 else 3,
                shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium,
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
private fun escalationStageLabel(
    stage: EscalationStage,
): String = when (stage) {
    EscalationStage.Idle -> stringResource(R.string.escalation_idle)
    EscalationStage.CheckingPermission -> stringResource(R.string.escalation_checking_permission)
    EscalationStage.ValidatingCommand -> stringResource(R.string.escalation_validating_command)
    EscalationStage.ImportingLocalResource -> stringResource(R.string.escalation_importing_local_resource)
    EscalationStage.InstallingResources -> stringResource(R.string.escalation_installing_resources)
    EscalationStage.ExecutingPayload -> stringResource(R.string.escalation_executing_payload)
    EscalationStage.CheckingRoot -> stringResource(R.string.escalation_checking_root)
    EscalationStage.ActivatingJailbreak -> stringResource(R.string.escalation_activating_jailbreak)
    EscalationStage.Done -> stringResource(R.string.escalation_done)
}

@Composable
private fun EscalationResultDialog(
    result: EscalationResult,
    onActivateJailbreak: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (result) {
        is EscalationResult.RootConfirmed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.escalation_success_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.adb_escalated_to_root))
                    Text(stringResource(R.string.jailbreak_activation_prompt))
                }
            },
            confirmButton = {
                TextButton(onClick = onActivateJailbreak) {
                    Text(stringResource(R.string.activate_selected_jailbreak))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.do_not_activate)) }
            },
        )

        is EscalationResult.JailbreakActivated -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.jailbreak_activation_success_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.jailbreak_activation_success_message))
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            },
        )

        is EscalationResult.JailbreakActivationFailure -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.jailbreak_activation_failure_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.jailbreak_activation_failure_message))
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
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            },
        )

        EscalationResult.Idle -> Unit
    }
}
