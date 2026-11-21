package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.data.model.BrowserProfile
import com.example.data.model.CuratedEndpoints
import com.example.data.model.EngineBackendPreference
import com.example.data.model.EngineSettings
import com.example.data.model.MAX_CONCURRENT_WORKERS_CAP
import com.example.data.model.NetworkRouteMode
import com.example.data.model.NewDownloadRequest
import com.example.data.model.ProtocolMode
import com.example.data.model.RemoteZipEntry
import com.example.engine.NetworkMatrixState
import com.example.engine.RemoteZipInspector
import com.example.ui.components.SkeletonPropertyRow
import com.example.ui.components.StatusPill
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.aetherNeonBorder
import com.example.ui.theme.contrastOnColor
import com.example.util.Formatters
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * In-tree Container Transform Overlay for Launching a Zero-Merge 64-Stream Pipeline,
 * including Remote `.zip` EOCD 64 KB selective extraction preview.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NewDownloadSheet(
    defaultSettings: EngineSettings,
    networkMatrix: NetworkMatrixState,
    onDismiss: () -> Unit,
    onLaunchDownload: (NewDownloadRequest) -> Unit,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onDismiss)

    var urlText by remember { mutableStateOf("") }
    var mirrorUrlsText by remember { mutableStateOf("") }
    var fileNameText by remember { mutableStateOf("") }
    var expectedSha256 by remember { mutableStateOf("") }
    var routeMode by remember { mutableStateOf(defaultSettings.defaultRouteMode) }
    var workerCount by remember { mutableFloatStateOf(defaultSettings.maxConcurrentWorkers.coerceIn(1, MAX_CONCURRENT_WORKERS_CAP).toFloat()) }
    var backendOverride by remember { mutableStateOf<EngineBackendPreference?>(null) }
    var protocolMode by remember { mutableStateOf(defaultSettings.defaultProtocolMode) }
    var browserProfile by remember { mutableStateOf(defaultSettings.defaultBrowserProfile) }
    var cellularCapBytes by remember { mutableLongStateOf(defaultSettings.maxCellularBytesPerSession) }
    var safDestinationUri by remember { mutableStateOf<Uri?>(null) }

    var zipProbing by remember { mutableStateOf(false) }
    var zipEntries by remember { mutableStateOf<List<RemoteZipEntry>>(emptyList()) }
    var zipError by remember { mutableStateOf<String?>(null) }
    var selectedZipEntry by remember { mutableStateOf<RemoteZipEntry?>(null) }

    val isAdaptiveManifest = remember(urlText) {
        val clean = urlText.substringBefore('?').lowercase()
        clean.endsWith(".m3u8") || clean.endsWith(".mpd")
    }
    val isRemoteZipUrl = remember(urlText) {
        urlText.substringBefore('?').lowercase().endsWith(".zip")
    }

    val safCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) safDestinationUri = uri
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 12.dp, vertical = 18.dp)
            .testTag("new_download_sheet"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey("fab_new_download", SharedPart.FAB_NEW_DOWNLOAD),
                    clipShape = RoundedCornerShape(24.dp),
                    zIndexInOverlay = 3f,
                )
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .aetherNeonBorder(
                    neon = neon,
                    accent = ext.cyan,
                    cornerRadius = 24.dp,
                    borderWidth = 1.2.dp,
                    fallbackBorderColor = ext.cyan.copy(alpha = 0.48f),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .animateContentSize(aetherSpringSpec(motionSpeed))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = ext.cyan,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Launch Zero-Merge Pipeline",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_new_download_sheet_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close new download panel", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    selectedZipEntry = null
                    zipEntries = emptyList()
                    zipError = null
                },
                label = { Text("Primary HTTP / HTTPS / HLS / DASH / .ZIP URL") },
                placeholder = { Text("https://example.com/archive.zip") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_url_input"),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                            if (!clip.isNullOrBlank()) urlText = clip
                        },
                        modifier = Modifier.testTag("paste_url_button"),
                    ) {
                        Icon(imageVector = Icons.Filled.ContentPaste, contentDescription = "Paste URL")
                    }
                },
            )

            AnimatedVisibility(visible = isAdaptiveManifest) {
                StatusPill(label = "HLS/DASH Parallel Segment Extractor Armed", color = ext.cyan)
            }

            // Remote .ZIP EOCD 64 KB Selective Preview Panel
            AnimatedVisibility(visible = isRemoteZipUrl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ext.violet.copy(alpha = 0.10f))
                        .border(1.dp, ext.violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, tint = ext.violet, modifier = Modifier.size(18.dp))
                            Text("Remote .ZIP Selective Extractor", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                        OutlinedButton(
                            onClick = {
                                zipProbing = true
                                zipError = null
                                scope.launch {
                                    val res = RemoteZipInspector.inspectRemoteZip(urlText)
                                    zipProbing = false
                                    res.onSuccess { zipEntries = it }
                                        .onFailure { zipError = it.message ?: "EOCD probe failed" }
                                }
                            },
                            enabled = !zipProbing,
                            modifier = Modifier.testTag("probe_remote_zip_button"),
                        ) {
                            Text(if (zipProbing) "Reading EOCD…" else "Preview Contents", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (zipProbing) {
                        SkeletonPropertyRow()
                        SkeletonPropertyRow()
                        SkeletonPropertyRow()
                    }
                    zipError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = ext.rose)
                    }
                    if (zipEntries.isNotEmpty()) {
                        Text(
                            text = "Tap any file to extract ONLY that file via HTTP Range (or leave unselected for full ZIP):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        zipEntries.take(8).forEach { entry ->
                            val picked = selectedZipEntry == entry
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (picked) ext.emerald.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
                                    .border(1.dp, if (picked) ext.emerald else ext.cardBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedZipEntry = if (picked) null else entry
                                        if (!picked) fileNameText = entry.fileName.substringAfterLast('/')
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = Formatters.formatBytes(entry.uncompressedSize),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (picked) ext.emerald else ext.cyan,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = mirrorUrlsText,
                onValueChange = { mirrorUrlsText = it },
                label = { Text("Mirror URLs (optional, one per line for Wi-Fi vs 5G bonding)") },
                placeholder = { Text("https://mirror2.example.com/file.bin") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "QUICK REAL-STREAM ENDPOINTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CuratedEndpoints.samples.forEach { sample ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, ext.cardBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                urlText = sample.url
                                fileNameText = sample.fileName
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sample.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            Text(sample.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(sample.approximateSizeLabel, style = MaterialTheme.typography.labelMedium, color = ext.cyan)
                    }
                }
            }

            OutlinedTextField(
                value = fileNameText,
                onValueChange = { fileNameText = it },
                label = { Text("Output Filename (optional)") },
                placeholder = { Text("Auto-detected from RFC 6266 or URL") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_filename_input"),
            )

            OutlinedTextField(
                value = expectedSha256,
                onValueChange = { expectedSha256 = it },
                label = { Text("Expected SHA-256 Checksum (optional)") },
                placeholder = { Text("64-char hex digest for S3 verification") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "ENGINE EXECUTION BACKEND (A/B BENCHMARK)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = backendOverride == null,
                    onClick = { backendOverride = null },
                    label = { Text("Global (${defaultSettings.preferredEngineBackend.badge})") },
                )
                FilterChip(
                    selected = backendOverride == EngineBackendPreference.FORCE_RUST_NATIVE,
                    onClick = { backendOverride = EngineBackendPreference.FORCE_RUST_NATIVE },
                    enabled = defaultSettings.enableRustEngine,
                    label = { Text("Force Rust Native") },
                )
                FilterChip(
                    selected = backendOverride == EngineBackendPreference.FORCE_KOTLIN_OKHTTP,
                    onClick = { backendOverride = EngineBackendPreference.FORCE_KOTLIN_OKHTTP },
                    enabled = defaultSettings.enableKotlinOkHttpEngine,
                    label = { Text("Force Kotlin OkHttp") },
                )
                FilterChip(
                    selected = backendOverride == EngineBackendPreference.FORCE_ARIA2_NEXT,
                    onClick = { backendOverride = EngineBackendPreference.FORCE_ARIA2_NEXT },
                    enabled = defaultSettings.enableAria2NextEngine,
                    label = { Text("Force aria2-next") },
                )
            }

            Text(
                text = "PROTOCOL & TLS FINGERPRINT PROFILE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProtocolMode.entries.forEach { pm ->
                    FilterChip(
                        selected = protocolMode == pm,
                        onClick = { protocolMode = pm },
                        label = { Text(pm.label) },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrowserProfile.entries.forEach { bp ->
                    FilterChip(
                        selected = browserProfile == bp,
                        onClick = { browserProfile = bp },
                        label = { Text(bp.label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TRANSPORT & LINK POLICY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (networkMatrix.activeValidatedLinks.isNotEmpty()) {
                        String.format(
                            "%d/4 links · %.1f Mbps est.",
                            networkMatrix.activeValidatedLinks.size,
                            networkMatrix.aggregateEstimatedMbps,
                        )
                    } else "No validated links",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.emerald,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetworkRouteMode.entries.forEach { mode ->
                    FilterChip(
                        selected = routeMode == mode,
                        onClick = { routeMode = mode },
                        label = { Text(mode.label) },
                    )
                }
            }

            Text(
                text = "PER-SESSION CELLULAR DATA CAP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    0L to "Unlimited",
                    250L * 1024L * 1024L to "250 MB",
                    1024L * 1024L * 1024L to "1 GB",
                    5L * 1024L * 1024L * 1024L to "5 GB",
                ).forEach { (bytes, label) ->
                    FilterChip(
                        selected = cellularCapBytes == bytes,
                        onClick = { cellularCapBytes = bytes },
                        label = { Text(label) },
                    )
                }
            }

            // USER REQUEST: no manual stream slider — concurrency is fully
            // automatic. The governor ramps up to the 128 hard ceiling and sheds
            // lanes the moment goodput stops improving.
            Column {
                Text(
                    text = "Auto-Adaptive Streams — up to $MAX_CONCURRENT_WORKERS_CAP lanes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Fully dynamic · governed by live goodput, RTT, thermal and link health",
                    style = MaterialTheme.typography.labelMedium,
                    color = ext.emerald,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val suggested = fileNameText.ifBlank {
                            urlText.substringBefore('?').substringAfterLast('/').ifBlank { "aether_download.bin" }
                        }
                        safCreateLauncher.launch(suggested)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (safDestinationUri != null) "SAF Target Locked" else "Pick SAF File…", color = MaterialTheme.colorScheme.onSurface)
                }
                if (safDestinationUri != null) {
                    OutlinedButton(onClick = { safDestinationUri = null }) {
                        Text("Use Vault", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Button(
                onClick = {
                    val mirrors = mirrorUrlsText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    onLaunchDownload(
                        NewDownloadRequest(
                            url = urlText.trim(),
                            mirrorUrls = mirrors,
                            customFileName = fileNameText.trim().takeIf { it.isNotBlank() },
                            expectedSha256 = expectedSha256.trim().takeIf { it.isNotBlank() },
                            routeMode = routeMode,
                            workers = workerCount.roundToInt(),
                            safUriString = safDestinationUri?.toString(),
                            backendOverride = backendOverride,
                            protocolMode = protocolMode,
                            browserProfile = browserProfile,
                            maxCellularBytes = cellularCapBytes,
                            selectedZipEntry = selectedZipEntry,
                        ),
                    )
                },
                enabled = urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ext.cyan,
                    contentColor = contrastOnColor(ext.cyan),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_download_button"),
            ) {
                Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedZipEntry != null) "Extract Selected File from .ZIP" else "Arm Pipeline & Download",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
