package com.crimson.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import coil.transform.Transformation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A logo (a channel's, a team's, a title's) drawn sharp at whatever size it is shown.
 *
 * Logos come large — ESPN's team logos are 500 px, providers' channel logos anything up to a few
 * thousand — and are shown at a few dozen pixels. The platform's shrinking picks pixels rather
 * than averaging them (a PNG decoded with a sample size is point-sampled, on Fire OS 5 as on the
 * emulator), which is what left jagged, pixelated edges round every logo. Here the image is
 * decoded near full size and brought down by [SmoothDownscale], halving with a filter each step
 * so every output pixel is an average of the pixels under it, then kept at exactly the size it
 * is drawn.
 */
@Composable
fun LogoImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Unbounded (inside a scrolling row, say): a size logos are never shown larger than.
        // A tenth larger than the box, so a card lifted under focus is not enlarging the logo.
        val width = ((if (constraints.hasBoundedWidth) constraints.maxWidth else with(density) { UNBOUNDED_DP.dp.roundToPx() }) * LIFT).roundToInt().coerceAtLeast(1)
        val height = ((if (constraints.hasBoundedHeight) constraints.maxHeight else with(density) { UNBOUNDED_DP.dp.roundToPx() }) * LIFT).roundToInt().coerceAtLeast(1)
        val request = remember(url, width, height) {
            ImageRequest.Builder(context)
                .data(url)
                // At least four times the target, so the smoothing has real pixels to average.
                .size(min(width * 4, MAX_DECODE), min(height * 4, MAX_DECODE))
                .precision(Precision.INEXACT)
                .transformations(SmoothDownscale(width, height))
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            alpha = alpha,
            onError = { onError() },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * A picture with rounded corners that are anti-aliased.
 *
 * Why not `Modifier.clip(shape)`: Android's renderer cuts a rounded clip without anti-aliasing,
 * so the corners of every clipped card and button came out stair-stepped, and worse still while
 * a card is enlarged under focus. A filled shape (`background(color, shape)`) is drawn smooth,
 * which is how every plain surface in the app is rounded; a picture cannot be filled that way, so
 * its corners are cut into the bitmap itself, smoothly, once, when it is loaded
 * (Coil's [RoundedCornersTransformation], at exactly the size it is shown).
 */
@Composable
fun RoundedImage(
    url: String?,
    contentDescription: String?,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.coerceAtLeast(1) else with(density) { UNBOUNDED_DP.dp.roundToPx() }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight.coerceAtLeast(1) else with(density) { UNBOUNDED_DP.dp.roundToPx() }
        val radius = with(density) { corner.toPx() }
        val request = remember(url, width, height, radius) {
            ImageRequest.Builder(context)
                .data(url)
                .size(width, height)
                .scale(Scale.FILL)
                .transformations(RoundedCornersTransformation(radius))
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Shrinks a bitmap to fit [width] x [height] (keeping its shape) with a box filter: repeated
 * halving with bilinear filtering, which averages each 2x2 block, and one last filtered step to
 * the exact size. Never enlarges.
 */
class SmoothDownscale(private val width: Int, private val height: Int) : Transformation {

    override val cacheKey: String = "smooth-$width-$height"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val scale = min(width.toFloat() / input.width, height.toFloat() / input.height)
        if (scale >= 1f) return input
        val targetW = max(1, (input.width * scale).roundToInt())
        val targetH = max(1, (input.height * scale).roundToInt())
        var current = input
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== input) current.recycle()
            current = next
        }
        if (current.width == targetW && current.height == targetH) return current
        val out = Bitmap.createScaledBitmap(current, targetW, targetH, true)
        if (current !== input && current !== out) current.recycle()
        return out
    }

    override fun equals(other: Any?): Boolean = other is SmoothDownscale && other.width == width && other.height == height
    override fun hashCode(): Int = 31 * width + height
}

/** Keeps a pathological logo (a 6000 px PNG) from being decoded whole. */
private const val MAX_DECODE = 2048
private const val UNBOUNDED_DP = 240
private const val LIFT = 1.1f
