package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.FilesScreen
import com.example.ui.screens.NewDownloadSheet
import com.example.ui.screens.TelemetryScreen
import com.example.ui.screens.TransfersScreen
import com.example.ui.theme.AetherTheme
import com.example.ui.theme.LocalAetherColors
import com.example.util.Formatters

const val ROUTE_TRANSFERS = "transfers"
const val ROUTE_FILES = "files"
const val ROUTE_ENGINE = "engine"

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
)

@OptIn(ExperimentalMaterial3Api::class)
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
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_TRANSFERS

    AetherTheme(themeMode = settings.themeMode) {
        val ext = LocalAetherColors.current

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
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
                                    text = "v7.1",
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
                            if (activeCount > 0) {
                                Text(
                                    text = Formatters.formatSpeedMbps(telemetry.instantBps),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ext.emerald,
                                    modifier = Modifier.padding(end = 16.dp),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    if (isCompact) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            windowInsets = WindowInsets.navigationBars,
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
                    if (currentRoute == ROUTE_TRANSFERS || currentRoute == ROUTE_FILES) {
                        FloatingActionButton(
                            onClick = { showNewDownloadSheet = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("fab_new_download"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "New Download",
                            )
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
                                .windowInsetsPadding(WindowInsets.navigationBars),
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
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 840.dp),
                        ) {
                            composable(ROUTE_TRANSFERS) {
                                TransfersScreen(
                                    downloads = filteredDownloads,
                                    selectedFilter = transferFilter,
                                    telemetry = telemetry,
                                    networkMatrix = networkMatrix,
                                    onSelectFilter = viewModel::setTransferFilter,
                                    onPause = viewModel::pauseDownload,
                                    onResume = viewModel::resumeDownload,
                                    onPauseAll = viewModel::pauseAll,
                                    onResumeAll = viewModel::resumeAll,
                                    onDelete = viewModel::deleteDownload,
                                    onRefreshUrl = viewModel::updateExpiredUrl,
                                    onRegrantSaf = viewModel::regrantSafUri,
                                    onPruneCas = viewModel::pruneCasCache,
                                    onOpenFile = viewModel::openFile,
                                    onShareFile = viewModel::shareFile,
                                    onOpenNewDownload = { showNewDownloadSheet = true },
                                )
                            }
                            composable(ROUTE_FILES) {
                                FilesScreen(
                                    files = completedFiles,
                                    storageStats = storageStats,
                                    casChunkCount = casChunkCount,
                                    casTotalBytes = casTotalBytes,
                                    selectedCategory = fileCategory,
                                    searchQuery = fileSearchQuery,
                                    onSelectCategory = viewModel::setFileCategory,
                                    onSearchQueryChange = viewModel::setFileSearchQuery,
                                    onOpenFile = viewModel::openFile,
                                    onShareFile = viewModel::shareFile,
                                    onDeleteFile = { entity -> viewModel.deleteDownload(entity.id, true) },
                                    onPruneCas = viewModel::pruneCasCache,
                                )
                            }
                            composable(ROUTE_ENGINE) {
                                TelemetryScreen(
                                    telemetry = telemetry,
                                    networkMatrix = networkMatrix,
                                    settings = settings,
                                    downloads = downloads,
                                    onUpdateSettings = viewModel::updateSettings,
                                    onRefreshNetwork = viewModel::refreshNetworkMatrix,
                                    onPruneCas = viewModel::pruneCasCache,
                                )
                            }
                        }
                    }
                }
            }

            if (showNewDownloadSheet) {
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
    }
}
