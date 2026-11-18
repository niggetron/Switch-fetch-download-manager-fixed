package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.engine.ArchitectureDecision
import com.example.engine.DiagnosticsCenter
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle

@Composable
fun EngineSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ext = LocalAetherColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = ext.cyan,
        )
        content()
    }
}

@Composable
fun EngineToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun EngineLayerRow(
    title: String,
    detail: String,
    color: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = color)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OsSurvivalAndDiagnosticsCard(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    isIgnoringBatteryOpt: Boolean,
    fontLicenseAudit: String,
    diagnosticsPreview: String,
    onOpenBatterySettings: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    val ext = LocalAetherColors.current

    EngineSectionCard(title = "OS SURVIVAL · HYPEROS 3 & REDACTED DIAGNOSTICS") {
        if (!isIgnoringBatteryOpt) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ext.amber.copy(alpha = 0.12f))
                    .border(1.dp, ext.amber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = ext.amber, modifier = Modifier.size(18.dp))
                    Text(
                        text = "HyperOS / Android Background Throttling Active",
                        style = MaterialTheme.typography.titleSmall,
                        color = ext.amber,
                    )
                }
                Text(
                    text = "Grant 'Unrestricted' battery optimization so long multi-GB transfers avoid aggressive OEM sleep & dataSync kill.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenBatterySettings,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("battery_exemption_button"),
                ) {
                    Text("Configure Battery Exemption")
                }
            }
        } else {
            StatusPill(label = "Battery Optimization: Unrestricted", color = ext.emerald)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPill(label = "Data Saver: ${networkMatrix.dataSaverLabel}", color = ext.cyan)
            StatusPill(label = "Socket Block Reason: ${telemetry.blockedReasonLabel}", color = if (telemetry.blockedReasonLabel == "None") ext.emerald else ext.rose)
            telemetry.cpuHeadroomPercent?.let { pct ->
                StatusPill(label = "API 36 CPU Headroom: $pct%", color = if (pct > 30) ext.emerald else ext.amber)
            }
            StatusPill(label = fontLicenseAudit, color = ext.violet)
        }

        val tail = diagnosticsPreview.lines().takeLast(6).joinToString("\n")
        if (tail.isNotBlank()) {
            Text(
                text = tail,
                style = MonoMetricStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(10.dp),
            )
        }

        OutlinedButton(
            onClick = onCopyDiagnostics,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("copy_diagnostics_button"),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("  Copy Redacted Diagnostics Report")
        }
    }
}

@Composable
fun ArchitectureDecisionLogCard(
    decisions: List<ArchitectureDecision> = DiagnosticsCenter.decisionLog,
) {
    val ext = LocalAetherColors.current
    EngineSectionCard(title = "ARCHITECTURE DECISION LOG (AUDIT TRAIL)") {
        decisions.forEach { adr ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = ext.emerald, modifier = Modifier.size(14.dp))
                        Text("${adr.id} · ${adr.title}", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(adr.date, style = MaterialTheme.typography.labelSmall, color = ext.cyan)
                }
                Text(
                    text = adr.rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
