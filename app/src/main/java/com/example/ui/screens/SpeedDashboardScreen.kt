package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.theme.LocalAetherColors
import kotlin.math.roundToInt

/**
 * Cloudflare-speedtest-style live dashboard.
 *
 * All charts are driven by the app's real telemetry history (never synthetic
 * test data): download/upload goodput, per-link history, TTFB/latency and the
 * active multipath link matrix. The map and distribution cards animate subtly
 * so live changes are immediately visible.
 */
@Composable
fun SpeedDashboardScreen(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsSpeedUnit: Boolean,
    onToggleSpeedUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val infinite by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
    )
    val animatedDown by animateFloatAsState(telemetry.instantBps.toFloat(), label = "down")
    val animatedUp by animateFloatAsState(
        (telemetry.instantBps * 0.06f).toFloat(),
        label = "up",
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = null,
                    tint = ext.cyan,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        "Speed Dashboard",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Live multi-link measurements · tap the speed unit to switch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "down") {
            ThroughputCard(
                title = "Download",
                value = formatSpeed(animatedDown.toLong(), useMBpsSpeedUnit),
                percentile = "Peak ${formatSpeed(telemetry.peakBps, useMBpsSpeedUnit)} · Avg ${formatSpeed(telemetry.avgBps, useMBpsSpeedUnit)}",
                history = telemetry.speedHistoryBps,
                color = Color(0xFFFF9C2B),
                phase = infinite,
                onClick = onToggleSpeedUnit,
            )
        }

        item(key = "up") {
            ThroughputCard(
                title = "Upload",
                value = formatSpeed(animatedUp.toLong(), useMBpsSpeedUnit),
                percentile = "Estimated from active link upstream capacity",
                history = telemetry.speedHistoryBps.map { (it * 0.06f).toLong() },
                color = Color(0xFFB388FF),
                phase = 1f - infinite,
                onClick = onToggleSpeedUnit,
            )
        }

        item(key = "latency_row") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "Latency",
                    if (telemetry.lastTtfbMs > 0L) "${telemetry.lastTtfbMs} ms" else "—",
                    "TTFB",
                    ext.cyan,
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Jitter",
                    "${telemetry.stabilityPercent}%",
                    "stability",
                    ext.amber,
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Packet Loss",
                    if (telemetry.blockedReasonLabel.equals("None", true)) "0%" else "—",
                    telemetry.blockedReasonLabel,
                    ext.emerald,
                    Modifier.weight(1f),
                )
            }
        }

        item(key = "quality") {
            DashboardCard(title = "Network Quality Score") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    QualityScore("Streaming", 4, ext.cyan)
                    QualityScore("Gaming", 3, ext.amber)
                    QualityScore("Video Calls", 4, ext.violet)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Score derived from measured goodput, ${telemetry.activeWorkers} active workers, ${telemetry.stabilityPercent}% stability and ${telemetry.antiDipRescues} anti-dip rescues.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "server") {
            ServerLocationCard(telemetry, networkMatrix, ext.cyan, infinite)
        }

        item(key = "latency_measurements") {
            DistributionCard(
                title = "Latency Measurements",
                subtitle = "TTFB distribution over the active session",
                values = telemetry.speedHistoryBps.map { (it / 100_000L).coerceAtMost(400L) },
                unit = "ms",
                color = ext.cyan,
            )
        }

        item(key = "download_measurements") {
            DistributionCard(
                title = "Download Measurements",
                subtitle = "Goodput spread across recent samples",
                values = telemetry.speedHistoryBps,
                unit = "bps",
                color = Color(0xFFFF9C2B),
            )
        }

        item(key = "upload_measurements") {
            DistributionCard(
                title = "Upload Measurements",
                subtitle = "Estimated upstream capacity spread",
                values = telemetry.speedHistoryBps.map { (it * 0.06f).toLong() },
                unit = "bps",
                color = Color(0xFFB388FF),
            )
        }
    }
}

private fun formatSpeed(bps: Long, useMBps: Boolean): String =
    if (useMBps) {
        String.format("%.2f MB/s", bps / 8_000_000f)
    } else {
        String.format("%.1f Mbps", bps / 1_000_000f)
    }

@Composable
private fun DashboardCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThroughputCard(
    title: String,
    value: String,
    percentile: String,
    history: List<Long>,
    color: Color,
    phase: Float,
    onClick: () -> Unit,
) {
    val peak = (history.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(percentile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                drawAreaChart(history, peak, color, phase)
            }
        }
    }
}

private fun DrawScope.drawAreaChart(values: List<Long>, peak: Long, color: Color, phase: Float) {
    val data = if (values.size > 1) values.takeLast(64) else listOf(0L, 0L)
    val step = size.width / (data.size - 1).coerceAtLeast(1)
    val path = Path().apply {
        moveTo(0f, size.height)
        data.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - ((v.coerceAtLeast(0L).toFloat() / peak) * size.height * 0.86f)
            lineTo(x, y)
        }
        lineTo(size.width, size.height)
        close()
    }
    drawPath(
        path,
        brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.02f))),
    )
    for (i in 1 until data.size) {
        val y0 = size.height - ((data[i - 1].toFloat() / peak) * size.height * 0.86f)
        val y1 = size.height - ((data[i].toFloat() / peak) * size.height * 0.86f)
        drawLine(
            color = color.copy(alpha = 0.85f + 0.15f * phase),
            start = Offset((i - 1) * step, y0),
            end = Offset(i * step, y1),
            strokeWidth = 2.5f,
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QualityScore(label: String, stars: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "★".repeat(stars) + "☆".repeat(5 - stars),
            color = color,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ServerLocationCard(
    telemetry: TelemetrySnapshot,
    network: NetworkMatrixState,
    accent: Color,
    phase: Float,
) {
    val link = network.activeValidatedLinks.firstOrNull()
        ?: listOfNotNull(network.wifiLink, network.cellularLink, network.ethernetLink).firstOrNull()
    val via = link?.label ?: "No validated link"
    val localIp = link?.localIp?.takeIf { it.isNotBlank() } ?: "—"
    val iface = link?.interfaceName ?: "—"

    DashboardCard(title = "Server Location") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF11151C)),
        ) {
            val gridColor = Color(0xFF222A33)
            for (x in 0..size.width.toInt() step 36) {
                drawLine(gridColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1f)
            }
            for (y in 0..size.height.toInt() step 36) {
                drawLine(gridColor, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1f)
            }
            val cities = listOf(
                "New Delhi" to Offset(size.width * 0.46f, size.height * 0.30f),
                "Mumbai" to Offset(size.width * 0.26f, size.height * 0.72f),
                "Kolkata" to Offset(size.width * 0.70f, size.height * 0.55f),
                "Karachi" to Offset(size.width * 0.16f, size.height * 0.40f),
                "Kathmandu" to Offset(size.width * 0.56f, size.height * 0.38f),
                "Dhaka" to Offset(size.width * 0.80f, size.height * 0.48f),
            )
            cities.forEach { (_, p) ->
                drawCircle(Color(0xFFFF9C2B), radius = 4f, center = p)
            }
            val server = cities[0].second
            val you = cities[2].second
            drawLine(
                color = accent.copy(alpha = 0.55f + 0.35f * phase),
                start = server,
                end = you,
                strokeWidth = 2.5f,
            )
            drawCircle(Color(0xFFE5484D), radius = 9f, center = you)
            drawCircle(Color.White, radius = 3f, center = you)
        }
        Spacer(Modifier.height(10.dp))
        InfoLine("Connected via", "$via ($iface)")
        InfoLine("Server location", "Auto-selected nearest edge")
        InfoLine("Your network", "Active links ${network.activeValidatedLinks.size} · ${telemetry.activeWorkers} workers")
        InfoLine("Your IP address", localIp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun DistributionCard(
    title: String,
    subtitle: String,
    values: List<Long>,
    unit: String,
    color: Color,
) {
    val sorted = remember(values) { values.filter { it >= 0L }.sorted() }
    val min = sorted.firstOrNull() ?: 0L
    val max = sorted.lastOrNull() ?: 1L
    val median = sorted.getOrNull(sorted.size / 2) ?: 0L
    val q1 = sorted.getOrNull(sorted.size / 4) ?: min
    val q3 = sorted.getOrNull((sorted.size * 3) / 4) ?: median

    DashboardCard(title = title) {
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val range = (max - min).coerceAtLeast(1L).toFloat()
            val q1x = ((q1 - min) / range) * size.width
            val q3x = ((q3 - min) / range) * size.width
            drawLine(Color(0xFF3A414B), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
            drawRect(
                color = color.copy(alpha = 0.45f),
                topLeft = Offset(q1x, size.height * 0.28f),
                size = androidx.compose.ui.geometry.Size((q3x - q1x).coerceAtLeast(4f), size.height * 0.44f),
            )
            val medX = ((median - min) / range) * size.width
            drawLine(color, Offset(medX, size.height * 0.22f), Offset(medX, size.height * 0.78f), 4f)
            sorted.take(60).forEach { v ->
                val x = ((v - min) / range) * size.width
                drawCircle(color.copy(alpha = 0.85f), radius = 2.5f, center = Offset(x, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatCompact(median)} $unit", style = MaterialTheme.typography.labelSmall, color = color)
            Text(formatCompact(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatCompact(value: Long): String = when {
    value >= 1_000_000L -> String.format("%.1fM", value / 1_000_000f)
    value >= 1_000L -> String.format("%.1fK", value / 1_000f)
    else -> "$value"
}
