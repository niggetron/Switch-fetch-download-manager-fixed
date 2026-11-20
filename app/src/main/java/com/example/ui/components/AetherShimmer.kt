package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.aetherNeonBorder

/**
 * Reusable luminance-adaptive shimmering gradient brush for AetherIO skeleton placeholders,
 * Stream 0 probing states, Wi-Fi scans, Remote .ZIP EOCD previews, and async hash calculation.
 */
@Composable
fun rememberAetherShimmerBrush(
    accentTint: Color? = null,
    durationMillis: Int = 1150,
): Brush {
    val ext = LocalAetherColors.current
    val baseSurface = MaterialTheme.colorScheme.surfaceVariant
    val highlight = accentTint?.copy(alpha = 0.28f)
        ?: ext.cyan.copy(alpha = 0.22f)
    val base = baseSurface.copy(alpha = 0.65f)

    val transition = rememberInfiniteTransition(label = "aether_shimmer_transition")
    val translate by transition.animateFloat(
        initialValue = -320f,
        targetValue = 960f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aether_shimmer_offset",
    )

    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translate, 0f),
        end = Offset(translate + 280f, 180f),
    )
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    shape: Shape = RoundedCornerShape(7.dp),
    accentTint: Color? = null,
) {
    val brush = rememberAetherShimmerBrush(accentTint = accentTint)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(brush),
    )
}

@Composable
fun SkeletonWifiRow(modifier: Modifier = Modifier) {
    val ext = LocalAetherColors.current
    val brush = rememberAetherShimmerBrush(accentTint = ext.violet)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(brush),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.36f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(brush),
            )
        }
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush),
        )
    }
}

@Composable
fun SkeletonTransferCard(modifier: Modifier = Modifier) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val brush = rememberAetherShimmerBrush(accentTint = ext.cyan)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .aetherNeonBorder(neon = neon, accent = ext.cyan, cornerRadius = 16.dp, fallbackBorderColor = ext.cardBorder)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(brush),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(brush),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(brush),
                )
            }
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(brush),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(brush),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush),
                )
            }
        }
    }
}

@Composable
fun SkeletonPropertyRow(modifier: Modifier = Modifier) {
    val brush = rememberAetherShimmerBrush()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(12.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(brush),
        )
    }
}
