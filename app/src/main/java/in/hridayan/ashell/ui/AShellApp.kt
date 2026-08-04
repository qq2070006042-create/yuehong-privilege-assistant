package `in`.hridayan.ashell.ui

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import `in`.hridayan.ashell.BuildConfig
import `in`.hridayan.ashell.shell.AnnouncementResult
import `in`.hridayan.ashell.shell.HttpAnnouncementApi
import `in`.hridayan.ashell.shell.HttpCompatibilityApi
import `in`.hridayan.ashell.shell.PrivilegeEscalator
import `in`.hridayan.ashell.shell.ShizukuShellController

@Composable
fun AShellApp() {
    AShellTheme {
        val context = LocalContext.current
        val shellController = remember { ShizukuShellController() }
        val compatibilityApi = remember(context.applicationContext) {
            HttpCompatibilityApi(context.applicationContext)
        }
        val announcementApi = remember { HttpAnnouncementApi() }
        val escalator = remember { PrivilegeEscalator(shellController, compatibilityApi) }

        var announcementResult by remember { mutableStateOf<AnnouncementResult?>(null) }
        var showAnnouncement by remember { mutableStateOf(false) }

        DisposableEffect(shellController, compatibilityApi, announcementApi, escalator) {
            shellController.register()
            onDispose {
                escalator.close()
                announcementApi.close()
                compatibilityApi.close()
                shellController.close()
            }
        }

        // 每次启动都拉取公告并弹窗；无论成功失败都弹（失败用本地 fallback）
        LaunchedEffect(Unit) {
            announcementApi.fetch { result ->
                announcementResult = result
                showAnnouncement = true
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            LocalAdbScreen(
                controller = shellController,
                escalator = escalator,
            )
        }

        if (showAnnouncement) {
            AnnouncementDialog(
                result = announcementResult,
                localVersion = BuildConfig.VERSION_NAME,
                onDismiss = { showAnnouncement = false },
                onExit = {
                    showAnnouncement = false
                    (context as? Activity)?.finishAffinity()
                },
            )
        }
    }
}
