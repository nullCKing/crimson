package com.crimson.ui.mylist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crimson.ui.components.EmptyState
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.FeedRowView
import com.crimson.ui.components.MainTab
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.PageHeader
import com.crimson.ui.components.PivotScroll
import com.crimson.ui.components.Tile
import com.crimson.ui.components.TopNav
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons

/** My List: what the viewer saved, what they are part way through, and their starred channels. */
@Composable
fun MyListScreen(
    rows: List<FeedRow>,
    nowMs: Long,
    avatar: Int,
    profileName: String,
    onTab: (MainTab) -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onOpen: (Tile, List<Tile>) -> Unit,
) {
    val tabFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    com.crimson.ui.components.InitialFocus(tabFocus)
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopNav(MainTab.MY_LIST, avatar, onTab, onSearch, onProfile, selectedTabRequester = tabFocus)
            PageHeader(
                "My List",
                "$profileName's saved movies and shows, what you're watching, and your channels.",
                modifier = Modifier.padding(start = Crimson.ScreenPadding, top = 8.dp),
                icon = CrimsonIcons.Bookmark,
            )
            Spacer(Modifier.height(14.dp))
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        "Your list is empty",
                        "Add movies and shows with My List on any title's page, and star channels in the guide. They'll all be here.",
                    )
                }
            } else {
                PivotScroll(offset = 26.dp) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 260.dp), modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { it.id }) { row ->
                            FeedRowView(row = row, nowMs = nowMs, onTileClick = { onOpen(it, row.tiles) })
                        }
                    }
                }
            }
        }
    }
}
