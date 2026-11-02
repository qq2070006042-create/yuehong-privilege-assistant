package com.GhostKey.app.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.GhostKey.app.ui.theme.CvePalette
import com.GhostKey.app.viewmodel.ExploitViewModel

/**
 * 设置页：内核 Profile 选择 / 导入（IMG·JSON·HOCON）/ 导出（文件名含版本号）/
 * 已导入管理 / Root 管理器检测 / 运行日志入口。
 *
 * 颜色与风格完全沿用 CvePalette 与 MaterialTheme，与主页保持一致。
 */
@Composable
fun SettingsScreen(
    viewModel: ExploitViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showProfilePicker by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf("") }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImageFromUri(uri, queryDisplayName(context, uri))
    }
    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTextFromUri(uri, queryDisplayName(context, uri))
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(pendingExport.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                Toast.makeText(context, "已导出 ${queryDisplayName(context, uri)}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val stored = viewModel.storedProfiles()
    val selected = uiState.selectedProfileRelease

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
                Text("设置", style = MaterialTheme.typography.titleLarge, color = CvePalette.TextPrimary)
                Text(
                    "内核 Profile · 导入 / 导出 / 管理器",
                    style = MaterialTheme.typography.labelSmall,
                    color = CvePalette.TextMuted,
                )
            }
        }
        HorizontalDivider(color = CvePalette.Outline, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard(title = "目标内核 Profile") {
                Text(
                    "当前：" + (selected ?: "自动适配（当前设备）"),
                    color = CvePalette.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "设备内核：${uiState.kernelRelease}",
                    color = CvePalette.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "命中：${uiState.matchedProfile}",
                    color = CvePalette.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showProfilePicker = true }) {
                        Text("选择内核")
                    }
                    OutlinedButton(onClick = {
                        pendingExport = viewModel.buildExportText()
                        createDoc.launch(viewModel.exportFileName())
                    }) {
                        Text("导出当前 Profile")
                    }
                }
                Text(
                    "导出的文件名自动带版本号（如 …-v${appVersion(context)}.conf）",
                    color = CvePalette.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            SettingsCard(title = "导入内核文件") {
                Text(
                    "支持 boot.img（自动提取 BTF 结构偏移 + kallsyms 符号地址）与 JSON / HOCON profile。" +
                        "GKI 6.6 内核可直接补齐全部字段；个别符号缺失时会列出待补项。",
                    color = CvePalette.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { imgPicker.launch(arrayOf("*/*")) }) {
                    Text("导入 IMG（boot.img）")
                }
                OutlinedButton(onClick = {
                    textPicker.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"),
                    )
                }) {
                    Text("导入 JSON / HOCON")
                }
                if (uiState.importMessage.isNotBlank()) {
                    val color = when {
                        uiState.importMessage.contains("失败") -> CvePalette.Danger
                        uiState.importMessage.contains("缺少") -> CvePalette.Warning
                        else -> CvePalette.Success
                    }
                    Text(
                        uiState.importMessage,
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SettingsCard(title = "已导入 Profile（${stored.size}）") {
                if (stored.isEmpty()) {
                    Text("暂无", color = CvePalette.TextMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    stored.forEach { sp ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    sp.release,
                                    color = CvePalette.TextPrimary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (sp.complete) "字段完整" else "缺少：${sp.missing.joinToString()}",
                                    color = if (sp.complete) CvePalette.Success else CvePalette.Warning,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { viewModel.deleteStoredProfile(sp.fileName) }) {
                                Text("删除", color = CvePalette.Danger, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        HorizontalDivider(color = CvePalette.Outline, thickness = 1.dp)
                    }
                }
            }

            SettingsCard(title = "Root 管理器（自动适配）") {
                Text(
                    "检测到：${uiState.detectedManager.ifBlank { "未检测到" }}",
                    color = CvePalette.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "KSU 来源：${uiState.ksuSourceLabel.ifBlank { "内置 ksud" }}",
                    color = CvePalette.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { viewModel.detectKsuSource() }) {
                    Text("重新检测")
                }
                Text(
                    "提权时优先从上面检测到的管理器提取 ksud（认 libksud.so 文件，任何 fork 均可命中），" +
                        "未检测到才回落 APK 内置 ksud。管理器升级即获得新内核 KMI 支持，无需更新本应用。",
                    color = CvePalette.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            SettingsCard(title = "日志") {
                val runCount = remember { viewModel.listRunLogs().size }
                OutlinedButton(onClick = onOpenLogs) {
                    Text("查看运行记录（$runCount 次）")
                }
                Text(
                    "每次 ROOT 单独保存一份记录，点进去看当次的完整日志",
                    color = CvePalette.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    if (showProfilePicker) {
        ProfilePickerDialog(
            options = listOf<String?>(null) + viewModel.knownReleases(),
            selected = selected,
            onPick = { r ->
                viewModel.selectProfile(r)
                showProfilePicker = false
            },
            onDismiss = { showProfilePicker = false },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CvePalette.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, CvePalette.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = CvePalette.TextPrimary)
        content()
    }
}

@Composable
private fun ProfilePickerDialog(
    options: List<String?>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = CvePalette.TextMuted)
            }
        },
        title = {
            Text("选择目标内核 Profile", color = CvePalette.TextPrimary)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { opt ->
                    val isSelected = opt == selected
                    val label = opt ?: "自动适配（当前设备）"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opt) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isSelected) "●" else "○",
                            color = if (isSelected) CvePalette.Accent else CvePalette.TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            label,
                            color = CvePalette.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(color = CvePalette.Outline, thickness = 1.dp)
                }
            }
        },
        containerColor = CvePalette.Surface,
    )
}

/** 查询 SAF Uri 的显示名（失败则回退到最后路径段）。 */
private fun queryDisplayName(context: Context, uri: Uri): String {
    val proj = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    val name = runCatching {
        context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()
    return name ?: uri.lastPathSegment ?: "imported"
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "unknown"
