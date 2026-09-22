package com.crimson.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crimson.core.model.RawEpisode
import com.crimson.core.model.RawSeriesInfo
import com.crimson.core.model.RawVodInfo
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel

/**
 * The details dialogs, and the button they are built from.
 *
 * They were the tail of the Movies screen and the tail of the TV Shows screen, which are now one
 * On Demand screen; the dialogs themselves did not need to change, so they moved here rather than
 * being rewritten. [DialogButton] lives here too because the login screen and the guide's
 * future-programme dialog had already borrowed it.
 */

@Composable
internal fun MovieDetailsDialog(
    info: RawVodInfo,
    theme: GuideTheme,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val playButtonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { playButtonFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .width(680.dp)
            .retroPanel(theme, theme.panel, corner = 6.dp, edge = theme.highlight, edgeWidth = 2.dp)
            // Swallows clicks so they do not fall through to the scrim and close the dialog.
            .tvInteractive(onSelect = {}, focusTarget = false)
            .padding(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Poster
            Box(
                modifier = Modifier
                    .width(170.dp)
                    .aspectRatio(0.67f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.background),
                contentAlignment = Alignment.Center,
            ) {
                if (!info.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = info.coverUrl,
                        contentDescription = info.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(text = "NO POSTER", color = theme.infoDetail)
                }
            }

            Spacer(Modifier.width(20.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(text = info.name, style = theme.headingStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)

                Spacer(Modifier.height(4.dp))

                val meta = listOfNotNull(
                    info.releaseDate?.take(4),
                    info.duration?.let { "$it mins" },
                    info.rating?.takeIf { it.isNotBlank() }?.let { "Rating: $it" },
                ).joinToString("  •  ")

                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = theme.highlight,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                val description = info.description
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        color = theme.cellText,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (!info.cast.isNullOrBlank()) {
                    Text(
                        text = "Cast: ${info.cast}",
                        color = theme.infoDetail,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DialogButton(
                        label = "▶ PLAY MOVIE",
                        primary = true,
                        theme = theme,
                        onClick = onPlay,
                        focusRequester = playButtonFocus,
                    )
                    DialogButton(label = "CLOSE", primary = false, theme = theme, onClick = onDismiss)
                }
            }
        }
    }
}

/**
 * A raised button inside a dialog, usable from the D-pad and from a mouse. With [focusTarget]
 * false it is a mouse target only and lights up on hover instead, for dialogs whose keys the
 * screen behind them already handles.
 */
@Composable
internal fun DialogButton(
    label: String,
    primary: Boolean,
    theme: GuideTheme,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    focusTarget: Boolean = true,
) {
    var lit by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .retroPanel(
                theme = theme,
                base = if (lit) theme.highlight else if (primary) theme.panelSelected else theme.panel,
                corner = 3.dp,
                edge = if (lit || primary) theme.highlight else theme.panelEdge,
                gloss = true,
            )
            .tvInteractive(
                onSelect = onClick,
                onFocus = { lit = it },
                onHover = { lit = it },
                focusRequester = focusRequester,
                focusTarget = focusTarget,
            )
            .padding(horizontal = 22.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = theme.labelStyle(if (lit) theme.highlightText else theme.cellText),
        )
    }
}

@Composable
internal fun SeriesDetailsDialog(
    info: RawSeriesInfo,
    theme: GuideTheme,
    onPlayEpisode: (episodeId: Long, extension: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var activeSeason by remember(info) { mutableStateOf(info.seasons.firstOrNull() ?: 1) }

    Box(
        modifier = Modifier
            .width(760.dp)
            .height(440.dp)
            .retroPanel(theme, theme.panel, corner = 6.dp, edge = theme.highlight, edgeWidth = 2.dp)
            // Swallows clicks so they do not fall through to the scrim and close the dialog.
            .tvInteractive(onSelect = {}, focusTarget = false)
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Cover
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .aspectRatio(0.67f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!info.cover.isNullOrBlank()) {
                        AsyncImage(
                            model = info.cover,
                            contentDescription = info.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("POSTER", color = theme.infoDetail, fontSize = theme.sectionSize)
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = info.name, style = theme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val plot = info.plot
                    if (!plot.isNullOrBlank()) {
                        Text(
                            text = plot,
                            color = theme.cellText,
                            fontSize = theme.detailSize,
                            fontFamily = theme.fontFamily,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Season Tabs
            if (info.seasons.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(info.seasons) { seasonNum ->
                        var isFocused by remember { mutableStateOf(false) }
                        val isSelected = seasonNum == activeSeason

                        Box(
                            modifier = Modifier
                                .retroPanel(
                                    theme = theme,
                                    base = when {
                                        isFocused -> theme.highlight
                                        isSelected -> theme.tabFillCurrent
                                        else -> theme.panelSelected
                                    },
                                    corner = 3.dp,
                                    edge = theme.panelEdge,
                                    gloss = true,
                                )
                                .tvInteractive(onSelect = { activeSeason = seasonNum }, onFocus = { isFocused = it })
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Season $seasonNum",
                                color = if (isFocused || isSelected) theme.highlightText else theme.cellText,
                                fontSize = theme.sectionSize,
                                fontFamily = theme.fontFamily,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Episode list for active season
            val currentEpisodes = info.episodes[activeSeason].orEmpty()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .retroPanel(theme, theme.background, corner = 3.dp, edge = theme.panelEdge)
                    .padding(8.dp),
            ) {
                if (currentEpisodes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No episodes found for this season.", color = theme.infoDetail)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(currentEpisodes) { ep ->
                            EpisodeRow(
                                episode = ep,
                                theme = theme,
                                onClick = { onPlayEpisode(ep.id, ep.containerExtension) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Footer / Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Press BACK to close", style = theme.captionStyle())
                Spacer(Modifier.width(12.dp))
                DialogButton(label = "CLOSE", primary = false, theme = theme, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: RawEpisode,
    theme: GuideTheme,
    onClick: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .retroPanel(
                theme = theme,
                base = if (hasFocus) theme.highlight else theme.panel,
                corner = 3.dp,
                edge = if (hasFocus) theme.highlight else null,
                gloss = hasFocus,
            )
            .tvInteractive(onSelect = onClick, onFocus = { hasFocus = it })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "E${episode.episodeNum}",
                color = if (hasFocus) theme.highlightText else theme.highlight,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = episode.title.ifBlank { "Episode ${episode.episodeNum}" },
                color = if (hasFocus) theme.highlightText else theme.cellText,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = "▶ PLAY",
            color = if (hasFocus) theme.highlightText else theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}
