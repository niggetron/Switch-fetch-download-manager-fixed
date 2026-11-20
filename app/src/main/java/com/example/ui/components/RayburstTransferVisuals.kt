package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SegmentState
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.MonoMetricStyle
import kotlin.math.abs
import kotlin.math.sin

/**
 * Rayburst-inspired "64 connections at once" glowing multi-lane striped progress bar.
 * Each lane represents an active parallel HTTP range stream colored by link, pulsing smoothly
 * while downloading and snapping cleanly to solid emerald when completed.
 */
@Composable
fun Rayburst64LaneBar(
    progress: Float,
    activeConnections: Int,
    maxConnections: Int,
    isCompleted: Boolean,
    isRunning: Boolean,
    isFailed: Boolean,
    segments: List<SegmentState>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val laneCount = maxConnections.coerceIn(8, 64)
    val clampedProgress = progress.coerceIn(0f, 1f)

    val transition = rememberInfiniteTransition(label = "rayburst_64_lane_pulse")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rayburst_wave_phase",
    )

    val trackBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Rayburst $laneCount-lane progress bar: ${(clampedProgress * 100).toInt()} percent, $activeConnections active streams"
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(trackBg),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            if (isCompleted) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(ext.emerald, ext.teal, ext.emerald)),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                )
                return@Canvas
            }

            val gap = if (laneCount > 32) 1.2.dp.toPx() else 1.8.dp.toPx()
            val segW = ((w - gap * (laneCount - 1)) / laneCount).coerceAtLeast(1.5f)
            val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            for (i in 0 until laneCount) {
                val laneFrac = (i + 1).toFloat() / laneCount.toFloat()
                val segState = if (segments.isNotEmpty()) {
                    segments[(i * segments.size) / laneCount]
                } else {
                    null
                }
                val isFilled = laneFrac <= clampedProgress
                val isLeadingEdge = isRunning && abs(laneFrac - clampedProgress) <= (3f / laneCount.toFloat())
                val pulse = 0.65f + 0.35f * sin((wavePhase * 6.28318f) + i * 0.45f)

                val laneColor = when {
                    isFailed && isFilled -> ext.rose
                    segState == SegmentState.VERIFIED -> ext.emerald
                    segState == SegmentState.STOLEN_HEDGE -> ext.amber
                    segState == SegmentState.ACTIVE_5G -> ext.violet
                    segState == SegmentState.ACTIVE_WIFI -> ext.cyan
                    segState == SegmentState.COMMITTED -> ext.teal
                    isLeadingEdge -> ext.neonGlow
                    isFilled -> when (i % 4) {
                        0 -> ext.cyan
                        1 -> ext.emerald
                        2 -> ext.violet
                        else -> ext.amber
                    }
                    isRunning && i < activeConnections -> ext.cyan.copy(alpha = 0.24f + 0.20f * pulse)
                    else -> trackBg
                }

                val alpha = when {
                    isLeadingEdge -> (0.78f + 0.22f * pulse).coerceIn(0f, 1f)
                    isFilled && isRunning -> (0.80f + 0.18f * pulse).coerceIn(0f, 1f)
                    isFilled -> 0.92f
                    else -> 1f
                }

                val x = i * (segW + gap)
                if (neon.enabled && (isLeadingEdge || (isRunning && isFilled && i % 4 == 0))) {
                    drawRoundRect(
                        color = laneColor.copy(alpha = 0.30f * neon.intensity),
                        topLeft = Offset(x - 1f, 0f),
                        size = Size(segW + 2f, h),
                        cornerRadius = corner,
                    )
                }
                drawRoundRect(
                    color = laneColor.copy(alpha = alpha),
                    topLeft = Offset(x, 1.dp.toPx()),
                    size = Size(segW, (h - 2.dp.toPx()).coerceAtLeast(2f)),
                    cornerRadius = corner,
                )
            }

            // Sweeping photon highlight along filled section while actively downloading
            if (isRunning && clampedProgress > 0.02f) {
                val beamX = w * clampedProgress * wavePhase
                val beamW = (w * 0.14f).coerceAtLeast(18f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.38f), Color.Transparent),
                        startX = beamX - beamW / 2f,
                        endX = beamX + beamW / 2f,
                    ),
                    size = Size(w * clampedProgress, h),
                )
            }
        }
    }
}

/**
 * Rayburst-inspired "Every piece, as it lands" dot-matrix extent visualizer.
 * Renders a crisp glowing grid of micro-dots where landed extents glow emerald/cyan,
 * active in-flight extents flash bright white/amber, and pending extents remain subtle.
 */
@Composable
fun RayburstPieceMatrix(
    segments: List<SegmentState>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val pendingDot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val transition = rememberInfiniteTransition(label = "rayburst_matrix_flash")
    val flashPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rayburst_matrix_phase",
    )

    val display = if (segments.isEmpty()) List(32) { SegmentState.PENDING } else segments
    val cols = 16
    val rows = ((display.size + cols - 1) / cols).coerceIn(1, 4)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((rows * 11).dp)
            .semantics {
                contentDescription = "Piece landing dot matrix with ${display.size} extents"
            },
    ) {
        val gapX = size.width / cols
        val gapY = size.height / rows
        val baseRadius = (minOf(gapX, gapY) * 0.28f).coerceIn(2.dp.toPx(), 4.dp.toPx())

        display.take(cols * rows).forEachIndexed { idx, state ->
            val r = idx / cols
            val c = idx % cols
            val cx = c * gapX + gapX / 2f
            val cy = r * gapY + gapY / 2f
            val pulse = 0.5f + 0.5f * sin(flashPhase + idx * 0.7f)

            val dotColor = when (state) {
                SegmentState.PENDING -> pendingDot
                SegmentState.COMMITTED -> ext.cyan
                SegmentState.VERIFIED -> ext.emerald
                SegmentState.ACTIVE_WIFI -> if (isRunning && pulse > 0.72f) Color.White else ext.cyan
                SegmentState.ACTIVE_5G -> if (isRunning && pulse > 0.72f) Color.White else ext.violet
                SegmentState.STOLEN_HEDGE -> ext.amber
                SegmentState.FAILED -> ext.rose
            }

            val isActive = state == SegmentState.ACTIVE_WIFI ||
                state == SegmentState.ACTIVE_5G ||
                state == SegmentState.STOLEN_HEDGE

            if (neon.enabled && (isActive || state == SegmentState.VERIFIED)) {
                drawCircle(
                    color = dotColor.copy(alpha = if (isActive) 0.36f * neon.intensity else 0.16f * neon.intensity),
                    radius = baseRadius * (if (isActive) 1.95f else 1.45f),
                    center = Offset(cx, cy),
                )
            }
            drawCircle(
                color = dotColor,
                radius = if (isActive && isRunning) baseRadius * (0.95f + 0.25f * pulse) else baseRadius,
                center = Offset(cx, cy),
            )
        }
    }
}

/**
 * Rayburst-inspired "Record it live" HLS/DASH live segment pill rail showing
 * incoming `.m4s` / `.ts` segment identifiers gliding smoothly into the capture stream.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RayburstLiveSegmentPills(
    segmentLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (segmentLabels.isEmpty()) return
    val ext = LocalAetherColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ext.violet.copy(alpha = 0.08f))
            .border(1.dp, ext.violet.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ext.rose),
            )
            Text(
                text = "LIVE STREAM SEGMENTS",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = ext.violet,
                fontWeight = FontWeight.Bold,
            )
        }
        AnimatedContent(
            targetState = segmentLabels.takeLast(4),
            transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
            },
            label = "rayburst_live_pills",
        ) { visiblePills ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visiblePills.forEachIndexed { idx, label ->
                    val isNewest = idx == visiblePills.lastIndex
                    val chipColor = if (isNewest) ext.emerald else ext.cyan
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(chipColor.copy(alpha = if (isNewest) 0.20f else 0.12f))
                            .border(1.dp, chipColor.copy(alpha = if (isNewest) 0.55f else 0.30f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(chipColor),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = label,
                            style = MonoMetricStyle.copy(fontSize = 10.sp),
                            color = chipColor,
                        )
                    }
                }
            }
        }
    }
}
