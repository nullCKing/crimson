package com.crimson.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.epg.ProgramCategory

/**
 * Every colour, size, count and spacing the guide grid uses.
 *
 * Crimson inherited this from RetroGuide, where it described a 2000s cable box. The structure is
 * unchanged — the grid is still drawn from these numbers and nothing else — but the defaults are
 * now the dark, flat, red-accented look of the rest of the app: no gradients, bevels, gloss or
 * scanlines, and a red highlight. The retro switches are still here, set to off.
 *
 * This is the one place to change the look. The grid is custom-drawn onto a Canvas, so there are
 * no styles or XML attributes to chase: the drawing code reads its numbers from here and nowhere
 * else. Changing [rowsVisible] from 5 to 6, or [movieCell] from magenta to green, is a one-line
 * edit with no other consequences.
 *
 * Sizes are in dp against a 960 x 540 dp reference, which is what a 1920 x 1080 television reports
 * at the density Android TV uses. [safeMarginFraction] keeps everything inside the 5% border that
 * older sets overscan away.
 */
@Immutable
data class GuideTheme(
    // ---------------------------------------------------------------- colour
    /** Page background. The deep navy of the Comcast reference. */
    val background: Color = Color(0xFF0A0A0C),
    /** The slightly lighter navy the info panel and headers sit on. */
    val panel: Color = Color(0xFF16161A),
    val panelEdge: Color = Color(0x1FFFFFFF),
    val panelSelected: Color = Color(0xFF26262C),

    /** Programme cells, coloured by category. Movies are magenta, everything else blue. */
    val movieCell: Color = Color(0xFF3A1519),
    val sportsCell: Color = Color(0xFF1A2433),
    val newsCell: Color = Color(0xFF202027),
    val kidsCell: Color = Color(0xFF202027),
    val seriesCell: Color = Color(0xFF202027),
    /** Cells that stand in for missing guide data. */
    val fillerCell: Color = Color(0xFF141418),

    /** The highlighted cell: bright yellow with dark text, as in the DirecTV reference. */
    val highlight: Color = Color(0xFFE50914),
    val highlightText: Color = Color.White,

    val cellText: Color = Color.White,
    /** The dark outline drawn behind cell text so it stays readable on any cell colour. */
    val cellTextOutline: Color = Color.Transparent,

    /** Time-header tabs: light blue fill, dark text, rounded top corners. */
    val tabFill: Color = Color(0xFF16161A),
    val tabText: Color = Color(0xFFBCBCC4),
    val tabFillCurrent: Color = Color(0xFF26262C),

    val channelColumn: Color = Color(0xFF16161A),
    val channelNumber: Color = Color(0xFF85858F),
    val channelName: Color = Color.White,

    val infoTitle: Color = Color.White,
    val infoDetail: Color = Color(0xFFBCBCC4),
    val clock: Color = Color.White,

    /** The vertical line marking the current moment across the grid. */
    val nowLine: Color = Color(0xFFE50914),

    // ---------------------------------------------------------------- layout
    /** TV-safe border, as a fraction of each edge. */
    val safeMarginFraction: Float = 0.05f,

    /** How many channel rows the grid shows at once. */
    val rowsVisible: Int = 5,
    /** How much time the grid shows at once. */
    val windowHours: Int = 2,

    val rowHeight: Dp = 44.dp,
    val rowGap: Dp = 4.dp,
    val channelColumnWidth: Dp = 120.dp,
    val timeHeaderHeight: Dp = 26.dp,
    val cellCorner: Dp = 6.dp,
    /** How deep the triangular notch cuts into a cell that runs past the window edge. */
    val notchDepth: Dp = 8.dp,
    /** Narrowest a cell may be drawn, so a programme clipped at the edge stays readable. */
    val cellMinWidth: Dp = 26.dp,
    val cellPadding: Dp = 10.dp,

    /**
     * Height of the whole upper section: info panel and preview window. Sized so that five rows
     * and the button bar fit under it inside the safe area of a 960 x 540 dp screen.
     */
    val infoPanelHeight: Dp = 168.dp,
    /** The preview window on the right of the info panel. 16:9. */
    val previewWidth: Dp = 264.dp,
    val previewHeight: Dp = 148.dp,

    // ---------------------------------------------------------------- type
    val fontFamily: FontFamily = FontFamily.SansSerif,
    val titleSize: TextUnit = 26.sp,
    val sectionSize: TextUnit = 13.sp,
    val detailSize: TextUnit = 14.sp,
    val clockSize: TextUnit = 16.sp,
    val cellTextSize: TextUnit = 12.sp,
    val channelNumberSize: TextUnit = 11.sp,
    val channelNameSize: TextUnit = 12.sp,
    val tabTextSize: TextUnit = 12.sp,
    val titleWeight: FontWeight = FontWeight.Bold,
    /** Half-width of the dark outline drawn behind cell text, in dp. */
    val textOutlineWidth: Dp = 0.dp,
    /** Cell titles wrap to at most this many lines before being truncated with an ellipsis. */
    val cellMaxLines: Int = 2,

    // ---------------------------------------------------------------- banner
    val bannerHeight: Dp = 118.dp,
    val bannerBackground: Color = Color(0xE6101014),
    val bannerProgress: Color = Color(0xFFE50914),
    val bannerProgressTrack: Color = Color(0x33FFFFFF),
    /** How long the channel banner stays on screen after a channel change. */
    val bannerVisibleMillis: Long = 4_000L,

    // ---------------------------------------------------------------- retro chrome
    /**
     * How much lighter the top of a cell, tab or panel is than its base colour. Every fill in a
     * 2000s cable guide was a vertical gradient — flat colour is what makes a screen look like a
     * web page instead of a set-top box. Zero turns the gradients off.
     */
    val gradientLift: Float = 0f,
    /** How much darker the bottom of a fill is than its base colour. */
    val gradientDrop: Float = 0f,
    /** The one-pixel light edge along the top and left of a raised element. */
    val bevelLight: Color = Color.Transparent,
    /** The one-pixel dark edge along the bottom and right. */
    val bevelDark: Color = Color.Transparent,
    /** The glassy band across the top half of tabs and the highlight, as on a DirecTV box. */
    val gloss: Color = Color.Transparent,
    /** The hard drop shadow behind headings and cell text. */
    val textShadow: Color = Color.Transparent,
    val textShadowOffset: Dp = 1.5.dp,
    /** Opacity of the CRT scanlines laid over the menu screens. Zero turns them off. */
    val scanlineAlpha: Float = 0f,
    /** Vertical pitch of the scanlines. */
    val scanlinePitch: Dp = 3.dp,
    /** The bar the header sits on: a darker navy so the page background reads as "behind" it. */
    val chromeBar: Color = Color(0xFF16161A),
    val chromeBarEdge: Color = Color(0x1FFFFFFF),
    /** The thin gold rule under the header, the Comcast i-Guide's signature line. */
    val chromeRule: Color = Color(0xFFE50914),
    /**
     * The coloured key badges on the bottom button bar. Cable boxes labelled their shortcut
     * buttons A, B and C in red, green and yellow; the bar reuses the same colours so a viewer
     * who has used one knows what they are looking at.
     */
    val keyRed: Color = Color(0xFFD03030),
    val keyGreen: Color = Color(0xFF30A040),
    val keyYellow: Color = Color(0xFFF0C020),
    val keyBlue: Color = Color(0xFF3060D0),
    /** Height of the bottom button bar. */
    val buttonBarHeight: Dp = 34.dp,
    /** The translucent mouse toolbar over full-screen video. */
    val mouseBarBackground: Color = Color(0xD0101014),
) {
    val windowMillis: Long get() = windowHours * 60L * 60L * 1000L

    /** The colour a programme cell is painted, by category. */
    fun cellColor(category: ProgramCategory, isFiller: Boolean): Color = when {
        isFiller -> fillerCell
        category == ProgramCategory.MOVIE -> movieCell
        category == ProgramCategory.SPORTS -> sportsCell
        category == ProgramCategory.NEWS -> newsCell
        category == ProgramCategory.KIDS -> kidsCell
        else -> seriesCell
    }

    /** Whether the retro gradients are on at all. */
    val hasGradients: Boolean get() = gradientLift > 0f || gradientDrop > 0f

    /** The lighter tint at the top of a gradient fill. */
    fun lifted(base: Color): Color = lerp(base, Color.White, gradientLift)

    /** The darker tint at the bottom of a gradient fill. */
    fun dropped(base: Color): Color = lerp(base, Color.Black, gradientDrop)

    companion object {
        val Default = GuideTheme()

        /** The look with none of the retro chrome, for screenshot tests that want flat colour. */
        val Flat = GuideTheme(
            gradientLift = 0f,
            gradientDrop = 0f,
            gloss = Color.Transparent,
            scanlineAlpha = 0f,
            bevelLight = Color.Transparent,
            bevelDark = Color.Transparent,
        )
    }
}

val LocalGuideTheme: ProvidableCompositionLocal<GuideTheme> =
    staticCompositionLocalOf { GuideTheme.Default }

/**
 * Wraps content in the guide's theme. Screenshot tests provide a modified copy to render the same
 * screens with, for example, six rows or a three-hour window.
 */
@Composable
fun CrimsonGuideTheme(
    theme: GuideTheme = GuideTheme.Default,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGuideTheme provides theme, content = content)
}

/** Shorthand for reading the current theme. */
val guideTheme: GuideTheme
    @Composable @ReadOnlyComposable get() = LocalGuideTheme.current
