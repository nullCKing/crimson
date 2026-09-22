package com.crimson.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Profile avatars: a rounded square in one of eight colours with a simple drawn face.
 *
 * Drawn rather than shipped as images, so they are crisp at every size from the 28 dp corner
 * badge to the 132 dp profile picker, and cost nothing in the APK.
 */
object Avatars {
    data class Style(val top: Color, val bottom: Color, val face: Int)

    val ALL = listOf(
        Style(Color(0xFFFF3B45), Color(0xFF9E0A12), 0),
        Style(Color(0xFF3D7BFF), Color(0xFF1737A8), 1),
        Style(Color(0xFFFFC23D), Color(0xFFD9730B), 2),
        Style(Color(0xFF2FD37A), Color(0xFF0B6B37), 3),
        Style(Color(0xFFA078FF), Color(0xFF4B1FA3), 1),
        Style(Color(0xFF2DD4C4), Color(0xFF0E6660), 0),
        Style(Color(0xFFFF77B8), Color(0xFFA0165A), 2),
        Style(Color(0xFFA4B3C7), Color(0xFF3A4658), 3),
    )

    fun style(index: Int): Style = ALL[Math.floorMod(index, ALL.size)]
}

@Composable
fun Avatar(index: Int, size: Dp, modifier: Modifier = Modifier) {
    val style = Avatars.style(index)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            brush = Brush.linearGradient(listOf(style.top, style.bottom), Offset.Zero, Offset(s, s)),
            cornerRadius = CornerRadius(s * 0.09f),
        )
        drawFace(style.face, s)
    }
}

private fun DrawScope.drawFace(face: Int, s: Float) {
    val ink = Color.White.copy(alpha = 0.94f)
    val eyeY = s * 0.40f
    val eyeR = s * 0.055f
    val leftEye = Offset(s * 0.34f, eyeY)
    val rightEye = Offset(s * 0.66f, eyeY)
    val stroke = Stroke(width = s * 0.055f, cap = StrokeCap.Round)
    when (face) {
        // Classic: two dots and a smile.
        0 -> {
            drawCircle(ink, eyeR, leftEye)
            drawCircle(ink, eyeR, rightEye)
            drawArc(ink, 20f, 140f, false, Offset(s * 0.28f, s * 0.36f), Size(s * 0.44f, s * 0.34f), style = stroke)
        }
        // Wink.
        1 -> {
            drawCircle(ink, eyeR, leftEye)
            drawLine(ink, Offset(s * 0.60f, eyeY), Offset(s * 0.72f, eyeY), strokeWidth = s * 0.05f, cap = StrokeCap.Round)
            drawArc(ink, 15f, 150f, false, Offset(s * 0.30f, s * 0.40f), Size(s * 0.40f, s * 0.28f), style = stroke)
        }
        // Big grin.
        2 -> {
            drawCircle(ink, eyeR * 1.1f, leftEye)
            drawCircle(ink, eyeR * 1.1f, rightEye)
            drawArc(ink, 0f, 180f, true, Offset(s * 0.29f, s * 0.46f), Size(s * 0.42f, s * 0.28f))
        }
        // Cool: a visor and a straight smile.
        else -> {
            drawRoundRect(ink, Offset(s * 0.22f, s * 0.33f), Size(s * 0.56f, s * 0.13f), CornerRadius(s * 0.06f))
            drawArc(ink, 30f, 120f, false, Offset(s * 0.33f, s * 0.44f), Size(s * 0.34f, s * 0.22f), style = stroke)
        }
    }
}
