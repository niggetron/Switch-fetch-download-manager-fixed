package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SegmentState
import com.example.ui.theme.LocalAetherColors
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

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Subtle horizontal baseline grid
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
        val maxVal = (samples.maxOrNull() ?: 1L).coerceAtLeast(1024L * 128L) // floor 1 Mbps scale
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

            // Concentric guide octagons
            listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { ring ->
                val ringPath = Path()
                for (i in 0 until count) {
                    val angle = -PI.toFloat() / 2f + i * angleStep
                    val x = cx + cos(angle) * radius * ring
                    val y = cy + sin(angle) * radius * ring
                    if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
                }
                ringPath.close()
                drawPath(
                    path = ringPath,
                    color = gridColor,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // Spokes
            for (i in 0 until count) {
                val angle = -PI.toFloat() / 2f + i * angleStep
                val x = cx + cos(angle) * radius
                val y = cy + sin(angle) * radius
                drawLine(
                    color = gridColor,
                    start = Offset(cx, cy),
                    end = Offset(x, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Data polygon
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
            drawPath(
                path = dataPath,
                color = polygonStroke,
                style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round),
            )

            // Vertices
            axes.forEachIndexed { i, axis ->
                val angle = -PI.toFloat() / 2f + i * angleStep
                val mag = (axis.scorePercent / 100f).coerceIn(0.05f, 1f)
                val x = cx + cos(angle) * radius * mag
                val y = cy + sin(angle) * radius * mag
                drawCircle(
                    color = ext.cyan,
                    radius = 3.5.dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }

        // Compact legend grid around/below
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
