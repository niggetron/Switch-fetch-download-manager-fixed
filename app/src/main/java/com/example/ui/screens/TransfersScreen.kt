package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadEntity
import com.example.data.model.BreakerState
import com.example.data.model.DownloadStatus
import com.example.data.model.SpeedometerStyle
import com.example.data.model.TransferFilter
import com.example.engine.LinkEndpointInfo
import com.example.engine.NetworkMatrixState
import com.example.engine.ResolvedEngineBackend
import com.example.engine.TelemetrySnapshot
import com.example.ui.components.KetchConnectionsInspectorCard
import com.example.ui.components.PerNetworkSpeedometerCluster
import com.example.ui.components.SegmentBitfieldMatrix
import com.example.ui.components.StatusPill
import com.example.ui.components.ThroughputSparkline
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalAnimatedVisibilityScope
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSharedElement
import com.example.ui.motion.aetherSkipToLookaheadSize
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoHeroMetricStyle
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TransfersScreen(
    downloads: List<DownloadEntity>,
    selectedFilter: TransferFilter,
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsSpeedUnit: Boolean,
    onToggleSpeedUnit: () -> Unit,
    onResetPeaks: () -> Unit,
    onSelectFilter: (TransferFilter) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onDelete: (String, Boolean) -> Unit,
    onRefreshUrl: (String, String) -> Unit,
    onRegrantSaf: (String, Uri) -> Unit,
    onPruneCas: () -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onShareFile: (DownloadEntity) -> Unit,
    onOpenNewDownload: () -> Unit,
    speedometerStyle: SpeedometerStyle = SpeedometerStyle.SUPERBIKE_GRID,
    onCycleSpeedometerStyle: () -> Unit = {},
    onOpenDualWifi: () -> Unit = {},
    onSwitchToKotlinAndResume: (String) -> Unit = onResume,
    isSimulating: Boolean = false,
    onOpenSimulator: () -> Unit = {},
    onStopSimulation: () -> Unit = {},
    onSelectGauge: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    var deleteTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var refreshUrlTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var safRegrantTargetId by remember { mutableStateOf<String?>(null) }
    var inspectedTransferId by remember { mutableStateOf<String?>(null) }
    val inspectedTransfer = remember(downloads, inspectedTransferId) {
        downloads.firstOrNull { it.id == inspectedTransferId }
    }

    val safRegrantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val id = safRegrantTargetId
        if (uri != null && id != null) {
            onRegrantSaf(id, uri)
        }
        safRegrantTargetId = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero_banner") {
                HeroTelemetryBanner(
                    telemetry = telemetry,
                    networkMatrix = networkMatrix,
                    useMBpsSpeedUnit = useMBpsSpeedUnit,
                    speedometerStyle = speedometerStyle,
                    isSimulating = isSimulating,
                    onToggleSpeedUnit = onToggleSpeedUnit,
                    onCycleSpeedometerStyle = onCycleSpeedometerStyle,
                    onOpenDualWifi = onOpenDualWifi,
                    onOpenSimulator = onOpenSimulator,
                    onStopSimulation = onStopSimulation,
                    onSelectGauge = onSelectGauge,
                    onResetPeaks = onResetPeaks,
                    activeCount = downloads.count { it.statusEnum.isActive },
                    hasPaused = downloads.any {
                        it.statusEnum == DownloadStatus.PAUSED ||
                            it.statusEnum == DownloadStatus.QUOTA_GUARD_R3
                    },
                    onPauseAll = onPauseAll,
                    onResumeAll = onResumeAll,
                )
            }

            if (telemetry.activeConnections.isNotEmpty()) {
                item(key = "ketch_connections_inspector") {
                    KetchConnectionsInspectorCard(
                        connections = telemetry.activeConnections,
                        activeEngineBadge = telemetry.activeEngineBadge,
                    )
                }
            }

            networkMatrix.pacWarningUrl?.let { pacUrl ->
                item(key = "pac_warning") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ext.amber.copy(alpha = 0.12f))
                            .border(1.dp, ext.amber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = ext.amber,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "PAC proxy detected ($pacUrl). Configure explicit SOCKS5h host/port in Engine settings to route over this proxy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.amber,
                        )
                    }
                }
            }

            item(key = "filter_strip") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransferFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { onSelectFilter(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("filter_${filter.name.lowercase()}"),
                        )
                    }
                }
            }

            if (downloads.isEmpty()) {
                item(key = "empty_state") {
                    EmptyTransfersCard(
                        onOpenNewDownload = onOpenNewDownload,
                        onOpenSimulator = onOpenSimulator,
                    )
                }
            } else {
                val activeTotalCount = downloads.count { it.statusEnum.isActive }
                items(downloads, key = { it.id }) { item ->
                    val liveTaskBps = if (item.statusEnum.isActive) {
                        val connSum = telemetry.activeConnections
                            .filter { it.taskId == item.id }
                            .sumOf { it.speedBps }
                        when {
                            connSum > 0L -> connSum
                            activeTotalCount == 1 -> telemetry.instantBps
                            else -> 0L
                        }
                    } else {
                        0L
                    }
                    TransferItemCard(
                        entity = item,
                        liveSpeedBps = liveTaskBps,
                        useMBpsSpeedUnit = useMBpsSpeedUnit,
                        onInspect = { inspectedTransferId = item.id },
                        onPause = { onPause(item.id) },
                        onResume = { onResume(item.id) },
                        onSwitchToKotlinAndResume = { onSwitchToKotlinAndResume(item.id) },
                        onDeleteRequest = { deleteTarget = item },
                        onRefreshUrlRequest = { refreshUrlTarget = item },
                        onRegrantSafRequest = {
                            safRegrantTargetId = item.id
                            safRegrantLauncher.launch(item.fileName)
                        },
                        onPruneCas = onPruneCas,
                        onOpen = { onOpenFile(item) },
                        onShare = { onShareFile(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(76.dp)) }
        }

        // In-tree Container Transform Overlay for Transfer Pipeline Inspector
        AnimatedVisibility(
            visible = inspectedTransfer != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                inspectedTransfer?.let { target ->
                    val liveBps = telemetry.activeConnections
                        .filter { it.taskId == target.id }
                        .sumOf { it.speedBps }
                    TransferInspectorOverlay(
                        entity = target,
                        liveSpeedBps = liveBps,
                        useMBpsSpeedUnit = useMBpsSpeedUnit,
                        onPause = { onPause(target.id) },
                        onResume = { onResume(target.id) },
                        onOpen = { onOpenFile(target) },
                        onShare = { onShareFile(target) },
                        onDismiss = { inspectedTransferId = null },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        DeleteDownloadDialog(
            entity = target,
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteFile ->
                if (inspectedTransferId == target.id) inspectedTransferId = null
                onDelete(target.id, deleteFile)
                deleteTarget = null
            },
        )
    }

    refreshUrlTarget?.let { target ->
        RefreshSignedUrlDialog(
            entity = target,
            onDismiss = { refreshUrlTarget = null },
            onSubmit = { newUrl ->
                onRefreshUrl(target.id, newUrl)
                refreshUrlTarget = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun HeroTelemetryBanner(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsSpeedUnit: Boolean,
    speedometerStyle: SpeedometerStyle,
    isSimulating: Boolean,
    onToggleSpeedUnit: () -> Unit,
    onCycleSpeedometerStyle: () -> Unit,
    onOpenDualWifi: () -> Unit,
    onOpenSimulator: () -> Unit,
    onStopSimulation: () -> Unit,
    onSelectGauge: (String) -> Unit,
    onResetPeaks: () -> Unit,
    activeCount: Int,
    hasPaused: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val ext = LocalAetherColors.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aetherSharedBounds(
                key = AetherSharedKey("hero", SharedPart.HERO_CONTAINER),
                clipShape = RoundedCornerShape(18.dp),
                useCockpitSpring = true,
            )
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(ext.heroTop, ext.heroBottom)))
            .border(
                width = if (isSimulating) 1.4.dp else 1.dp,
                color = if (isSimulating) ext.amber.copy(alpha = 0.65f) else ext.cardBorder,
                shape = RoundedCornerShape(18.dp),
            )
            .animateContentSize(aetherSpringSpec(motionSpeed))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(visible = isSimulating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ext.amber.copy(alpha = 0.14f))
                    .border(1.dp, ext.amber.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("sim_active_banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = ext.amber, modifier = Modifier.size(16.dp))
                    Text(
                        text = "SIMULATION MODE ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.amber,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = onOpenSimulator,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Tune", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onStopSimulation,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Stop", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            isSimulating -> "SIMULATED THROUGHPUT"
                            telemetry.isMemfdMapped -> "MEMFD ZERO-JNI"
                            else -> "AGGREGATE THROUGHPUT"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSimulating) ext.amber else ext.cyan,
                    )
                    StatusPill(
                        label = telemetry.activeEngineBadge,
                        color = ext.emerald,
                        modifier = Modifier.aetherSharedElement(
                            key = AetherSharedKey("hero", SharedPart.HERO_ENGINE_BADGE),
                        ),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = Formatters.formatSpeed(telemetry.instantBps, useMBpsSpeedUnit),
                        style = MonoHeroMetricStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = Formatters.formatSpeed(telemetry.instantBps, !useMBpsSpeedUnit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusPill(label = "MAX ${Formatters.formatSpeed(telemetry.peakBps, useMBpsSpeedUnit)}", color = ext.amber)
                    StatusPill(label = "AVG ${Formatters.formatSpeed(telemetry.avgBps, useMBpsSpeedUnit)}", color = ext.violet)
                    StatusPill(label = "STAB ${telemetry.stabilityPercent}%", color = ext.emerald)
                    if (telemetry.antiDipRescues > 0) {
                        StatusPill(label = "RESCUES ${telemetry.antiDipRescues}", color = ext.cyan)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (activeCount > 0) {
                    OutlinedButton(
                        onClick = onPauseAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("pause_all_button"),
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause All")
                    }
                } else if (hasPaused) {
                    OutlinedButton(
                        onClick = onResumeAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("resume_all_button"),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume")
                    }
                }
            }
        }

        ThroughputSparkline(
            historyBps = telemetry.speedHistoryBps,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey("hero", SharedPart.HERO_SPARKLINE),
                    clipShape = RoundedCornerShape(8.dp),
                ),
        )

        // Live Quad-Link Bike/Vehicle Speedometers (Wi-Fi 1, Mobile 5G, Wi-Fi 2, USB/Eth)
        PerNetworkSpeedometerCluster(
            telemetry = telemetry,
            networkMatrix = networkMatrix,
            useMBpsUnit = useMBpsSpeedUnit,
            speedometerStyle = speedometerStyle,
            onToggleUnit = onToggleSpeedUnit,
            onCycleSpeedometerStyle = onCycleSpeedometerStyle,
            onOpenDualWifi = onOpenDualWifi,
            onSelectGauge = onSelectGauge,
            onOpenSimulator = onOpenSimulator,
            isSimulating = isSimulating,
            onResetPeaks = onResetPeaks,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkEndpointPill(title = "Wi-Fi 1", endpoint = networkMatrix.wifiLink)
            LinkEndpointPill(title = "Wi-Fi 2", endpoint = networkMatrix.wifi2Link)
            LinkEndpointPill(
                title = if (networkMatrix.cellularLeaseRequested) "5G/LTE (Leased)" else "5G/LTE",
                endpoint = networkMatrix.cellularLink,
            )
            LinkEndpointPill(title = "USB / Eth", endpoint = networkMatrix.ethernetLink)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TelemetryMiniCell(
                label = "SLAB POOL",
                value = String.format("%.1f / 12.0 MB", telemetry.slabAllocatedMb.coerceAtLeast(0.5f)),
                accent = ext.violet,
                modifier = Modifier.weight(1f),
            )
            TelemetryMiniCell(
                label = "QUAD LINKS",
                value = "${networkMatrix.activeValidatedLinks.size} / 4 Live",
                accent = ext.emerald,
                modifier = Modifier.weight(1f),
            )
            TelemetryMiniCell(
                label = "QUOTA GUARD",
                value = Formatters.formatDurationHms(telemetry.quotaRemainingMs),
                accent = ext.amber,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LinkEndpointPill(
    title: String,
    endpoint: LinkEndpointInfo?,
) {
    val ext = LocalAetherColors.current
    val (label, color) = if (endpoint == null) {
        "$title · Offline" to MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        val br = when (endpoint.breakerState) {
            BreakerState.CLOSED -> "CLOSED"
            BreakerState.OPEN -> "OPEN"
            BreakerState.HALF_OPEN -> "HALF-OPEN"
        }
        val c = when (endpoint.breakerState) {
            BreakerState.CLOSED -> ext.emerald
            BreakerState.OPEN -> ext.rose
            BreakerState.HALF_OPEN -> ext.amber
        }
        "${endpoint.interfaceName} · ${String.format("%.0f", endpoint.ewmaMbps)}M · $br" to c
    }
    StatusPill(label = label, color = color)
}

@Composable
private fun TelemetryMiniCell(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .border(1.dp, LocalAetherColors.current.cardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MonoMetricStyle,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun TransferItemCard(
    entity: DownloadEntity,
    liveSpeedBps: Long,
    useMBpsSpeedUnit: Boolean,
    onInspect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSwitchToKotlinAndResume: () -> Unit,
    onDeleteRequest: () -> Unit,
    onRefreshUrlRequest: () -> Unit,
    onRegrantSafRequest: () -> Unit,
    onPruneCas: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    var expanded by remember { mutableStateOf(entity.statusEnum.isActive || entity.statusEnum.isIssue) }
    val extentMb = (entity.segmentSizeBytes / (1024L * 1024L)).coerceAtLeast(1L)

    val statusColor = when (entity.statusEnum) {
        DownloadStatus.COMPLETED -> ext.emerald
        DownloadStatus.STREAMING,
        DownloadStatus.WORK_STEALING,
        DownloadStatus.PROBING_0RTT,
        DownloadStatus.VERIFYING_BAO,
        DownloadStatus.QUEUED -> ext.cyan
        DownloadStatus.PAUSED,
        DownloadStatus.LINK_MIGRATE_R1,
        DownloadStatus.QUOTA_GUARD_R3 -> ext.amber
        DownloadStatus.TOKEN_EXPIRED_R2,
        DownloadStatus.ENOSPC_R4,
        DownloadStatus.SAF_EBADF_R5,
        DownloadStatus.FAILED,
        DownloadStatus.CANCELLED -> ext.rose
    }

    val statusLabel = when (entity.statusEnum) {
        DownloadStatus.QUEUED -> "QUEUED"
        DownloadStatus.PROBING_0RTT -> "0-RTT PROBE"
        DownloadStatus.STREAMING -> "${extentMb}M STREAMING"
        DownloadStatus.WORK_STEALING -> "TAIL HEDGE"
        DownloadStatus.VERIFYING_BAO -> "S3 SHA-256"
        DownloadStatus.PAUSED -> "PAUSED"
        DownloadStatus.COMPLETED -> "COMMITTED"
        DownloadStatus.LINK_MIGRATE_R1 -> "R1 LINK MIGRATE"
        DownloadStatus.TOKEN_EXPIRED_R2 -> "R2 403/410 EXPIRED"
        DownloadStatus.QUOTA_GUARD_R3 -> "R3 QUOTA GUARD"
        DownloadStatus.ENOSPC_R4 -> "R4 ENOSPC"
        DownloadStatus.SAF_EBADF_R5 -> "R5 SAF EBADF"
        DownloadStatus.FAILED -> "FAILED"
        DownloadStatus.CANCELLED -> "CANCELLED"
    }

    val engineTag = when (entity.engineBackendUsed) {
        ResolvedEngineBackend.RUST_LIBAETHER_CORE.id -> "RUST CORE"
        ResolvedEngineBackend.KOTLIN_OKHTTP.id -> "OKHTTP"
        else -> null
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aetherSharedBounds(
                key = AetherSharedKey(entity.id, SharedPart.TRANSFER_CARD),
                clipShape = RoundedCornerShape(18.dp),
            )
            .border(1.dp, ext.cardBorder, MaterialTheme.shapes.large)
            .animateContentSize(aetherSpringSpec(motionSpeed))
            .testTag("transfer_card_${entity.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .aetherSharedElement(
                            key = AetherSharedKey(entity.id, SharedPart.TRANSFER_ICON),
                            clipShape = RoundedCornerShape(10.dp),
                        )
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = fileIconForName(entity.fileName, entity.mimeType),
                        contentDescription = null,
                        tint = statusColor,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .aetherSharedElement(key = AetherSharedKey(entity.id, SharedPart.TRANSFER_TITLE))
                            .aetherSkipToLookaheadSize(),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatusPill(
                            label = statusLabel,
                            color = statusColor,
                            modifier = Modifier.aetherSharedElement(
                                key = AetherSharedKey(entity.id, SharedPart.TRANSFER_STATUS),
                            ),
                        )
                        if (engineTag != null) {
                            StatusPill(label = engineTag, color = ext.violet)
                        }
                        if (entity.statusEnum.isActive && liveSpeedBps > 0L) {
                            val remaining = (entity.totalBytes - entity.downloadedBytes).coerceAtLeast(0L)
                            val etaLabel = if (entity.totalBytes > 0L) {
                                " · ETA ${Formatters.formatEta(remaining, liveSpeedBps)}"
                            } else {
                                ""
                            }
                            StatusPill(
                                label = "${Formatters.formatSpeed(liveSpeedBps, useMBpsSpeedUnit)}$etaLabel",
                                color = ext.emerald,
                            )
                        }
                        if (entity.allUrls.size > 1) {
                            StatusPill(label = "${entity.allUrls.size} Mirrors", color = ext.amber)
                        }
                        Text(
                            text = if (entity.totalBytes > 0L) {
                                "${Formatters.formatBytes(entity.downloadedBytes)} / ${Formatters.formatBytes(entity.totalBytes)}"
                            } else {
                                Formatters.formatBytes(entity.downloadedBytes)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(
                    onClick = onInspect,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("inspect_transfer_${entity.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInFull,
                        contentDescription = "Inspect transfer pipeline",
                        tint = ext.cyan,
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse details" else "Expand details",
                    )
                }
            }

            if (entity.statusEnum != DownloadStatus.COMPLETED) {
                if (entity.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { entity.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                } else if (entity.statusEnum.isActive) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }

            entity.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${extentMb} MB AETHLDG2 LEDGER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${entity.parsedSegments().size} extents · ${Formatters.formatPercent(entity.progressFraction)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ext.cyan,
                            )
                        }
                        SegmentBitfieldMatrix(
                            segments = entity.parsedSegments(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aetherSharedBounds(
                                    key = AetherSharedKey(entity.id, SharedPart.TRANSFER_LEDGER),
                                    clipShape = RoundedCornerShape(6.dp),
                                ),
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "TTFB: ${entity.ttfbMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entity.wifiBytes > 0L || entity.cellBytes > 0L) {
                            Text(
                                text = "Wi-Fi ${Formatters.formatBytes(entity.wifiBytes)} · 5G ${Formatters.formatBytes(entity.cellBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ext.cyan,
                            )
                        }
                        Text(
                            text = entity.routeModeEnum.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.violet,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (entity.statusEnum) {
                            DownloadStatus.COMPLETED -> {
                                Button(
                                    onClick = onOpen,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open")
                                }
                                OutlinedButton(
                                    onClick = onShare,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }

                            DownloadStatus.TOKEN_EXPIRED_R2 -> {
                                Button(
                                    onClick = onRefreshUrlRequest,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Update Signed URL")
                                }
                            }

                            DownloadStatus.SAF_EBADF_R5 -> {
                                Button(
                                    onClick = onRegrantSafRequest,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Re-Grant SAF")
                                }
                            }

                            DownloadStatus.ENOSPC_R4 -> {
                                OutlinedButton(
                                    onClick = onPruneCas,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Prune CAS")
                                }
                                Button(
                                    onClick = onResume,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry")
                                }
                            }

                            else -> {
                                val missingRustSo = entity.statusEnum == DownloadStatus.FAILED &&
                                    (entity.errorMessage?.contains("libaether_core.so", ignoreCase = true) == true)
                                if (entity.statusEnum.isActive) {
                                    OutlinedButton(
                                        onClick = onPause,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Pause")
                                    }
                                } else if (missingRustSo) {
                                    Button(
                                        onClick = onSwitchToKotlinAndResume,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("switch_kotlin_fallback_${entity.id}"),
                                    ) {
                                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Use Kotlin OkHttp & Retry")
                                    }
                                } else {
                                    Button(
                                        onClick = onResume,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Resume")
                                    }
                                }
                            }
                        }

                        IconButton(
                            onClick = onDeleteRequest,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete transfer",
                                tint = ext.rose,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TransferInspectorOverlay(
    entity: DownloadEntity,
    liveSpeedBps: Long,
    useMBpsSpeedUnit: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = LocalAetherColors.current
    BackHandler(onBack = onDismiss)
    val extentMb = (entity.segmentSizeBytes / (1024L * 1024L)).coerceAtLeast(1L)
    val statusColor = when {
        entity.statusEnum == DownloadStatus.COMPLETED -> ext.emerald
        entity.statusEnum.isActive -> ext.cyan
        entity.statusEnum.isIssue -> ext.rose
        else -> ext.amber
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.64f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(16.dp)
            .testTag("transfer_inspector_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 580.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey(entity.id, SharedPart.TRANSFER_CARD),
                    clipShape = RoundedCornerShape(22.dp),
                    zIndexInOverlay = 3f,
                )
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, statusColor.copy(alpha = 0.65f), RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .aetherSharedElement(
                                key = AetherSharedKey(entity.id, SharedPart.TRANSFER_ICON),
                                clipShape = RoundedCornerShape(14.dp),
                            )
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(statusColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = fileIconForName(entity.fileName, entity.mimeType),
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entity.fileName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .aetherSharedElement(key = AetherSharedKey(entity.id, SharedPart.TRANSFER_TITLE))
                                .aetherSkipToLookaheadSize(),
                        )
                        StatusPill(
                            label = entity.statusEnum.name,
                            color = statusColor,
                            modifier = Modifier.aetherSharedElement(
                                key = AetherSharedKey(entity.id, SharedPart.TRANSFER_STATUS),
                            ),
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_transfer_inspector_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close pipeline inspector")
                }
            }

            if (entity.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { entity.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill(label = "Live ${Formatters.formatSpeed(liveSpeedBps, useMBpsSpeedUnit)}", color = ext.cyan)
                StatusPill(label = "Peak ${Formatters.formatSpeed(entity.peakSpeedBps, useMBpsSpeedUnit)}", color = ext.amber)
                StatusPill(label = "Avg ${Formatters.formatSpeed(entity.avgSpeedBps, useMBpsSpeedUnit)}", color = ext.violet)
                StatusPill(label = "TTFB ${entity.ttfbMs} ms", color = ext.emerald)
            }

            Text(
                text = "Endpoint: ${entity.url}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Route: ${entity.routeModeEnum.label} · Workers: ${entity.workerCount} · Syscall Batches: ${entity.syscallWriteBatches}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Link Split: Wi-Fi ${Formatters.formatBytes(entity.wifiBytes)} · Cellular ${Formatters.formatBytes(entity.cellBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = ext.cyan,
            )

            Text(
                text = "${extentMb} MB AETHLDG2 ZERO-MERGE EXTENT BITFIELD (${entity.parsedSegments().size} extents)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentBitfieldMatrix(
                segments = entity.parsedSegments(),
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherSharedBounds(
                        key = AetherSharedKey(entity.id, SharedPart.TRANSFER_LEDGER),
                        clipShape = RoundedCornerShape(8.dp),
                    ),
            )

            if (!entity.actualSha256.isNullOrBlank()) {
                Text(
                    text = "SHA-256: ${entity.actualSha256}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.emerald,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (entity.statusEnum == DownloadStatus.COMPLETED) {
                    Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open")
                    }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                } else if (entity.statusEnum.isActive) {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pause")
                    }
                } else {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resume")
                    }
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun EmptyTransfersCard(
    onOpenNewDownload: () -> Unit,
    onOpenSimulator: () -> Unit,
) {
    val ext = LocalAetherColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ext.cyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = ext.cyan,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(text = "Zero Active Pipelines", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Add any HTTP/HTTPS/HLS/DASH URL or launch the Speed & Animation Simulator to preview all 4 motorsport speedometers and shared-element morphs live.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenNewDownload,
                modifier = Modifier.testTag("empty_new_download_button"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Download")
            }
            OutlinedButton(
                onClick = onOpenSimulator,
                modifier = Modifier.testTag("empty_open_sim_button"),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Simulate")
            }
        }
    }
}

@Composable
private fun DeleteDownloadDialog(
    entity: DownloadEntity,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteBackingFile by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Transfer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Remove \"${entity.fileName}\" from AetherIO?")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { deleteBackingFile = !deleteBackingFile },
                ) {
                    Checkbox(checked = deleteBackingFile, onCheckedChange = { deleteBackingFile = it })
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Also delete file from device storage")
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(deleteBackingFile) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RefreshSignedUrlDialog(
    entity: DownloadEntity,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var newUrl by remember { mutableStateOf(entity.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("R2 Token Refresh · Update URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste a fresh signed CDN URL to resume ${entity.fileName} from exact committed extents.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text("Refreshed HTTPS URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(newUrl) }, enabled = newUrl.isNotBlank()) {
                Text("Hot-Swap & Resume")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun fileIconForName(fileName: String, mimeType: String) = when {
    mimeType.startsWith("image/") || mimeType.startsWith("video/") -> Icons.Outlined.Image
    fileName.endsWith(".zip", true) ||
        fileName.endsWith(".tar", true) ||
        fileName.endsWith(".gz", true) ||
        fileName.endsWith(".7z", true) -> Icons.Outlined.FolderZip
    mimeType.startsWith("text/") ||
        fileName.endsWith(".txt", true) ||
        fileName.endsWith(".pdf", true) -> Icons.Outlined.Description
    else -> Icons.Outlined.Inventory2
}
