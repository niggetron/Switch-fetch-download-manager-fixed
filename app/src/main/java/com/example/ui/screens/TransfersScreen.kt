package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import com.example.data.model.TransferFilter
import com.example.engine.LinkEndpointInfo
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.components.SegmentBitfieldMatrix
import com.example.ui.components.StatusPill
import com.example.ui.components.ThroughputSparkline
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoHeroMetricStyle
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters

@Composable
fun TransfersScreen(
    downloads: List<DownloadEntity>,
    selectedFilter: TransferFilter,
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
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
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    var deleteTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var refreshUrlTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var safRegrantTargetId by remember { mutableStateOf<String?>(null) }

    val safRegrantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val id = safRegrantTargetId
        if (uri != null && id != null) {
            onRegrantSaf(id, uri)
        }
        safRegrantTargetId = null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero Telemetry Banner
        item(key = "hero_banner") {
            HeroTelemetryBanner(
                telemetry = telemetry,
                networkMatrix = networkMatrix,
                activeCount = downloads.count { it.statusEnum.isActive },
                hasPaused = downloads.any {
                    it.statusEnum == DownloadStatus.PAUSED ||
                        it.statusEnum == DownloadStatus.QUOTA_GUARD_R3
                },
                onPauseAll = onPauseAll,
                onResumeAll = onResumeAll,
            )
        }

        // PAC Auto-Proxy Warning Banner (F9)
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

        // Filter Strip
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
                EmptyTransfersCard(onOpenNewDownload = onOpenNewDownload)
            }
        } else {
            items(downloads, key = { it.id }) { item ->
                TransferItemCard(
                    entity = item,
                    onPause = { onPause(item.id) },
                    onResume = { onResume(item.id) },
                    onDeleteRequest = { deleteTarget = item },
                    onRefreshUrlRequest = { refreshUrlTarget = item },
                    onRegrantSafRequest = {
                        safRegrantTargetId = item.id
                        safRegrantLauncher.launch(item.fileName)
                    },
                    onPruneCas = onPruneCas,
                    onOpen = { onOpenFile(item) },
                    onShare = { onShareFile(item) },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(76.dp)) }
    }

    deleteTarget?.let { target ->
        DeleteDownloadDialog(
            entity = target,
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteFile ->
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

@Composable
private fun HeroTelemetryBanner(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    activeCount: Int,
    hasPaused: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val ext = LocalAetherColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(ext.heroTop, ext.heroBottom)))
            .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (telemetry.isMemfdMapped) "THROUGHPUT · MEMFD ZERO-JNI" else "THROUGHPUT PIPELINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.cyan,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = Formatters.formatSpeedMbps(telemetry.instantBps),
                        style = MonoHeroMetricStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Formatters.formatSpeedMBps(telemetry.instantBps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                .height(54.dp),
        )

        // Per-LinkKey Interface & Circuit Breaker Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkEndpointPill(
                title = "Wi-Fi",
                endpoint = networkMatrix.wifiLink,
            )
            LinkEndpointPill(
                title = if (networkMatrix.cellularLeaseRequested) "5G/LTE (Leased)" else "5G/LTE",
                endpoint = networkMatrix.cellularLink,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TelemetryMiniCell(
                label = "SLAB ARENA",
                value = String.format("%.1f / 8.0 MB", telemetry.slabAllocatedMb.coerceAtLeast(0.5f)),
                accent = ext.violet,
                modifier = Modifier.weight(1f),
            )
            TelemetryMiniCell(
                label = "LINKS",
                value = when {
                    networkMatrix.isMultipathReady -> "Wi-Fi + 5G"
                    networkMatrix.wifiLink != null -> "Wi-Fi"
                    networkMatrix.cellularLink != null -> "5G/LTE"
                    else -> "Offline"
                },
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

@Composable
private fun TransferItemCard(
    entity: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDeleteRequest: () -> Unit,
    onRefreshUrlRequest: () -> Unit,
    onRegrantSafRequest: () -> Unit,
    onPruneCas: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val ext = LocalAetherColors.current
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ext.cardBorder, MaterialTheme.shapes.large)
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
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusPill(label = statusLabel, color = statusColor)
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
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse details" else "Expand details",
                    )
                }
            }

            // Progress bar
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

            // Error or recovery banner
            entity.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 2 MB Segment Bitfield Grid
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${extentMb} MB F2FS EXTENT LEDGER",
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Syscall & TTFB row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "TTFB: ${entity.ttfbMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Batches: ${entity.syscallWriteBatches}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = entity.routeModeEnum.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.violet,
                        )
                    }

                    // Contextual Action Bar
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
                                if (entity.statusEnum.isActive) {
                                    OutlinedButton(
                                        onClick = onPause,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Pause")
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

@Composable
private fun EmptyTransfersCard(onOpenNewDownload: () -> Unit) {
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
        Text(
            text = "Zero Active Pipelines",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Add any HTTP/HTTPS URL or pick a diagnostic stream to run adaptive BDP aligned Wi-Fi + 5G downloads with zero post-download merge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = onOpenNewDownload,
            modifier = Modifier.testTag("empty_new_download_button"),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("New Download")
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
                    Checkbox(
                        checked = deleteBackingFile,
                        onCheckedChange = { deleteBackingFile = it },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Also delete file from device storage")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(deleteBackingFile) }) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
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
            Button(
                onClick = { onSubmit(newUrl) },
                enabled = newUrl.isNotBlank(),
            ) {
                Text("Hot-Swap & Resume")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
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
