package `in`.hridayan.ashell.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.hridayan.ashell.BuildConfig
import `in`.hridayan.ashell.R
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
        AnnouncementLoadingPage()
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
private fun AnnouncementLoadingPage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.announcement_loading),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
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
