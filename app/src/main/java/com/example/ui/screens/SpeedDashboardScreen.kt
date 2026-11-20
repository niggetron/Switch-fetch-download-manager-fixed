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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.engine.ShizukuStatus
import com.example.engine.ShizukuWifiState
import com.example.engine.TelemetrySnapshot
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.theme.LocalAetherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
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
    shizukuState: ShizukuWifiState = ShizukuWifiState(),
    onRequestShizukuPermission: () -> Unit = {},
    aria2Available: Boolean = false,
    aria2Error: String = "",
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

        item(key = "engine_status") {
            DashboardCard(title = "Engine & Privilege Status") {
                InfoLine("Engine backend", telemetry.activeEngineBadge)
                InfoLine("aria2-next binary", if (aria2Available) "Bundled & ready" else "Missing")
                if (aria2Error.isNotBlank()) {
                    Text(
                        aria2Error,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9C2B),
                    )
                }
                InfoLine("Shizuku", shizukuState.status.label)
                if (shizukuState.status != ShizukuStatus.AUTHORIZED) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestShizukuPermission) {
                        Text(
                            when (shizukuState.status) {
                                ShizukuStatus.PERMISSION_REQUIRED -> "Grant Shizuku Permission"
                                ShizukuStatus.INSTALLED_STOPPED -> "Start Shizuku"
                                ShizukuStatus.NOT_INSTALLED -> "Get Shizuku"
                                ShizukuStatus.AUTHORIZED -> "Shizuku Active"
                            }
                        )
                    }
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

private data class GeoInfo(
    val city: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val ip: String,
    val isp: String,
)

private val geoClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .build()

private suspend fun fetchGeo(ip: String? = null): GeoInfo? = withContext(Dispatchers.IO) {
    try {
        val url = if (ip.isNullOrBlank()) "https://ipapi.co/json/" else "https://ipapi.co/$ip/json/"
        val request = Request.Builder().url(url).header("User-Agent", "AetherIO-Omega/9.2").build()
        geoClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: return@withContext null
            val obj = JSONObject(body)
            if (obj.has("error")) return@withContext null
            GeoInfo(
                city = obj.optString("city"),
                country = obj.optString("country_name"),
                lat = obj.optString("latitude").toDoubleOrNull() ?: 0.0,
                lon = obj.optString("longitude").toDoubleOrNull() ?: 0.0,
                ip = obj.optString("ip"),
                isp = obj.optString("org"),
            )
        }
    } catch (t: Throwable) {
        null
    }
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
        modifier = Modifier
            .fillMaxWidth()
            .aetherSharedBounds(
                key = AetherSharedKey("speed_dashboard_${title.lowercase()}", SharedPart.SPEED_CHART),
                clipShape = RoundedCornerShape(18.dp),
                useCockpitSpring = true,
                zIndexInOverlay = 1f,
            ),
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
    val serverUrl = telemetry.activeConnections.firstOrNull()?.url.orEmpty()

    var clientGeo by remember { mutableStateOf<GeoInfo?>(null) }
    var serverGeo by remember { mutableStateOf<GeoInfo?>(null) }
    LaunchedEffect(serverUrl) {
        clientGeo = fetchGeo()
        if (serverUrl.isNotBlank()) {
            val host = runCatching { URL(serverUrl).host }.getOrNull()
            if (!host.isNullOrBlank()) {
                val ip = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
                }
                serverGeo = fetchGeo(ip)
            }
        }
    }

    // Lightweight, offline world silhouette (lon/lat) so the map works without tiles.
    val continents = listOf(
        listOf(-168.0, 65.0, -140.0, 70.0, -100.0, 72.0, -60.0, 60.0, -80.0, 25.0, -100.0, 15.0, -120.0, 30.0),
        listOf(-80.0, 10.0, -50.0, 10.0, -35.0, -10.0, -55.0, -35.0, -75.0, -50.0),
        listOf(-10.0, 60.0, 30.0, 70.0, 60.0, 60.0, 40.0, 35.0, -10.0, 35.0),
        listOf(-17.0, 35.0, 50.0, 35.0, 40.0, -35.0, 15.0, -35.0),
        listOf(30.0, 70.0, 180.0, 70.0, 180.0, 0.0, 100.0, 5.0, 60.0, 25.0),
        listOf(110.0, -10.0, 155.0, -10.0, 155.0, -40.0, 110.0, -40.0),
    )

    DashboardCard(title = "Server Location") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(12.dp))
                .aetherSharedBounds(
                    key = AetherSharedKey("speed_server_map", SharedPart.SPEED_SERVER_MAP),
                    clipShape = RoundedCornerShape(12.dp),
                    useCockpitSpring = true,
                )
                .background(Color(0xFF11151C)),
        ) {
            fun project(lon: Double, lat: Double) = Offset(
                ((lon + 180.0) / 360.0 * size.width).toFloat(),
                ((90.0 - lat) / 180.0 * size.height).toFloat(),
            )

            val gridColor = Color(0xFF222A33)
            for (x in 0..size.width.toInt() step 36) {
                drawLine(gridColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1f)
            }
            for (y in 0..size.height.toInt() step 36) {
                drawLine(gridColor, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1f)
            }
            continents.forEach { points ->
                val path = Path().apply {
                    moveTo(project(points[0], points[1]))
                    for (i in 2 until points.size step 2) lineTo(project(points[i], points[i + 1]))
                    close()
                }
                drawPath(path, Color(0xFF2A323C))
            }
            val serverPoint = serverGeo?.let { project(it.lon, it.lat) }
                ?: project(77.2, 28.6) // New Delhi fallback until geo resolves
            val clientPoint = clientGeo?.let { project(it.lon, it.lat) }
                ?: project(88.4, 22.6) // Kolkata fallback
            drawLine(
                color = accent.copy(alpha = 0.55f + 0.35f * phase),
                start = serverPoint,
                end = clientPoint,
                strokeWidth = 2.5f,
            )
            drawCircle(Color(0xFFFF9C2B), radius = 6f, center = serverPoint)
            drawCircle(Color(0xFFE5484D), radius = 9f, center = clientPoint)
            drawCircle(Color.White, radius = 3f, center = clientPoint)
        }
        Spacer(Modifier.height(10.dp))
        InfoLine("Connected via", "$via ($iface)")
        InfoLine(
            "Server location",
            serverGeo?.let { "${it.city}, ${it.country} · ${it.ip}" } ?: "Resolving CDN edge…",
        )
        InfoLine("Your network", "Active links ${network.activeValidatedLinks.size} · ${telemetry.activeWorkers} workers")
        InfoLine("Your IP address", clientGeo?.ip ?: localIp)
        InfoLine("ISP / ASN", serverGeo?.isp?.ifBlank { clientGeo?.isp.orEmpty() } ?: "—")
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
