package com.crimson.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType

/** The wordmark: heavy, condensed, red. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: TextUnit = 24.sp) {
    Text(
        text = "CRIMSON",
        modifier = modifier.graphicsLayer {
            scaleX = 0.86f
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        },
        style = CrimsonType.Display.copy(
            fontSize = size,
            lineHeight = size,
            letterSpacing = (size.value * 0.02f).sp,
            color = Crimson.Red,
            fontWeight = FontWeight.Black,
            shadow = androidx.compose.ui.graphics.Shadow(Crimson.Red.copy(alpha = 0.45f), Offset.Zero, size.value * 0.6f),
        ),
    )
}

enum class ButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * A button. Primary is crimson, the others are glass. On focus every style turns solid white with
 * dark text and lifts, so there is never any doubt which button Select will press.
 */
@Composable
fun CrimsonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onFocus: (Boolean) -> Unit = {},
    compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            // Red at rest, white under focus: a primary button that was white either way left
            // the viewer unable to tell whether it or its neighbour had the cursor.
            style == ButtonStyle.PRIMARY -> Crimson.Red
            style == ButtonStyle.DANGER -> Crimson.Red.copy(alpha = 0.18f)
            style == ButtonStyle.GHOST -> Color.Transparent
            else -> Crimson.Glass
        },
        animationSpec = tween(140),
        label = "buttonBg",
    )
    val content = when {
        focused -> Color.Black
        style == ButtonStyle.PRIMARY -> Color.White
        style == ButtonStyle.DANGER -> Crimson.RedBright
        else -> Crimson.TextPrimary
    }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160, easing = FastOutSlowInEasing), label = "buttonScale")
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(background)
            .then(if (style == ButtonStyle.GHOST && !focused) Modifier.border(1.dp, Crimson.StrokeStrong, shape) else Modifier)
            .tvInteractive(
                onSelect = { if (enabled) onClick() },
                onFocus = { focused = it; onFocus(it) },
                focusRequester = focusRequester,
            )
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 7.dp else 10.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = CrimsonType.Label.copy(fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold, color = content), maxLines = 1)
    }
}

/** A round icon-only button: search, add to list, back. */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    contentDescription: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Crimson.GlassStrong
        else -> Crimson.Glass
    }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(160), label = "iconScale")
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .tvInteractive(onSelect = onClick, onFocus = { focused = it }, focusRequester = focusRequester),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A filter chip. Selected chips are red; focus is white, as everywhere. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            focused -> Color.White
            selected -> Crimson.Red
            else -> Crimson.Glass
        },
        tween(140), label = "chipBg",
    )
    val fg = if (focused) Color.Black else Color.White
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "chipScale")
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .tvInteractive(onSelect = onClick, onFocus = { focused = it; onFocus(it) }, focusRequester = focusRequester)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (leading != null) {
            Text(leading, style = CrimsonType.Label.copy(fontSize = 14.sp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = CrimsonType.Label.copy(color = fg, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** A thin progress line: red over a translucent track. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Color.White.copy(alpha = 0.22f)) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Crimson.Red),
        )
    }
}

/** The red LIVE pill, with a pulsing dot. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "LIVE", small: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "livePulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "livePulseAlpha",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Crimson.Red)
            .padding(horizontal = if (small) 5.dp else 7.dp, vertical = if (small) 1.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (small) 5.dp else 6.dp)
                .graphicsLayer { alpha = pulse }
                .clip(CircleShape)
                .background(Color.White),
        )
        Spacer(Modifier.width(if (small) 4.dp else 5.dp))
        Text(
            text,
            style = CrimsonType.Overline.copy(color = Color.White, fontSize = if (small) 8.sp else 10.sp, letterSpacing = 1.2.sp),
        )
    }
}

/** A small outlined badge: an age rating, "HD", "NEW". */
@Composable
fun OutlineBadge(text: String, modifier: Modifier = Modifier, color: Color = Crimson.TextSecondary) {
    Text(
        text = text,
        style = CrimsonType.Caption.copy(color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp),
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        maxLines = 1,
    )
}

/**
 * The line of facts under a title: rating star, year, age rating, runtime, genres. Each part is
 * optional; missing ones simply do not appear, with no stray separators.
 */
@Composable
fun MetaLine(
    modifier: Modifier = Modifier,
    rating: Float? = null,
    year: Int? = null,
    ageRating: String? = null,
    runtime: String? = null,
    extra: List<String> = emptyList(),
    isNew: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        var first = true
        fun gap() = if (first) { first = false; false } else true
        if (isNew) {
            Text("NEW", style = CrimsonType.Label.copy(color = Crimson.Green, fontWeight = FontWeight.Black))
            first = false
        }
        if (rating != null && rating > 0f) {
            if (gap()) Spacer(Modifier.width(12.dp))
            Icon(CrimsonIcons.Star, null, tint = Crimson.Gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(String.format("%.1f", rating), style = CrimsonType.Label.copy(color = Crimson.TextPrimary))
        }
        if (year != null) {
            if (gap()) Spacer(Modifier.width(12.dp))
            Text(year.toString(), style = CrimsonType.Label.copy(color = Crimson.TextSecondary))
        }
        if (!ageRating.isNullOrBlank()) {
            if (gap()) Spacer(Modifier.width(12.dp))
            OutlineBadge(ageRating)
        }
        if (!runtime.isNullOrBlank()) {
            if (gap()) Spacer(Modifier.width(12.dp))
            Text(runtime, style = CrimsonType.Label.copy(color = Crimson.TextSecondary))
        }
        extra.filter { it.isNotBlank() }.forEach { part ->
            if (gap()) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(3.dp).clip(CircleShape).background(Crimson.TextTertiary))
                Spacer(Modifier.width(10.dp))
            }
            Text(part, style = CrimsonType.Label.copy(color = Crimson.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The red ring spinner. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 44.dp, stroke: Dp = 4.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinnerAngle")
    val sweep by transition.animateFloat(60f, 280f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "spinnerSweep")
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        drawArc(
            color = Color.White.copy(alpha = 0.08f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
            style = Stroke(w),
        )
        drawArc(
            color = Crimson.Red,
            startAngle = angle, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** A row heading, with an optional muted subtitle beside it. */
@Composable
fun RowHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: String? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(title, style = CrimsonType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(subtitle, style = CrimsonType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, style = CrimsonType.Caption.copy(color = Crimson.Red, fontWeight = FontWeight.Bold))
        }
    }
}

/** A labelled empty state, for a page or a panel with nothing to show yet. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, busy: Boolean = false) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            Spinner(size = 36.dp, stroke = 3.dp)
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = CrimsonType.Title)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = CrimsonType.Body,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(420.dp),
        )
    }
}
