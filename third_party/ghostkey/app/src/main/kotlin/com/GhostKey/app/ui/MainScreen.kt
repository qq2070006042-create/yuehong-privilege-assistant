package com.GhostKey.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.GhostKey.app.R
import com.GhostKey.app.device.DeviceBinding
import com.GhostKey.app.model.ShizukuStatus
import com.GhostKey.app.shizuku.ShizukuPermissionManager
import com.GhostKey.app.model.StageState
import com.GhostKey.app.viewmodel.LogEntry
import com.GhostKey.app.native.NativeContract
import com.GhostKey.app.ui.theme.CvePalette
import com.GhostKey.app.viewmodel.ExploitViewModel
import com.GhostKey.app.viewmodel.ExploitUiState
import java.util.Locale

/** 漏洞编号，仅用于标题区展示。 */
private const val CVE_ID = "CVE-2026-98260"

/** 顶层页面（主页 / 设置 / 日志）。 */
private enum class Screen { MAIN, SETTINGS, LOGS }

/** 内核表未命中时 ViewModel 回落的文案前缀。 */
private const val UNMATCHED_PREFIX = "未匹配"

/** 阶段英文代号 → 中文短名。 */
private val STAGE_LABELS = mapOf(
    NativeContract.Stage.CALIB_A to "校准 A",
    NativeContract.Stage.CALIB_B to "校准 B",
    NativeContract.Stage.INJECT to "注入",
    NativeContract.Stage.VERIFY to "校验",
    NativeContract.Stage.KSU to "KernelSU",
)

@Composable
fun MainScreen(viewModel: ExploitViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var screen by remember { mutableStateOf(Screen.MAIN) }

    BackHandler(enabled = screen != Screen.MAIN) {
        screen = if (screen == Screen.LOGS) Screen.SETTINGS else Screen.MAIN
        viewModel.refreshStatus()
    }

    when (screen) {
        Screen.MAIN -> MainContent(
            viewModel = viewModel,
            uiState = uiState,
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            viewModel = viewModel,
            onBack = {
                screen = Screen.MAIN
                viewModel.refreshStatus()
            },
            onOpenLogs = { screen = Screen.LOGS },
        )
        Screen.LOGS -> LogViewerScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.SETTINGS },
        )
    }
}

@Composable
private fun MainContent(
    viewModel: ExploitViewModel,
    uiState: ExploitUiState,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val appName = stringResource(R.string.app_name)

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(uiState.logs.lastIndex)
        }
    }

    val shizukuReady = uiState.shizukuStatus == ShizukuStatus.READY
    val kernelMatched = uiState.matchedProfile.isNotBlank() &&
        !uiState.matchedProfile.startsWith(UNMATCHED_PREFIX) &&
        uiState.matchedProfile != "检测中..."
    val blockReason = when {
        uiState.isRunning -> "流水线执行中，请勿退出。"
        !shizukuReady -> "请先完成 Shizuku 授权（见上方提示）。"
        !kernelMatched -> "当前内核未命中可用 profile（内置表或已导入）。"
        uiState.kernelSupport == DeviceBinding.KernelSupport.UNKNOWN ->
            "内核版本不可读或为模拟器/通用内核，无法执行。"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CvePalette.Backdrop)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = appName,
            onRefresh = { viewModel.refreshStatus() },
            onSettings = onOpenSettings,
        )
        HorizontalRule()
        DeviceInfoBar(
            model = uiState.deviceModel,
            kernel = uiState.kernelRelease,
            inRange = uiState.kernelSupport == DeviceBinding.KernelSupport.IN_RANGE,
            unknown = uiState.kernelSupport == DeviceBinding.KernelSupport.UNKNOWN,
        )
        HorizontalRule()
        ShizukuModule(
            shizukuStatus = uiState.shizukuStatus,
            shizukuDetail = uiState.shizukuDetail,
            managerVersion = uiState.managerVersion,
            onRequest = { viewModel.requestShizukuPermission() },
        )
        HorizontalRule()
        TemporaryRootBanner()
        HorizontalRule()
        PipelinePanel(
            states = uiState.stages,
            currentStage = uiState.currentStage,
            stageStatus = uiState.stageStatus,
            progress = uiState.progress,
            durations = uiState.stageDurations,
            hint = uiState.stageHint,
            elapsedMs = uiState.elapsedMs,
            isRunning = uiState.isRunning,
        )
        TerminalPanel(
            logs = uiState.logs,
            listState = listState,
            isRunning = uiState.isRunning,
            onClear = viewModel::clearLogs,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 120.dp),
        )
        ActionBar(
            canStart = !uiState.isRunning && shizukuReady && kernelMatched &&
                uiState.kernelSupport != DeviceBinding.KernelSupport.UNKNOWN,
            blockReason = blockReason,
            isRunning = uiState.isRunning,
            onStart = { viewModel.requestStartExploit() },
            onStop = { viewModel.stopExploit() },
        )
    }

    if (uiState.showInjectRiskDialog) {
        InjectRiskDialog(onDismiss = viewModel::dismissInjectRiskDialog)
    }
    if (uiState.showKernelRangeDialog) {
        KernelRangeDialog(
            kernel = uiState.kernelRelease,
            onConfirm = viewModel::confirmStartExploit,
            onDismiss = viewModel::dismissKernelRangeDialog,
        )
    }
    if (uiState.showUsbCloseDialog) {
        UsbCloseDialog(onDismiss = viewModel::dismissUsbCloseDialog)
    }
}

/**
 * 内核范围外提醒弹窗：当前内核不在漏洞已验证的适用范围内（v1.0.7 起仅提醒不拦截），
 * 用户可自行权衡后选择「仍要执行」继续 ROOT，或「取消」放弃。
 */
@Composable
private fun KernelRangeDialog(
    kernel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = CvePalette.TextMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("仍要执行", color = CvePalette.Warning)
            }
        },
        title = {
            Text(
                text = "内核不在适用范围内",
                style = MaterialTheme.typography.titleMedium,
                color = CvePalette.Warning,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionText(
                    title = "当前内核",
                    body = kernel,
                )
                SectionText(
                    title = "漏洞影响范围（CVE-2026-98260 官方通告）",
                    body = "POSIX CPU timer exec() UAF 自 Linux 5.7 引入。" +
                        "已修复：6.6 ≥ 6.6.158、6.12 ≥ 6.12.112、6.18 ≥ 6.18.54、7.2 ≥ 7.2.8、7.3 及之后；" +
                        "5.10 / 5.15 / 6.1 等分支未见修复，仍受影响。",
                )
                SectionText(
                    title = "提醒",
                    body = "你的内核不在上述影响范围内（低于 5.7，或已是修复版本）。" +
                        "执行仍会继续（不被阻止），但利用大概率失败；另外厂商可能已回移植修复而未改版本号，" +
                        "此判断仅供参考。请自行评估风险。",
                    highlight = true,
                )
            }
        },
    )
}

/**
 * 常驻「临时 ROOT」提示条：明确告知本工具提供的是重启失效的临时 root，
 * 与「持久 root（KernelSU/Magisk）」区分，避免用户误解。
 */
@Composable
private fun TemporaryRootBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 22.dp)
                .background(CvePalette.Warning, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                "临时 ROOT · 重启后失效",
                color = CvePalette.Warning,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "本工具提供的是临时 root，重启后自动恢复；如需持久 root 请使用 KernelSU 等方案。",
                color = CvePalette.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * USB 调试提醒弹窗：ROOT 成功后用户从外部（KernelSU 等）返回 APP 时，
 * 若检测到 USB 调试仍开启则提示关闭（提权已完成，通道可以收掉）。
 */
@Composable
private fun UsbCloseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = CvePalette.Accent)
            }
        },
        title = {
            Text(
                text = "建议关闭 USB 调试",
                style = MaterialTheme.typography.titleMedium,
                color = CvePalette.Warning,
            )
        },
        text = {
            Column {
                Text(
                    "ROOT 已完成，检测到「USB 调试」仍处于开启状态。若提权完成后仍长期保持该通道开启，" +
                        "本地恶意应用可能借道 adb 获取 shell 权限，扩大攻击面。",
                    color = CvePalette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "请进入 设置 → 系统与更新 → 开发者选项，关闭「USB 调试」与「USB 无线调试」。" +
                        "之后可随时重新开启，不影响正常使用。",
                    color = CvePalette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

/**
 * 带倒计时确认按钮：倒计时归零前不可点击，用于「必须读完才能继续」的弹窗。
 * 倒计时与按钮完全绑定，且弹窗禁用外部/返回键关闭，用户无法跳过。
 */
@Composable
private fun CountdownConfirmButton(
    text: String,
    totalSeconds: Int = 5,
    enabledColor: Color,
    onConfirm: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(totalSeconds) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    Button(
        onClick = onConfirm,
        enabled = remaining <= 0,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = enabledColor,
            contentColor = Color(0xFF02201C),
            disabledContainerColor = CvePalette.SurfaceRaised,
            disabledContentColor = CvePalette.TextMuted,
        ),
    ) {
        Text(
            text = if (remaining > 0) "$text ($remaining)" else text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 注入阶段 SO 风险弹窗：进入注入（UAF 利用）时展示，告知 SO 风险及
 * 关闭 USB 调试/无线调试的解决方法。同样 5 秒倒计时、不可外部关闭。
 */
@Composable
private fun InjectRiskDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        confirmButton = {
            CountdownConfirmButton(
                text = "我知道了",
                totalSeconds = 5,
                enabledColor = CvePalette.Warning,
                onConfirm = onDismiss,
            )
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "注入阶段 · 安全提示",
                    style = MaterialTheme.typography.titleMedium,
                    color = CvePalette.TextPrimary,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionText(
                    title = "当前状态",
                    body = "已进入注入（UAF 漏洞利用）阶段，正在通过 adb / USB 调试通道获取 shell 上下文，以写入内核 ftrace。",
                )
                SectionText(
                    title = "SO 风险说明",
                    body = "该过程依赖「USB 调试 / USB 无线调试」建立的 adb 通道。若提权完成后仍长期保持该通道开启，本地恶意应用可能借道 adb 获取 shell 权限，扩大攻击面。",
                    highlight = true,
                )
                SectionText(
                    title = "解决方法",
                    body = "提权完成后，请进入 设置 → 系统与更新 → 开发者选项，关闭「USB 调试」与「USB 无线调试」。只需关闭一次即可消除该风险，之后可随时重新开启，不影响设备正常使用。",
                )
                SectionText(
                    title = "提示",
                    body = "本弹窗为安全提醒，流水线会在后台继续运行。",
                )
            }
        },
    )
}

/** 须知/风险弹窗内的小段落：标题 + 正文，highlight 时用警告色强调。 */
@Composable
private fun SectionText(title: String, body: String, highlight: Boolean = false) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (highlight) CvePalette.Warning else CvePalette.TextPrimary,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.height(3.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = CvePalette.TextSecondary,
    )
    Spacer(modifier = Modifier.height(10.dp))
}

/**
 * 设备信息条：展示「机型」与「内核」两项，并标出内核适用范围状态
 * （v1.0.7 起范围外仅弹窗提醒、不拦截执行；模拟器/不可读仍拦截）。
 * 颜色沿用 CvePalette：在范围内为 Success（绿），范围外为 Warning（橙）。
 */
@Composable
private fun DeviceInfoBar(
    model: String,
    kernel: String,
    inRange: Boolean,
    unknown: Boolean = false,
) {
    val tint = when {
        unknown -> CvePalette.Danger
        inRange -> CvePalette.Success
        else -> CvePalette.Warning
    }
    val statusText = when {
        unknown -> "内核不支持（不可执行）"
        inRange -> "内核在适用范围内"
        else -> "内核在适用范围外（执行前会提醒）"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "设备",
                style = MaterialTheme.typography.titleSmall,
                color = CvePalette.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow(label = "机型", value = model.ifBlank { "未知" })
        Spacer(modifier = Modifier.height(6.dp))
        InfoRow(label = "内核", value = kernel.ifBlank { "检测中..." })
    }
}

/** 单行「标签 : 值」：标签固定宽度，值用等宽字体呈现内核/机型字符串。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CvePalette.TextMuted,
            modifier = Modifier.width(36.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = CvePalette.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CvePalette.Outline),
    )
}

@Composable
private fun AppHeader(title: String, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 34.dp)
                .background(CvePalette.Accent, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = CvePalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$CVE_ID",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = CvePalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRefresh) {
            Text(
                text = "刷新",
                style = MaterialTheme.typography.labelLarge,
                color = CvePalette.Accent,
            )
        }
        TextButton(onClick = onSettings) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.labelLarge,
                color = CvePalette.Accent,
            )
        }
    }
}

/**
 * Shizuku 授权模块（重构后的整体 UI，视觉优化版）。
 * 颜色完全沿用 CvePalette：语义色由状态决定（成功/警告/危险），按钮仍用 Accent。
 * 已授权状态：绿色对勾徽标 + 「已授权」状态胶囊 + 一行确认信息，整体沉静；
 * 未授权状态：对应语义色徽标 + 提示卡（图标 + 说明 + 全宽操作按钮），引导用户完成授权。
 */
@Composable
private fun ShizukuModule(
    shizukuStatus: ShizukuStatus,
    shizukuDetail: String,
    managerVersion: String,
    onRequest: () -> Unit,
) {
    val context = LocalContext.current
    val isReady = shizukuStatus == ShizukuStatus.READY
    val tint = when (shizukuStatus) {
        ShizukuStatus.READY -> CvePalette.Success
        ShizukuStatus.PERMISSION_REQUIRED -> CvePalette.Warning
        ShizukuStatus.NOT_RUNNING -> CvePalette.Danger
        ShizukuStatus.NOT_INSTALLED -> CvePalette.Danger
    }
    val statusText = when (shizukuStatus) {
        ShizukuStatus.READY ->
            if (shizukuDetail.isBlank()) "已授权" else "已授权 · $shizukuDetail"
        ShizukuStatus.PERMISSION_REQUIRED -> "待授权"
        ShizukuStatus.NOT_RUNNING -> "服务未运行"
        ShizukuStatus.NOT_INSTALLED -> "未安装"
    }
    val (hintText, actionLabel, onAction) = when (shizukuStatus) {
        ShizukuStatus.NOT_INSTALLED -> Triple(
            "未检测到 Shizuku Manager。请先安装它，再通过 adb 或无线调试启动服务。",
            "下载 Shizuku",
            { openShizukuOrStore(context, launchApp = false) },
        )
        ShizukuStatus.NOT_RUNNING -> Triple(
            "Shizuku 已安装但服务未运行。点击打开 Shizuku 并启动服务（adb 或无线调试）后，再请求授权。",
            "打开 Shizuku",
            { openShizukuOrStore(context, launchApp = true) },
        )
        ShizukuStatus.PERMISSION_REQUIRED -> Triple(
            "点击「请求授权」后本应用会出现在 Shizuku 的待授权列表，在 Shizuku 中点「允许」即可。",
            "请求授权",
            onRequest,
        )
        ShizukuStatus.READY -> Triple("", "", {})
    }

    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .border(1.dp, if (isReady) CvePalette.AccentDim else CvePalette.Outline, shape),
        shape = shape,
        color = if (isReady) CvePalette.SurfaceRaised else CvePalette.Surface,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isReady) CheckBadge(tint = tint) else ShizukuIconBadge(tint = tint)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Shizuku 授权",
                        style = MaterialTheme.typography.titleSmall,
                        color = CvePalette.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(text = shizukuPillText(shizukuStatus), tint = tint)
            }

            if (!isReady) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tint.copy(alpha = 0.08f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(10.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HintIconBadge(tint = tint)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = hintText,
                                style = MaterialTheme.typography.bodySmall,
                                color = CvePalette.TextSecondary,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onAction,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CvePalette.Accent),
                            ) {
                                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 已授权状态的绿色对勾徽标。 */
@Composable
private fun CheckBadge(tint: Color, size: Dp = 36.dp) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f), modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = tint,
            )
        }
    }
}

/** 右上角状态胶囊（已连接 / 待授权 / 未运行 / 未安装）。 */
@Composable
private fun StatusPill(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

private fun shizukuPillText(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.READY -> "已连接"
    ShizukuStatus.PERMISSION_REQUIRED -> "待授权"
    ShizukuStatus.NOT_RUNNING -> "未运行"
    ShizukuStatus.NOT_INSTALLED -> "未安装"
}

@Composable
private fun ShizukuIconBadge(tint: Color, size: Dp = 36.dp) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.14f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "S",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = tint,
            )
        }
    }
}

@Composable
private fun HintIconBadge(tint: Color, size: Dp = 22.dp) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.16f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "!",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = tint,
            )
        }
    }
}

private fun openShizukuOrStore(context: Context, launchApp: Boolean) {
    val pkg = ShizukuPermissionManager.MANAGER_PACKAGE
    if (launchApp) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            runCatching { context.startActivity(launch) }.onFailure { openShizukuStore(context) }
            return
        }
    }
    openShizukuStore(context)
}

private fun openShizukuStore(context: Context) {
    val pkg = ShizukuPermissionManager.MANAGER_PACKAGE
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(market) }.onFailure {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(web) }
    }
}

@Composable
private fun PipelinePanel(
    states: List<StageState>,
    currentStage: Int,
    stageStatus: String,
    progress: Float,
    durations: List<Long>,
    hint: String,
    elapsedMs: Long,
    isRunning: Boolean,
) {
    val total = states.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "提权流水线",
                style = MaterialTheme.typography.titleSmall,
                color = CvePalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            val completed = states.count {
                it == StageState.SUCCESS || it == StageState.DEGRADED ||
                    it == StageState.FAILED || it == StageState.STOPPED
            }
            Text(
                text = buildString {
                    append("$completed/$total")
                    append(" · ")
                    append(statusLabel(stageStatus))
                    if (elapsedMs > 0L) append(" · ${formatDuration(elapsedMs)}")
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = CvePalette.TextMuted,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        ProgressTrack(progress = progress.coerceIn(0f, 1f))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前动作",
                style = MaterialTheme.typography.labelSmall,
                color = CvePalette.TextMuted,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = hint.ifBlank { "等待开始" },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (isRunning) CvePalette.Accent else CvePalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            NativeContract.Stage.ORDERED.forEachIndexed { index, code ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StageNode(
                        index = index,
                        total = total,
                        state = states[index],
                        previousDone = if (index > 0) isSettled(states[index - 1]) else false,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = STAGE_LABELS[code] ?: code,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (states[index] == StageState.PENDING) {
                            CvePalette.TextMuted
                        } else {
                            stateColor(states[index])
                        },
                        maxLines = 1,
                    )
                    Text(
                        text = durationLabel(state = states[index], ms = durations.getOrNull(index) ?: 0L),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                        ),
                        color = if (states[index] == StageState.RUNNING) {
                            CvePalette.Accent
                        } else {
                            CvePalette.TextMuted
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 该阶段是否已结束（用于连线高亮）。 */
private fun isSettled(state: StageState): Boolean = when (state) {
    StageState.SUCCESS, StageState.DEGRADED, StageState.FAILED, StageState.SKIPPED, StageState.STOPPED -> true
    StageState.PENDING, StageState.RUNNING -> false
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0.0s"
    return if (ms < 60_000L) {
        String.format(Locale.US, "%.1fs", ms / 1000f)
    } else {
        String.format(Locale.US, "%dm%02ds", ms / 60_000L, (ms % 60_000L) / 1000L)
    }
}

private fun durationLabel(state: StageState, ms: Long): String = when (state) {
    StageState.PENDING -> "待执行"
    StageState.RUNNING -> "${formatDuration(ms)} …"
    StageState.SUCCESS, StageState.DEGRADED -> formatDuration(ms)
    StageState.FAILED -> "失败 · ${formatDuration(ms)}"
    StageState.SKIPPED -> "跳过"
    StageState.STOPPED -> "中断 · ${formatDuration(ms)}"
}

@Composable
private fun ProgressTrack(progress: Float) {
    val shape = RoundedCornerShape(3.dp)
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "pipelineProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(CvePalette.SurfaceRaised, shape),
    ) {
        if (animated > 0.002f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animated)
                    .height(4.dp)
                    .background(CvePalette.Accent, shape),
            )
        }
    }
}

@Composable
private fun StageNode(
    index: Int,
    total: Int,
    state: StageState,
    previousDone: Boolean,
) {
    val radius = 13.dp
    val trackColor = if (previousDone) CvePalette.AccentDim else CvePalette.Outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val cx = size.width / 2f
            val w = 2.5.dp.toPx()
            val gap = radius.toPx() + 3.dp.toPx()
            if (index > 0) {
                drawLine(
                    color = trackColor,
                    start = Offset(0f, cy),
                    end = Offset(cx - gap, cy),
                    strokeWidth = w,
                    cap = StrokeCap.Butt,
                )
            }
            if (index < total - 1) {
                drawLine(
                    color = if (isSettled(state)) CvePalette.AccentDim else CvePalette.Outline,
                    start = Offset(cx + gap, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = w,
                    cap = StrokeCap.Butt,
                )
            }
        }
        StageGlyph(state = state, boxSize = radius * 2)
    }
}

@Composable
private fun StageGlyph(state: StageState, boxSize: Dp) {
    val color = stateColor(state)
    when (state) {
        StageState.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(boxSize),
            strokeWidth = 2.5.dp,
            color = color,
        )
        else -> Canvas(modifier = Modifier.size(boxSize)) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val dashed = state == StageState.SKIPPED || state == StageState.STOPPED
            if (dashed) {
                drawCircle(
                    color = color,
                    radius = r,
                    center = c,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                            0f,
                        ),
                    ),
                )
            }
            when (state) {
                StageState.PENDING -> {
                    drawCircle(color = CvePalette.SurfaceRaised, radius = r, center = c)
                    drawCircle(
                        color = CvePalette.OutlineStrong,
                        radius = r,
                        center = c,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawCircle(
                        color = CvePalette.OutlineStrong,
                        radius = r * 0.3f,
                        center = c,
                    )
                }
                StageState.SUCCESS -> {
                    drawCircle(color = color, radius = r, center = c)
                    val mark = Path().apply {
                        moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
                        lineTo(c.x - r * 0.10f, c.y + r * 0.36f)
                        lineTo(c.x + r * 0.46f, c.y - r * 0.38f)
                    }
                    drawPath(
                        path = mark,
                        color = CvePalette.OnBadge,
                        style = Stroke(
                            width = 2.4.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                StageState.DEGRADED -> {
                    drawCircle(color = color, radius = r, center = c)
                    drawLine(
                        color = CvePalette.OnBadge,
                        start = Offset(c.x, c.y - r * 0.44f),
                        end = Offset(c.x, c.y + r * 0.04f),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(color = CvePalette.OnBadge, radius = r * 0.11f,
                        center = Offset(c.x, c.y + r * 0.28f))
                }
                StageState.SKIPPED -> {
                    drawLine(
                        color = color,
                        start = Offset(c.x - r * 0.32f, c.y),
                        end = Offset(c.x + r * 0.32f, c.y),
                        strokeWidth = 2.0.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                StageState.STOPPED -> {
                    drawCircle(color = color, radius = r * 0.26f, center = c)
                }
                StageState.FAILED -> {
                    drawCircle(color = color, radius = r, center = c)
                    val d = r * 0.38f
                    drawLine(
                        color = CvePalette.OnBadgeError,
                        start = Offset(c.x - d, c.y - d),
                        end = Offset(c.x + d, c.y + d),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = CvePalette.OnBadgeError,
                        start = Offset(c.x + d, c.y - d),
                        end = Offset(c.x - d, c.y + d),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                StageState.RUNNING -> Unit
            }
        }
    }
}

@Composable
private fun stateColor(state: StageState): Color = when (state) {
    StageState.PENDING -> CvePalette.TextMuted
    StageState.RUNNING -> CvePalette.Accent
    StageState.SUCCESS -> CvePalette.Success
    StageState.DEGRADED -> CvePalette.Warning
    StageState.FAILED -> CvePalette.Danger
    StageState.SKIPPED -> CvePalette.TextMuted
    StageState.STOPPED -> CvePalette.Warning
}

private fun statusLabel(raw: String): String = when (raw) {
    "running" -> "执行中"
    "ok" -> "成功"
    "degraded" -> "降级完成"
    "failed" -> "失败"
    "skipped" -> "跳过"
    "成功" -> "成功"
    "失败" -> "失败"
    "已停止" -> "已停止"
    "启动中" -> "启动中"
    "就绪" -> "待命"
    else -> raw
}

@Composable
private fun TerminalPanel(
    logs: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRunning: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onCopy: () -> Unit = {
        if (logs.isNotEmpty()) {
            val sb = StringBuilder()
            for (entry in logs) {
                sb.append("[").append(entry.formattedTime).append("] ")
                    .append(entry.message).append("\n")
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cve98260_log", sb.toString()))
            Toast.makeText(context, "已复制 ${logs.size} 行日志", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp)
            .border(1.dp, CvePalette.Outline, shape)
            .clip(shape),
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            color = CvePalette.SurfaceRaised,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isRunning) CvePalette.Accent else CvePalette.TextMuted,
                            CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "终端",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = CvePalette.TextPrimary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "执行中" else "空闲",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRunning) CvePalette.Accent else CvePalette.TextMuted,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "复制",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CvePalette.Accent,
                    modifier = Modifier
                        .clickable { onCopy() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CvePalette.Danger,
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = "${logs.size} 行",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CvePalette.TextMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        HorizontalRule()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CvePalette.SurfaceSunken),
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "等待执行…",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = CvePalette.TextMuted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    items(logs) { entry -> TerminalLine(entry = entry) }
                }
            }
        }
    }
}

@Composable
private fun TerminalLine(entry: LogEntry) {
    val tone = logToneOf(entry.message)
    val color = when (tone) {
        LogTone.SYSTEM -> CvePalette.Info
        LogTone.FLOW -> CvePalette.Accent
        LogTone.ERROR -> CvePalette.Danger
        LogTone.WARN -> CvePalette.Warning
        LogTone.PLAIN -> CvePalette.TextSecondary
    }
    val background = when (tone) {
        LogTone.ERROR -> CvePalette.Danger.copy(alpha = 0.10f)
        LogTone.WARN -> CvePalette.Warning.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "[${entry.formattedTime}]",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            color = CvePalette.TextMuted,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 日志着色判定已抽到 [logToneOf]（ui/LogTone.kt），供终端与日志详情页共用，
 * 避免两处各自演化出不同颜色。
 */

@Composable
private fun ActionBar(
    canStart: Boolean,
    blockReason: String?,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 14.dp),
    ) {
        if (blockReason != null) {
            Text(
                text = blockReason,
                style = MaterialTheme.typography.bodySmall,
                color = CvePalette.Warning,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CvePalette.Accent,
                    contentColor = Color(0xFF02201C),
                    disabledContainerColor = CvePalette.SurfaceRaised,
                    disabledContentColor = CvePalette.TextMuted,
                ),
            ) {
                Text(
                    text = "开始提权",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = onStop,
                enabled = isRunning,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = CvePalette.Danger,
                    disabledContentColor = CvePalette.TextMuted,
                ),
            ) {
                Text(
                    text = "停止",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
