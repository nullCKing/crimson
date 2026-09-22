package com.crimson.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crimson.ui.theme.Crimson

/**
 * The one focus treatment every card uses: a lift, a white ring and a shadow.
 *
 * Scale is done in the graphics layer, so a focused card grows without re-measuring the row it
 * sits in — on a Fire Stick a layout pass per focus change across a row of forty posters is the
 * difference between smooth and not.
 */
@Composable
fun Modifier.cardFocus(
    focused: Boolean,
    shape: Shape,
    scale: Float = 1.08f,
    ring: Dp = 2.5.dp,
): Modifier {
    val s by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "cardScale",
    )
    return this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .then(if (focused) Modifier.shadow(18.dp, shape, clip = false) else Modifier)
        .then(if (focused) Modifier.border(ring, Crimson.FocusRing, shape) else Modifier)
}

/**
 * Makes a scrolling list keep its focused child at a fixed position — [offset] from the leading
 * edge — the way a streaming app's rows do, instead of scrolling only as far as needed to reveal
 * it. The row moves under a still cursor, which is what reads as "premium" on a television.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(offset: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { offset.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
