package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SegmentState
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ThroughputSparkline(
    historyBps: List<Long>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val gridColor = ext.gridLine
    val lineColor = ext.emerald
    val accentCyan = ext.cyan

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "Aggregate download throughput history sparkline"
        }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val rows = 3
        for (i in 1..rows) {
            val y = h * (i / (rows + 1f))
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val samples = if (historyBps.size >= 2) historyBps else listOf(0L, 0L)
        val maxVal = (samples.maxOrNull() ?: 1L).coerceAtLeast(1024L * 128L)
        val stepX = w / (samples.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()

        samples.forEachIndexed { idx, bps ->
            val x = idx * stepX
            val norm = (bps.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
            val y = h - (norm * (h * 0.86f)) - (h * 0.06f)
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.28f),
                    accentCyan.copy(alpha = 0.04f),
                    Color.Transparent,
                )
            ),
        )
        drawPath(
            path = linePath,
            brush = Brush.horizontalGradient(listOf(accentCyan, lineColor)),
            style = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
fun DualLinkSpeedHistoryChart(
    wifiHistoryBps: List<Long>,
    cellHistoryBps: List<Long>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val gridColor = ext.gridLine
    val wifiColor = ext.cyan
    val cellColor = ext.violet

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .semantics { contentDescription = "Wi-Fi versus 5G Cellular speed history comparison chart" }
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            for (i in 1..3) {
                val y = h * (i / 4f)
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1.dp.toPx())
            }

            val wifi = if (wifiHistoryBps.size >= 2) wifiHistoryBps else listOf(0L, 0L)
            val cell = if (cellHistoryBps.size >= 2) cellHistoryBps else listOf(0L, 0L)
            val maxVal = maxOf(wifi.maxOrNull() ?: 1L, cell.maxOrNull() ?: 1L)
                .coerceAtLeast(1024L * 128L)

            fun drawSeries(samples: List<Long>, strokeColor: Color) {
                val stepX = w / (samples.size - 1).coerceAtLeast(1)
                val path = Path()
                samples.forEachIndexed { idx, bps ->
                    val x = idx * stepX
                    val norm = (bps.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                    val y = h - (norm * (h * 0.85f)) - (h * 0.07f)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            drawSeries(wifi, wifiColor)
            drawSeries(cell, cellColor)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendDot(label = "Wi-Fi (${Formatters.formatSpeedMbps(wifiHistoryBps.lastOrNull() ?: 0L)})", color = wifiColor)
            LegendDot(label = "5G/LTE (${Formatters.formatSpeedMbps(cellHistoryBps.lastOrNull() ?: 0L)})", color = cellColor)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SankeyLinkSplitBar(
    wifiBytes: Long,
    cellBytes: Long,
    ethBytes: Long = 0L,
    wifi2Bytes: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val total = (wifiBytes + wifi2Bytes + cellBytes + ethBytes).coerceAtLeast(1L)
    val wifiFrac = (wifiBytes.toFloat() / total).coerceIn(0f, 1f)
    val wifi2Frac = (wifi2Bytes.toFloat() / total).coerceIn(0f, 1f)
    val cellFrac = (cellBytes.toFloat() / total).coerceIn(0f, 1f)
    val ethFrac = (ethBytes.toFloat() / total).coerceIn(0f, 1f)

    Column(
        modifier = modifier.semantics {
            contentDescription = "Quad-link interface split bar: Wi-Fi 1 ${(wifiFrac * 100).toInt()}%, Wi-Fi 2 ${(wifi2Frac * 100).toInt()}%, 5G ${(cellFrac * 100).toInt()}%, USB ${(ethFrac * 100).toInt()}%"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
        ) {
            val w = size.width
            val h = size.height
            drawRect(color = ext.cardBorder, size = Size(w, h))
            var x = 0f
            if (wifiBytes > 0L) {
                val sw = w * wifiFrac
                drawRect(color = ext.cyan, topLeft = Offset(x, 0f), size = Size(sw, h))
                x += sw
            }
            if (wifi2Bytes > 0L) {
                val w2w = w * wifi2Frac
                drawRect(color = ext.violet, topLeft = Offset(x, 0f), size = Size(w2w, h))
                x += w2w
            }
            if (cellBytes > 0L) {
                val cw = w * cellFrac
                drawRect(color = ext.amber, topLeft = Offset(x, 0f), size = Size(cw, h))
                x += cw
            }
            if (ethBytes > 0L) {
                val ew = w * ethFrac
                drawRect(color = ext.emerald, topLeft = Offset(x, 0f), size = Size(ew, h))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LegendDot("Wi-Fi 1 ${(wifiFrac * 100).toInt()}% (${Formatters.formatBytes(wifiBytes)})", ext.cyan)
            if (wifi2Bytes > 0L) {
                LegendDot("Wi-Fi 2 ${(wifi2Frac * 100).toInt()}% (${Formatters.formatBytes(wifi2Bytes)})", ext.violet)
            }
            LegendDot("5G ${(cellFrac * 100).toInt()}% (${Formatters.formatBytes(cellBytes)})", ext.amber)
            if (ethBytes > 0L) {
                LegendDot("USB ${(ethFrac * 100).toInt()}% (${Formatters.formatBytes(ethBytes)})", ext.emerald)
            }
        }
    }
}

@Composable
fun BdpConcurrencyHeatmap(
    currentWorkers: Int,
    lastRttMs: Long,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val rttBuckets = listOf("<=20ms", "40ms", "80ms", "120ms+")
    val workerCols = listOf(2, 4, 8, 12, 16)
    val activeRttRow = when {
        lastRttMs <= 25L -> 0
        lastRttMs <= 55L -> 1
        lastRttMs <= 100L -> 2
        else -> 3
    }
    val activeCol = workerCols.indexOfFirst { it >= currentWorkers }.let { if (it < 0) 4 else it }

    Column(
        modifier = modifier.semantics {
            contentDescription = "2D BDP concurrency heatmap across workers and RTT latency"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        workerCols.let { cols ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.width(54.dp))
                cols.forEach { w ->
                    Text(
                        text = "${w}W",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        rttBuckets.forEachIndexed { rIdx, rLabel ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(54.dp),
                )
                workerCols.forEachIndexed { cIdx, _ ->
                    val score = ((4 - rIdx) * 0.16f + (cIdx + 1) * 0.12f).coerceIn(0.15f, 1.0f)
                    val isSelected = rIdx == activeRttRow && cIdx == activeCol
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(ext.emerald.copy(alpha = 0.15f + score * 0.55f))
                            .then(
                                if (isSelected) {
                                    Modifier.border(1.5.dp, ext.cyan, RoundedCornerShape(4.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Text("●", style = MaterialTheme.typography.labelSmall, color = ext.cyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RooflineParetoChart(
    goodputBps: Long,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val currentMbps = (goodputBps * 8.0) / 1_000_000.0
    Column(
        modifier = modifier.semantics {
            contentDescription = "Log-log operational intensity roofline performance model"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val kneeX = w * 0.42f
            val roofY = h * 0.18f

            val roofPath = Path().apply {
                moveTo(0f, h * 0.88f)
                lineTo(kneeX, roofY)
                lineTo(w, roofY)
            }
            drawPath(
                path = roofPath,
                color = ext.amber,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            val opX = (w * 0.65f)
            val opNorm = (currentMbps / 1000.0).toFloat().coerceIn(0.05f, 1.0f)
            val opY = (h * 0.88f) - opNorm * (h * 0.70f)
            drawCircle(color = ext.emerald, radius = 5.dp.toPx(), center = Offset(opX, opY))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Quad-Link Storage/CPU Roofline (1000 Mbps)", style = MaterialTheme.typography.labelSmall, color = ext.amber)
            Text(String.format("Now: %.1f Mbps", currentMbps), style = MonoMetricStyle, color = ext.emerald)
        }
    }
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SegmentBitfieldMatrix(
    segments: List<SegmentState>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val pendingColor = MaterialTheme.colorScheme.surfaceVariant
    val committedColor = ext.cyan
    val verifiedColor = ext.emerald
    val wifiColor = Color(0xFF38BDF8)
    val cell5gColor = ext.violet
    val hedgeColor = ext.amber
    val failColor = ext.rose

    val displaySegments = if (segments.isEmpty()) listOf(SegmentState.PENDING) else segments
    val cols = when {
        displaySegments.size <= 8 -> displaySegments.size.coerceAtLeast(4)
        displaySegments.size <= 24 -> 12
        else -> 16
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    when {
                        displaySegments.size <= cols -> 12.dp
                        displaySegments.size <= cols * 2 -> 24.dp
                        else -> 36.dp
                    }
                )
        ) {
            val gap = 3.dp.toPx()
            val cellW = ((size.width - gap * (cols - 1)) / cols).coerceAtLeast(4f)
            val rows = ((displaySegments.size + cols - 1) / cols).coerceAtLeast(1)
            val cellH = ((size.height - gap * (rows - 1)) / rows).coerceAtLeast(4f)
            val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            displaySegments.take(cols * 4).forEachIndexed { idx, state ->
                val r = idx / cols
                val c = idx % cols
                val x = c * (cellW + gap)
                val y = r * (cellH + gap)
                val color = when (state) {
                    SegmentState.PENDING -> pendingColor
                    SegmentState.COMMITTED -> committedColor
                    SegmentState.VERIFIED -> verifiedColor
                    SegmentState.ACTIVE_WIFI -> wifiColor
                    SegmentState.ACTIVE_5G -> cell5gColor
                    SegmentState.STOLEN_HEDGE -> hedgeColor
                    SegmentState.FAILED -> failColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(cellW, cellH),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

data class RadarAxis(
    val label: String,
    val scorePercent: Float,
)

@Composable
fun EightAxisRadarChart(
    axes: List<RadarAxis>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val gridColor = ext.cardBorder
    val polygonStroke = ext.emerald
    val polygonFill = ext.emerald.copy(alpha = 0.22f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .padding(18.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = min(cx, cy)
            val count = axes.size.coerceAtLeast(3)
            val angleStep = (2.0 * PI / count).toFloat()

            listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { ring ->
                val ringPath = Path()
                for (i in 0 until count) {
                    val angle = -PI.toFloat() / 2f + i * angleStep
                    val x = cx + cos(angle) * radius * ring
                    val y = cy + sin(angle) * radius * ring
                    if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
                }
                ringPath.close()
                drawPath(path = ringPath, color = gridColor, style = Stroke(width = 1.dp.toPx()))
            }

            for (i in 0 until count) {
                val angle = -PI.toFloat() / 2f + i * angleStep
                val x = cx + cos(angle) * radius
                val y = cy + sin(angle) * radius
                drawLine(color = gridColor, start = Offset(cx, cy), end = Offset(x, y), strokeWidth = 1.dp.toPx())
            }

            val dataPath = Path()
            axes.forEachIndexed { i, axis ->
                val angle = -PI.toFloat() / 2f + i * angleStep
                val mag = (axis.scorePercent / 100f).coerceIn(0.05f, 1f)
                val x = cx + cos(angle) * radius * mag
                val y = cy + sin(angle) * radius * mag
                if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
            }
            dataPath.close()
            drawPath(path = dataPath, color = polygonFill)
            drawPath(path = dataPath, color = polygonStroke, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))

            axes.forEachIndexed { i, axis ->
                val angle = -PI.toFloat() / 2f + i * angleStep
                val mag = (axis.scorePercent / 100f).coerceIn(0.05f, 1f)
                val x = cx + cos(angle) * radius * mag
                val y = cy + sin(angle) * radius * mag
                drawCircle(color = ext.cyan, radius = 3.5.dp.toPx(), center = Offset(x, y))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 216.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            axes.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    pair.forEach { axis ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(ext.emerald)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${axis.label} ${axis.scorePercent.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = labelColor,
                            )
                        }
                    }
                }
            }
        }
    }
}
