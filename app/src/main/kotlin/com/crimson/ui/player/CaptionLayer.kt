package com.crimson.ui.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import com.crimson.data.settings.CaptionSize
import kotlinx.coroutines.delay

/**
 * Captions over the video, in one style whatever their source.
 *
 * Drawn here rather than by Media3's SubtitleView for two reasons. A downloaded file is timed by
 * the app (sync and nudges move it), not by the player. And the style has to be safe on Fire OS 5:
 * SubtitleView's drop-shadow edge is a blurred text shadow, which crashes that renderer (see
 * `BlurredTextShadows`); the outline here is a stroke, which does not.
 *
 * [raised] lifts the captions above the player's controls while they are showing, and
 * [besideMenu] centres them in the space the menu leaves, so size and style changes can be seen as
 * they are made. [level] is how
 * bright the captions are drawn, 0–1, so that dimmed video does not leave them glaring.
 */
@Composable
fun CaptionLayer(
    captions: CaptionState,
    cues: List<Cue>,
    position: () -> Long,
    size: CaptionSize,
    background: Boolean,
    raised: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
    /** The captions-and-sound panel is open on the right; captions move clear of it. */
    besideMenu: Boolean = false,
) {
    if (!captions.isOn) return
    val track = captions.track
    var now by remember { mutableLongStateOf(0L) }
    if (track != null) {
        // A downloaded file's captions are looked up by the position, ten times a second.
        LaunchedEffect(track) {
            while (true) {
                now = position()
                delay(100)
            }
        }
    }
    val lines: List<AnnotatedString> = withoutRepeats(
        when {
            track != null -> track.at(now).map { markup(it.text) }
            captions.status == CaptionState.Status.EMBEDDED || captions.status == CaptionState.Status.BROADCAST ->
                cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }.map { spanned(it.text!!) }
            else -> emptyList()
        },
    )
    val bitmaps = if (track == null) cues.filter { it.bitmap != null } else emptyList()
    if (lines.isEmpty() && bitmaps.isEmpty()) return

    val colour = Color(level, level, level)
    BoxWithConstraints(modifier.fillMaxSize()) {
        bitmaps.forEach { cue -> BitmapCue(cue, maxWidth.value, maxHeight.value) }
        if (lines.isNotEmpty()) {
            val style = TextStyle(
                color = colour,
                fontSize = (BASE_SP * size.scale).sp,
                lineHeight = (BASE_SP * size.scale * 1.22f).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (raised) 200.dp else 44.dp, start = 80.dp, end = if (besideMenu) MENU_WIDTH + 40.dp else 80.dp)
                    .widthIn(max = maxWidth * 0.8f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                lines.forEach { CaptionText(it, style, background) }
            }
        }
    }
}

@Composable
private fun CaptionText(text: AnnotatedString, style: TextStyle, background: Boolean) {
    if (background) {
        Text(
            text,
            style = style,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    } else {
        // An outline: the same text stroked in black underneath, then filled. A stroke, never a
        // blurred shadow, which Fire OS 5 cannot draw.
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = style.copy(color = Color.Black, drawStyle = Stroke(width = style.fontSize.value * 0.22f)))
            Text(text, style = style)
        }
    }
}

/** A sample caption in the chosen style, for the Settings page. */
@Composable
fun CaptionSample(size: CaptionSize, background: Boolean, modifier: Modifier = Modifier) {
    val style = TextStyle(
        color = Color.White,
        fontSize = (BASE_SP * size.scale).sp,
        lineHeight = (BASE_SP * size.scale * 1.22f).sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        CaptionText(markup("This is how captions will look."), style, background)
    }
}

/** An image caption (PGS, VobSub), placed where the cue says as a fraction of the screen. */
@Composable
private fun BitmapCue(cue: Cue, widthDp: Float, heightDp: Float) {
    val bitmap = cue.bitmap ?: return
    val fraction = { v: Float, fallback: Float -> if (v == Cue.DIMEN_UNSET) fallback else v }
    val w = fraction(cue.size, bitmap.width.toFloat() / 1920f) * widthDp
    val h = if (cue.bitmapHeight != Cue.DIMEN_UNSET) cue.bitmapHeight * heightDp else w * bitmap.height / bitmap.width
    val x = fraction(cue.position, 0.5f) * widthDp - when (cue.positionAnchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> w / 2
        Cue.ANCHOR_TYPE_END -> w
        else -> 0f
    }
    val y = fraction(cue.line, 0.85f) * heightDp - when (cue.lineAnchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> h / 2
        Cue.ANCHOR_TYPE_END -> h
        else -> 0f
    }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.offset(x.dp, y.dp).size(w.dp, h.dp),
    )
}

/**
 * Each caption once. Some files repeat a line in overlapping cues (captions converted from
 * roll-up TV captions carry the previous line into the next cue), which would otherwise show the
 * same words in two boxes: a cue whose every line is shown by another cue is dropped.
 */
internal fun withoutRepeats(cues: List<AnnotatedString>): List<AnnotatedString> {
    if (cues.size < 2) return cues
    fun linesOf(c: AnnotatedString) = c.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val all = cues.map(::linesOf)
    return cues.filterIndexed { i, _ ->
        val mine = all[i]
        mine.isNotEmpty() && all.indices.none { j ->
            j != i && all[j].containsAll(mine) && (all[j].size > mine.size || j < i)
        }
    }
}

/** `<i>` markup from a parsed subtitle file, as styled text. */
internal fun markup(text: String): AnnotatedString = buildAnnotatedString {
    var italic = false
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("<i>", i) -> { italic = true; i += 3 }
            text.startsWith("</i>", i) -> { italic = false; i += 4 }
            else -> {
                val next = text.indexOf('<', i + 1).let { if (it < 0) text.length else it }
                val chunk = text.substring(i, next)
                if (italic) pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(chunk)
                if (italic) pop()
                i = next
            }
        }
    }
}

/** A Media3 cue's text with its italics kept; colours and fonts are dropped for one house style. */
private fun spanned(text: CharSequence): AnnotatedString = buildAnnotatedString {
    append(text.toString().trim('\n'))
    if (text is Spanned) {
        val lead = text.toString().length - text.toString().trimStart('\n').length
        for (span in text.getSpans(0, text.length, StyleSpan::class.java)) {
            if (span.style != Typeface.ITALIC && span.style != Typeface.BOLD_ITALIC) continue
            val start = (text.getSpanStart(span) - lead).coerceIn(0, length)
            val end = (text.getSpanEnd(span) - lead).coerceIn(start, length)
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
        }
    }
}

private const val BASE_SP = 25f
private val MENU_WIDTH = 400.dp
