package com.crimson.ui.search

import com.crimson.AppContainer
import com.crimson.core.catalog.AdultContent
import com.crimson.core.catalog.SearchRank
import com.crimson.core.model.Country
import com.crimson.domain.GuideChannel
import com.crimson.ui.Mappers
import com.crimson.ui.SearchScope
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.TitleTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchState(
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val searching: Boolean = false,
    val channels: List<ChannelTile> = emptyList(),
    val movies: List<TitleTile> = emptyList(),
    val shows: List<TitleTile> = emptyList(),
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && shows.isEmpty()
}

/**
 * Search across live channels, films and series at once.
 *
 * Runs against the local database, so it answers as the viewer types and works with the provider
 * down. Results are re-ordered by [SearchRank] — the database's `LIKE` finds, the ranking decides
 * — so "NBC" puts NBC first and not MSNBC, and a US channel beats a foreign feed of the same name.
 */
class SearchController(
    private val container: AppContainer,
    private val channelsInGuide: () -> List<GuideChannel>,
    private val nowPlaying: suspend (List<GuideChannel>) -> List<ChannelTile>,
) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        _state.value = SearchState()
    }

    fun open(query: String, scope: SearchScope) {
        _state.value = _state.value.copy(scope = scope)
        setQuery(query, debounce = false)
    }

    fun setScope(scope: SearchScope) {
        _state.value = _state.value.copy(scope = scope)
    }

    fun type(char: Char) = setQuery(_state.value.query + char)

    fun backspace() = setQuery(_state.value.query.dropLast(1))

    fun clear() = setQuery("")

    fun setQuery(query: String, debounce: Boolean = true) {
        val trimmed = query.take(40)
        _state.value = _state.value.copy(query = trimmed)
        job?.cancel()
        val term = trimmed.trim()
        if (term.isEmpty()) {
            _state.value = _state.value.copy(searching = false, channels = emptyList(), movies = emptyList(), shows = emptyList())
            return
        }
        _state.value = _state.value.copy(searching = true)
        job = scope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            val db = container.database
            val (channelRows, vod, series) = withContext(Dispatchers.IO) {
                Triple(
                    db.channelDao().search(term, 400),
                    if (term.length >= 2) db.libraryDao().searchVod(term, 240) else emptyList(),
                    if (term.length >= 2) db.libraryDao().searchSeries(term, 160) else emptyList(),
                )
            }
            // The guide's own list knows favourites and is already filtered by the viewer's
            // rules; a database hit that the rules exclude is not offered.
            val guide = channelsInGuide().associateBy { it.streamId }
            val channels = SearchRank.rank(
                channelRows.mapNotNull { guide[it.streamId] },
                term,
                nameOf = { it.name },
                bonus = { ch ->
                    (if (ch.country == Country.US) 40 else 0) +
                        (if (ch.market == null) 20 else 0) +
                        (if (ch.isFavorite) 30 else 0)
                },
            ).take(60)
            val movies = SearchRank.rank(
                Mappers.dedupe(vod.filterNot { it.isAdult }, { it.name }, { it.titleYear }),
                term,
                nameOf = { Mappers.tile(it).name },
                bonus = { v -> ((v.imdbVotes ?: 0) / 20_000).coerceAtMost(300) - (if (v.isForeign) 200 else 0) },
            ).take(60).map(Mappers::tile)
            val shows = SearchRank.rank(
                Mappers.dedupe(series.filterNot { it.isAdult }, { it.name }, { it.titleYear }),
                term,
                nameOf = { Mappers.tile(it).name },
                bonus = { s -> ((s.imdbVotes ?: 0) / 10_000).coerceAtMost(300) - (if (s.isForeign) 200 else 0) },
            ).take(60).map(Mappers::tile)
            val channelTiles = nowPlaying(channels)
            if (_state.value.query.trim() == term) {
                _state.value = _state.value.copy(
                    searching = false,
                    channels = channelTiles,
                    movies = movies.filterNot { AdultContent.isAdult(it.name) },
                    shows = shows,
                )
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 260L
    }
}
