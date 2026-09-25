package com.crimson.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.catalog.CategoryTitles
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.CrimsonTextField
import com.crimson.ui.components.EmptyState
import com.crimson.ui.components.LiveCard
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.PageHeader
import com.crimson.ui.components.Spinner
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos

class DirectoryActions(
    val onQuery: (String) -> Unit,
    val onRegion: (String) -> Unit,
    val onCategory: (String) -> Unit,
    val onChannel: (ChannelTile, List<ChannelTile>) -> Unit,
)

/**
 * Every live category the provider has, by country: a searchable list of countries, that
 * country's categories, and the channels in the one selected. Moving through the first two
 * columns updates the next one live, so browsing a country is a matter of scrolling.
 */
@Composable
fun DirectoryScreen(state: DirectoryState, nowMs: Long, actions: DirectoryActions) {
    val firstRegion = remember { FocusRequester() }
    com.crimson.ui.components.InitialFocus(firstRegion, key = state.regions.isNotEmpty())
    PageBackground {
        Column(Modifier.fillMaxSize().padding(start = Crimson.ScreenPadding, top = 26.dp, end = 24.dp)) {
            PageHeader(
                "All Channels by Country",
                "${state.regions.size} countries and regions · ${state.regions.sumOf { it.categories.size }} categories",
                icon = CrimsonIcons.Globe,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(230.dp).fillMaxHeight()) {
                    CrimsonTextField("Find a country", state.query, actions.onQuery, placeholder = "Japan, Brazil, UK…", imeAction = ImeAction.Done)
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.visibleRegions, key = { it.region.code }) { entry ->
                            ListItem(
                                leading = entry.region.flag,
                                title = entry.region.name,
                                trailing = entry.categories.size.toString(),
                                selected = entry.region.code == state.selectedRegion,
                                onFocus = { actions.onRegion(entry.region.code) },
                                onClick = { actions.onRegion(entry.region.code) },
                                focusRequester = if (entry == state.visibleRegions.firstOrNull()) firstRegion else null,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.width(250.dp).fillMaxHeight()) {
                    Text("CATEGORIES", style = CrimsonType.Overline.copy(color = Crimson.TextTertiary, fontSize = 9.sp))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.categories, key = { it.categoryId }) { cat ->
                            ListItem(
                                leading = null,
                                title = CategoryTitles.clean(cat.name),
                                trailing = if (cat.imported) null else "•",
                                selected = cat.categoryId == state.selectedCategory,
                                onFocus = { actions.onCategory(cat.categoryId) },
                                onClick = { actions.onCategory(cat.categoryId) },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        state.loadingChannels -> Box(Modifier.fillMaxSize().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
                            Spinner(size = 34.dp, stroke = 3.dp)
                        }
                        state.channels.isEmpty() -> EmptyState(
                            "Pick a category",
                            state.error ?: "Channels in the selected category appear here. Categories marked • are read from your provider when opened.",
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 8.dp, top = 22.dp, end = 12.dp, bottom = 140.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(state.channels, key = { it.key }) { tile ->
                                LiveCard(
                                    tile = tile,
                                    nowMs = nowMs,
                                    onClick = { actions.onChannel(tile, state.channels) },
                                    width = 180.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListItem(
    leading: String?,
    title: String,
    trailing: String?,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            focused -> Color.White
            selected -> Crimson.SurfaceHigh
            else -> Color.Transparent
        },
        tween(120), label = "listItem",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(6.dp))
            .tvInteractive(onSelect = onClick, onFocus = { focused = it; if (it) onFocus() }, focusRequester = focusRequester)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected && !focused) {
            Box(Modifier.width(3.dp).height(14.dp).background(Crimson.Red))
            Spacer(Modifier.width(8.dp))
        }
        if (leading != null) {
            Text(leading, style = CrimsonType.Label.copy(fontSize = 16.sp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            style = CrimsonType.Label.copy(
                fontSize = 13.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                color = if (focused) Color.Black else if (selected) Crimson.TextPrimary else Crimson.TextSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = CrimsonType.Caption.copy(color = if (focused) Color.DarkGray else Crimson.TextTertiary))
        }
        if (selected && !focused) {
            Spacer(Modifier.width(4.dp))
            Icon(CrimsonIcons.ChevronRight, null, tint = Crimson.TextTertiary, modifier = Modifier.width(16.dp))
        }
    }
}
