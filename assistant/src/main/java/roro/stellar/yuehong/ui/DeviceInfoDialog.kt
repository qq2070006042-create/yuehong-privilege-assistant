package roro.stellar.yuehong.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.DeviceProfile

@Composable
fun DeviceInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(DeviceProfile.collect()) }
    val githubUrl = stringResource(R.string.github_source_url)
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    Dialog(onDismissRequest = onDismiss) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(180)) + scaleIn(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                initialScale = 0.94f,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.device_info),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider()

                    InfoRow(stringResource(R.string.model_name), profile.modelName)
                    InfoRow(stringResource(R.string.system_version), profile.systemVersion)
                    InfoRow(stringResource(R.string.kernel_version), profile.kernelVersion)

                    HorizontalDivider()

                    // GitHub 链接按钮：独占一行，避免窄屏被挤出可视区域
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (githubUrl.isBlank()) {
                                Toast.makeText(
                                    context,
                                    R.string.github_link_not_configured,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    ) {
                        Icon(
                            modifier = Modifier.size(18.dp),
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = null,
                        )
                        Text(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(R.string.github),
                        )
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss,
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectionContainer {
            Text(
                text = value.ifBlank { "-" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
