package com.crimson.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crimson.player.PlaybackState
import com.crimson.ui.BannerState
import com.crimson.ui.input.mouseWheel
import com.crimson.ui.input.onPointerActivity
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroButton
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.retroPanel
import kotlinx.coroutines.delay

/** What the player screen can ask the app to do, from the remote or from the mouse toolbar. */
data class WatchingActions(
    val onSelect: () -> Unit = {},
    val onChannelUp: () -> Unit = {},
    val onChannelDown: () -> Unit = {},
    val onGuide: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onLastChannel: () -> Unit = {},
    val onInfo: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * What sits on top of full-screen video: the channel banner after a change, a small
 * "Reconnecting…" label while a stalled stream is being retried, an error panel once the
 * retry budget is spent, the channel number being typed, and — only while a mouse is moving — a
 * toolbar of the things the remote's buttons do.
 *
 * Nothing here is ever on screen for long. Full-screen playback is the app's primary job, and the
 * overlay's business is to get out of the way. There is deliberately no scanline or tint over the
 * video: any full-screen blended layer costs a Fire TV Stick on every decoded frame.
 */
@Composable
fun WatchingOverlay(
    playback: PlaybackState,
    banner: BannerState,
    nowMs: Long,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    pendingChannelNumber: String = "",
    actions: WatchingActions = WatchingActions(),
) {
    var bannerVisible by remember { mutableStateOf(false) }

    // Restarts on every tune, including a re-tune to the same channel, because showToken changes.
    LaunchedEffect(banner.showToken) {
        if (banner.showToken == 0L) return@LaunchedEffect
        bannerVisible = true
        delay(theme.bannerVisibleMillis)
        bannerVisible = false
    }

    // The mouse toolbar shows while the pointer is moving and hides a few seconds after it stops.
    var lastPointerActivity by remember { mutableLongStateOf(0L) }
    var toolbarVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastPointerActivity) {
        if (lastPointerActivity == 0L) return@LaunchedEffect
        toolbarVisible = true
        delay(MOUSE_TOOLBAR_MILLIS)
        toolbarVisible = false
    }

    Box(
        modifier = modifier
            .onPointerActivity { lastPointerActivity = System.currentTimeMillis() }
            .mouseWheel(onUp = actions.onChannelUp, onDown = actions.onChannelDown)
            // A click on the picture is Select: open the guide, or retry after an error.
            .tvInteractive(onSelect = actions.onSelect, focusTarget = false),
    ) {
        if (playback.isReconnecting) {
            ReconnectingLabel(
                theme = theme,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp),
            )
        }

        if (pendingChannelNumber.isNotEmpty()) {
            ChannelNumberEntry(
                digits = pendingChannelNumber,
                theme = theme,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp),
            )
        }

        playback.error?.let { message ->
            PlaybackErrorPanel(
                message = message,
                theme = theme,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = bannerVisible && playback.error == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            val channel = banner.channel
            if (channel != null) {
                ChannelBanner(
                    number = channel.number,
                    name = channel.name,
                    now = banner.now,
                    next = banner.next,
                    nowMs = nowMs,
                    theme = theme,
                    logoUrl = channel.logoUrl,
                    isFavorite = channel.isFavorite,
                )
            }
        }

        AnimatedVisibility(
            visible = toolbarVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        ) {
            MouseToolbar(
                theme = theme,
                isFavorite = banner.channel?.isFavorite == true,
                actions = actions,
            )
        }
    }
}

/**
 * The toolbar a mouse user gets over the video. Every button is one the remote already has;
 * there is nothing here a remote cannot do, so the two ways of driving the app stay identical.
 */
@Composable
private fun MouseToolbar(theme: GuideTheme, isFavorite: Boolean, actions: WatchingActions) {
    val buttons = listOf(
        BarButton("CH ▲", actions.onChannelUp, key = "▲", keyColor = theme.keyBlue),
        BarButton("CH ▼", actions.onChannelDown, key = "▼", keyColor = theme.keyBlue),
        BarButton("GUIDE", actions.onGuide, key = "OK", keyColor = theme.keyGreen),
        BarButton("INFO", actions.onInfo, key = "i", keyColor = theme.keyBlue),
        BarButton(if (isFavorite) "UNSTAR" else "STAR", actions.onToggleFavorite, key = "★", keyColor = theme.keyYellow),
        BarButton("LAST", actions.onLastChannel, key = "▶‖", keyColor = theme.keyRed),
        BarButton("HOME", actions.onHome, key = "◄", keyColor = theme.keyBlue),
        BarButton("SETTINGS", actions.onOpenSettings, key = "MENU", keyColor = theme.keyBlue),
    )
    Row(
        modifier = Modifier
            .height(theme.buttonBarHeight + 8.dp)
            .retroPanel(theme, theme.mouseBarBackground, corner = 4.dp, edge = theme.chromeBarEdge)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        for (button in buttons) RetroButton(button = button, theme = theme)
    }
}

/**
 * The number the viewer is typing, in the corner where a cable box put it. Shown for the moment
 * between the first digit and the tune.
 */
@Composable
fun ChannelNumberEntry(digits: String, theme: GuideTheme, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .retroPanel(theme, theme.chromeBar, corner = 4.dp, edge = theme.highlight, edgeWidth = 2.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = digits + "_".takeIf { digits.length < com.crimson.ui.CrimsonViewModel.MAX_CHANNEL_DIGITS }.orEmpty(),
            style = theme.headingStyle(theme.channelNumber).copy(fontSize = theme.titleSize * 1.4f),
        )
        Text(text = "CHANNEL", style = theme.captionStyle())
    }
}

/** Overlaid while the player is retrying a stalled stream. */
@Composable
fun ReconnectingLabel(theme: GuideTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .retroPanel(theme, theme.chromeBar, corner = 3.dp, edge = theme.chromeBarEdge)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Reconnecting…",
            color = theme.cellText,
            fontSize = theme.detailSize,
            fontFamily = theme.fontFamily,
        )
    }
}

/** Shown once the retry budget is spent. */
@Composable
fun PlaybackErrorPanel(
    message: String,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(560.dp)
            .retroPanel(theme, theme.panel, corner = 5.dp, edge = theme.keyRed, edgeWidth = 2.dp)
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "This channel could not be played.",
            color = theme.infoTitle,
            fontSize = theme.detailSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = message,
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
        Text(
            text = "Press SELECT or click to try again, or UP and DOWN to change channel.",
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
    }
}

private const val MOUSE_TOOLBAR_MILLIS = 3_500L
