package com.crimson.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.player.PlaybackState
import com.crimson.ui.BannerState
import com.crimson.ui.NowPlaying
import com.crimson.ui.components.LiveBadge
import com.crimson.ui.components.ProgressLine
import com.crimson.ui.components.Spinner
import com.crimson.ui.input.mouseWheel
import com.crimson.ui.input.onPointerActivity
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.live.timeRange
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActions(
    val onSelect: () -> Unit,
    val onChannelUp: () -> Unit,
    val onChannelDown: () -> Unit,
    val onTogglePause: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val position: () -> Long,
    val duration: () -> Long,
    val onMenu: () -> Unit = {},
    /** The pointer moved or clicked: bring the controls up. */
    val onPointer: () -> Unit = {},
)

/**
 * Everything over full-screen video.
 *
 * Live, it is a channel banner in the manner of a live streaming service: the channel, what is on
 * and how far in, and what is next, for a few seconds after every change. For a film or an
 * episode it is a streaming player's controls ([VodControls]): title at the top, a time bar and a
 * row of buttons at the bottom that the remote moves between, shown on any key and whenever
 * paused. Nothing stays up for long, and nothing sits over
 * the picture while it plays: any full-screen blended layer costs a Fire TV Stick on every frame.
 */
@Composable
fun PlayerOverlay(
    playback: PlaybackState,
    banner: BannerState,
    nowPlaying: NowPlaying?,
    vodControls: VodControls,
    nowMs: Long,
    pendingDigits: String,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    captionsOn: Boolean = false,
) {
    var pointerAt by remember { mutableLongStateOf(0L) }
    Box(
        modifier
            .fillMaxSize()
            .onPointerActivity {
                pointerAt = System.currentTimeMillis()
                if (nowPlaying?.isVod == true) actions.onPointer()
            }
            .then(if (nowPlaying?.isVod == true) Modifier else Modifier.mouseWheel(actions.onChannelUp, actions.onChannelDown))
            .tvInteractive(onSelect = { if (nowPlaying?.isVod == true) actions.onTogglePause() else actions.onSelect() }, focusTarget = false),
    ) {
        if (nowPlaying?.isVod == true) {
            VodControlsView(playback, nowPlaying, vodControls, actions, captionsOn)
        } else {
            LiveBanner(banner, nowMs, maxOf(banner.showToken, pointerAt))
        }

        if (playback.isBuffering && !playback.isReconnecting && playback.error == null) {
            Spinner(Modifier.align(Alignment.Center), size = 56.dp, stroke = 4.dp)
        }
        if (playback.isReconnecting) {
            ReconnectingLabel(Modifier.align(Alignment.TopEnd).padding(32.dp))
        }
        if (pendingDigits.isNotEmpty()) {
            ChannelNumberEntry(pendingDigits, Modifier.align(Alignment.TopEnd).padding(40.dp))
        }
        playback.error?.let { message ->
            PlaybackErrorPanel(message, actions.onRetry, actions.onBack, Modifier.align(Alignment.Center))
        }
    }
}

// ---------------------------------------------------------------------- live

@Composable
private fun LiveBanner(banner: BannerState, nowMs: Long, token: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (token == 0L) return@LaunchedEffect
        visible = true
        delay(BANNER_MS)
        visible = false
    }
    val channel = banner.channel ?: return
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(300)) { it / 3 } + fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.92f))))
                    .padding(start = Crimson.ScreenPadding, end = Crimson.ScreenPadding, top = 56.dp, bottom = 30.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(
                        Modifier
                            .size(96.dp, 60.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.crimson.ui.components.ChannelLogo(channel.logoUrl, channel.shortName, Modifier.padding(8.dp).fillMaxSize(), textSize = 13.sp)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveBadge()
                            Spacer(Modifier.width(10.dp))
                            Text(
                                (if (channel.number > 0) "${channel.number}  " else "") + channel.name.uppercase() + if (channel.isFavorite) "  ★" else "",
                                style = CrimsonType.Overline.copy(color = Crimson.TextSecondary, fontSize = 11.sp),
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            banner.now?.title ?: channel.name,
                            style = CrimsonType.Headline.copy(fontSize = 26.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        banner.now?.let { now ->
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(timeRange(now.startMs, now.endMs), style = CrimsonType.Label.copy(color = Crimson.TextSecondary))
                                Spacer(Modifier.width(12.dp))
                                val progress = if (now.endMs > now.startMs) (nowMs - now.startMs).toFloat() / (now.endMs - now.startMs) else 0f
                                ProgressLine(progress, Modifier.width(220.dp), height = 4.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("${((now.endMs - nowMs) / 60_000L).coerceAtLeast(0)} min left", style = CrimsonType.Caption)
                            }
                            if (now.description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(now.description, style = CrimsonType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    banner.next?.let { next ->
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.width(220.dp)) {
                            Text("UP NEXT", style = CrimsonType.Overline.copy(color = Crimson.TextTertiary, fontSize = 9.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(next.title, style = CrimsonType.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(next.startMs)), style = CrimsonType.Caption)
                        }
                    }
                }
                Text(
                    "▲ ▼  Channels      OK  Guide      ≡  Audio & subtitles      BACK  Exit",
                    style = CrimsonType.Caption.copy(color = Crimson.TextTertiary, fontSize = 10.sp),
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = (-30).dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- films and episodes

@Composable
private fun VodControlsView(playback: PlaybackState, nowPlaying: NowPlaying, controls: VodControls, actions: PlayerActions, captionsOn: Boolean) {
    val visible = controls.visible || playback.isPaused
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible, playback.isEnded) {
        while (visible || playback.isEnded) {
            position = actions.position()
            duration = actions.duration()
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(300)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(150.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                )
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(230.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                )
                Row(Modifier.padding(start = Crimson.ScreenPadding, top = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        CrimsonIcons.Back, "Back", tint = Color.White,
                        modifier = Modifier.size(26.dp).tvInteractive(onSelect = actions.onBack, focusTarget = false),
                    )
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(nowPlaying.title, style = CrimsonType.Title.copy(fontSize = 21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!nowPlaying.subtitle.isNullOrBlank()) {
                            Text(nowPlaying.subtitle, style = CrimsonType.Body.copy(color = Crimson.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Crimson.ScreenPadding, vertical = 28.dp)) {
                    TimeBar(position, duration, controls.scrubMs, focused = controls.button == null)
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val focus = controls.button
                        RoundButton(
                            if (playback.isPaused || playback.isEnded) CrimsonIcons.Play else CrimsonIcons.Pause,
                            focus == VodButton.PLAY_PAUSE, 56.dp,
                        ) { actions.onTogglePause() }
                        Spacer(Modifier.width(14.dp))
                        RoundButton(CrimsonIcons.Rewind, focus == VodButton.BACK_10, 46.dp, label = "10") { actions.onSeekBy(-10_000L) }
                        Spacer(Modifier.width(12.dp))
                        RoundButton(CrimsonIcons.Forward, focus == VodButton.FORWARD_10, 46.dp, label = "10") { actions.onSeekBy(10_000L) }
                        Spacer(Modifier.weight(1f))
                        PillButton(focus == VodButton.AUDIO_SUBTITLES, actions.onMenu) {
                            Text(
                                "CC",
                                style = CrimsonType.Label.copy(fontSize = 10.sp, fontWeight = FontWeight.Black, color = it),
                                modifier = Modifier
                                    .border(1.5.dp, it, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(if (captionsOn) "Audio & Subtitles  ·  On" else "Audio & Subtitles", style = CrimsonType.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = it))
                        }
                        nowPlaying.next?.let { next ->
                            Spacer(Modifier.width(14.dp))
                            PillButton(focus == VodButton.NEXT_EPISODE, actions.onNext) {
                                Icon(CrimsonIcons.SkipNext, null, tint = it, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Next Episode  ·  E${next.number}", style = CrimsonType.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = it))
                            }
                        }
                    }
                }
            }
        }
        if (playback.isEnded && nowPlaying.next != null) {
            NextEpisodeCard(nowPlaying, Modifier.align(Alignment.BottomEnd).padding(Crimson.ScreenPadding), actions.onNext)
        }
    }
}

/**
 * Where the video is, as a bar across the screen, red as far as it has played. With focus it
 * thickens and its knob turns white; while it is being moved, the knob and a time above it show
 * where playback will jump to.
 */
@Composable
private fun TimeBar(position: Long, duration: Long, scrubMs: Long?, focused: Boolean) {
    val shown = scrubMs ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val playedFraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), style = CrimsonType.Label.copy(fontSize = 13.sp))
        Spacer(Modifier.width(16.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.CenterStart) {
            val barHeight = if (focused) 6.dp else 4.dp
            val width = maxWidth
            Box(Modifier.fillMaxWidth().height(barHeight).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)))
            if (scrubMs != null && fraction > playedFraction) {
                // Ahead of what has been watched, lighter, out to where it will jump to.
                Box(Modifier.width(width * fraction).height(barHeight).background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(50)))
            }
            Box(Modifier.width(width * playedFraction).height(barHeight).background(Crimson.Red, RoundedCornerShape(50)))
            val knob = if (focused) 20.dp else 14.dp
            Box(
                Modifier
                    .offset(x = (width * fraction - knob / 2).coerceIn(0.dp, width - knob))
                    .size(knob)
                    .background(if (focused) Color.White else Crimson.Red, CircleShape)
                    .then(if (focused) Modifier.border(3.dp, Crimson.Red, CircleShape) else Modifier)
            )
            if (scrubMs != null) {
                val bubble = 86.dp
                Text(
                    formatTime(scrubMs),
                    style = CrimsonType.Label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .offset(x = (width * fraction - bubble / 2).coerceIn(0.dp, width - bubble), y = (-30).dp)
                        .width(bubble)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .padding(vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(if (duration > 0) "-" + formatTime(duration - position) else "--:--", style = CrimsonType.Label.copy(fontSize = 13.sp, color = Crimson.TextSecondary))
    }
}

/** A round transport button: white with a dark icon when focused, glass otherwise. */
@Composable
private fun RoundButton(icon: ImageVector, focused: Boolean, size: Dp, label: String? = null, onClick: () -> Unit) {
    val content = if (focused) Color.Black else Color.White
    Box(
        Modifier
            .size(size)
            .background(if (focused) Color.White else Crimson.ControlFill, CircleShape)
            .tvInteractive(onSelect = onClick, focusTarget = false),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.5f))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.36f))
                Text(label, style = CrimsonType.Label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = content))
            }
        }
    }
}

/** A labelled button on the right of the row: white when focused, glass otherwise. */
@Composable
private fun PillButton(focused: Boolean, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .background(if (focused) Color.White else Crimson.ControlFill, RoundedCornerShape(8.dp))
            .tvInteractive(onSelect = onClick, focusTarget = false)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(if (focused) Color.Black else Color.White)
    }
}

@Composable
private fun NextEpisodeCard(nowPlaying: NowPlaying, modifier: Modifier, onNext: () -> Unit) {
    val next = nowPlaying.next ?: return
    var seconds by remember { mutableLongStateOf(6L) }
    LaunchedEffect(next.id) {
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
    }
    Row(
        modifier
            .background(Crimson.SurfaceRaised.copy(alpha = 0.96f), RoundedCornerShape(10.dp))
            .tvInteractive(onSelect = onNext, focusTarget = false)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(128.dp, 72.dp).background(Crimson.SurfaceHigh, RoundedCornerShape(6.dp))) {
            val art = next.image ?: nowPlaying.backdrop
            if (!art.isNullOrBlank()) com.crimson.ui.components.RoundedImage(art, null, 6.dp, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.width(200.dp)) {
            Text("NEXT EPISODE", style = CrimsonType.Overline)
            Spacer(Modifier.height(4.dp))
            Text("E${next.number} · ${next.title}", style = CrimsonType.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(if (seconds > 0) "Playing in $seconds… · OK to play now" else "Starting…", style = CrimsonType.Caption)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

// ---------------------------------------------------------------------- shared

/** The channel number being typed on a numeric remote or keyboard. */
@Composable
fun ChannelNumberEntry(digits: String, modifier: Modifier = Modifier) {
    Text(
        digits,
        // No blurred shadow: the dark plate already separates the digits from the video, and a
        // blurred text shadow crashes the renderer on Fire OS 5 (see BlurredTextShadows).
        style = CrimsonType.Display.copy(fontSize = 52.sp),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

/** The small label shown while a stalled stream is being retried. */
@Composable
fun ReconnectingLabel(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(size = 16.dp, stroke = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Reconnecting…", style = CrimsonType.Label)
    }
}

@Composable
fun PlaybackErrorPanel(message: String, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(420.dp)
            .background(Crimson.SurfaceRaised.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This stream isn't playing", style = CrimsonType.Title)
        Spacer(Modifier.height(6.dp))
        Text(
            "The provider stopped sending video ($message). It may be offline, or the account's connection limit may be in use elsewhere.",
            style = CrimsonType.Body,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.crimson.ui.components.CrimsonButton("Try Again", onRetry, icon = CrimsonIcons.Refresh, style = com.crimson.ui.components.ButtonStyle.PRIMARY)
            com.crimson.ui.components.CrimsonButton("Back", onBack)
        }
        Spacer(Modifier.height(8.dp))
        Text("OK retries", style = CrimsonType.Caption)
    }
}

const val BANNER_MS = 5_000L
