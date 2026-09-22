package com.crimson.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType

/**
 * An on-screen keyboard for the search page.
 *
 * A television's system keyboard covers half the screen and hides the results it is typing for;
 * streaming apps all draw their own compact grid beside the results instead, so each letter
 * visibly narrows what is on the right. A hardware keyboard works as well — the search page
 * listens for typed characters directly.
 */
@Composable
fun OnScreenKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKeyRequester: FocusRequester? = null,
    keySize: Dp = 38.dp,
) {
    val rows = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyButton(width = keySize * 3 + 10.dp, height = keySize, icon = CrimsonIcons.Space, label = "SPACE", onClick = { onChar(' ') })
            KeyButton(width = keySize * 3 / 2 + 2.dp, height = keySize, icon = CrimsonIcons.Backspace, onClick = onBackspace)
            KeyButton(width = keySize * 3 / 2 + 3.dp, height = keySize, label = "CLEAR", onClick = onClear)
        }
        rows.forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                keys.forEachIndexed { c, ch ->
                    KeyButton(
                        width = keySize,
                        height = keySize,
                        label = ch.uppercase(),
                        onClick = { onChar(ch) },
                        focusRequester = if (r == 0 && c == 0) firstKeyRequester else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    label: String? = null,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White else Crimson.SurfaceRaised, tween(90), label = "keyBg")
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .tvInteractive(onSelect = onClick, onFocus = { focused = it }, focusRequester = focusRequester),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (focused) Color.Black else Crimson.TextPrimary
        when {
            icon != null && label != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Box(Modifier.width(4.dp))
                Text(label, style = CrimsonType.Caption.copy(color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp))
            }
            icon != null -> Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            label != null -> Text(
                label,
                style = CrimsonType.Label.copy(color = color, fontSize = if (label.length > 1) 10.sp else 15.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** The line that shows what has been typed, with a blinking caret. */
@Composable
fun SearchQueryField(query: String, placeholder: String, modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
    val caret by transition.animateFloat(
        1f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(tween(530), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Crimson.SurfaceRaised)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CrimsonIcons.Search, null, tint = Crimson.TextTertiary, modifier = Modifier.size(20.dp))
        Box(Modifier.width(10.dp))
        if (query.isEmpty()) {
            Text(placeholder, style = CrimsonType.Body.copy(color = Crimson.TextTertiary), maxLines = 1)
        } else {
            Text(query, style = CrimsonType.Title.copy(fontSize = 17.sp), maxLines = 1)
        }
        Box(
            Modifier
                .width(2.dp)
                .height(20.dp)
                .background(Crimson.Red.copy(alpha = caret)),
        )
    }
}
