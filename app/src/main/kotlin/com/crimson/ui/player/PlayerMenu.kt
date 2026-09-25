package com.crimson.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.skip.Theme
import com.crimson.data.settings.AppSettings
import com.crimson.ui.components.InitialFocus
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType

class PlayerMenuActions(
    val onCaptions: () -> Unit,
    val onTryAnother: () -> Unit,
    val onNudge: (Long) -> Unit,
    val onCaptionSize: (Int) -> Unit,
    val onCaptionBackground: () -> Unit,
    val onDialogueBoost: () -> Unit,
    val onBrightness: (Int) -> Unit,
    val onSettings: () -> Unit,
    val onClose: () -> Unit,
    val onSkipIntro: () -> Unit = {},
    val onSkipEnding: () -> Unit = {},
    val onForgetThemes: () -> Unit = {},
    val onSelectAudio: (String) -> Unit = {},
    val onPreferEnglishAudio: () -> Unit = {},
)

/**
 * Captions, dialogue boost, theme-song skipping and brightness, over the video: a panel down the right-hand side, as
 * a streaming service's audio-and-subtitles menu is. Every change shows at once behind it, and is
 * remembered for next time. Up and Down move, OK switches, Left and Right adjust, BACK closes.
 */
@Composable
fun PlayerMenu(
    captions: CaptionState,
    settings: AppSettings,
    isLive: Boolean,
    actions: PlayerMenuActions,
    modifier: Modifier = Modifier,
    /** For an episode: its show's theme-song skipping. Null for films and live TV. */
    themeSkip: ThemeSkipState? = null,
    /** The title's audio tracks; a choice is only offered when there are several. */
    audioTracks: List<com.crimson.player.AudioOption> = emptyList(),
) {
    val first = remember { FocusRequester() }
    InitialFocus(first)
    Box(
        modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape || event.key == Key.Menu)) {
                    actions.onClose(); true
                } else false
            },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(470.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(400.dp)
                .fillMaxHeight()
                .background(Crimson.Surface.copy(alpha = 0.97f))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Audio & Subtitles", style = CrimsonType.Headline.copy(fontSize = 24.sp))
            Text("OK switches · ◀ ▶ adjust · BACK closes", style = CrimsonType.Caption)
            Spacer(Modifier.height(6.dp))

            Heading("Captions")
            MenuRow(
                label = "Captions (English)",
                value = if (captions.isOn) "On" else "Off",
                detail = captionDetail(captions, isLive),
                onSelect = actions.onCaptions,
                switch = captions.isOn,
                focusRequester = first,
            )
            if (captions.status == CaptionState.Status.ONLINE) {
                MenuRow(
                    label = "Timing",
                    value = nudgeLabel(captions.nudgeMs),
                    detail = "If captions come early or late: ◀ earlier, ▶ later",
                    onSelect = { actions.onNudge(-captions.nudgeMs) },
                    onLeft = { actions.onNudge(-NUDGE_MS) },
                    onRight = { actions.onNudge(NUDGE_MS) },
                )
                if (captions.choices > 1) {
                    MenuRow(
                        label = "Try different captions",
                        value = "${captions.choice + 1} of ${captions.choices}",
                        detail = "Another file for this title, if these don't match",
                        onSelect = actions.onTryAnother,
                    )
                }
            }
            MenuRow(
                label = "Size",
                value = settings.captionSize.label,
                onSelect = { actions.onCaptionSize(+1) },
                onLeft = { actions.onCaptionSize(-1) },
                onRight = { actions.onCaptionSize(+1) },
            )
            MenuRow(
                label = "Background",
                value = if (settings.captionBackground) "Dark box" else "Outline",
                onSelect = actions.onCaptionBackground,
                onLeft = actions.onCaptionBackground,
                onRight = actions.onCaptionBackground,
            )

            if (!isLive) {
                Spacer(Modifier.height(4.dp))
                Heading("Audio")
                if (audioTracks.size > 1) {
                    audioTracks.forEach { track ->
                        MenuRow(
                            label = track.label,
                            value = if (track.selected) "Playing" else null,
                            onSelect = { actions.onSelectAudio(track.id) },
                        )
                    }
                }
                MenuRow(
                    label = "English audio first",
                    value = null,
                    detail = if (audioTracks.size > 1) "Picks the English dub when a title has one" else "This title has one audio track",
                    onSelect = actions.onPreferEnglishAudio,
                    switch = settings.preferEnglishAudio,
                )
            }

            Spacer(Modifier.height(4.dp))
            Heading("Sound")
            MenuRow(
                label = "Dialogue boost",
                value = if (settings.dialogueBoost) "On" else "Off",
                detail = "Clearer voices; explosions and music turned down",
                onSelect = actions.onDialogueBoost,
                switch = settings.dialogueBoost,
            )

            if (themeSkip?.show != null) {
                Spacer(Modifier.height(4.dp))
                Heading("Theme songs · this show")
                MenuRow(
                    label = "Skip the opening theme",
                    value = null,
                    detail = themeDetail(themeSkip, Theme.Kind.INTRO),
                    onSelect = actions.onSkipIntro,
                    switch = themeSkip.choice.intro,
                )
                MenuRow(
                    label = "Skip the ending theme",
                    value = null,
                    detail = themeDetail(themeSkip, Theme.Kind.OUTRO),
                    onSelect = actions.onSkipEnding,
                    switch = themeSkip.choice.ending,
                )
                val learned = themeSkip.learnedIntros + themeSkip.learnedEndings
                if (learned > 0) {
                    MenuRow(
                        label = "Forget the learned songs",
                        value = "$learned learned",
                        detail = "If it ever skips something that isn't the theme",
                        onSelect = actions.onForgetThemes,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Heading("Picture")
            MenuRow(
                label = "Video brightness",
                value = "${settings.videoBrightness}%",
                detail = "Dims the video only, for a dark room",
                onSelect = { actions.onBrightness(+1) },
                onLeft = { actions.onBrightness(-1) },
                onRight = { actions.onBrightness(+1) },
                meter = settings.videoBrightness / 100f,
            )

            if (isLive) {
                Spacer(Modifier.height(4.dp))
                MenuRow(label = "All settings", value = null, onSelect = actions.onSettings, chevron = true)
            }
        }
    }
}

private fun captionDetail(captions: CaptionState, isLive: Boolean): String = when (captions.status) {
    CaptionState.Status.OFF -> if (isLive) "The channel's own captions, when it sends them" else "From the video, or found online and synced"
    CaptionState.Status.ONLINE -> listOfNotNull(captions.source, captions.detail).joinToString("\n")
    else -> captions.detail ?: captions.source ?: ""
}

private fun themeDetail(s: ThemeSkipState, kind: Theme.Kind): String {
    val on = if (kind == Theme.Kind.INTRO) s.choice.intro else s.choice.ending
    val learned = if (kind == Theme.Kind.INTRO) s.learnedIntros else s.learnedEndings
    val timed = if (kind == Theme.Kind.INTRO) s.times.hasIntro else s.times.hasOutro
    return when {
        !on -> "Off for this show"
        learned > 0 -> "Knows the song by ear · skips it a few seconds in"
        timed -> "Skipped by this episode's times from the intro databases · learning the song"
        else -> "Learning the song · it's skipped from the episode after it has been heard twice"
    }
}

private fun nudgeLabel(ms: Long): String =
    if (ms == 0L) "In sync" else (if (ms > 0) "+" else "−") + String.format("%.2f s", kotlin.math.abs(ms) / 1000.0)

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), style = CrimsonType.Overline.copy(color = Crimson.TextTertiary), modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun MenuRow(
    label: String,
    value: String?,
    onSelect: () -> Unit,
    detail: String? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    switch: Boolean? = null,
    meter: Float? = null,
    chevron: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Crimson.SurfaceHigh else Crimson.SurfaceRaised, tween(120), label = "menuRow")
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .tvInteractive(
                onSelect = onSelect,
                onFocus = { focused = it },
                focusRequester = focusRequester,
                onLeft = onLeft?.let { f -> { f(); true } },
                onRight = onRight?.let { f -> { f(); true } },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = CrimsonType.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            when {
                switch != null -> Switch(switch)
                value != null -> {
                    if (onLeft != null && focused) Text("◀  ", style = CrimsonType.Label.copy(color = Crimson.TextTertiary, fontSize = 11.sp))
                    Text(value, style = CrimsonType.Label.copy(fontSize = 14.sp, color = if (focused) Color.White else Crimson.TextSecondary))
                    if (onRight != null && focused) Text("  ▶", style = CrimsonType.Label.copy(color = Crimson.TextTertiary, fontSize = 11.sp))
                }
            }
            if (chevron) Icon(CrimsonIcons.ChevronRight, null, tint = Crimson.TextTertiary, modifier = Modifier.size(18.dp))
        }
        if (meter != null) {
            Spacer(Modifier.height(8.dp))
            com.crimson.ui.components.ProgressLine(meter, height = 4.dp, track = Color.White.copy(alpha = 0.14f))
        }
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(detail, style = CrimsonType.Caption, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .size(40.dp, 22.dp)
            .background(if (on) Crimson.Red else Crimson.SurfaceHigh, RoundedCornerShape(50))
            .border(1.dp, Crimson.Stroke, RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).background(Color.White, CircleShape))
    }
}

private const val NUDGE_MS = 250L
