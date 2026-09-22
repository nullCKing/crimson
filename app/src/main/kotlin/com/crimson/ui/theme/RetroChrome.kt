package com.crimson.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crimson.ui.guide.headerClock
import com.crimson.ui.input.tvInteractive

/**
 * The set-top-box chrome every screen is built from.
 *
 * A 2000s cable guide is recognisable from across the room by three things: every fill is a
 * vertical gradient, every raised element has a one-pixel light edge on top and a dark one
 * underneath, and every heading sits on a hard drop shadow. This file draws those three things
 * once, from [GuideTheme], so the screens only say *what* they show and the theme says how. The
 * grid, which is drawn on a Canvas rather than composed, has its own copy of the same rules in
 * `GuideGrid.kt`; keep the two in step.
 */

// ---------------------------------------------------------------------- fills

/** A vertical gradient from the lifted tint of [base] at the top to its dropped tint at the bottom. */
fun GuideTheme.verticalFill(base: Color, height: Float): Brush =
    if (!hasGradients) {
        Brush.verticalGradient(listOf(base, base), startY = 0f, endY = height.coerceAtLeast(1f))
    } else {
        Brush.verticalGradient(
            colors = listOf(lifted(base), base, dropped(base)),
            startY = 0f,
            endY = height.coerceAtLeast(1f),
        )
    }

/**
 * The raised, gradient-filled, bevelled panel that rows, tiles, dialogs and buttons all share.
 *
 * The bevel is drawn as two L-shaped strokes rather than a border: a lighter line along the top
 * and left, a darker line along the bottom and right. That is what gives a flat rectangle the
 * plastic look of a set-top-box menu.
 */
fun Modifier.retroPanel(
    theme: GuideTheme,
    base: Color,
    corner: Dp = 4.dp,
    edge: Color? = theme.panelEdge,
    edgeWidth: Dp = 1.dp,
    gloss: Boolean = false,
): Modifier = drawBehind {
    val r = corner.toPx()
    val outline = edgeWidth.toPx()
    val radius = CornerRadius(r, r)

    drawRoundRect(brush = theme.verticalFill(base, size.height), cornerRadius = radius)
    if (gloss && theme.gloss.alpha > 0f) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(theme.gloss, Color.Transparent),
                startY = 0f,
                endY = size.height / 2f,
            ),
            size = Size(size.width, size.height / 2f),
            cornerRadius = radius,
        )
    }
    drawBevel(theme, inset = outline)
    if (edge != null) {
        drawRoundRect(
            color = edge,
            cornerRadius = radius,
            style = Stroke(width = outline),
        )
    }
}

/** The one-pixel light top-left and dark bottom-right edges, inside any outer border. */
fun DrawScope.drawBevel(theme: GuideTheme, inset: Float = 0f, width: Float = 1.5f) {
    if (theme.bevelLight.alpha == 0f && theme.bevelDark.alpha == 0f) return
    val l = inset + width / 2f
    val t = inset + width / 2f
    val rgt = size.width - inset - width / 2f
    val b = size.height - inset - width / 2f
    if (rgt <= l || b <= t) return
    drawLine(theme.bevelLight, Offset(l, t), Offset(rgt, t), strokeWidth = width)
    drawLine(theme.bevelLight, Offset(l, t), Offset(l, b), strokeWidth = width)
    drawLine(theme.bevelDark, Offset(l, b), Offset(rgt, b), strokeWidth = width)
    drawLine(theme.bevelDark, Offset(rgt, t), Offset(rgt, b), strokeWidth = width)
}

/**
 * CRT scanlines over whatever is underneath.
 *
 * One rectangle filled with a repeating three-pixel gradient shader, not hundreds of lines, so
 * redrawing it costs one quad however often the grid under it invalidates. Never put this over
 * full-screen video: it would add a blended full-screen layer to every frame the decoder produces,
 * which a Fire TV Stick cannot spare.
 */
fun Modifier.scanlines(theme: GuideTheme): Modifier =
    if (theme.scanlineAlpha <= 0f) {
        this
    } else {
        this
            .drawWithContent {
                drawContent()
                val pitch = theme.scanlinePitch.toPx().coerceAtLeast(2f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.66f to Color.Transparent,
                        0.67f to Color.Black.copy(alpha = theme.scanlineAlpha),
                        1f to Color.Black.copy(alpha = theme.scanlineAlpha),
                        startY = 0f,
                        endY = pitch,
                        tileMode = TileMode.Repeated,
                    ),
                )
            }
    }

// ---------------------------------------------------------------------- type

/** The hard drop shadow behind text, in a form TextStyle accepts. */
fun GuideTheme.shadow(density: androidx.compose.ui.unit.Density): Shadow = with(density) {
    Shadow(color = textShadow, offset = Offset(textShadowOffset.toPx(), textShadowOffset.toPx()), blurRadius = 0f)
}

/** Dark text on the yellow highlight needs no shadow; light text on navy does. */
@Composable
private fun GuideTheme.shadowFor(color: Color): Shadow? =
    if (color == highlightText) null else shadow(androidx.compose.ui.platform.LocalDensity.current)

@Composable
fun GuideTheme.headingStyle(color: Color = infoTitle): TextStyle = TextStyle(
    color = color,
    fontSize = titleSize,
    fontFamily = fontFamily,
    fontWeight = titleWeight,
    shadow = shadowFor(color),
)

@Composable
fun GuideTheme.labelStyle(color: Color = cellText, bold: Boolean = true): TextStyle = TextStyle(
    color = color,
    fontSize = detailSize,
    fontFamily = fontFamily,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    shadow = shadowFor(color),
)

@Composable
fun GuideTheme.captionStyle(color: Color = infoDetail): TextStyle = TextStyle(
    color = color,
    fontSize = sectionSize,
    fontFamily = fontFamily,
)

// ---------------------------------------------------------------------- header

/**
 * The bar across the top of every menu screen: the RETRO★GUIDE wordmark or a screen title on
 * the left, the clock in a bezelled box on the right, a gold rule underneath.
 */
@Composable
fun RetroHeader(
    title: String,
    subtitle: String?,
    nowMs: Long,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .retroPanel(theme, theme.chromeBar, corner = 3.dp, edge = theme.chromeBarEdge)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = theme.headingStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(text = subtitle, style = theme.captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                ClockBox(nowMs = nowMs, theme = theme)
                if (trailing != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = trailing, style = theme.captionStyle(theme.highlight))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(theme.chromeRule)
        )
    }
}

/** The wordmark, in the two colours the launcher banner uses. */
@Composable
fun RetroWordmark(theme: GuideTheme, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = "RETRO", style = theme.headingStyle(theme.highlight))
        Text(text = "★", style = theme.headingStyle(theme.tabFillCurrent))
        Text(text = "GUIDE", style = theme.headingStyle())
    }
}

/** The date and time in a sunken bezel, as on the corner of an i-Guide. */
@Composable
fun ClockBox(nowMs: Long, theme: GuideTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .retroPanel(theme, theme.background, corner = 3.dp, edge = theme.panelEdge)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = headerClock(nowMs),
            color = theme.clock,
            fontSize = theme.clockSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------- button bar

/** One entry on the bottom button bar. [key] is the badge letter; null draws no badge. */
@Immutable
data class BarButton(
    val label: String,
    val onClick: () -> Unit,
    val key: String? = null,
    val keyColor: Color? = null,
    val enabled: Boolean = true,
    val active: Boolean = false,
)

/**
 * The row of shortcut buttons along the bottom of a screen.
 *
 * On a cable box this row told the viewer what the coloured remote buttons did. Here it does
 * that and one more thing: every entry is clickable, which is what makes the app usable with a
 * mouse and no remote at all — Back, favourites, paging the guide, all of it. The buttons are
 * not focus targets, so a D-pad never gets lost in them; hovering one highlights it instead.
 */
@Composable
fun RetroButtonBar(
    buttons: List<BarButton>,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(theme.buttonBarHeight)
            .retroPanel(theme, theme.chromeBar, corner = 3.dp, edge = theme.chromeBarEdge)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (button in buttons) {
            RetroButton(button = button, theme = theme)
        }
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = hint,
                style = theme.captionStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A single bar button: a coloured key badge and a label on a raised, gradient panel. */
@Composable
fun RetroButton(button: BarButton, theme: GuideTheme, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val raised = button.active || hovered
    val base = when {
        !button.enabled -> theme.panel
        raised -> theme.highlight
        else -> theme.panelSelected
    }
    val text = if (raised && button.enabled) theme.highlightText else theme.cellText
    Row(
        modifier = modifier
            .fillMaxHeight()
            .retroPanel(theme, base, corner = 3.dp, edge = theme.panelEdge, gloss = raised)
            .tvInteractive(
                onSelect = { if (button.enabled) button.onClick() },
                onHover = { hovered = it },
                focusTarget = false,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (button.key != null) {
            Box(
                modifier = Modifier
                    .retroPanel(theme, button.keyColor ?: theme.keyBlue, corner = 3.dp, edge = theme.bevelDark, gloss = true)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = button.key,
                    color = Color.White,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = button.label,
            color = if (button.enabled) text else theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The page frame every menu screen uses: header on top, the bar of buttons along the bottom,
 * and the content between them, all inside the overscan-safe margin with scanlines over the lot.
 */
@Composable
fun RetroPage(
    theme: GuideTheme,
    header: @Composable () -> Unit,
    buttons: List<BarButton>,
    modifier: Modifier = Modifier,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .scanlines(theme),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 960.dp * theme.safeMarginFraction,
                    vertical = 540.dp * theme.safeMarginFraction,
                ),
        ) {
            header()
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content()
            }
            Spacer(Modifier.height(8.dp))
            RetroButtonBar(buttons = buttons, theme = theme, hint = hint)
        }
    }
}
