package `in`.hridayan.ashell.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.hridayan.ashell.R
import `in`.hridayan.ashell.shell.AnnouncementResult

// 首次启动公告弹窗：含版本验证
// - 版本匹配或端点未配置/失败：显示版本号/作者/公告，确定按钮关闭
// - 版本不匹配：标题改为"需要更新"，禁止取消（仅"立即更新"+"退出应用"）
@Composable
fun AnnouncementDialog(
    result: AnnouncementResult?,
    localVersion: String,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
) {
    if (result == null) return

    // 服务器返回优先；端点未配置 / 失败时退回本地字符串
    val version: String
    val author: String
    val announcement: String
    val needUpdate: Boolean
    when (result) {
        is AnnouncementResult.Success -> {
            version = result.info.version.ifBlank { localVersion }
            author = result.info.author.ifBlank { stringResource(R.string.announcement_author_fallback) }
            announcement = result.info.announcement.ifBlank { stringResource(R.string.announcement_default_text) }
            // 版本号非空且与本地不一致 → 需要更新
            needUpdate = result.info.version.isNotBlank() &&
                !result.info.version.equals(localVersion, ignoreCase = true)
        }

        AnnouncementResult.EndpointNotConfigured, is AnnouncementResult.Failure -> {
            version = localVersion
            author = stringResource(R.string.announcement_author_fallback)
            announcement = stringResource(R.string.announcement_default_text)
            needUpdate = false
        }
    }

    val context = LocalContext.current

    Dialog(
        onDismissRequest = {
            // 版本不匹配时禁止通过点击外部或返回键取消
            if (!needUpdate) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !needUpdate,
            dismissOnClickOutside = !needUpdate,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(
                        if (needUpdate) R.string.update_required_title
                        else R.string.announcement_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (needUpdate) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                HorizontalDivider()

                InfoLine(stringResource(R.string.announcement_version_label), version)
                InfoLine(stringResource(R.string.announcement_author_label), author)

                if (needUpdate) {
                    InfoLine(
                        stringResource(R.string.local_version_label),
                        localVersion,
                    )
                }

                HorizontalDivider()

                SelectionContainer {
                    Text(
                        text = if (needUpdate) {
                            stringResource(R.string.update_required_message, localVersion, version)
                        } else {
                            announcement
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }

                if (needUpdate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onExit,
                        ) {
                            Text(stringResource(R.string.exit_app))
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (UPDATE_URL.isBlank()) {
                                    Toast.makeText(
                                        context,
                                        R.string.update_link_not_configured,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.update_now))
                        }
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss,
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

// 公告版本不一致时，“立即更新”跳转到项目更新主页
private const val UPDATE_URL = "https://yhyun.asia/"

@Composable
private fun InfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
