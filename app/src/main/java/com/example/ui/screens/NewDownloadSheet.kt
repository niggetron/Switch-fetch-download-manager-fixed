package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.CuratedEndpoints
import com.example.data.model.EngineSettings
import com.example.data.model.NetworkRouteMode
import com.example.data.model.NewDownloadRequest
import com.example.ui.theme.LocalAetherColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewDownloadSheet(
    defaultSettings: EngineSettings,
    onDismiss: () -> Unit,
    onLaunchDownload: (NewDownloadRequest) -> Unit,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var urlText by remember { mutableStateOf("") }
    var fileNameText by remember { mutableStateOf("") }
    var expectedSha256 by remember { mutableStateOf("") }
    var routeMode by remember { mutableStateOf(defaultSettings.defaultRouteMode) }
    var workerCount by remember { mutableFloatStateOf(defaultSettings.maxConcurrentWorkers.toFloat()) }
    var safDestinationUri by remember { mutableStateOf<Uri?>(null) }

    val safCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) safDestinationUri = uri
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                )
            }

            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("HTTP / HTTPS Download URL") },
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
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = "Paste URL from clipboard",
                        )
                    }
                },
            )

            // Curated Real HTTP Public Diagnostic Streams
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
                            Text(
                                text = sample.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = sample.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = sample.approximateSizeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.cyan,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = fileNameText,
                onValueChange = { fileNameText = it },
                label = { Text("Output Filename (optional)") },
                placeholder = { Text("Auto-detected from Content-Disposition or URL") },
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
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Route Mode Selector
            Text(
                text = "TRANSPORT & LINK POLICY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            // Concurrency Workers Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "2 MB Segment Workers",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${workerCount.roundToInt()} parallel streams",
                        style = MaterialTheme.typography.labelMedium,
                        color = ext.emerald,
                    )
                }
                Slider(
                    value = workerCount,
                    onValueChange = { workerCount = it },
                    valueRange = 1f..8f,
                    steps = 6,
                )
            }

            // Destination Picker (App Vault vs SAF ParcelFileDescriptor)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val suggested = fileNameText.ifBlank {
                            urlText.substringBefore('?').substringAfterLast('/')
                                .ifBlank { "aether_download.bin" }
                        }
                        safCreateLauncher.launch(suggested)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (safDestinationUri != null) "SAF Target Locked" else "Pick SAF File…")
                }
                if (safDestinationUri != null) {
                    OutlinedButton(onClick = { safDestinationUri = null }) {
                        Text("Use App Vault")
                    }
                }
            }
            Text(
                text = if (safDestinationUri != null) {
                    "SAF URI: ${safDestinationUri.toString().take(48)}…"
                } else {
                    "Default destination: App-Managed Downloads Vault (FileProvider ready)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    onLaunchDownload(
                        NewDownloadRequest(
                            url = urlText.trim(),
                            customFileName = fileNameText.trim().takeIf { it.isNotBlank() },
                            expectedSha256 = expectedSha256.trim().takeIf { it.isNotBlank() },
                            routeMode = routeMode,
                            workers = workerCount.roundToInt(),
                            safUriString = safDestinationUri?.toString(),
                        )
                    )
                },
                enabled = urlText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_download_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.RocketLaunch,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Arm 2 MB Pipeline & Download")
            }
        }
    }
}
