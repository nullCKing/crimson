package com.crimson.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Crimson look, in one place.
 *
 * Near-black surfaces in a few steps of lift, white type in three strengths, and one accent: a
 * saturated red used sparingly — the wordmark, what is live, progress, the selected tab, the
 * primary action. Focus is white (a ring and a lift), never red, so the accent keeps meaning
 * "this is important" rather than "this is where the cursor is".
 *
 * Sizes are in dp against the 960 x 540 dp a 1080p television reports.
 */
object Crimson {
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF141417)
    val SurfaceRaised = Color(0xFF1C1C21)
    val SurfaceHigh = Color(0xFF28282F)
    val Stroke = Color(0x1FFFFFFF)
    val StrokeStrong = Color(0x40FFFFFF)

    val Red = Color(0xFFE50914)
    val RedBright = Color(0xFFFF2A35)
    val RedDeep = Color(0xFF7A0710)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFBCBCC4)
    val TextTertiary = Color(0xFF85858F)

    /** IMDb's star, for ratings. */
    val Gold = Color(0xFFF5C518)
    val Green = Color(0xFF46D369)

    val FocusRing = Color(0xFFFFFFFF)
    val Glass = Color(0x33FFFFFF)
    val GlassStrong = Color(0x4DFFFFFF)

    /** Left-to-right fade that lets text sit on a backdrop. */
    val ScrimLeft = Brush.horizontalGradient(
        0f to Background,
        0.35f to Background.copy(alpha = 0.85f),
        0.65f to Background.copy(alpha = 0.25f),
        1f to Color.Transparent,
    )

    /** Bottom-up fade that lets rows sit on a backdrop. */
    val ScrimBottom = Brush.verticalGradient(
        0f to Color.Transparent,
        0.55f to Background.copy(alpha = 0.55f),
        1f to Background,
    )

    val ScrimTop = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.75f),
        1f to Color.Transparent,
    )

    val RedGradient = Brush.linearGradient(listOf(Color(0xFFFF2A35), Color(0xFFB0060F)))

    val Font: FontFamily = FontFamily.SansSerif

    val ScreenPadding = 48.dp
}

/** The type scale. */
object CrimsonType {
    val Display = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.Black, fontSize = 38.sp,
        lineHeight = 40.sp, letterSpacing = (-0.3).sp, color = Crimson.TextPrimary,
    )
    val Headline = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp,
        lineHeight = 32.sp, letterSpacing = (-0.3).sp, color = Crimson.TextPrimary,
    )
    val Title = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.Bold, fontSize = 19.sp,
        lineHeight = 24.sp, color = Crimson.TextPrimary,
    )
    val RowTitle = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        lineHeight = 20.sp, color = Crimson.TextPrimary,
    )
    val Body = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.Normal, fontSize = 13.sp,
        lineHeight = 19.sp, color = Crimson.TextSecondary,
    )
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold, color = Crimson.TextPrimary)
    val Label = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
        lineHeight = 16.sp, color = Crimson.TextPrimary,
    )
    val Caption = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.Medium, fontSize = 11.sp,
        lineHeight = 14.sp, color = Crimson.TextTertiary,
    )
    val Overline = TextStyle(
        fontFamily = Crimson.Font, fontWeight = FontWeight.Black, fontSize = 10.sp,
        lineHeight = 12.sp, letterSpacing = 2.2.sp, color = Crimson.Red,
    )
}
