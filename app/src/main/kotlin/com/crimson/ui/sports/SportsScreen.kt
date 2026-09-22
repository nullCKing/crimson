package com.crimson.ui.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.crimson.core.sports.League
import com.crimson.ui.components.Chip
import com.crimson.ui.components.EmptyState
import com.crimson.ui.components.FeedRowView
import com.crimson.ui.components.MainTab
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.PageHeader
import com.crimson.ui.components.PivotScroll
import com.crimson.ui.components.Tile
import com.crimson.ui.components.TopNav
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SportsActions(
    val onTab: (MainTab) -> Unit,
    val onSearch: () -> Unit,
    val onProfile: () -> Unit,
    val onLeague: (League?) -> Unit,
    val onGame: (Tile) -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * Scores and schedules for the big leagues. Selecting a game is a way into it: it searches the
 * live channels for the network showing it — NBC, ESPN, Prime — so the viewer lands on the
 * channel, one Select away from the game.
 */
@Composable
fun SportsScreen(state: SportsState, avatar: Int, actions: SportsActions) {
    val firstChip = remember { FocusRequester() }
    com.crimson.ui.components.InitialFocus(firstChip)
    // Live scores stay live while the page is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            actions.onRefresh()
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopNav(MainTab.SPORTS, avatar, actions.onTab, actions.onSearch, actions.onProfile)
            Row(Modifier.padding(start = Crimson.ScreenPadding, top = 8.dp, end = Crimson.ScreenPadding), verticalAlignment = Alignment.Bottom) {
                PageHeader("Sports", "Live scores and what's on today. Select a game to find the channel showing it.", icon = CrimsonIcons.Trophy)
                Spacer(Modifier.weight(1f))
                if (state.updatedAt > 0) {
                    Text(
                        "Updated " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(state.updatedAt)) + " · ESPN",
                        style = CrimsonType.Caption,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = Crimson.ScreenPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip("All Sports", selected = state.league == null, onClick = { actions.onLeague(null) }, focusRequester = firstChip) }
                items(state.leaguesWithGames, key = { it.id }) { league ->
                    Chip(league.label, selected = state.league?.id == league.id, onClick = { actions.onLeague(league) })
                }
            }
            val rows = state.rows
            when {
                rows.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState("Checking the scoreboard", "Fetching today's games…", busy = true)
                }
                rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        if (state.failed) "Scores are unavailable" else "No games right now",
                        if (state.failed) "The scoreboard could not be reached. It is checked again every minute."
                        else "Nothing is scheduled in the next week for this league.",
                    )
                }
                else -> PivotScroll(offset = 26.dp) {
                    LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 260.dp), modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, r -> r.id }) { _, row ->
                            FeedRowView(row = row, nowMs = System.currentTimeMillis(), onTileClick = actions.onGame)
                        }
                    }
                }
            }
        }
    }
}
