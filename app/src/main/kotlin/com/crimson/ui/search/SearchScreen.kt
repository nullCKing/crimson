package com.crimson.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import com.crimson.ui.SearchScope
import com.crimson.ui.components.Chip
import com.crimson.ui.components.EmptyState
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.FeedRowView
import com.crimson.ui.components.OnScreenKeyboard
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.RowKind
import com.crimson.ui.components.SearchQueryField
import com.crimson.ui.components.Spinner
import com.crimson.ui.components.Tile
import com.crimson.ui.components.PivotScroll
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos

class SearchActions(
    val onChar: (Char) -> Unit,
    val onBackspace: () -> Unit,
    val onClear: () -> Unit,
    val onScope: (SearchScope) -> Unit,
    val onOpen: (Tile, List<Tile>) -> Unit,
)

/**
 * Search, laid out the way a television search page is: a keyboard on the left that the remote
 * can drive, and the results on the right narrowing with every letter. Arriving from a game on
 * the Sports page, the query is already filled in and the scope set to Live TV, so the channel
 * showing the game is the first card.
 */
@Composable
fun SearchScreen(
    state: SearchState,
    nowMs: Long,
    suggestions: List<FeedRow>,
    actions: SearchActions,
) {
    val firstKey = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val arrivedWithQuery = remember { state.query.isNotBlank() && state.scope == SearchScope.LIVE }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    LaunchedEffect(arrivedWithQuery, state.channels.isNotEmpty()) {
        if (arrivedWithQuery && state.channels.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstResult.requestFocus() }
        }
    }

    PageBackground(
        Modifier.onPreviewKeyEvent { event ->
            // A hardware keyboard types straight into the query.
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.Backspace -> { actions.onBackspace(); true }
                event.key == Key.Spacebar -> { actions.onChar(' '); true }
                else -> {
                    val cp = event.utf16CodePoint
                    val ch = if (cp > 0) cp.toChar() else null
                    if (ch != null && (ch.isLetterOrDigit() || ch == '+' || ch == '&' || ch == '-')) {
                        actions.onChar(ch.lowercaseChar()); true
                    } else false
                }
            }
        }
    ) {
        Row(Modifier.fillMaxSize().padding(top = 28.dp)) {
            Column(
                Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .padding(start = Crimson.ScreenPadding, end = 16.dp),
            ) {
                Text("Search", style = CrimsonType.Headline)
                Spacer(Modifier.height(14.dp))
                SearchQueryField(state.query, "Titles, channels, teams", Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                OnScreenKeyboard(
                    onChar = actions.onChar,
                    onBackspace = actions.onBackspace,
                    onClear = actions.onClear,
                    firstKeyRequester = firstKey,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 24.dp, top = 48.dp)) {
                    SearchScope.entries.forEach { scope ->
                        Chip(
                            text = scope.label + countFor(state, scope),
                            selected = state.scope == scope,
                            onClick = { actions.onScope(scope) },
                        )
                    }
                    if (state.searching) Spinner(size = 22.dp, stroke = 2.5.dp)
                }
                Spacer(Modifier.height(6.dp))
                Results(state, nowMs, suggestions, firstResult, actions)
            }
        }
    }
}

private fun countFor(state: SearchState, scope: SearchScope): String {
    if (!state.hasQuery) return ""
    val n = when (scope) {
        SearchScope.ALL -> state.channels.size + state.movies.size + state.shows.size
        SearchScope.LIVE -> state.channels.size
        SearchScope.MOVIES -> state.movies.size
        SearchScope.SHOWS -> state.shows.size
    }
    return if (n > 0) "  $n" else ""
}

@Composable
private fun Results(
    state: SearchState,
    nowMs: Long,
    suggestions: List<FeedRow>,
    firstResult: FocusRequester,
    actions: SearchActions,
) {
    val rows: List<FeedRow> = if (!state.hasQuery) {
        suggestions
    } else {
        val live = FeedRow("s_live", "Live Channels", RowKind.LIVE, state.channels)
        val movies = FeedRow("s_movies", "Movies", RowKind.POSTER, state.movies)
        val shows = FeedRow("s_shows", "TV Shows", RowKind.POSTER, state.shows)
        when (state.scope) {
            SearchScope.ALL -> listOf(live, movies, shows)
            SearchScope.LIVE -> state.channels.chunked(3).mapIndexed { i, chunk -> FeedRow("s_live_$i", if (i == 0) "Live Channels" else "", RowKind.LIVE, chunk) }
            SearchScope.MOVIES -> state.movies.chunked(5).mapIndexed { i, chunk -> FeedRow("s_m_$i", if (i == 0) "Movies" else "", RowKind.POSTER, chunk) }
            SearchScope.SHOWS -> state.shows.chunked(5).mapIndexed { i, chunk -> FeedRow("s_s_$i", if (i == 0) "TV Shows" else "", RowKind.POSTER, chunk) }
        }.filter { it.tiles.isNotEmpty() }
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (state.hasQuery && !state.searching) {
                EmptyState("No matches for “${state.query}”", "Try a channel name, a team, a network or part of a title.")
            } else if (!state.hasQuery) {
                EmptyState("Find something to watch", "Type with the keyboard, or with a remote's number keys. Results appear as you type.")
            }
        }
        return
    }
    val firstKey = rows.firstOrNull()?.tiles?.firstOrNull()?.key
    PivotScroll(offset = 24.dp) {
        LazyColumn(contentPadding = PaddingValues(bottom = 240.dp), modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                FeedRowView(
                    row = row,
                    nowMs = nowMs,
                    onTileClick = { tile -> actions.onOpen(tile, row.tiles) },
                    startPadding = 24.dp,
                    restoreKey = firstKey,
                    restoreRequester = firstResult,
                    showHeading = row.title.isNotBlank(),
                )
            }
        }
    }
}
