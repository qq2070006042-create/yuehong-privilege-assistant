package roro.stellar.yuehong.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import roro.stellar.yuehong.R

internal object NecessaryPermissions {
    private const val QUERY_ALL_PACKAGES_OP = "android:query_all_packages"

    fun hasAppListAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val appOpsResult = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(
                    QUERY_ALL_PACKAGES_OP,
                    Process.myUid(),
                    context.packageName,
                )
        }.getOrNull()
        if (appOpsResult == AppOpsManager.MODE_IGNORED ||
            appOpsResult == AppOpsManager.MODE_ERRORED
        ) return false

        val visibleApplications = runCatching {
            context.packageManager.getInstalledApplications(0)
        }.getOrNull() ?: return false

        // When app-list access is denied, Android commonly exposes only this
        // assistant and one package directory. A protected /data/app returns
        // null and is deliberately treated as unknown rather than empty.
        if (visibleApplications.size <= 1) return false
        val dataAppCount = countInstalledAppDirectories()
        if (dataAppCount != null && dataAppCount <= 1) return false
        return true
    }

    /** Returns the number of package directories, or null when /data/app is unreadable. */
    private fun countInstalledAppDirectories(): Int? = runCatching {
        val groups = File("/data/app").listFiles() ?: return@runCatching null
        var count = 0
        for (group in groups) {
            if (!group.isDirectory || group.name.startsWith(".")) continue
            val nested = group.listFiles() ?: return@runCatching null
            val packageDirectories = nested.count { it.isDirectory && !it.name.startsWith(".") }
            count += if (packageDirectories > 0) {
                packageDirectories
            } else if (File(group, "base.apk").isFile) {
                1
            } else {
                0
            }
        }
        count
    }.getOrNull()

    fun hasNotificationAccess(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = context
            .getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() != false
        return permissionGranted && notificationsEnabled
    }

    fun allGranted(context: Context): Boolean =
        hasAppListAccess(context) && hasNotificationAccess(context)
}

@Composable
internal fun NecessaryPermissionsScreen(
    onGranted: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var appListGranted by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }

    fun refresh() {
        appListGranted = NecessaryPermissions.hasAppListAccess(context)
        notificationGranted = NecessaryPermissions.hasNotificationAccess(context)
        checking = false
    }

    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    fun openAppPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refresh()
        if (NecessaryPermissions.allGranted(context)) onGranted()
    }

    LaunchedEffect(Unit) {
        refresh()
        if (NecessaryPermissions.allGranted(context)) onGranted()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NecessaryBrandHeader()
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                    ) {
                        Text(
                            text = stringResource(R.string.necessary_permissions_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.necessary_permissions_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.necessary_permissions_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                        Text(
                            text = stringResource(R.string.necessary_permissions_checking),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        PermissionStatusRow(
                            label = stringResource(R.string.necessary_permission_app_list),
                            granted = appListGranted,
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.necessary_permission_notifications),
                            granted = notificationGranted,
                        )
                        if (!appListGranted) {
                            Text(
                                text = stringResource(R.string.necessary_permission_app_list_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                            onClick = onExit,
                        ) {
                            Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            enabled = !checking,
                            shape = MaterialTheme.shapes.medium,
                            onClick = {
                                val appListReady = NecessaryPermissions.hasAppListAccess(context)
                                val notificationReady = NecessaryPermissions.hasNotificationAccess(context)
                                appListGranted = appListReady
                                notificationGranted = notificationReady
                                checking = false
                                when {
                                    !notificationReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    !notificationReady -> openNotificationSettings()
                                    !appListReady -> openAppPermissionSettings()
                                    else -> onGranted()
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.necessary_permissions_retry),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.necessary_permissions_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NecessaryBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(40.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        SharedDeviceInfoButton(compact = true)
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(
                        if (granted) R.string.necessary_permission_granted
                        else R.string.necessary_permission_missing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) colors.primary else colors.error,
                )
            }
            Surface(
                shape = CircleShape,
                color = if (granted) colors.primaryContainer else colors.errorContainer,
            ) {
                Text(
                    text = if (granted) "✓" else "!",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (granted) colors.onPrimaryContainer else colors.onErrorContainer,
                )
            }
        }
    }
}
