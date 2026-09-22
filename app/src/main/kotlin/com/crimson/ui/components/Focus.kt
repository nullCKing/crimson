package com.crimson.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crimson.ui.theme.Crimson

/**
 * The one focus treatment every card uses: a lift and a white ring.
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
        // No drop shadow: a blurred elevation shadow redrawn on every frame of a scroll was the
        // single most expensive thing on screen, and the ring and the lift read as focus without it.
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
            // A spring, not a tween: the scroll target is recalculated on every frame while the
            // list moves, and a fixed-duration tween restarted each frame barely moves at all.
            override val scrollAnimationSpec = spring<Float>(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioNoBouncy,
            )

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * The opposite of [PivotScroll]: scroll only as far as needed to reveal the focused child, with a
 * small margin, and not at all when it is already on screen. For document-like pages — a title's
 * details — where a pivot would scroll the heading away.
 *
 * Needed explicitly because Compose's default on a television is itself a pivot, at 30% of the
 * container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RevealScroll(margin: Dp = 24.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { margin.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = spring<Float>(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioNoBouncy,
            )

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val trailing = offset + size
                return when {
                    offset >= 0f && trailing <= containerSize -> 0f
                    offset < 0f || size > containerSize - 2 * px -> offset - px
                    else -> trailing - containerSize + px
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Puts focus on [requester] when a page appears, and again once the page transition has
 * finished: the outgoing page gives up its focus when it is disposed, a moment after the new
 * page has claimed it, and on a television nothing may ever be left without focus.
 */
@Composable
fun InitialFocus(requester: androidx.compose.ui.focus.FocusRequester, key: Any? = Unit) {
    androidx.compose.runtime.LaunchedEffect(key) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { requester.requestFocus() }
        kotlinx.coroutines.delay(320)
        runCatching { requester.requestFocus() }
    }
}
