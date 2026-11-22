package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppDestination
import com.example.ui.PostOfficeViewModel
import com.example.ui.screens.ConfigGameDbScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LogsAboutScreen
import com.example.ui.screens.RoomsSessionsScreen
import com.example.ui.theme.PostOfficeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PostOfficeTheme {
                PostOfficeApp()
            }
        }
    }
}

private data class NavItemSpec(
    val destination: AppDestination,
    val labelRes: Int,
    val iconRes: Int,
    val cdRes: Int,
    val tag: String
)

private val NAV_ITEMS = listOf(
    NavItemSpec(
        destination = AppDestination.DASHBOARD,
        labelRes = R.string.nav_dashboard,
        iconRes = R.drawable.ic_nav_dashboard,
        cdRes = R.string.cd_nav_dashboard,
        tag = "nav_dashboard"
    ),
    NavItemSpec(
        destination = AppDestination.ROOMS,
        labelRes = R.string.nav_rooms,
        iconRes = R.drawable.ic_nav_rooms,
        cdRes = R.string.cd_nav_rooms,
        tag = "nav_rooms"
    ),
    NavItemSpec(
        destination = AppDestination.CONFIG,
        labelRes = R.string.nav_config,
        iconRes = R.drawable.ic_nav_config,
        cdRes = R.string.cd_nav_config,
        tag = "nav_config"
    ),
    NavItemSpec(
        destination = AppDestination.LOGS,
        labelRes = R.string.nav_logs,
        iconRes = R.drawable.ic_nav_logs,
        cdRes = R.string.cd_nav_logs,
        tag = "nav_logs"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostOfficeApp(
    viewModel: PostOfficeViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val destination by viewModel.selectedDestination.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val logs by viewModel.logEntries.collectAsStateWithLifecycle()
    val logFilter by viewModel.logFilter.collectAsStateWithLifecycle()
    val gplText by viewModel.gplLicenseText.collectAsStateWithLifecycle()
    val ignoringBatteryOptimizations by viewModel.ignoringBatteryOptimizations.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.startServer()
    }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBatteryOptimizationStatus()
    }

    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importConfigFromUri(uri)
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportConfigToUri(uri)
    }

    val importGameDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importGameDbFromUri(uri)
    }

    val exportGameDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportGameDbToUri(uri)
    }

    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.exportLogsToUri(uri)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBatteryOptimizationStatus()
                viewModel.refreshLocalAddresses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshLocalAddresses()
        viewModel.refreshBatteryOptimizationStatus()
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    BackHandler(enabled = destination != AppDestination.DASHBOARD) {
        viewModel.selectDestination(AppDestination.DASHBOARD)
    }

    val onBatteryOptimizationToggle: (Boolean) -> Unit = { desiredIgnore ->
        val packageUri = Uri.parse("package:${context.packageName}")
        val primaryIntent = if (desiredIgnore) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        try {
            batteryOptLauncher.launch(primaryIntent)
        } catch (_: Exception) {
            try {
                batteryOptLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                try {
                    batteryOptLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                } catch (_: Exception) {
                }
            }
        }
    }

    val onStartRequest = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.startServer()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.startServer()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NAV_ITEMS.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item.destination,
                                onClick = { viewModel.selectDestination(item.destination) },
                                icon = {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = stringResource(item.cdRes),
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NAV_ITEMS.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item.destination,
                                onClick = { viewModel.selectDestination(item.destination) },
                                icon = {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = stringResource(item.cdRes),
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (destination) {
                        AppDestination.DASHBOARD -> DashboardScreen(
                            state = runtimeState,
                            isIgnoringBatteryOptimizations = ignoringBatteryOptimizations,
                            onStart = onStartRequest,
                            onStop = viewModel::stopServer,
                            onRestart = viewModel::restartServer,
                            onWakeLockToggle = viewModel::setWakeLockEnabled,
                            onBatteryOptimizationToggle = onBatteryOptimizationToggle,
                            onCopied = viewModel::notifyCopied
                        )
                        AppDestination.ROOMS -> RoomsSessionsScreen(state = runtimeState)
                        AppDestination.CONFIG -> ConfigGameDbScreen(
                            editorState = editorState,
                            onUpdateConfigVisual = viewModel::updateConfigVisual,
                            onSaveConfig = viewModel::saveConfig,
                            onResetConfig = viewModel::resetConfigToDefault,
                            onImportConfig = { importConfigLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            onExportConfig = { exportConfigLauncher.launch("config.json") },
                            onAddGameName = viewModel::addOrUpdateGameName,
                            onRemoveGameName = viewModel::removeGameName,
                            onAddCrosslink = viewModel::addOrUpdateCrosslink,
                            onRemoveCrosslink = viewModel::removeCrosslink,
                            onAddCodQuirk = viewModel::addCodQuirkGame,
                            onRemoveCodQuirk = viewModel::removeCodQuirkGame,
                            onSaveGameDb = viewModel::saveGameDb,
                            onResetGameDb = viewModel::resetGameDbToDefault,
                            onImportGameDb = { importGameDbLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            onExportGameDb = { exportGameDbLauncher.launch("game_db.json") }
                        )
                        AppDestination.LOGS -> LogsAboutScreen(
                            logs = logs,
                            filter = logFilter,
                            gplLicenseText = gplText,
                            onSelectCategory = viewModel::setLogCategory,
                            onSearchQueryChange = viewModel::setLogSearchQuery,
                            onToggleAutoScroll = viewModel::toggleLogAutoScroll,
                            onClearLogs = viewModel::clearLogs,
                            onExportLogs = { exportLogsLauncher.launch("aemu_postoffice_logs.txt") }
                        )
                    }
                }
            }
        }
    }
}
