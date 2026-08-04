package `in`.hridayan.ashell.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        var announcementAccepted by remember { mutableStateOf(false) }

        if (announcementAccepted) {
            LocalAdbContent()
        } else {
            AnnouncementGate(
                onContinue = { announcementAccepted = true },
                onExit = { (context as? Activity)?.finishAffinity() },
            )
        }
    }
}

@Composable
private fun AnnouncementGate(
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    val announcementApi = remember { HttpAnnouncementApi() }
    var announcementResult by remember { mutableStateOf<AnnouncementResult?>(null) }

    DisposableEffect(announcementApi) {
        onDispose { announcementApi.close() }
    }

    LaunchedEffect(announcementApi) {
        announcementApi.fetch { result -> announcementResult = result }
    }

    val result = announcementResult
    if (result == null) {
        AnnouncementLoadingScreen()
    } else {
        AnnouncementScreen(
            result = result,
            localVersion = BuildConfig.VERSION_NAME,
            onContinue = onContinue,
            onExit = onExit,
        )
    }
}

@Composable
private fun LocalAdbContent() {
    val context = LocalContext.current
    val shellController = remember { ShizukuShellController() }
    val compatibilityApi = remember(context.applicationContext) {
        HttpCompatibilityApi(context.applicationContext)
    }
    val escalator = remember { PrivilegeEscalator(shellController, compatibilityApi) }

    DisposableEffect(shellController, compatibilityApi, escalator) {
        shellController.register()
        onDispose {
            escalator.close()
            compatibilityApi.close()
            shellController.close()
        }
    }

    LocalAdbScreen(
        controller = shellController,
        escalator = escalator,
    )
}

