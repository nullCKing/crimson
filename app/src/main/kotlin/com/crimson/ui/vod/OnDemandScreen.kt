package com.crimson.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crimson.ui.BrowseState
import com.crimson.ui.OnDemandMode
import com.crimson.ui.UiState
import com.crimson.ui.components.PosterCard
import com.crimson.ui.components.PosterItem
import com.crimson.ui.components.RetroRow
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroHeader
import com.crimson.ui.theme.RetroPage
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel

/**
 * On Demand: films and series on one screen.
 *
 * They were two screens with the same layout, the same sidebar and the same dialogs, and a viewer
 * looking for something to watch does not first decide whether it is a film. So they are one
 * screen with a switch at the top, and the sidebar's first entry is the curated rows rather than
 * a category — which is what somebody browsing actually wants, with the provider's two hundred
 * categories still there underneath for somebody who does not.
 *
 * Everything here reads the cached catalogue, so switching between films and series, or between
 * categories, costs a database query rather than a round trip to the provider.
 */
@Composable
fun OnDemandScreen(
    ui: UiState,
    browse: BrowseState,
    nowMs: Long,
    theme: GuideTheme,
    onSetMode: (OnDemandMode) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onOpenItem: (PosterItem) -> Unit,
    onPlayMovie: (streamId: Long, extension: String?) -> Unit,
    onPlayEpisode: (episodeId: Long, extension: String?) -> Unit,
    onDismissDialog: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMovies = ui.onDemandMode == OnDemandMode.MOVIES
    val rows = if (isMovies) browse.movieRows else browse.seriesRows
    val categoryName = ui.onDemandCategories.firstOrNull { it.categoryId == ui.onDemandCategoryId }?.categoryName

    Box(modifier = modifier.fillMaxSize()) {
        RetroPage(
            theme = theme,
            header = {
                RetroHeader(
                    title = "ON DEMAND  •  " + (categoryName?.uppercase() ?: "FEATURED"),
                    subtitle = if (isMovies) "Films" else "Series",
                    nowMs = nowMs,
                    theme = theme,
                    trailing = if (ui.onDemandCategoryId != null) "${ui.onDemandItems.size} TITLES" else null,
                )
            },
            buttons = listOf(
                BarButton("BACK", onBack, key = "◄", keyColor = theme.keyBlue),
                BarButton(
                    label = if (isMovies) "SHOW SERIES" else "SHOW FILMS",
                    onClick = { onSetMode(if (isMovies) OnDemandMode.SERIES else OnDemandMode.MOVIES) },
                    key = "A",
                    keyColor = theme.keyRed,
                ),
                BarButton("BROWSE", onOpenBrowse, key = "B", keyColor = theme.keyGreen),
                BarButton("SETTINGS", onOpenSettings, key = "MENU", keyColor = theme.keyBlue),
            ),
            hint = "FEATURED IS THE CURATED LISTS  •  CATEGORIES ARE YOUR PROVIDER'S OWN",
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(230.dp)
                        .fillMaxHeight()
                        .retroPanel(theme, theme.chromeBar, corner = 4.dp, edge = theme.chromeBarEdge)
                        .padding(6.dp),
                ) {
                    ModeSwitch(isMovies = isMovies, theme = theme, onSetMode = onSetMode)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "CATEGORIES",
                        style = theme.captionStyle(theme.highlight).copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            CategoryRow(
                                label = "★ FEATURED",
                                selected = ui.onDemandCategoryId == null,
                                theme = theme,
                                onClick = { onSelectCategory(null) },
                            )
                        }
                        itemsIndexed(ui.onDemandCategories, key = { _, c -> c.categoryId }) { _, cat ->
                            CategoryRow(
                                label = cat.categoryName,
                                selected = cat.categoryId == ui.onDemandCategoryId,
                                theme = theme,
                                onClick = { onSelectCategory(cat.categoryId) },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        ui.onDemandCategoryId != null -> {
                            if (ui.onDemandItems.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = theme.highlight)
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 128.dp),
                                    contentPadding = PaddingValues(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(ui.onDemandItems, key = { "${it.kind}:${it.id}" }) { item ->
                                        PosterCard(
                                            item = item,
                                            theme = theme,
                                            width = 128.dp,
                                            onSelect = { onOpenItem(item) },
                                        )
                                    }
                                }
                            }
                        }

                        rows.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = browse.note ?: "Reading the catalogue…",
                                    style = theme.labelStyle(theme.infoDetail),
                                )
                            }
                        }

                        else -> LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(rows, key = { it.id }) { row ->
                                RetroRow(
                                    title = row.title.uppercase(),
                                    subtitle = row.subtitle,
                                    items = row.items,
                                    theme = theme,
                                    onSelect = onOpenItem,
                                )
                            }
                        }
                    }
                }
            }
        }

        // The details dialogs are the ones the two old screens had, unchanged.
        if (ui.selectedVodInfo != null || ui.isLoadingVodInfo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xCC000000))
                    .tvInteractive(onSelect = onDismissDialog, focusTarget = false),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.isLoadingVodInfo) {
                    CircularProgressIndicator(color = theme.highlight)
                } else ui.selectedVodInfo?.let { info ->
                    MovieDetailsDialog(
                        info = info,
                        theme = theme,
                        onPlay = { onPlayMovie(info.streamId, info.containerExtension) },
                        onDismiss = onDismissDialog,
                    )
                }
            }
        }
        if (ui.selectedSeriesInfo != null || ui.isLoadingSeriesInfo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xCC000000))
                    .tvInteractive(onSelect = onDismissDialog, focusTarget = false),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.isLoadingSeriesInfo) {
                    CircularProgressIndicator(color = theme.highlight)
                } else ui.selectedSeriesInfo?.let { info ->
                    SeriesDetailsDialog(
                        info = info,
                        theme = theme,
                        onPlayEpisode = onPlayEpisode,
                        onDismiss = onDismissDialog,
                    )
                }
            }
        }
    }
}

/** The films / series switch, drawn as the two-position selector a set-top box would use. */
@Composable
private fun ModeSwitch(isMovies: Boolean, theme: GuideTheme, onSetMode: (OnDemandMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeButton("FILMS", isMovies, theme, Modifier.weight(1f)) { onSetMode(OnDemandMode.MOVIES) }
        ModeButton("SERIES", !isMovies, theme, Modifier.weight(1f)) { onSetMode(OnDemandMode.SERIES) }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .retroPanel(
                theme = theme,
                base = when {
                    focused -> theme.highlight
                    selected -> theme.tabFillCurrent
                    else -> theme.panel
                },
                corner = 3.dp,
                edge = if (focused) theme.highlight else theme.panelEdge,
                gloss = true,
            )
            .tvInteractive(onSelect = onClick, onFocus = { focused = it })
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused || selected) theme.highlightText else theme.cellText,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CategoryRow(
    label: String,
    selected: Boolean,
    theme: GuideTheme,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .retroPanel(
                theme = theme,
                base = when {
                    focused -> theme.highlight
                    selected -> theme.panelSelected
                    else -> theme.panel
                },
                corner = 3.dp,
                edge = if (focused) theme.highlight else if (selected) theme.panelEdge else null,
                gloss = focused,
            )
            .tvInteractive(onSelect = onClick, onFocus = { focused = it })
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (focused) theme.highlightText else theme.cellText,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
