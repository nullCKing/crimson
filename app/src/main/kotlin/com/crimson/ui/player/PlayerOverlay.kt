package com.crimson.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
)

/**
 * Everything over full-screen video.
 *
 * Live, it is a channel banner in the manner of a live streaming service: the channel, what is on
 * and how far in, and what is next, for a few seconds after every change. For a film or an
 * episode it is a streaming player's controls: title at the top, scrubber and transport at the
 * bottom, shown on any key and whenever paused. Nothing stays up for long, and nothing sits over
 * the picture while it plays: any full-screen blended layer costs a Fire TV Stick on every frame.
 */
@Composable
fun PlayerOverlay(
    playback: PlaybackState,
    banner: BannerState,
    nowPlaying: NowPlaying?,
    controlsToken: Long,
    nowMs: Long,
    pendingDigits: String,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    var pointerAt by remember { mutableLongStateOf(0L) }
    Box(
        modifier
            .fillMaxSize()
            .onPointerActivity { pointerAt = System.currentTimeMillis() }
            .then(if (nowPlaying?.isVod == true) Modifier else Modifier.mouseWheel(actions.onChannelUp, actions.onChannelDown))
            .tvInteractive(onSelect = { if (nowPlaying?.isVod == true) actions.onTogglePause() else actions.onSelect() }, focusTarget = false),
    ) {
        if (nowPlaying?.isVod == true) {
            VodControls(playback, nowPlaying, maxOf(controlsToken, pointerAt), actions)
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
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
                    "▲ ▼  Channels      OK  Guide      BACK  Exit",
                    style = CrimsonType.Caption.copy(color = Crimson.TextTertiary, fontSize = 10.sp),
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = (-30).dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- films and episodes

@Composable
private fun VodControls(playback: PlaybackState, nowPlaying: NowPlaying, token: Long, actions: PlayerActions) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(token, playback.isPaused) {
        visible = true
        if (!playback.isPaused) {
            delay(CONTROLS_MS)
            visible = false
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible, playback.isEnded) {
        while (visible || playback.isEnded) {
            position = actions.position()
            duration = actions.duration()
            delay(500)
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
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(190.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
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
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Crimson.ScreenPadding, vertical = 26.dp)) {
                    Scrubber(position, duration)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TransportButton(CrimsonIcons.Rewind, 40.dp) { actions.onSeekBy(-10_000L) }
                        Spacer(Modifier.width(14.dp))
                        TransportButton(if (playback.isPaused || playback.isEnded) CrimsonIcons.Play else CrimsonIcons.Pause, 52.dp, primary = true) { actions.onTogglePause() }
                        Spacer(Modifier.width(14.dp))
                        TransportButton(CrimsonIcons.Forward, 40.dp) { actions.onSeekBy(10_000L) }
                        Spacer(Modifier.width(22.dp))
                        Text("LEFT/RIGHT  10s     OK  ${if (playback.isPaused) "Play" else "Pause"}     REW/FF  30s", style = CrimsonType.Caption.copy(fontSize = 10.sp))
                        Spacer(Modifier.weight(1f))
                        nowPlaying.next?.let { next ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Crimson.Glass)
                                    .tvInteractive(onSelect = actions.onNext, focusTarget = false)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(CrimsonIcons.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Next Episode  ·  E${next.number}", style = CrimsonType.Label.copy(fontWeight = FontWeight.Bold))
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

@Composable
private fun Scrubber(position: Long, duration: Long) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), style = CrimsonType.Label.copy(fontSize = 12.sp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.CenterStart) {
            ProgressLine(fraction, height = 4.dp, track = Color.White.copy(alpha = 0.25f))
            val density = LocalDensity.current
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val x = with(density) { (constraints.maxWidth * fraction).toDp() } - 7.dp
                Box(
                    Modifier
                        .offset(x = x.coerceAtLeast(0.dp))
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Crimson.Red)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(if (duration > 0) "-" + formatTime(duration - position) else "--:--", style = CrimsonType.Label.copy(fontSize = 12.sp, color = Crimson.TextSecondary))
    }
}

@Composable
private fun TransportButton(icon: ImageVector, size: Dp, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.14f))
            .tvInteractive(onSelect = onClick, focusTarget = false),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(size * 0.52f))
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
            .clip(RoundedCornerShape(10.dp))
            .background(Crimson.SurfaceRaised.copy(alpha = 0.96f))
            .tvInteractive(onSelect = onNext, focusTarget = false)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(6.dp)).background(Crimson.SurfaceHigh)) {
            val art = next.image ?: nowPlaying.backdrop
            if (!art.isNullOrBlank()) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
        style = CrimsonType.Display.copy(fontSize = 52.sp, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 16f)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

/** The small label shown while a stalled stream is being retried. */
@Composable
fun ReconnectingLabel(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.7f))
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
            .clip(RoundedCornerShape(12.dp))
            .background(Crimson.SurfaceRaised.copy(alpha = 0.96f))
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
const val CONTROLS_MS = 4_000L
