package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadEntity
import com.example.data.model.FileCategory
import com.example.data.repository.StorageStats
import com.example.ui.components.SegmentBitfieldMatrix
import com.example.ui.components.StatusPill
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalAnimatedVisibilityScope
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSharedElement
import com.example.ui.motion.aetherSkipToLookaheadSize
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FilesScreen(
    files: List<DownloadEntity>,
    storageStats: StorageStats,
    casChunkCount: Int,
    casTotalBytes: Long,
    selectedCategory: FileCategory,
    searchQuery: String,
    onSelectCategory: (FileCategory) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onShareFile: (DownloadEntity) -> Unit,
    onDeleteFile: (DownloadEntity) -> Unit,
    onPruneCas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    var inspectTarget by remember { mutableStateOf<DownloadEntity?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "storage_vault_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "F2FS ZERO-MERGE VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = ext.cyan,
                            )
                            Text(
                                text = "${storageStats.downloadedFilesCount} Verified Files",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        OutlinedButton(
                            onClick = onPruneCas,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Prune CAS")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        VaultStatBox(
                            label = "DOWNLOADED",
                            value = Formatters.formatBytes(storageStats.downloadedBytesTotal),
                            modifier = Modifier.weight(1f),
                        )
                        VaultStatBox(
                            label = "FREE SPACE",
                            value = Formatters.formatBytes(storageStats.freeBytes),
                            modifier = Modifier.weight(1f),
                        )
                        VaultStatBox(
                            label = "CAS INDEX",
                            value = "$casChunkCount (${Formatters.formatBytes(casTotalBytes)})",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item(key = "files_search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search downloaded files or MIME types…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("files_search_input"),
                )
            }

            item(key = "files_categories") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { onSelectCategory(cat) },
                            label = { Text(cat.label) },
                        )
                    }
                }
            }

            if (files.isEmpty()) {
                item(key = "empty_files") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderCopy,
                            contentDescription = null,
                            tint = ext.cyan,
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            text = "No Files Found",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Completed transfers appear here immediately with zero post-download merge freeze and full SHA-256 verification.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(files, key = { it.id }) { file ->
                    DownloadedFileCard(
                        entity = file,
                        onOpen = { onOpenFile(file) },
                        onShare = { onShareFile(file) },
                        onInspect = { inspectTarget = file },
                        onDelete = { onDeleteFile(file) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(76.dp)) }
        }

        // In-tree Container Transform Overlay for Zero-Merge Integrity Audit
        AnimatedVisibility(
            visible = inspectTarget != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                inspectTarget?.let { item ->
                    FileIntegrityAuditOverlay(
                        entity = item,
                        onOpen = { onOpenFile(item) },
                        onShare = { onShareFile(item) },
                        onDismiss = { inspectTarget = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultStatBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DownloadedFileCard(
    entity: DownloadEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onInspect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aetherSharedBounds(
                key = AetherSharedKey(entity.id, SharedPart.FILE_CARD),
                clipShape = RoundedCornerShape(18.dp),
            )
            .border(1.dp, ext.cardBorder, MaterialTheme.shapes.large)
            .clickable(onClick = onInspect)
            .testTag("file_card_${entity.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .aetherSharedElement(
                            key = AetherSharedKey(entity.id, SharedPart.FILE_ICON),
                            clipShape = RoundedCornerShape(10.dp),
                        )
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ext.emerald.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = fileIconForName(entity.fileName, entity.mimeType),
                        contentDescription = null,
                        tint = ext.emerald,
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
                            .aetherSharedElement(key = AetherSharedKey(entity.id, SharedPart.FILE_TITLE))
                            .aetherSkipToLookaheadSize(),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = Formatters.formatBytes(entity.totalBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!entity.actualSha256.isNullOrBlank()) {
                            StatusPill(label = "SHA-256 OK", color = ext.emerald)
                        }
                        if (entity.isSafDestination) {
                            StatusPill(label = "SAF", color = ext.violet)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                IconButton(
                    onClick = onInspect,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = "Inspect integrity",
                        tint = ext.cyan,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete file",
                        tint = ext.rose,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FileIntegrityAuditOverlay(
    entity: DownloadEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = LocalAetherColors.current
    BackHandler(onBack = onDismiss)

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
            .testTag("file_integrity_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey(entity.id, SharedPart.FILE_CARD),
                    clipShape = RoundedCornerShape(22.dp),
                    zIndexInOverlay = 3f,
                )
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, ext.emerald.copy(alpha = 0.60f), RoundedCornerShape(22.dp))
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
                                key = AetherSharedKey(entity.id, SharedPart.FILE_ICON),
                                clipShape = RoundedCornerShape(14.dp),
                            )
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ext.emerald.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = fileIconForName(entity.fileName, entity.mimeType),
                            contentDescription = null,
                            tint = ext.emerald,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ZERO-MERGE INTEGRITY AUDIT",
                            style = MaterialTheme.typography.labelSmall,
                            color = ext.emerald,
                        )
                        Text(
                            text = entity.fileName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .aetherSharedElement(key = AetherSharedKey(entity.id, SharedPart.FILE_TITLE))
                                .aetherSkipToLookaheadSize(),
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_file_integrity_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close integrity audit")
                }
            }

            Text(
                text = "Size: ${Formatters.formatBytes(entity.totalBytes)} (${entity.totalBytes} B)",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Mean Throughput: ${Formatters.formatSpeedMbps(entity.avgSpeedBps)} · Peak: ${Formatters.formatSpeedMbps(entity.peakSpeedBps)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "TTFB: ${entity.ttfbMs} ms · Write Batches: ${entity.syscallWriteBatches}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!entity.actualSha256.isNullOrBlank()) {
                Text(
                    text = "SHA-256 Digest:\n${entity.actualSha256}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.emerald,
                )
            }
            Text(
                text = "2 MB Extent Ledger:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentBitfieldMatrix(
                segments = entity.parsedSegments(),
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherSharedBounds(
                        key = AetherSharedKey(entity.id, SharedPart.FILE_LEDGER),
                        clipShape = RoundedCornerShape(8.dp),
                    ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                OutlinedButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
