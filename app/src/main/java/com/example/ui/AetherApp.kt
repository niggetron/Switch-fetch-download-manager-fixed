package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.FocusedGaugeOverlay
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalAnimatedVisibilityScope
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.LocalSharedTransitionScope
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherOverlayZIndex
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.screens.AnimationSimulatorSheet
import com.example.ui.screens.ColorStudioSheet
import com.example.ui.screens.DualWifiConnectorSheet
import com.example.ui.screens.FilesScreen
import com.example.ui.screens.NewDownloadSheet
import com.example.ui.screens.SpeedDashboardScreen
import com.example.ui.screens.TelemetryScreen
import com.example.ui.screens.TransfersScreen
import com.example.ui.theme.AetherTheme
import com.example.ui.theme.LocalAetherColors
import com.example.util.Formatters

const val ROUTE_TRANSFERS = "transfers"
const val ROUTE_FILES = "files"
const val ROUTE_ENGINE = "engine"
const val ROUTE_SPEED = "speed"

private data class NavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val destinations = listOf(
    NavDestination(ROUTE_TRANSFERS, "Transfers", Icons.Filled.Download, Icons.Outlined.Download),
    NavDestination(ROUTE_FILES, "Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    NavDestination(ROUTE_ENGINE, "Engine", Icons.Filled.Speed, Icons.Outlined.Speed),
    NavDestination(ROUTE_SPEED, "Speed", Icons.Filled.Speed, Icons.Outlined.Speed),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AetherApp(viewModel: AetherViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val filteredDownloads by viewModel.filteredDownloads.collectAsStateWithLifecycle()
    val transferFilter by viewModel.transferFilter.collectAsStateWithLifecycle()
    val completedFiles by viewModel.completedFiles.collectAsStateWithLifecycle()
    val fileCategory by viewModel.fileCategory.collectAsStateWithLifecycle()
    val fileSearchQuery by viewModel.fileSearchQuery.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val casChunkCount by viewModel.casChunkCount.collectAsStateWithLifecycle()
    val casTotalBytes by viewModel.casTotalBytes.collectAsStateWithLifecycle()
    val networkMatrix by viewModel.networkMatrix.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val diagnosticsPreview by viewModel.diagnosticsPreview.collectAsStateWithLifecycle()
    val aria2Secret by viewModel.aria2Secret.collectAsStateWithLifecycle()
    val useMBpsSpeedUnit by viewModel.useMBpsSpeedUnit.collectAsStateWithLifecycle()
    val dualWifiCapabilities by viewModel.dualWifiCapabilities.collectAsStateWithLifecycle()
    val nearbyWifiNetworks by viewModel.nearbyWifiNetworks.collectAsStateWithLifecycle()
    val connectedWifi1Ssid by viewModel.connectedWifi1Ssid.collectAsStateWithLifecycle()
    val isScanningWifi by viewModel.isScanningWifi.collectAsStateWithLifecycle()
    val isConnectingWifi2 by viewModel.isConnectingWifi2.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val flightSampleCount by viewModel.flightSampleCount.collectAsStateWithLifecycle()
    val simulationState by viewModel.simulationState.collectAsStateWithLifecycle()

    // Lifecycle-aware 4 Hz active / 0 Hz ON_STOP telemetry polling
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.setUiForeground(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.setUiForeground(false)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Gracefully continue regardless of grant; FGS still runs on Android */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    var showNewDownloadSheet by remember { mutableStateOf(false) }
    var showDualWifiSheet by remember { mutableStateOf(false) }
    var showSimulatorSheet by remember { mutableStateOf(false) }
    var showColorStudioSheet by remember { mutableStateOf(false) }
    var focusedGaugeId by remember { mutableStateOf<String?>(null) }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_TRANSFERS

    AetherTheme(
        themeMode = settings.themeMode,
        uiThemeConfig = settings.uiThemeConfig,
    ) {
        val ext = LocalAetherColors.current

        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this,
                LocalMotionSpeedMultiplier provides simulationState.motionSpeedMultiplier,
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isCompact = maxWidth < 600.dp

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .aetherOverlayZIndex(2f),
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                ),
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "AetherIO",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "v9.1",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ext.cyan,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ext.cyan.copy(alpha = 0.14f))
                                                .border(1.dp, ext.cyan.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                },
                                actions = {
                                    val activeCount = downloads.count { it.statusEnum.isActive }
                                    if (activeCount > 0 || simulationState.isRunning) {
                                        Text(
                                            text = Formatters.formatSpeed(telemetry.instantBps, useMBpsSpeedUnit),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (simulationState.isRunning) ext.amber else ext.emerald,
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                    }
                                    // Morphing "Sim Lab" pill -> AnimationSimulatorSheet
                                    AnimatedVisibility(
                                        visible = !showSimulatorSheet,
                                        enter = fadeIn(tween(180)),
                                        exit = fadeOut(tween(150)),
                                    ) {
                                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(end = 12.dp)
                                                    .aetherSharedBounds(
                                                        key = AetherSharedKey("sim_lab", SharedPart.SIM_LAB_PILL),
                                                        clipShape = RoundedCornerShape(14.dp),
                                                        zIndexInOverlay = 2.5f,
                                                    )
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(
                                                        if (simulationState.isRunning) ext.amber.copy(alpha = 0.18f)
                                                        else ext.cyan.copy(alpha = 0.14f)
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (simulationState.isRunning) ext.amber.copy(alpha = 0.65f)
                                                        else ext.cyan.copy(alpha = 0.45f),
                                                        shape = RoundedCornerShape(14.dp),
                                                    )
                                                    .clickable { showSimulatorSheet = true }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                                    .testTag("topbar_sim_lab_button"),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.AutoAwesome,
                                                    contentDescription = "Open Speed and Animation Simulator",
                                                    tint = if (simulationState.isRunning) ext.amber else ext.cyan,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Text(
                                                    text = if (simulationState.isRunning) "Sim ON" else "Sim Lab",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (simulationState.isRunning) ext.amber else ext.cyan,
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        },
                        bottomBar = {
                            if (isCompact) {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    windowInsets = WindowInsets.navigationBars,
                                    modifier = Modifier.aetherOverlayZIndex(2f),
                                ) {
                                    destinations.forEach { dest ->
                                        val selected = currentRoute == dest.route
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = {
                                                if (currentRoute != dest.route) {
                                                    navController.navigate(dest.route) {
                                                        popUpTo(ROUTE_TRANSFERS) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                    contentDescription = dest.label,
                                                )
                                            },
                                            label = { Text(dest.label) },
                                            modifier = Modifier.testTag("nav_${dest.route}"),
                                        )
                                    }
                                }
                            }
                        },
                        floatingActionButton = {
                            AnimatedVisibility(
                                visible = !showNewDownloadSheet && (currentRoute == ROUTE_TRANSFERS || currentRoute == ROUTE_FILES),
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(140)),
                            ) {
                                CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                                    FloatingActionButton(
                                        onClick = { showNewDownloadSheet = true },
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .aetherOverlayZIndex(2f)
                                            .aetherSharedBounds(
                                                key = AetherSharedKey("fab_new_download", SharedPart.FAB_NEW_DOWNLOAD),
                                                clipShape = RoundedCornerShape(16.dp),
                                                zIndexInOverlay = 3f,
                                            )
                                            .testTag("fab_new_download"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = "New Download",
                                        )
                                    }
                                }
                            }
                        },
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            if (!isCompact) {
                                NavigationRail(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .windowInsetsPadding(WindowInsets.navigationBars)
                                        .aetherOverlayZIndex(2f),
                                ) {
                                    Spacer(modifier = Modifier.size(12.dp))
                                    destinations.forEach { dest ->
                                        val selected = currentRoute == dest.route
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = {
                                                if (currentRoute != dest.route) {
                                                    navController.navigate(dest.route) {
                                                        popUpTo(ROUTE_TRANSFERS) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                    contentDescription = dest.label,
                                                )
                                            },
                                            label = { Text(dest.label) },
                                            modifier = Modifier.testTag("rail_${dest.route}"),
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                NavHost(
                                    navController = navController,
                                    startDestination = ROUTE_TRANSFERS,
                                    enterTransition = { fadeIn(tween(240)) },
                                    exitTransition = { fadeOut(tween(180)) },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .widthIn(max = 840.dp),
                                ) {
                                    composable(ROUTE_TRANSFERS) {
                                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                                            TransfersScreen(
                                                downloads = filteredDownloads,
                                                selectedFilter = transferFilter,
                                                telemetry = telemetry,
                                                networkMatrix = networkMatrix,
                                                useMBpsSpeedUnit = useMBpsSpeedUnit,
                                                speedometerStyle = settings.speedometerStyle,
                                                isSimulating = simulationState.isRunning,
                                                isSimulatingHlsMode = simulationState.simulatedTransferHlsMode,
                                                onToggleSimHlsMode = viewModel::setSimulatedTransferHlsMode,
                                                onToggleSpeedUnit = viewModel::toggleSpeedDisplayUnit,
                                                onCycleSpeedometerStyle = viewModel::cycleSpeedometerStyle,
                                                onOpenDualWifi = { showDualWifiSheet = true },
                                                onOpenSimulator = { showSimulatorSheet = true },
                                                onStopSimulation = viewModel::stopSpeedSimulation,
                                                onSelectGauge = { gaugeId -> focusedGaugeId = gaugeId },
                                                onResetPeaks = viewModel::resetTelemetryPeaks,
                                                onSelectFilter = viewModel::setTransferFilter,
                                                onPause = viewModel::pauseDownload,
                                                onResume = viewModel::resumeDownload,
                                                onSwitchToKotlinAndResume = viewModel::switchTaskToKotlinAndResume,
                                                onPauseAll = viewModel::pauseAll,
                                                onResumeAll = viewModel::resumeAll,
                                                onDelete = viewModel::deleteDownload,
                                                onRefreshUrl = viewModel::updateExpiredUrl,
                                                onRegrantSaf = viewModel::regrantSafUri,
                                                onPruneCas = viewModel::pruneCasCache,
                                                onOpenFile = viewModel::openFile,
                                                onShareFile = viewModel::shareFile,
                                                onSaveMd5 = viewModel::saveDownloadMd5,
                                                onSaveSha256 = viewModel::saveDownloadSha256,
                                                onOpenNewDownload = { showNewDownloadSheet = true },
                                            )
                                        }
                                    }
                                    composable(ROUTE_FILES) {
                                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                                            FilesScreen(
                                                files = completedFiles,
                                                storageStats = storageStats,
                                                casChunkCount = casChunkCount,
                                                casTotalBytes = casTotalBytes,
                                                selectedCategory = fileCategory,
                                                searchQuery = fileSearchQuery,
                                                useMBpsSpeedUnit = useMBpsSpeedUnit,
                                                onSelectCategory = viewModel::setFileCategory,
                                                onSearchQueryChange = viewModel::setFileSearchQuery,
                                                onOpenFile = viewModel::openFile,
                                                onShareFile = viewModel::shareFile,
                                                onDeleteFile = { entity -> viewModel.deleteDownload(entity.id, true) },
                                                onPruneCas = viewModel::pruneCasCache,
                                                onRefreshUrl = viewModel::updateExpiredUrl,
                                                onSaveMd5 = viewModel::saveDownloadMd5,
                                                onSaveSha256 = viewModel::saveDownloadSha256,
                                            )
                                        }
                                    }
                                    composable(ROUTE_ENGINE) {
                                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                                            TelemetryScreen(
                                                telemetry = telemetry,
                                                networkMatrix = networkMatrix,
                                                settings = settings,
                                                downloads = downloads,
                                                fontLicenseAudit = viewModel.fontLicenseAudit,
                                                diagnosticsPreview = diagnosticsPreview,
                                                aria2Secret = aria2Secret,
                                                useMBpsSpeedUnit = useMBpsSpeedUnit,
                                                isSimulating = simulationState.isRunning,
                                                motionSpeedMultiplier = simulationState.motionSpeedMultiplier,
                                                flightSampleCount = flightSampleCount,
                                                onToggleSpeedUnit = viewModel::toggleSpeedDisplayUnit,
                                                onResetPeaks = viewModel::resetTelemetryPeaks,
                                                onOpenDualWifi = { showDualWifiSheet = true },
                                                onOpenSimulator = { showSimulatorSheet = true },
                                                onOpenColorStudio = { showColorStudioSheet = true },
                                                onSaveDiagnosticsTxt = viewModel::saveDiagnosticsTxtToUri,
                                                onSaveDiagnosticsJson = viewModel::saveDiagnosticsJsonToUri,
                                                onShareDiagnosticsBundle = viewModel::shareDiagnosticsBundle,
                                                onSelectGauge = { gaugeId -> focusedGaugeId = gaugeId },
                                                onUpdateSettings = viewModel::updateSettings,
                                                onRefreshNetwork = viewModel::refreshNetworkMatrix,
                                                onPruneCas = viewModel::pruneCasCache,
                                                onCopyDiagnostics = viewModel::copyDiagnosticsToClipboard,
                                                onCopyAria2Secret = viewModel::copyAria2SecretToClipboard,
                                            )
                                        }
                                    }
                                    composable(ROUTE_SPEED) {
                                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                                            SpeedDashboardScreen(
                                                telemetry = telemetry,
                                                networkMatrix = networkMatrix,
                                                useMBpsSpeedUnit = useMBpsSpeedUnit,
                                                onToggleSpeedUnit = viewModel::toggleSpeedDisplayUnit,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 1. In-tree Container Transform: FAB -> New Download Sheet
                    AnimatedVisibility(
                        visible = showNewDownloadSheet,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(180)),
                    ) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                            NewDownloadSheet(
                                defaultSettings = settings,
                                networkMatrix = networkMatrix,
                                onDismiss = { showNewDownloadSheet = false },
                                onLaunchDownload = { req ->
                                    viewModel.enqueueDownload(req)
                                    showNewDownloadSheet = false
                                },
                            )
                        }
                    }

                    // 2. In-tree Container Transform: "+ Wi-Fi 2" Pill -> Dual Wi-Fi Connector Sheet
                    AnimatedVisibility(
                        visible = showDualWifiSheet,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(180)),
                    ) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                            DualWifiConnectorSheet(
                                settings = settings,
                                networkMatrix = networkMatrix,
                                capabilities = dualWifiCapabilities,
                                nearbyNetworks = nearbyWifiNetworks,
                                connectedWifi1Ssid = connectedWifi1Ssid,
                                isScanning = isScanningWifi,
                                shizukuState = shizukuState,
                                isConnecting = isConnectingWifi2,
                                onProbeCapabilities = viewModel::probeDualWifi,
                                onScanNearbyWifi = viewModel::scanNearbyWifi,
                                onRequestShizukuPermission = viewModel::requestShizukuPermission,
                                onToggleShizukuLowLatency = viewModel::toggleShizukuLowLatency,
                                onConnectDirectWifi2 = viewModel::connectDirectWifi2,
                                onConnectWifi2Relay = viewModel::connectWifi2Relay,
                                onDisconnectWifi2 = viewModel::disconnectWifi2,
                                onDismiss = { showDualWifiSheet = false },
                            )
                        }
                    }

                    // 3. In-tree Container Transform: TopAppBar "Sim Lab" Pill -> Speed & Animation Simulator
                    AnimatedVisibility(
                        visible = showSimulatorSheet,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(180)),
                    ) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                            AnimationSimulatorSheet(
                                simState = simulationState,
                                currentStyle = settings.speedometerStyle,
                                useMBpsUnit = useMBpsSpeedUnit,
                                liveAggregateBps = telemetry.instantBps,
                                livePeakBps = telemetry.peakBps,
                                onStartSimulation = viewModel::startSpeedSimulation,
                                onStopSimulation = viewModel::stopSpeedSimulation,
                                onUpdateCustomSpeeds = viewModel::updateCustomSimSpeeds,
                                onToggleAutoCycleLayouts = viewModel::setAutoCycleSpeedometerLayouts,
                                onSetMotionSpeed = viewModel::setMotionSpeedMultiplier,
                                onCycleSpeedometerStyle = viewModel::cycleSpeedometerStyle,
                                onTriggerNewDownloadMorph = { showNewDownloadSheet = true },
                                onTriggerWifi2Morph = { showDualWifiSheet = true },
                                onTriggerFocusedGaugeMorph = { focusedGaugeId = "wifi1" },
                                onToggleSimTransferHlsMode = viewModel::setSimulatedTransferHlsMode,
                                onDismiss = { showSimulatorSheet = false },
                            )
                        }
                    }

                    // 4. In-tree Container Transform: Any Speedometer Gauge Card -> Full-Screen Cockpit Zoom
                    AnimatedVisibility(
                        visible = focusedGaugeId != null,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(180)),
                    ) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                            focusedGaugeId?.let { id ->
                                FocusedGaugeOverlay(
                                    gaugeId = id,
                                    telemetry = telemetry,
                                    networkMatrix = networkMatrix,
                                    useMBpsUnit = useMBpsSpeedUnit,
                                    isSimulating = simulationState.isRunning,
                                    onPulseLinkBurst = viewModel::pulseLinkBurst,
                                    onDismiss = { focusedGaugeId = null },
                                )
                            }
                        }
                    }

                    // 5. Telegram-Style Color Studio (18 Themes) & App-Wide Neon Glow Overlay
                    AnimatedVisibility(
                        visible = showColorStudioSheet,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(180)),
                    ) {
                        ColorStudioSheet(
                            currentConfig = settings.uiThemeConfig,
                            onApplyConfig = { cfg ->
                                viewModel.updateUiThemeConfig(cfg)
                                showColorStudioSheet = false
                            },
                            onDismiss = { showColorStudioSheet = false },
                        )
                    }
                }
            }
        }
    }
}
