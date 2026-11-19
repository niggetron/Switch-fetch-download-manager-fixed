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
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BrowserProfile
import com.example.data.model.CuratedEndpoints
import com.example.data.model.EngineBackendPreference
import com.example.data.model.EngineSettings
import com.example.data.model.NetworkRouteMode
import com.example.data.model.NewDownloadRequest
import com.example.data.model.ProtocolMode
import com.example.engine.NetworkMatrixState
import com.example.ui.components.StatusPill
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import kotlin.math.roundToInt

/**
 * In-tree Container Transform Overlay for Launching a Zero-Merge Pipeline.
 * Morphs directly from the FloatingActionButton (`FAB_NEW_DOWNLOAD`) via official
 * Jetpack Compose `sharedBounds` inside the root `SharedTransitionLayout`.
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
    val motionSpeed = LocalMotionSpeedMultiplier.current

    BackHandler(onBack = onDismiss)

    var urlText by remember { mutableStateOf("") }
    var mirrorUrlsText by remember { mutableStateOf("") }
    var fileNameText by remember { mutableStateOf("") }
    var expectedSha256 by remember { mutableStateOf("") }
    var routeMode by remember { mutableStateOf(defaultSettings.defaultRouteMode) }
    var workerCount by remember { mutableFloatStateOf(defaultSettings.maxConcurrentWorkers.toFloat()) }
    var backendOverride by remember { mutableStateOf<EngineBackendPreference?>(null) }
    var protocolMode by remember { mutableStateOf(defaultSettings.defaultProtocolMode) }
    var browserProfile by remember { mutableStateOf(defaultSettings.defaultBrowserProfile) }
    var cellularCapBytes by remember { mutableLongStateOf(defaultSettings.maxCellularBytesPerSession) }
    var safDestinationUri by remember { mutableStateOf<Uri?>(null) }

    val isAdaptiveManifest = remember(urlText) {
        val clean = urlText.substringBefore('?').lowercase()
        clean.endsWith(".m3u8") || clean.endsWith(".mpd")
    }

    val safCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) safDestinationUri = uri
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
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
                .border(1.2.dp, ext.cyan.copy(alpha = 0.48f), RoundedCornerShape(24.dp))
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
                    Text("Launch Zero-Merge Pipeline", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_new_download_sheet_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close new download panel")
                }
            }

            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("Primary HTTP / HTTPS / HLS / DASH URL") },
                placeholder = { Text("https://example.com/archive.tar.gz") },
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
                StatusPill(label = "HLS/DASH Adaptive Segment Extractor Armed", color = ext.cyan)
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
                            Text(sample.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

            // Engine Backend A/B Selector
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
            }

            // Protocol & TLS Fingerprint Profile
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

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Adaptive BDP Segment Workers", style = MaterialTheme.typography.titleSmall)
                    Text("${workerCount.roundToInt()} parallel streams", style = MaterialTheme.typography.labelMedium, color = ext.emerald)
                }
                Slider(value = workerCount, onValueChange = { workerCount = it }, valueRange = 1f..16f, steps = 14)
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
                    Text(if (safDestinationUri != null) "SAF Target Locked" else "Pick SAF File…")
                }
                if (safDestinationUri != null) {
                    OutlinedButton(onClick = { safDestinationUri = null }) { Text("Use Vault") }
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
                        )
                    )
                },
                enabled = urlText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_download_button"),
            ) {
                Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Arm Pipeline & Download")
            }
        }
    }
}
