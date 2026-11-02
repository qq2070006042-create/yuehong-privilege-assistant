package roro.stellar.manager.ui.features.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import roro.stellar.yuehong.shell.PrivilegeEscalator
import roro.stellar.yuehong.shell.StellarShellController
import roro.stellar.yuehong.ui.LocalAdbScreen
import roro.stellar.manager.R
import roro.stellar.manager.domain.apps.AppsViewModel
import roro.stellar.manager.domain.apps.appsViewModel
import roro.stellar.manager.ui.components.AdaptiveLayoutProvider
import roro.stellar.manager.ui.features.apps.AppsScreen
import roro.stellar.manager.ui.features.home.StartWirelessAdbCard
import roro.stellar.manager.ui.navigation.components.LocalTopAppBarState
import roro.stellar.manager.ui.navigation.components.TopAppBarProvider
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.manager.ui.theme.ThemePreferences

class ManagerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_IS_ROOT = "is_root"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_HAS_SECURE_SETTINGS = "has_secure_settings"
        private const val EXTRA_ALLOW_TCP_IP_PORT = "allow_tcp_ip_port"

        fun createLogsIntent(context: Context): Intent {
            return Intent(context, ManagerActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, ManagerRoute.Logs.route)
            }
        }

        fun createWorkspaceIntent(context: Context): Intent {
            return Intent(context, ManagerActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, ManagerRoute.Workspace.route)
            }
        }

        fun createAuthorizationIntent(context: Context): Intent {
            return Intent(context, ManagerActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, ManagerRoute.Authorization.route)
            }
        }

        fun createStarterIntent(
            context: Context,
            isRoot: Boolean,
            host: String?,
            port: Int,
            hasSecureSettings: Boolean = false,
            allowTcpIpPort: Boolean = false,
        ): Intent {
            return Intent(context, ManagerActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, ManagerRoute.Starter.route)
                putExtra(EXTRA_IS_ROOT, isRoot)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_HAS_SECURE_SETTINGS, hasSecureSettings)
                putExtra(EXTRA_ALLOW_TCP_IP_PORT, allowTcpIpPort)
            }
        }
    }

    private val appsModel by appsViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val route = intent.getStringExtra(EXTRA_ROUTE) ?: ManagerRoute.Logs.route
        val isRoot = intent.getBooleanExtra(EXTRA_IS_ROOT, true)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val hasSecureSettings = intent.getBooleanExtra(EXTRA_HAS_SECURE_SETTINGS, false)
        val allowTcpIpPort = intent.getBooleanExtra(EXTRA_ALLOW_TCP_IP_PORT, false)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            },
        )

        setContent {
            val themeMode = ThemePreferences.themeMode.value
            StellarTheme(themeMode = themeMode) {
                TopAppBarProvider {
                    ManagerNavHost(
                        startRoute = route,
                        isRoot = isRoot,
                        host = host,
                        port = port,
                        hasSecureSettings = hasSecureSettings,
                        allowTcpIpPort = allowTcpIpPort,
                        appsViewModel = appsModel,
                        onClose = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagerNavHost(
    startRoute: String,
    isRoot: Boolean,
    host: String?,
    port: Int,
    hasSecureSettings: Boolean,
    allowTcpIpPort: Boolean,
    appsViewModel: AppsViewModel,
    onClose: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startRoute,
    ) {
        composable(ManagerRoute.Workspace.route) {
            AdaptiveLayoutProvider {
                StellarWorkspaceScreen(appsViewModel = appsViewModel)
            }
        }

        composable(ManagerRoute.Authorization.route) {
            AdaptiveLayoutProvider {
                StellarAuthorizationScreen(appsViewModel = appsViewModel)
            }
        }

        composable(ManagerRoute.Logs.route) {
            LogsScreen(onBackClick = onClose)
        }

        composable(ManagerRoute.Starter.route) {
            StarterScreen(
                isRoot = isRoot,
                host = host,
                port = port,
                hasSecureSettings = hasSecureSettings,
                allowTcpIpPort = allowTcpIpPort,
                onClose = onClose,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StellarWorkspaceScreen(appsViewModel: AppsViewModel) {
    val context = LocalContext.current
    val topAppBarState = LocalTopAppBarState.current!!
    val controller = remember { StellarShellController() }
    val escalator = remember(context.applicationContext) {
        PrivilegeEscalator(context.applicationContext, controller)
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        Triple(R.string.stellar_tab_access, Icons.Filled.Wifi, "access"),
        Triple(R.string.stellar_tab_privilege, Icons.Filled.Security, "privilege"),
    )

    DisposableEffect(controller, escalator) {
        controller.register()
        onDispose {
            escalator.close()
            controller.close()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.stellar_workspace_title)) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    tabs.forEachIndexed { index, (label, icon, description) ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(stringResource(label)) },
                            icon = { Icon(icon, contentDescription = description) },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (selectedTab) {
                0 -> ActivationAndAuthorizationScreen(
                    topAppBarState = topAppBarState,
                    appsViewModel = appsViewModel,
                )

                1 -> LocalAdbScreen(
                    controller = controller,
                    escalator = escalator,
                    embedded = true,
                )

                else -> LocalAdbScreen(
                    controller = controller,
                    escalator = escalator,
                    embedded = true,
                )
            }
        }
    }
}

@Composable
private fun StellarAuthorizationScreen(appsViewModel: AppsViewModel) {
    val topAppBarState = LocalTopAppBarState.current!!
    AppsScreen(
        topAppBarState = topAppBarState,
        appsViewModel = appsViewModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivationAndAuthorizationScreen(
    topAppBarState: androidx.compose.material3.TopAppBarState,
    appsViewModel: AppsViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val wirelessController = remember { StellarShellController() }
    var wirelessDebugEnabled by remember(context) {
        mutableStateOf(isWirelessDebugEnabled(context))
    }

    DisposableEffect(lifecycleOwner, wirelessController) {
        wirelessController.register()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                wirelessDebugEnabled = isWirelessDebugEnabled(context)
                wirelessController.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            wirelessController.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            StartWirelessAdbCard(
                wirelessDebugEnabled = wirelessDebugEnabled,
                stellarStatus = wirelessController.status,
                onStartClick = {
                    context.startActivity(
                        ManagerActivity.createStarterIntent(
                            context = context,
                            isRoot = false,
                            host = "127.0.0.1",
                            port = 0,
                            hasSecureSettings = false,
                            allowTcpIpPort = false,
                        ),
                    )
                },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AppsScreen(
                topAppBarState = topAppBarState,
                appsViewModel = appsViewModel,
                embedded = true,
            )
        }
    }
}

private fun isWirelessDebugEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
}.getOrDefault(false)

private sealed class ManagerRoute(val route: String) {
    data object Workspace : ManagerRoute("workspace")
    data object Authorization : ManagerRoute("authorization")
    data object Logs : ManagerRoute("logs")
    data object Starter : ManagerRoute("starter")
}
