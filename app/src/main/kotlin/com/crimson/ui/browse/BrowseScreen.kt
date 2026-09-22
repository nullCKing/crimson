package com.crimson.ui.browse

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.crimson.ui.PackageSummary
import com.crimson.ui.components.PosterItem
import com.crimson.ui.components.RetroRow
import com.crimson.ui.components.RetroSearchField
import com.crimson.ui.components.SectionLabel
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroHeader
import com.crimson.ui.theme.RetroPage
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel

/**
 * Browse: one screen for finding something to watch rather than knowing what you want.
 *
 * A search box over everything — channels, films and series at once — then the viewer's starred
 * channels, the channel packages, and rows built from curated lists showing only the titles this
 * account actually carries. It is the shape a streaming service uses because it is the shape that
 * works, drawn the way a set-top box would have drawn it.
 *
 * While a search is running the rows are replaced by its results rather than pushed down the
 * page: a viewer who has typed something is looking for it, not for what was underneath.
 */
@Composable
fun BrowseScreen(
    state: BrowseState,
    nowMs: Long,
    theme: GuideTheme,
    onQueryChange: (String) -> Unit,
    onOpenItem: (PosterItem) -> Unit,
    onOpenPackage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnDemand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RetroPage(
        theme = theme,
        modifier = modifier,
        header = {
            RetroHeader(
                title = "BROWSE",
                subtitle = "Search everything, or pick up where a cable guide left off",
                nowMs = nowMs,
                theme = theme,
                trailing = if (state.hasQuery) "${state.resultCount} RESULTS" else null,
            )
        },
        buttons = listOf(
            BarButton("BACK", onBack, key = "◄", keyColor = theme.keyBlue),
            BarButton("ON DEMAND", onOpenOnDemand, key = "B", keyColor = theme.keyGreen),
            BarButton("SETTINGS", onOpenSettings, key = "MENU", keyColor = theme.keyBlue),
        ),
        hint = "TYPE TO SEARCH  •  ▲▼ MOVE BETWEEN ROWS  •  ◄► ALONG A ROW",
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RetroSearchField(
                value = state.query,
                onValueChange = onQueryChange,
                theme = theme,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.hasQuery) {
                    if (state.searching && state.resultCount == 0) {
                        item { Text(text = "Searching…", style = theme.captionStyle()) }
                    } else if (state.resultCount == 0) {
                        item {
                            Text(
                                text = "Nothing matches \"${state.query}\".",
                                style = theme.labelStyle(theme.infoDetail),
                            )
                        }
                    }
                    if (state.channelResults.isNotEmpty()) {
                        item {
                            RetroRow(
                                title = "CHANNELS",
                                subtitle = "Live now",
                                items = state.channelResults,
                                theme = theme,
                                onSelect = onOpenItem,
                                posterWidth = 150.dp,
                            )
                        }
                    }
                    if (state.movieResults.isNotEmpty()) {
                        item {
                            RetroRow("FILMS", "On demand", state.movieResults, theme, onOpenItem)
                        }
                    }
                    if (state.seriesResults.isNotEmpty()) {
                        item {
                            RetroRow("SERIES", "On demand", state.seriesResults, theme, onOpenItem)
                        }
                    }
                } else {
                    if (state.packages.isNotEmpty()) {
                        item {
                            Column {
                                SectionLabel(
                                    "LIVE TV LINEUPS",
                                    "Choose a lineup or category to open the guide",
                                    theme,
                                )
                                Spacer(Modifier.height(6.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.packages, key = { it.id }) { pkg ->
                                        PackageCard(pkg, theme) { onOpenPackage(pkg.id) }
                                    }
                                }
                            }
                        }
                    }
                    if (state.favorites.isNotEmpty()) {
                        item {
                            RetroRow(
                                title = "★ YOUR FAVOURITE CHANNELS",
                                subtitle = "Starred in the guide",
                                items = state.favorites,
                                theme = theme,
                                onSelect = onOpenItem,
                                posterWidth = 150.dp,
                            )
                        }
                    }
                    state.note?.let { note ->
                        item { Text(text = note, style = theme.captionStyle()) }
                    }
                    // Films and series alternate so the top of the page has both.
                    val rows = interleave(state.movieRows, state.seriesRows)
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

private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
    val out = ArrayList<T>(a.size + b.size)
    var i = 0
    while (i < a.size || i < b.size) {
        a.getOrNull(i)?.let(out::add)
        b.getOrNull(i)?.let(out::add)
        i++
    }
    return out
}

/** A package, with how much of it this account can actually fill. */
@Composable
private fun PackageCard(
    pkg: PackageSummary,
    theme: GuideTheme,
    onOpen: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(300.dp)
            .retroPanel(
                theme = theme,
                base = if (focused) theme.highlight else theme.panelSelected,
                corner = 5.dp,
                edge = if (focused) theme.highlight else theme.panelEdge,
                edgeWidth = if (focused) 2.dp else 1.dp,
                gloss = true,
            )
            .tvInteractive(onSelect = onOpen, onFocus = { focused = it })
            .padding(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pkg.name.uppercase(),
                style = theme.headingStyle(if (focused) theme.highlightText else theme.infoTitle)
                    .copy(fontSize = theme.detailSize),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badgeText = when {
                pkg.id == "__categories__" -> "${pkg.found} GROUPS"
                pkg.total == pkg.found -> "${pkg.found} CH"
                else -> "${pkg.found}/${pkg.total}"
            }
            Box(
                modifier = Modifier
                    .retroPanel(theme, theme.keyRed, corner = 3.dp, edge = theme.bevelDark, gloss = true)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badgeText,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = theme.sectionSize,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = pkg.description,
            color = if (focused) theme.highlightText else theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
