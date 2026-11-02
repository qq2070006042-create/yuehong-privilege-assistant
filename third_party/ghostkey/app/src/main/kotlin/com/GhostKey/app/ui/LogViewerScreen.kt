package com.GhostKey.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.GhostKey.app.data.RunLogMeta
import com.GhostKey.app.data.RunLogStore
import com.GhostKey.app.data.RunResult
import com.GhostKey.app.ui.theme.CvePalette
import com.GhostKey.app.viewmodel.ExploitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页：**两级**。
 *
 * 1. 一级＝「每一次 ROOT 运行」的列表（按开始时间倒序，最新在最上）；
 * 2. 点某一行 → 二级＝这一次运行的全部记录；返回即回到列表。
 *
 * 记录来自 `filesDir/run_logs/`（[RunLogStore]），**不依赖内存**，
 * 所以 App 被杀 / 重启后依然能看到历史每一次运行——这正是「按每一轮设计」的前提。
 */
@Composable
fun LogViewerScreen(viewModel: ExploitViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<RunLogMeta?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val runs = remember(refreshKey) { viewModel.listRunLogs() }

    val current = opened
    if (current == null) {
        RunListPage(
            runs = runs,
            onBack = onBack,
            onOpen = { opened = it },
            onExportAll = {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { viewModel.exportAllRunsText() }
                    val name = exportTextToDownload(context, text)
                    toast(context, if (name != null) "已导出到 Download/$name" else "导出失败，请重试")
                }
            },
            onClearAll = {
                viewModel.clearRuns()
                refreshKey++
            },
        )
    } else {
        RunDetailPage(
            viewModel = viewModel,
            meta = current,
            onBack = {
                opened = null
                refreshKey++
            },
            onDelete = {
                viewModel.deleteRun(current)
                opened = null
                refreshKey++
            },
        )
    }
}


@Composable
private fun RunListPage(
    runs: List<RunLogMeta>,
    onBack: () -> Unit,
    onOpen: (RunLogMeta) -> Unit,
    onExportAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CvePalette.Backdrop)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = CvePalette.Accent, style = MaterialTheme.typography.labelLarge)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "运行记录",
                    style = MaterialTheme.typography.titleLarge,
                    color = CvePalette.TextPrimary,
                )
                Text(
                    if (runs.isEmpty()) "暂无记录" else "共 ${runs.size} 次 · 最新在最上",
                    style = MaterialTheme.typography.labelSmall,
                    color = CvePalette.TextMuted,
                )
            }
            TextButton(onClick = onExportAll, enabled = runs.isNotEmpty()) {
                Text("导出全部", color = CvePalette.Accent, style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = CvePalette.Outline, thickness = 1.dp)

        if (runs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无运行记录", color = CvePalette.TextMuted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "每执行一次 ROOT 会单独保存一份记录",
                        color = CvePalette.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(count = runs.size, key = { i -> runs[i].file.name }) { i ->
                    RunRow(runs[i], onOpen)
                }
                item(key = "clear_footer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(
                                "清空全部记录",
                                color = CvePalette.TextMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部运行记录？") },
            text = {
                Text("将删除 ${runs.size} 份历史日志文件，不可恢复。\n主页终端里的实时内容不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("清空", color = CvePalette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消", color = CvePalette.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun RunRow(meta: RunLogMeta, onOpen: (RunLogMeta) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(meta) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${meta.runId} 次运行",
                    color = CvePalette.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    meta.result.label,
                    color = resultColor(meta.result),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(dateTimeFormat.format(Date(meta.startedAt)))
                    append(" · ")
                    append(RunLogStore.fmtDuration(meta.durationMs))
                    meta.lineCount?.let { append(" · $it 条") }
                },
                color = CvePalette.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.kernel.isNotBlank()) {
                Text(
                    meta.kernel,
                    color = CvePalette.TextMuted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("›", color = CvePalette.TextMuted, style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider(color = CvePalette.Outline, thickness = 1.dp)
}


@Composable
private fun RunDetailPage(
    viewModel: ExploitViewModel,
    meta: RunLogMeta,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var lines by remember(meta.file.name) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(meta.file.name) {
        lines = withContext(Dispatchers.IO) { viewModel.runLogText(meta).lines() }
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CvePalette.Backdrop)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = CvePalette.Accent, style = MaterialTheme.typography.labelLarge)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "第 ${meta.runId} 次运行",
                    style = MaterialTheme.typography.titleLarge,
                    color = CvePalette.TextPrimary,
                )
                Text(
                    buildString {
                        append(dateTimeFormat.format(Date(meta.startedAt)))
                        append(" · ")
                        append(meta.result.label)
                        meta.exitCode?.let { append(" (exit=$it)") }
                        append(" · ")
                        append(RunLogStore.fmtDuration(meta.durationMs))
                        meta.lineCount?.let { append(" · $it 条") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = resultColor(meta.result),
                )
            }
            TextButton(
                onClick = {
                    val text = lines?.joinToString("\n").orEmpty()
                    if (text.isBlank()) {
                        toast(context, "内容尚未读出")
                    } else {
                        copyToClipboard(context, text)
                        toast(context, "已复制本次记录")
                    }
                },
                enabled = lines != null,
            ) {
                Text("复制", color = CvePalette.Accent, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = {
                    val text = lines?.joinToString("\n").orEmpty()
                    val name = exportTextToDownload(context, text)
                    toast(context, if (name != null) "已导出到 Download/$name" else "导出失败，请重试")
                },
                enabled = lines != null,
            ) {
                Text("导出", color = CvePalette.Accent, style = MaterialTheme.typography.labelLarge)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CvePalette.Surface)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            infoLine("设备", meta.device)
            infoLine("内核", meta.kernel)
            infoLine("Profile", meta.profile)
        }
        HorizontalDivider(color = CvePalette.Outline, thickness = 1.dp)

        val body = lines
        if (body == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("读取中…", color = CvePalette.TextMuted)
            }
        } else if (body.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("本次没有采集到日志", color = CvePalette.TextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(count = body.size, key = { i -> i }) { i ->
                    val line = body[i]
                    Text(
                        line,
                        color = toneColor(logToneOf(line)),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                item(key = "delete_footer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = onDelete) {
                            Text(
                                "删除这一次记录",
                                color = CvePalette.Danger,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun infoLine(label: String, value: String) {
    if (value.isNotBlank()) {
        Row {
            Text(
                label,
                color = CvePalette.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(52.dp),
            )
            Text(
                value,
                color = CvePalette.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun resultColor(result: RunResult) = when (result) {
    RunResult.SUCCESS -> CvePalette.Success
    RunResult.FAILED -> CvePalette.Danger
    RunResult.STOPPED -> CvePalette.TextMuted
    RunResult.INTERRUPTED -> CvePalette.Warning
    RunResult.RUNNING -> CvePalette.Accent
}

private fun toneColor(tone: LogTone) = when (tone) {
    LogTone.SYSTEM -> CvePalette.Info
    LogTone.FLOW -> CvePalette.Accent
    LogTone.ERROR -> CvePalette.Danger
    LogTone.WARN -> CvePalette.Warning
    LogTone.PLAIN -> CvePalette.TextSecondary
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("cve98260-log", text))
}

/**
 * 把文本写入公共下载目录（Download/）。
 * minSdk 31，经 MediaStore 写入无需存储权限。返回文件名，失败返回 null。
 */
private fun exportTextToDownload(context: Context, content: String): String? = runCatching {
    val fileName = "GhostKey-log-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + ".txt"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("insert failed")
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(content.toByteArray(Charsets.UTF_8))
    } ?: error("output stream null")
    fileName
}.getOrNull()
