package roro.stellar.manager.ui.features.workbench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ghostlock.app.ui.AssistantGhostlockWorkbenchControls
import com.ghostlock.app.ui.GhostlockEffect
import com.ghostlock.app.ui.GhostlockViewModel
import com.ghostlock.app.ui.GhostlockLogLine
import com.ghostlock.app.ui.DialogType
import com.GhostKey.app.ui.AssistantGhostKeyWorkbenchControls
import com.GhostKey.app.ui.LogTone
import com.GhostKey.app.ui.logToneOf
import com.GhostKey.app.viewmodel.ExploitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.manager.ui.features.wired.VivoWiredViewModel
import roro.stellar.manager.ui.features.wired.VivoWiredWorkbenchControls
import roro.stellar.manager.ui.features.manager.ManagerActivity
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.KernelSuManagerInfo
import roro.stellar.yuehong.shell.KernelSuManagerSelection
import roro.stellar.yuehong.shell.KernelSuManagerTarget
import roro.stellar.yuehong.shell.PrivilegeEscalator
import roro.stellar.yuehong.shell.StellarShellController
import roro.stellar.yuehong.shell.StellarStatus
import roro.stellar.yuehong.ui.DirtyFragVrWorkbenchControls
import roro.stellar.yuehong.ui.StellarPrivilegeWorkbenchControls

private enum class WorkbenchMode(val title: String) {
    Stellar("Stellar"),
    DirtyFragVr("DirtyFrag VR"),
    GhostLock("GhostLock"),
    GhostKey("GhostKey"),
    VivoWired("vivo / iQOO 有线"),
}

private data class ManagerDisplay(
    val label: String,
    val icon: Bitmap?,
)

private data class TerminalLine(
    val text: String,
    val color: androidx.compose.ui.graphics.Color? = null,
    val error: Boolean = false,
)

@Composable
fun PrivilegeWorkbenchScreen(
    ghostlockViewModel: GhostlockViewModel,
    ghostKeyViewModel: ExploitViewModel,
    onManagerTargetChanged: (packageName: String, ksudPath: String) -> Unit,
    onGhostlockEffect: (GhostlockEffect) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { StellarShellController() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val escalator = remember(context.applicationContext) {
        PrivilegeEscalator(context.applicationContext, controller)
    }
    val ghostlockState by ghostlockViewModel.state.collectAsStateWithLifecycle()
    val ghostKeyState by ghostKeyViewModel.uiState.collectAsStateWithLifecycle()
    val vivoViewModel: VivoWiredViewModel = viewModel()
    val vivoState by vivoViewModel.state.collectAsStateWithLifecycle()

    var selectedPackage by remember { mutableStateOf(KernelSuManagerSelection.selectedPackage(context)) }
    var managerTarget by remember { mutableStateOf(KernelSuManagerSelection.selectedManagerTarget(context)) }
    var managerDisplay by remember {
        mutableStateOf(ManagerDisplay(selectedPackage, null))
    }
    var selectedMode by rememberSaveable { mutableStateOf(WorkbenchMode.Stellar) }
    var managerPickerVisible by rememberSaveable { mutableStateOf(false) }
    var modePickerVisible by rememberSaveable { mutableStateOf(false) }
    var modeInfoVisible by rememberSaveable { mutableStateOf(false) }
    var managers by remember { mutableStateOf<List<KernelSuManagerInfo>?>(null) }
    var wirelessDebugEnabled by remember { mutableStateOf(false) }
    var dirtyFragLog by remember { mutableStateOf("") }

    fun refreshWirelessStatus() {
        wirelessDebugEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
        controller.refreshStatus()
    }

    fun updateManagerTarget(packageName: String, path: String?) {
        managerTarget = path?.takeIf(String::isNotBlank)?.let {
            KernelSuManagerTarget(packageName, it)
        }
        onManagerTargetChanged(packageName, managerTarget?.libKsudPath.orEmpty())
    }

    LaunchedEffect(Unit) {
        val selected = KernelSuManagerSelection.selectedPackage(context)
        val resolved = withContext(Dispatchers.IO) {
            KernelSuManagerSelection.selectedManagerTarget(context)
                ?: KernelSuManagerSelection.refreshSelectedManager(context)?.let {
                    KernelSuManagerTarget(it.packageName, it.libKsudPath)
                }
        }
        selectedPackage = selected
        managerTarget = resolved
        onManagerTargetChanged(selected, resolved?.libKsudPath.orEmpty())
        managerDisplay = loadManagerDisplay(context, selected)
        refreshWirelessStatus()
    }

    DisposableEffect(controller, escalator) {
        controller.register()
        onDispose {
            escalator.close()
            controller.close()
        }
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshWirelessStatus()
                ghostKeyViewModel.refreshStatus()
                ghostKeyViewModel.onAppResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ghostlockViewModel) {
        ghostlockViewModel.effects.collect(onGhostlockEffect)
    }

    LaunchedEffect(managerPickerVisible) {
        if (managerPickerVisible) {
            managers = null
            managers = withContext(Dispatchers.IO) { KernelSuManagerSelection.scan(context) }
        }
    }

    val terminalLines = when (selectedMode) {
        WorkbenchMode.Stellar -> controller.output.map {
            TerminalLine(text = it.text, error = it.isError)
        }
        WorkbenchMode.DirtyFragVr -> dirtyFragLog.lineSequence()
            .filter(String::isNotEmpty)
            .toList()
            .takeLast(1200)
            .map(::TerminalLine)
            .toList()
        WorkbenchMode.GhostLock -> ghostlockState.logLines.map(::toTerminalLine)
        WorkbenchMode.GhostKey -> ghostKeyState.logs.map {
            TerminalLine(
                text = it.message,
                error = logToneOf(it.message) == LogTone.ERROR ||
                    it.message.contains("失败") || it.message.contains("错误"),
            )
        }
        WorkbenchMode.VivoWired -> vivoState.logs.map(::TerminalLine)
    }

    val modeRunning = when (selectedMode) {
        WorkbenchMode.Stellar -> controller.isRunning ||
            escalator.stage !in setOf(
                roro.stellar.yuehong.shell.EscalationStage.Idle,
                roro.stellar.yuehong.shell.EscalationStage.Done,
            )
        WorkbenchMode.DirtyFragVr -> false
        WorkbenchMode.GhostLock -> ghostlockState.running
        WorkbenchMode.GhostKey -> ghostKeyState.isRunning
        WorkbenchMode.VivoWired -> vivoState.running
    }
    val wirelessPrivilegeReady = wirelessDebugEnabled && controller.status in setOf(
        StellarStatus.Granted,
        StellarStatus.NeedsPermission,
    )
    val wirelessActivationHint = when {
        selectedMode == WorkbenchMode.Stellar && !wirelessPrivilegeReady ->
            if (!wirelessDebugEnabled) "需要先激活无线调试才能使用 Stellar 模式"
            else "无线调试提权服务未就绪，请点击上方无线调试卡片启动"
        selectedMode == WorkbenchMode.GhostKey && !wirelessPrivilegeReady ->
            if (!wirelessDebugEnabled) "需要先激活无线调试才能使用 GhostKey 模式"
            else "无线调试提权服务未就绪，请点击上方无线调试卡片启动"
        selectedMode == WorkbenchMode.GhostLock && ghostlockState.shizukuEnabled && !wirelessPrivilegeReady ->
            if (!wirelessDebugEnabled) "Shizuku 路线需要无线调试；未就绪时不会回退，可在设置中关闭 Shizuku 改用基础路线"
            else "无线调试提权服务未就绪；Shizuku 路线不会回退，可在设置中关闭 Shizuku 改用基础路线"
        selectedMode == WorkbenchMode.GhostLock && ghostlockState.shizukuEnabled &&
            ghostlockState.shizukuStatus != com.ghostlock.app.domain.model.ShizukuStatus.READY ->
            "Shizuku 执行服务未就绪；点击运行会失败且不会回退，可在设置中关闭 Shizuku 改用基础路线"
        else -> null
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val viewportHeight = maxHeight
            val pageScrollState = rememberScrollState()
            SubcomposeLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(pageScrollState),
            ) { constraints ->
                val width = constraints.maxWidth
                val unconstrainedHeight = constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE)
                val top = subcompose("workbench-content") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        WorkbenchAppHeader()

                        WirelessStatus(
                            wirelessDebugEnabled = wirelessDebugEnabled,
                            stellarStatus = controller.status,
                            onStart = {
                                context.startActivity(
                                    ManagerActivity.createStarterIntent(
                                        context = context,
                                        isRoot = false,
                                        host = "127.0.0.1",
                                        port = 0,
                                        hasSecureSettings = false,
                                        allowTcpIpPort = false,
                                    ),
                                )
                            },
                        )

                        WorkbenchCard(
                            manager = managerDisplay,
                            mode = selectedMode,
                            onSelectManager = { managerPickerVisible = true },
                            onSelectMode = { modePickerVisible = true },
                            onModeInfo = { modeInfoVisible = true },
                        ) {
                            wirelessActivationHint?.let { hint ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Text(
                                        text = hint,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            when (selectedMode) {
                                WorkbenchMode.Stellar -> StellarPrivilegeWorkbenchControls(controller, escalator)
                                WorkbenchMode.DirtyFragVr -> DirtyFragVrWorkbenchControls(
                                    managerPackage = selectedPackage,
                                    ksudPath = managerTarget?.libKsudPath.orEmpty(),
                                    log = dirtyFragLog,
                                    onLogChanged = { dirtyFragLog = it },
                                )
                                WorkbenchMode.GhostLock -> AssistantGhostlockWorkbenchControls(
                                    state = ghostlockState,
                                    wirelessPrivilegeReady = wirelessPrivilegeReady,
                                    onRun = { otaUrl ->
                                        ghostlockViewModel.onWorkbenchRun(otaUrl, wirelessPrivilegeReady)
                                    },
                                    onParseImage = ghostlockViewModel::parseOffsets,
                                    onCpuPairSelected = ghostlockViewModel::selectCpuPair,
                                    onSafeModeChanged = ghostlockViewModel::toggleSafeMode,
                                    onShizukuChanged = ghostlockViewModel::toggleShizuku,
                                    onDialogItemSelected = ghostlockViewModel::onDialogItemSelected,
                                    onDialogDismiss = ghostlockViewModel::onDialogDismiss,
                                    onDialogDismissFinished = ghostlockViewModel::onDialogDismissFinished,
                                    onOverwriteConfirm = ghostlockViewModel::onOverwriteConfirm,
                                    onOverwriteDismiss = ghostlockViewModel::onOverwriteDismiss,
                                )
                                WorkbenchMode.GhostKey -> AssistantGhostKeyWorkbenchControls(
                                    state = ghostKeyState,
                                    wirelessReady = wirelessPrivilegeReady,
                                    onRun = { otaUrl ->
                                        ghostKeyViewModel.startWorkbench(
                                            otaUrl = otaUrl,
                                            managerPackage = selectedPackage,
                                            managerKsudPath = managerTarget?.libKsudPath.orEmpty(),
                                        )
                                    },
                                    onParseLocalBoot = ghostKeyViewModel::parseWorkbenchBoot,
                                    onStop = ghostKeyViewModel::stopExploit,
                                    onConfirmOutOfRange = ghostKeyViewModel::confirmStartExploit,
                                    onDismissOutOfRange = ghostKeyViewModel::dismissKernelRangeDialog,
                                    onDismissInjectRisk = ghostKeyViewModel::dismissInjectRiskDialog,
                                    onDismissUsbWarning = ghostKeyViewModel::dismissUsbCloseDialog,
                                )
                                WorkbenchMode.VivoWired -> VivoWiredWorkbenchControls(vivoViewModel)
                            }
                        }
                    }
                }.map { it.measure(unconstrainedHeight.copy(maxWidth = width)) }

                val topHeight = top.maxOfOrNull { it.height } ?: 0
                val terminalMinHeight = 204.dp.roundToPx()
                val terminalBottomPadding = 8.dp.roundToPx()
                val terminalGap = 12.dp.roundToPx()
                val desiredTerminalHeight = maxOf(
                    terminalMinHeight,
                    viewportHeight.roundToPx() - topHeight - terminalGap - terminalBottomPadding,
                )
                val terminal = subcompose("terminal") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TerminalPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(desiredTerminalHeight.toDp())
                                .padding(horizontal = 16.dp),
                            title = "${selectedMode.title} · 终端",
                            lines = terminalLines,
                            running = modeRunning,
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(terminalBottomPadding.toDp()))
                    }
                }.map {
                    it.measure(
                        androidx.compose.ui.unit.Constraints.fixedWidth(width).copy(
                            maxHeight = Int.MAX_VALUE,
                            minHeight = 0,
                        ),
                    )
                }
                val terminalY = topHeight + terminalGap
                val contentHeight = maxOf(
                    viewportHeight.roundToPx(),
                    terminalY + (terminal.maxOfOrNull { it.height } ?: 0),
                )
                layout(width, contentHeight) {
                    top.forEach { it.placeRelative(0, 0) }
                    terminal.forEach { it.placeRelative(0, terminalY) }
                }
            }
        }
    }

    if (managerPickerVisible) {
        ManagerPickerDialog(
            selectedPackage = selectedPackage,
            managers = managers,
            onDismiss = { managerPickerVisible = false },
            onSelected = { manager ->
                val packageName = if (manager == null) {
                    KernelSuManagerSelection.saveSelectedPackage(
                        context,
                        KernelSuManagerSelection.DEFAULT_PACKAGE,
                    )
                } else {
                    KernelSuManagerSelection.saveSelectedManager(context, manager)
                }
                selectedPackage = packageName
                managerPickerVisible = false
                val info = manager ?: managers?.firstOrNull { it.packageName == packageName }
                if (info != null) {
                    managerDisplay = ManagerDisplay(info.label, drawableToBitmap(info.icon))
                    updateManagerTarget(packageName, info.libKsudPath)
                } else {
                    updateManagerTarget(packageName, null)
                    managerDisplay = ManagerDisplay(packageName, null)
                    scope.launch { managerDisplay = loadManagerDisplay(context, packageName) }
                }
                ghostlockViewModel.refreshAccessStatus()
                ghostKeyViewModel.refreshStatus()
            },
        )
    }

    if (modePickerVisible) {
        ModePickerDialog(
            selected = selectedMode,
            onDismiss = { modePickerVisible = false },
            onSelected = {
                selectedMode = it
                modePickerVisible = false
            },
        )
    }

    if (modeInfoVisible) {
        ModeInfoDialog(onDismiss = { modeInfoVisible = false })
    }

}

@Composable
private fun WorkbenchAppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "月虹提权助手",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WorkbenchCard(
    manager: ManagerDisplay,
    mode: WorkbenchMode,
    onSelectManager: () -> Unit,
    onSelectMode: () -> Unit,
    onModeInfo: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SelectorRow(
                title = "ROOT 管理器",
                value = manager.label,
                icon = manager.icon,
                fallbackSymbol = "#",
                onClick = onSelectManager,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 68.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
            SelectorRow(
                title = "提权模式",
                value = mode.title,
                icon = null,
                fallbackSymbol = "ϟ",
                onClick = onSelectMode,
                infoAction = onModeInfo,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 18.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SelectorRow(
    title: String,
    value: String,
    icon: Bitmap?,
    fallbackSymbol: String,
    onClick: () -> Unit,
    infoAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (icon != null) {
            Image(
                painter = BitmapPainter(icon.asImageBitmap()),
                contentDescription = value,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = Color.Transparent,
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = fallbackSymbol,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (fallbackSymbol == "ϟ") 26.sp else 24.sp,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (infoAction != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(role = Role.Button, onClick = infoAction)
                        .semantics { contentDescription = "提权模式说明" },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("?", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WirelessStatus(
    wirelessDebugEnabled: Boolean,
    stellarStatus: StellarStatus,
    onStart: () -> Unit,
) {
    val connected = wirelessDebugEnabled && (
        stellarStatus == StellarStatus.NeedsPermission || stellarStatus == StellarStatus.Granted
    )
    val label = when {
        connected -> "已连接"
        wirelessDebugEnabled && stellarStatus == StellarStatus.Checking -> "检查中"
        wirelessDebugEnabled -> "未就绪"
        else -> "未开启"
    }
    val detail = when {
        wirelessDebugEnabled && stellarStatus == StellarStatus.NeedsPermission -> "提权服务已连接，请完成 Stellar 应用授权"
        connected -> "无线调试已开启，提权服务已连接"
        wirelessDebugEnabled && stellarStatus == StellarStatus.Checking -> "无线调试已开启，正在检查提权服务"
        wirelessDebugEnabled -> "无线调试已开启，但提权服务未就绪"
        else -> "开启无线调试后，才能使用依赖 ADB 的提权功能"
    }
    val statusColor = when {
        connected -> MaterialTheme.colorScheme.primary
        wirelessDebugEnabled -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !connected, onClick = onStart),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "无线调试",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = statusColor.copy(alpha = 0.12f),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TerminalPanel(
    modifier: Modifier = Modifier,
    title: String,
    lines: List<TerminalLine>,
    running: Boolean,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val exportText = remember(lines) {
        lines.joinToString("\n") { it.text.trimEnd('\r', '\n') }
    }
    val selectableLog = remember(lines) {
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                withStyle(
                    SpanStyle(
                        color = line.color ?: if (line.error) Color(0xFFFF7474) else Color(0xFFE0E2E8),
                    ),
                ) {
                    append(line.text.trimEnd('\r', '\n'))
                }
                if (index != lines.lastIndex) append('\n')
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val exportError = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("无法创建导出文件")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(exportText) }
                    }.exceptionOrNull()
                }
                Toast.makeText(
                    context,
                    if (exportError == null) "终端日志已导出" else "导出失败：${exportError.message ?: "写入文件失败"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    LaunchedEffect(selectableLog) {
        if (selectableLog.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = Color(0xFF191A20),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = if (running) Color(0xFFFFC857) else Color(0xFF55C88B)) {}
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE1E2E8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (running) "运行中" else "就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9B9DA8),
                )
                TextButton(
                    enabled = lines.isNotEmpty(),
                    onClick = { exportLauncher.launch("yuehong-terminal-log.txt") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("导出", color = Color(0xFFB8C5FF))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 9.dp), color = Color(0xFF373943))
            if (lines.isEmpty()) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("等待操作输出…", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E919C))
                }
            } else {
                SelectionContainer(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 7.dp),
                ) {
                    Text(
                        text = selectableLog,
                        modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagerPickerDialog(
    selectedPackage: String,
    managers: List<KernelSuManagerInfo>?,
    onDismiss: () -> Unit,
    onSelected: (KernelSuManagerInfo?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 ROOT 管理器") },
        text = {
            when (managers) {
                null -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("正在扫描已安装的兼容管理器…")
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "当前：$selectedPackage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (managers.isEmpty()) {
                        Text("未找到带有可识别 ksud 的管理器")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        ) {
                            items(managers, key = { it.packageName }) { manager ->
                                ManagerChoiceRow(
                                    manager = manager,
                                    selected = manager.packageName == selectedPackage,
                                    onClick = { onSelected(manager) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = managers != null,
                onClick = {
                    onSelected(managers?.firstOrNull {
                        it.packageName == KernelSuManagerSelection.DEFAULT_PACKAGE
                    })
                },
            ) { Text("使用默认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ManagerChoiceRow(
    manager: KernelSuManagerInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val bitmap = remember(manager.packageName) { drawableToBitmap(manager.icon) }
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = manager.label,
                modifier = Modifier.size(36.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(manager.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            Text(manager.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun ModePickerDialog(
    selected: WorkbenchMode,
    onDismiss: () -> Unit,
    onSelected: (WorkbenchMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择提权模式") },
        text = {
            Column {
                WorkbenchMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = mode != WorkbenchMode.DirtyFragVr || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                            ) { onSelected(mode) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.title, fontWeight = if (mode == selected) FontWeight.Bold else FontWeight.Medium)
                            if (mode == WorkbenchMode.DirtyFragVr && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                                Text(
                                    stringResource(R.string.mode_dirtyfrag_vr_kernel_required),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        RadioButton(
                            selected = mode == selected,
                            enabled = mode != WorkbenchMode.DirtyFragVr || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                            onClick = { onSelected(mode) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ModeInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提权模式说明") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeDescription(
                    "Stellar",
                    "内置多种机型提权载荷，通过 Shell 完成提权，适合各种机型。",
                )
                ModeDescription(
                    "DirtyFrag VR",
                    "通过 CVE-2026-43284 漏洞完成提权，支持去除 VR 探针（推荐 vivo/iQOO 单设备使用），推荐 5 系、6 系内核。",
                )
                ModeDescription(
                    "GhostLock",
                    "通过 CVE-2026-43499 漏洞完成提权，适配绝大部分 6 系内核设备。",
                )
                ModeDescription(
                    "GhostKey",
                    "通过 CVE-2026-98260 漏洞与 Shell 完成提权，推荐高版本系统使用。",
                )
                ModeDescription(
                    "vivo / iQOO 有线",
                    "通过对应机型的提权载荷，并通过双设备有线连接完成提权。",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ModeDescription(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun loadManagerDisplay(context: Context, packageName: String): ManagerDisplay =
    withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            ManagerDisplay(
                label = info.loadLabel(context.packageManager).toString().ifBlank { packageName },
                icon = drawableToBitmap(info.loadIcon(context.packageManager)),
            )
        }.getOrDefault(ManagerDisplay(packageName, null))
    }

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
    return runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }.getOrNull()
}

private fun toTerminalLine(line: GhostlockLogLine) = TerminalLine(
    text = line.text,
    color = androidx.compose.ui.graphics.Color(line.color),
)
