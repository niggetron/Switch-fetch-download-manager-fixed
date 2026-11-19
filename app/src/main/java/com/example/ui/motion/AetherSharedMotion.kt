package com.example.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Strongly-typed Shared Element keys per official Jetpack Compose guidelines
 * (https://developer.android.com/develop/ui/compose/animation/shared-elements#unique-keys).
 */
enum class SharedPart {
    HERO_CONTAINER,
    HERO_ENGINE_BADGE,
    HERO_SPARKLINE,
    GAUGE_CARD,
    GAUGE_DIAL,
    GAUGE_TITLE,
    GAUGE_LIVE_READOUT,
    GAUGE_MAX_CHIP,
    GAUGE_AVG_CHIP,
    FAB_NEW_DOWNLOAD,
    WIFI2_CONNECTOR_PILL,
    SIM_LAB_PILL,
    TRANSFER_CARD,
    TRANSFER_ICON,
    TRANSFER_TITLE,
    TRANSFER_STATUS,
    TRANSFER_LEDGER,
    FILE_CARD,
    FILE_ICON,
    FILE_TITLE,
    FILE_LEDGER,
}

data class AetherSharedKey(
    val id: String,
    val part: SharedPart,
)

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Global motion speed multiplier controlled by the Animation & Speed Simulation Lab:
 * 0.25f = Slow-Mo inspection, 0.5f = Half speed, 1.0f = Default expressive, 1.5f = Snappy.
 */
val LocalMotionSpeedMultiplier = compositionLocalOf { 1.0f }

/**
 * Expressive Material 3 spatial BoundsTransform that honors [LocalMotionSpeedMultiplier].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun aetherSpatialBoundsTransform(speedMultiplier: Float = 1.0f): BoundsTransform =
    BoundsTransform { _, _ ->
        val m = speedMultiplier.coerceIn(0.2f, 2.0f)
        if (m < 0.85f) {
            // In slow-motion inspection mode, use deterministic scaled tween so every arc is visibly inspectable
            tween(durationMillis = (420f / m).toInt().coerceIn(220, 2200))
        } else {
            spring(
                dampingRatio = 0.80f,
                stiffness = Spring.StiffnessMediumLow * m,
            )
        }
    }

@OptIn(ExperimentalSharedTransitionApi::class)
fun aetherCockpitBoundsTransform(speedMultiplier: Float = 1.0f): BoundsTransform =
    BoundsTransform { _, _ ->
        val m = speedMultiplier.coerceIn(0.2f, 2.0f)
        if (m < 0.85f) {
            tween(durationMillis = (460f / m).toInt().coerceIn(240, 2400))
        } else {
            spring(
                dampingRatio = 0.76f,
                stiffness = 320f * m,
            )
        }
    }

fun <T> aetherSpringSpec(
    speedMultiplier: Float = 1.0f,
    dampingRatio: Float = 0.80f,
    stiffness: Float = 360f,
): FiniteAnimationSpec<T> {
    val m = speedMultiplier.coerceIn(0.2f, 2.0f)
    return if (m < 0.85f) {
        tween(durationMillis = (380f / m).toInt().coerceIn(200, 2000))
    } else {
        spring(dampingRatio = dampingRatio, stiffness = stiffness * m)
    }
}

/**
 * Safe sharedBounds modifier that activates automatically when rendered inside
 * [LocalSharedTransitionScope] + [LocalAnimatedVisibilityScope] (and safely no-ops in isolated tests/previews).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.aetherSharedBounds(
    key: AetherSharedKey,
    clipShape: Shape = RoundedCornerShape(16.dp),
    resizeMode: SharedTransitionScope.ResizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
    zIndexInOverlay: Float = 0f,
    useCockpitSpring: Boolean = false,
    enter: EnterTransition = fadeIn(tween(220)),
    exit: ExitTransition = fadeOut(tween(180)),
): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalAnimatedVisibilityScope.current
    val speed = LocalMotionSpeedMultiplier.current
    if (sharedScope == null || animScope == null) {
        this
    } else {
        with(sharedScope) {
            this@composed.sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope,
                enter = enter,
                exit = exit,
                boundsTransform = if (useCockpitSpring) {
                    aetherCockpitBoundsTransform(speed)
                } else {
                    aetherSpatialBoundsTransform(speed)
                },
                resizeMode = resizeMode,
                zIndexInOverlay = zIndexInOverlay,
                clipInOverlayDuringTransition = OverlayClip(clipShape),
            )
        }
    }
}

/**
 * Safe sharedElement modifier for icons, titles, badges, and dials that visually fly across transitions.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.aetherSharedElement(
    key: AetherSharedKey,
    clipShape: Shape? = null,
    zIndexInOverlay: Float = 0.5f,
    useCockpitSpring: Boolean = false,
): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalAnimatedVisibilityScope.current
    val speed = LocalMotionSpeedMultiplier.current
    if (sharedScope == null || animScope == null) {
        this
    } else {
        with(sharedScope) {
            val base = this@composed.sharedElement(
                state = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope,
                boundsTransform = if (useCockpitSpring) {
                    aetherCockpitBoundsTransform(speed)
                } else {
                    aetherSpatialBoundsTransform(speed)
                },
                zIndexInOverlay = zIndexInOverlay,
                clipInOverlayDuringTransition = clipShape?.let { OverlayClip(it) }
                    ?: OverlayClip(RoundedCornerShape(12.dp)),
            )
            base
        }
    }
}

/**
 * Prevents text reflow jank when a container expands or morphs during a shared element transition.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.aetherSkipToLookaheadSize(): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    if (sharedScope == null) {
        this
    } else {
        with(sharedScope) {
            this@composed.skipToLookaheadSize()
        }
    }
}

/**
 * Keeps persistent navigation chrome (TopAppBar, NavigationBar, FAB) cleanly above
 * in-flight shared elements per official Jetsnack overlay guidance.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.aetherOverlayZIndex(zIndex: Float = 2f): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    if (sharedScope == null) {
        this
    } else {
        with(sharedScope) {
            this@composed.renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndex)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
fun aetherScaleTextResizeMode(): SharedTransitionScope.ResizeMode =
    SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.FillWidth, Alignment.CenterStart)
