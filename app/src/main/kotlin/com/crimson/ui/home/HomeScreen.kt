package com.crimson.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.ClockBox
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroPage
import com.crimson.ui.theme.RetroWordmark
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.retroPanel

/**
 * The main menu: six tiles, a header with the wordmark and the clock, and the button bar.
 *
 * Styled after the main menu of a 2000s cable box — gradient tiles with bevelled edges, a
 * coloured badge in each corner, the highlight in gradient yellow with a gloss. Every tile
 * works from the D-pad and from a mouse.
 */
@Composable
fun HomeScreen(
    channelCount: Int,
    favoriteChannelCount: Int,
    nowMs: Long,
    theme: GuideTheme,
    onNavigateLiveTv: () -> Unit,
    onNavigateOnDemand: () -> Unit,
    onNavigateBrowse: () -> Unit,
    onNavigateGuide: () -> Unit,
    onNavigateFavorites: () -> Unit,
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveTvFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { liveTvFocus.requestFocus() }
    }

    RetroPage(
        theme = theme,
        modifier = modifier,
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .retroPanel(theme, theme.chromeBar, corner = 3.dp, edge = theme.chromeBarEdge)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    RetroWordmark(theme)
                    Text(
                        text = "INTERACTIVE PROGRAM GUIDE",
                        style = theme.captionStyle(),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    ClockBox(nowMs = nowMs, theme = theme)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$channelCount CHANNELS",
                        style = theme.captionStyle(theme.highlight),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .retroPanel(theme, theme.chromeRule, corner = 0.dp, edge = null)
            )
        },
        buttons = listOf(
            BarButton("GUIDE", onNavigateGuide, key = "A", keyColor = theme.keyRed),
            BarButton("BROWSE", onNavigateBrowse, key = "B", keyColor = theme.keyGreen),
            BarButton("FAVORITES", onNavigateFavorites, key = "\u2605", keyColor = theme.keyYellow),
            BarButton("SETTINGS", onNavigateSettings, key = "MENU", keyColor = theme.keyBlue),
        ),
        hint = "▲▼◄► MOVE  •  SELECT OPENS  •  MOUSE: HOVER AND CLICK",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeMenuTile(
                    title = "LIVE TV",
                    subtitle = "American Cable lineup",
                    badge = "TV",
                    theme = theme,
                    onClick = onNavigateLiveTv,
                    focusRequester = liveTvFocus,
                    modifier = Modifier.weight(1f),
                )
                HomeMenuTile(
                    title = "ON DEMAND",
                    subtitle = "Films and complete series",
                    badge = "VOD",
                    theme = theme,
                    onClick = onNavigateOnDemand,
                    modifier = Modifier.weight(1f),
                )
                HomeMenuTile(
                    title = "BROWSE",
                    subtitle = "Search everything, curated picks, channel packages",
                    badge = "NEW",
                    theme = theme,
                    onClick = onNavigateBrowse,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeMenuTile(
                    title = "GUIDE",
                    subtitle = "Full-screen program grid",
                    badge = "EPG",
                    theme = theme,
                    onClick = onNavigateGuide,
                    modifier = Modifier.weight(1f),
                )
                HomeMenuTile(
                    title = "FAVORITES",
                    subtitle = if (favoriteChannelCount > 0) "$favoriteChannelCount starred channels" else "Star channels in the guide to fill this",
                    badge = "★",
                    theme = theme,
                    onClick = onNavigateFavorites,
                    modifier = Modifier.weight(1f),
                )
                HomeMenuTile(
                    title = "SETTINGS",
                    subtitle = "Filters, markets & account",
                    badge = "SETUP",
                    theme = theme,
                    onClick = onNavigateSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomeMenuTile(
    title: String,
    subtitle: String,
    badge: String,
    theme: GuideTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .retroPanel(
                theme = theme,
                base = if (hasFocus) theme.highlight else theme.panel,
                corner = 5.dp,
                edge = if (hasFocus) theme.highlight else theme.panelEdge,
                edgeWidth = if (hasFocus) 2.dp else 1.dp,
                gloss = true,
            )
            .tvInteractive(
                onSelect = onClick,
                onFocus = { hasFocus = it },
                focusRequester = focusRequester,
            )
            .padding(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    style = theme.headingStyle(if (hasFocus) theme.highlightText else theme.infoTitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .retroPanel(
                            theme = theme,
                            base = if (hasFocus) theme.keyRed else theme.keyBlue,
                            corner = 3.dp,
                            edge = theme.bevelDark,
                            gloss = true,
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badge,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = subtitle,
                color = if (hasFocus) theme.highlightText else theme.infoDetail,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
