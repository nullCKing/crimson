package com.crimson.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.skip.Theme
import com.crimson.ui.theme.CrimsonType
import kotlinx.coroutines.delay

/**
 * "Skipped the opening theme", for a few seconds after a skip, top right, so a jump is never a
 * mystery. Going back into the song plays it: a theme is skipped once per episode.
 */
@Composable
fun ThemeSkipNote(skipped: ThemeSkipState.Skipped?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(skipped?.at) {
        if (skipped == null) return@LaunchedEffect
        visible = true
        delay(NOTE_MS)
        visible = false
    }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible && skipped != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 48.dp),
        ) {
            val what = if (skipped?.kind == Theme.Kind.OUTRO) "ending" else "opening"
            Text(
                "Skipped the $what theme",
                style = CrimsonType.Label.copy(fontSize = 14.sp, color = Color.White),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

private const val NOTE_MS = 4_000L
