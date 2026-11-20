package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.NetworkRouteMode
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.aetherNeonBorder
import com.example.ui.theme.contrastOnColor
import com.example.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

private data class MediaExtraInfo(
    val bitrateLabel: String? = null,
    val durationLabel: String? = null,
    val width: String? = null,
    val height: String? = null,
) {
    val hasAny: Boolean
        get() = bitrateLabel != null || durationLabel != null || width != null || height != null
}

/**
 * 1DM+-style Super-Properties modal sheet triggered when tapping any downloading,
 * paused, or completed transfer item (or simulated transfer card).
 * Includes full metadata, exact byte sizes, speed/connection stats, MediaMetadataRetriever
 * video/audio stream inspection, and interactive on-demand MD5 / SHA-256 CALCULATE buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadPropertiesSheet(
    entity: DownloadEntity,
    useMBpsUnit: Boolean,
    liveSpeedBps: Long = 0L,
    isSimulatedPreview: Boolean = false,
    onDismiss: () -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onShareFile: (DownloadEntity) -> Unit,
    onRefreshUrl: (String, String) -> Unit,
    onSaveMd5: (String, String) -> Unit,
    onSaveSha256: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onDismiss)

    var md5Value by remember(entity.id, entity.actualMd5) { mutableStateOf(entity.actualMd5.orEmpty()) }
    var sha256Value by remember(entity.id, entity.actualSha256) { mutableStateOf(entity.actualSha256.orEmpty()) }
    var computingMd5 by remember(entity.id) { mutableStateOf(false) }
    var computingSha256 by remember(entity.id) { mutableStateOf(false) }

    var editingUrl by remember(entity.id) { mutableStateOf(false) }
    var draftUrl by remember(entity.id) { mutableStateOf(entity.url) }

    var mediaLoading by remember(entity.id) { mutableStateOf(true) }
    var mediaInfo by remember(entity.id) { mutableStateOf(MediaExtraInfo()) }

    LaunchedEffect(entity.id, entity.destinationUri, entity.downloadedBytes) {
        mediaLoading = true
        mediaInfo = if (isSimulatedPreview) {
            MediaExtraInfo(
                bitrateLabel = "14.8 Mbps",
                durationLabel = "2h 14m 08s",
                width = "3840",
                height = "2160",
            )
        } else {
            probeMediaMetadata(context, entity)
        }
        mediaLoading = false
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    val totalDisplay = if (entity.totalBytes > 0L) {
        "${Formatters.formatBytes(entity.totalBytes)} (${entity.totalBytes} bytes)"
    } else {
        "Unknown"
    }
    val finishedDisplay = "${Formatters.formatBytes(entity.downloadedBytes)} (${entity.downloadedBytes} bytes)"
    val remainingDisplay = if (entity.totalBytes > 0L) {
        "${Formatters.formatBytes(entity.remainingBytes)} (${entity.remainingBytes} bytes)"
    } else {
        "—"
    }
    val resumeLabel = if (entity.supportsRange) "Yes" else "No"
    val wifiOnlyLabel = if (entity.routeModeEnum == NetworkRouteMode.WIFI_ONLY) "Yes" else "No"
    val isCompleted = entity.statusEnum == DownloadStatus.COMPLETED
    val isRunning = entity.statusEnum.isActive
    val effectiveSpeedBps = if (liveSpeedBps > 0L) liveSpeedBps else entity.avgSpeedBps
    val activeConnections = if (isRunning) entity.workerCount else 0
    val engineLabel = entity.engineBackendUsed.ifBlank { "AetherIO Hybrid" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .testTag("download_properties_sheet"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 580.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .aetherNeonBorder(
                    neon = neon,
                    accent = if (isCompleted) ext.emerald else ext.cyan,
                    cornerRadius = 22.dp,
                    borderWidth = 1.3.dp,
                    fallbackBorderColor = ext.cardBorder,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Properties!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    StatusPill(
                        label = if (isSimulatedPreview) "SIMULATED" else entity.statusEnum.name,
                        color = when {
                            isSimulatedPreview -> ext.amber
                            isCompleted -> ext.emerald
                            entity.statusEnum.isIssue -> ext.rose
                            else -> ext.cyan
                        },
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_properties_icon_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close properties",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider(color = ext.cardBorder)

            // Core 1DM+ Table Fields
            PropertyKeyValueRow("Name", entity.fileName, highlight = true)

            // Download link + Copy + Edit Expired URL
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "Download link",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(114.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = entity.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.cyan,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { copyToClipboard("Download link", entity.url) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Link", style = MaterialTheme.typography.labelSmall)
                            }
                            if (!isSimulatedPreview) {
                                TextButton(
                                    onClick = { editingUrl = !editingUrl },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (editingUrl) "Cancel" else "Update Link", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = editingUrl) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draftUrl,
                            onValueChange = { draftUrl = it },
                            label = { Text("Fresh Mirror / Non-Expired URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                if (draftUrl.isNotBlank()) {
                                    onRefreshUrl(entity.id, draftUrl.trim())
                                    editingUrl = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ext.cyan,
                                contentColor = contrastOnColor(ext.cyan),
                            ),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            PropertyKeyValueRow("Path", entity.destinationUri)
            PropertyKeyValueRow("Wifi only", wifiOnlyLabel)
            PropertyKeyValueRow(
                label = "Resume",
                value = resumeLabel,
                valueColor = if (entity.supportsRange) ext.emerald else ext.rose,
            )
            PropertyKeyValueRow("Size", totalDisplay)
            PropertyKeyValueRow("Finished", finishedDisplay)
            PropertyKeyValueRow("Remaining", remainingDisplay)
            PropertyKeyValueRow(
                label = "Speed",
                value = "${Formatters.formatSpeed(effectiveSpeedBps, useMBpsUnit)} (MAX ${Formatters.formatSpeed(entity.peakSpeedBps, useMBpsUnit)} · AVG ${Formatters.formatSpeed(entity.avgSpeedBps, useMBpsUnit)})",
                valueColor = ext.cyan,
            )
            PropertyKeyValueRow(
                label = "Connections",
                value = "$activeConnections active / ${entity.maxConnectionsUsed.coerceAtLeast(entity.workerCount)} max ($engineLabel)",
                valueColor = ext.emerald,
            )
            PropertyKeyValueRow(
                label = "ETA",
                value = if (isCompleted) "Completed" else Formatters.formatEta(entity.remainingBytes, effectiveSpeedBps),
            )
            PropertyKeyValueRow("Date added", Formatters.formatTimestamp(entity.createdAt))
            PropertyKeyValueRow("Started", Formatters.formatTimestamp(if (entity.startedAt > 0L) entity.startedAt else entity.createdAt))
            PropertyKeyValueRow(
                label = "Ended",
                value = if ((entity.completedAt ?: 0L) > 0L) Formatters.formatTimestamp(entity.completedAt ?: 0L) else "In progress",
            )
            PropertyKeyValueRow(
                label = "Active time",
                value = Formatters.formatActiveDuration(entity.activeDownloadMs),
                valueColor = ext.amber,
            )
            PropertyKeyValueRow("Date modified", Formatters.formatTimestamp(entity.updatedAt))

            HorizontalDivider(color = ext.cardBorder)

            // Additional Information (MediaMetadataRetriever with shimmer skeleton)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ADDITIONAL INFORMATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.violet,
                    fontWeight = FontWeight.Bold,
                )
                if (mediaLoading) {
                    SkeletonPropertyRow()
                    SkeletonPropertyRow()
                } else if (mediaInfo.hasAny) {
                    mediaInfo.bitrateLabel?.let { PropertyKeyValueRow("Bitrate", it) }
                    mediaInfo.durationLabel?.let { PropertyKeyValueRow("Duration", it) }
                    mediaInfo.width?.let { PropertyKeyValueRow("Width", it) }
                    mediaInfo.height?.let { PropertyKeyValueRow("Height", it) }
                } else {
                    PropertyKeyValueRow("MIME / Stream", "${entity.mimeType} · ${entity.streamType}")
                    val etagVal = entity.eTag.orEmpty()
                    if (etagVal.isNotBlank()) {
                        PropertyKeyValueRow("ETag", etagVal)
                    }
                }
            }

            HorizontalDivider(color = ext.cardBorder)

            // On-Demand MD5 & SHA-256 Checksum Calculators (matching 1DM+ CALCULATE buttons)
            ChecksumCalculatorRow(
                title = "MD5 checksum",
                hashValue = md5Value,
                isComputing = computingMd5,
                buttonTag = "calculate_md5_button",
                onCalculate = {
                    if (computingMd5) return@ChecksumCalculatorRow
                    computingMd5 = true
                    scope.launch {
                        val digest = if (isSimulatedPreview) {
                            kotlinx.coroutines.delay(550)
                            "9e107d9d372bb6826bd81d3542a419d6"
                        } else {
                            computeFileDigest(context, entity, "MD5")
                        }
                        computingMd5 = false
                        if (!digest.isNullOrBlank()) {
                            md5Value = digest
                            if (!isSimulatedPreview) onSaveMd5(entity.id, digest)
                        } else {
                            Toast.makeText(context, "File not readable for MD5", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCopy = { copyToClipboard("MD5", md5Value) },
            )

            ChecksumCalculatorRow(
                title = "SHA-256 checksum",
                hashValue = sha256Value,
                isComputing = computingSha256,
                buttonTag = "calculate_sha256_button",
                onCalculate = {
                    if (computingSha256) return@ChecksumCalculatorRow
                    computingSha256 = true
                    scope.launch {
                        val digest = if (isSimulatedPreview) {
                            kotlinx.coroutines.delay(650)
                            "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"
                        } else {
                            computeFileDigest(context, entity, "SHA-256")
                        }
                        computingSha256 = false
                        if (!digest.isNullOrBlank()) {
                            sha256Value = digest
                            if (!isSimulatedPreview) onSaveSha256(entity.id, digest)
                        } else {
                            Toast.makeText(context, "File not readable for SHA-256", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCopy = { copyToClipboard("SHA-256", sha256Value) },
            )

            val expectedHash = entity.expectedSha256.orEmpty()
            if (expectedHash.isNotBlank() && sha256Value.isNotBlank()) {
                val matched = expectedHash.equals(sha256Value, ignoreCase = true)
                StatusPill(
                    label = if (matched) "SHA-256 VERIFIED MATCH" else "SHA-256 MISMATCH",
                    color = if (matched) ext.emerald else ext.rose,
                )
            }

            // Per-link byte split & Rayburst piece matrix
            val totalLinkBytes = entity.wifiBytes + entity.wifi2Bytes + entity.cellBytes + entity.usbBytes
            if (totalLinkBytes > 0L) {
                HorizontalDivider(color = ext.cardBorder)
                Text(
                    text = "MULTI-LINK INTERFACE SPLIT",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.cyan,
                    fontWeight = FontWeight.Bold,
                )
                SankeyLinkSplitBar(
                    wifiBytes = entity.wifiBytes,
                    wifi2Bytes = entity.wifi2Bytes,
                    cellBytes = entity.cellBytes,
                    ethBytes = entity.usbBytes,
                )
            }

            val segStates = entity.parsedSegments()
            if (segStates.isNotEmpty()) {
                Text(
                    text = "PIECE LANDING MATRIX (${segStates.size} EXTENTS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RayburstPieceMatrix(
                    segments = segStates,
                    isRunning = isRunning,
                )
            }

            HorizontalDivider(color = ext.cardBorder)

            // Bottom Actions: CLOSE · SHARE · OPEN (matching 1DM+ footer)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isSimulatedPreview && isCompleted) {
                    OutlinedButton(
                        onClick = { onShareFile(entity) },
                        modifier = Modifier.testTag("properties_share_button"),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SHARE", color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("properties_close_button"),
                ) {
                    Text(
                        text = "CLOSE",
                        color = ext.cyan,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (!isSimulatedPreview) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onOpenFile(entity) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ext.emerald,
                            contentColor = contrastOnColor(ext.emerald),
                        ),
                        modifier = Modifier.testTag("properties_open_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("OPEN", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyKeyValueRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    valueColor: Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(114.dp),
        )
        Text(
            text = value,
            style = if (highlight) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChecksumCalculatorRow(
    title: String,
    hashValue: String,
    isComputing: Boolean,
    buttonTag: String,
    onCalculate: () -> Unit,
    onCopy: () -> Unit,
) {
    val ext = LocalAetherColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(114.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                isComputing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SkeletonBox(
                            modifier = Modifier.fillMaxWidth(),
                            height = 18.dp,
                            accentTint = ext.teal,
                        )
                        Text(
                            text = "Hashing file stream…",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = ext.teal,
                        )
                    }
                }
                hashValue.isNotBlank() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ext.teal.copy(alpha = 0.12f))
                            .border(1.dp, ext.teal.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onCopy)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = hashValue,
                            style = MonoMetricStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy $title",
                            tint = ext.teal,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onCalculate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ext.teal,
                            contentColor = contrastOnColor(ext.teal),
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag(buttonTag),
                    ) {
                        Text(
                            text = "CALCULATE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun probeMediaMetadata(
    context: Context,
    entity: DownloadEntity,
): MediaExtraInfo = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        if (entity.isSafDestination) {
            retriever.setDataSource(context, Uri.parse(entity.destinationUri))
        } else {
            val file = File(entity.destinationUri)
            if (!file.exists() || file.length() <= 0L) return@withContext MediaExtraInfo()
            retriever.setDataSource(file.absolutePath)
        }
        val bitrateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.takeIf { it.isNotBlank() }
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.takeIf { it.isNotBlank() }

        val bitrateFormatted = bitrateRaw?.let { bps ->
            when {
                bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
                bps >= 1_000L -> String.format(Locale.US, "%.0f kbps", bps / 1_000.0)
                else -> "$bps bps"
            }
        }
        val durationFormatted = durationMs?.takeIf { it > 0L }?.let { Formatters.formatActiveDuration(it) }
        MediaExtraInfo(
            bitrateLabel = bitrateFormatted,
            durationLabel = durationFormatted,
            width = width,
            height = height,
        )
    } catch (_: Throwable) {
        MediaExtraInfo()
    } finally {
        runCatching { retriever.release() }
    }
}

private suspend fun computeFileDigest(
    context: Context,
    entity: DownloadEntity,
    algorithm: String,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val stream: InputStream = if (entity.isSafDestination) {
            context.contentResolver.openInputStream(Uri.parse(entity.destinationUri))
                ?: return@runCatching null
        } else {
            val f = File(entity.destinationUri)
            if (!f.exists() || !f.canRead()) return@runCatching null
            f.inputStream()
        }
        stream.use { input ->
            val digest = MessageDigest.getInstance(algorithm)
            val buf = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()
}
