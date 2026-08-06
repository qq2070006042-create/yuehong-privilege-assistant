package `in`.hridayan.ashell.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.hridayan.ashell.R
import `in`.hridayan.ashell.shell.AnnouncementResult

// 首次启动公告页面：加载并确认后才允许进入本地 ADB 页面。
// - 版本匹配或端点未配置/失败：显示版本号/作者/公告，确定按钮继续
// - 版本不匹配：切换为警示色，仅允许跳转更新页或退出应用
@Composable
fun AnnouncementScreen(
    result: AnnouncementResult,
    localVersion: String,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    val version: String
    val author: String
    val announcement: String
    val needUpdate: Boolean
    when (result) {
        is AnnouncementResult.Success -> {
            version = result.info.version.ifBlank { localVersion }
            author = result.info.author.ifBlank { stringResource(R.string.announcement_author_fallback) }
            announcement = result.info.announcement.ifBlank { stringResource(R.string.announcement_default_text) }
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
    val colors = MaterialTheme.colorScheme
    val accent = if (needUpdate) colors.error else colors.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader(accent = accent)
            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .animateContentSize(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surfaceContainerHigh,
                    ) {
                        Text(
                            text = stringResource(
                                if (needUpdate) R.string.update_badge
                                else R.string.announcement_badge,
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                if (needUpdate) R.string.update_required_title
                                else R.string.announcement_title,
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (needUpdate) colors.error else colors.onSurface,
                        )
                        Text(
                            text = stringResource(
                                if (needUpdate) R.string.update_required_subtitle
                                else R.string.announcement_subtitle,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    if (needUpdate) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            InfoTile(
                                label = stringResource(R.string.local_version_label),
                                value = localVersion,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                            InfoTile(
                                label = stringResource(R.string.announcement_version_label),
                                value = version,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        InfoTile(
                            label = stringResource(R.string.announcement_author_label),
                            value = author,
                            accent = accent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            InfoTile(
                                label = stringResource(R.string.announcement_version_label),
                                value = version,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                            InfoTile(
                                label = stringResource(R.string.announcement_author_label),
                                value = author,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.announcement_content_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfaceContainerLow,
                    ) {
                        SelectionContainer {
                            Text(
                                text = if (needUpdate) {
                                    stringResource(R.string.update_required_message, localVersion, version)
                                } else {
                                    announcement
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (needUpdate) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = onExit,
                    ) {
                        Text(stringResource(R.string.exit_app), fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        ),
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
                        Text(stringResource(R.string.update_now), fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                MotionButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onContinue,
                ) {
                    Text(
                        text = stringResource(R.string.announcement_continue),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun AnnouncementLoadingScreen() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.announcement_loading_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                    Text(
                        text = stringResource(R.string.announcement_loading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.announcement_security_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(accent: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.announcement_ready_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InfoTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            }
        }
    }
}

// 公告版本不一致时，“立即更新”跳转到项目更新主页
private const val UPDATE_URL = "https://yhyun.asia/"
