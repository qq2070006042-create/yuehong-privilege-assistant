package `in`.hridayan.ashell.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import `in`.hridayan.ashell.BuildConfig
import `in`.hridayan.ashell.shell.AnnouncementResult
import `in`.hridayan.ashell.shell.HttpAnnouncementApi
import `in`.hridayan.ashell.shell.HttpCompatibilityApi
import `in`.hridayan.ashell.shell.PrivilegeEscalator
import `in`.hridayan.ashell.shell.ShizukuShellController
import `in`.hridayan.ashell.shell.ShizukuStatus
import kotlinx.coroutines.delay

private enum class AppPage {
    Announcement,
    ShizukuPermission,
    LocalAdb,
}

@Composable
fun AShellApp() {
    AShellTheme {
        val context = LocalContext.current
        val shellController = remember { ShizukuShellController() }
        val compatibilityApi = remember(context.applicationContext) {
            HttpCompatibilityApi(context.applicationContext)
        }
        val escalator = remember { PrivilegeEscalator(shellController, compatibilityApi) }
        var page by rememberSaveable { mutableStateOf(AppPage.Announcement) }

        DisposableEffect(shellController, compatibilityApi, escalator) {
            shellController.register()
            onDispose {
                escalator.close()
                compatibilityApi.close()
                shellController.close()
            }
        }

        // 权限页确认授权后短暂停留展示成功状态，再自动进入本地 ADB。
        // 本地 ADB 使用期间若 Shizuku 服务断开或权限失效，则平滑退回权限页。
        LaunchedEffect(page, shellController.status) {
            when {
                page == AppPage.ShizukuPermission &&
                    shellController.status == ShizukuStatus.Granted -> {
                    delay(650)
                    if (shellController.status == ShizukuStatus.Granted) {
                        page = AppPage.LocalAdb
                    }
                }

                page == AppPage.LocalAdb &&
                    shellController.status !in setOf(
                        ShizukuStatus.Granted,
                        ShizukuStatus.Checking,
                    ) -> page = AppPage.ShizukuPermission
            }
        }

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val direction = if (forward) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                    initialOffsetX = { width -> direction * width / 3 },
                ) + fadeIn(tween(320)) + scaleIn(tween(420), initialScale = 0.985f)
                val exit = slideOutHorizontally(
                    animationSpec = tween(420, easing = FastOutSlowInEasing),
                    targetOffsetX = { width -> -direction * width / 4 },
                ) + fadeOut(tween(260)) + scaleOut(tween(360), targetScale = 0.99f)
                enter togetherWith exit
            },
            label = "app-page-transition",
        ) { activePage ->
            when (activePage) {
                AppPage.Announcement -> AnnouncementGate(
                    onContinue = {
                        page = if (shellController.status == ShizukuStatus.Granted) {
                            AppPage.LocalAdb
                        } else {
                            AppPage.ShizukuPermission
                        }
                    },
                    onExit = { (context as? Activity)?.finishAffinity() },
                )

                AppPage.ShizukuPermission -> ShizukuPermissionScreen(
                    controller = shellController,
                )

                AppPage.LocalAdb -> LocalAdbScreen(
                    controller = shellController,
                    escalator = escalator,
                )
            }
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
