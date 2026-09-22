package com.crimson.ui.sports

import com.crimson.AppContainer
import com.crimson.core.sports.EventState
import com.crimson.core.sports.League
import com.crimson.core.sports.Leagues
import com.crimson.core.sports.SportsEvent
import com.crimson.data.sports.SportsRepository
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.GameTile
import com.crimson.ui.components.RowKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SportsState(
    val league: League? = null,
    val events: List<SportsEvent> = emptyList(),
    val loading: Boolean = true,
    val updatedAt: Long = 0L,
    val failed: Boolean = false,
) {
    /** Grouped the way a scores page reads: live, then by day, then recent results. */
    val rows: List<FeedRow>
        get() {
            val now = System.currentTimeMillis()
            val shown = events.filter { league == null || it.league.id == league.id }
            val ranked = SportsRepository.rank(shown, now)
            val out = ArrayList<FeedRow>()
            ranked.filter { it.state == EventState.LIVE }.takeIf { it.isNotEmpty() }?.let {
                out += FeedRow("sports_live", "Live Now", RowKind.SPORTS, it.map(::GameTile), subtitle = "${it.size} games")
            }
            val dayFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            val zone = TimeZone.getDefault()
            fun dayOf(ms: Long) = Math.floorDiv(ms + zone.getOffset(ms), DAY)
            val today = dayOf(now)
            ranked.filter { it.state == EventState.UPCOMING && it.startMs - now < 8 * DAY }
                .groupBy { dayOf(it.startMs) }
                .toSortedMap()
                .forEach { (day, games) ->
                    val title = when (day - today) {
                        0L -> "Later Today"
                        1L -> "Tomorrow"
                        else -> dayFormat.format(Date(games.first().startMs))
                    }
                    out += FeedRow("sports_day_$day", title, RowKind.SPORTS, games.sortedBy { it.startMs }.map(::GameTile), subtitle = "${games.size} games")
                }
            ranked.filter { it.state == EventState.FINAL && now - it.startMs < 2 * DAY }.takeIf { it.isNotEmpty() }?.let {
                out += FeedRow("sports_final", "Final Scores", RowKind.SPORTS, it.map(::GameTile))
            }
            return out
        }

    val leaguesWithGames: List<League>
        get() = Leagues.ALL.filter { l -> events.any { it.league.id == l.id } }

    companion object {
        const val DAY = 86_400_000L
    }
}

/** The Sports page: every followed league's scoreboard, refreshed while the page is open. */
class SportsController(private val container: AppContainer) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(SportsState())
    val state: StateFlow<SportsState> = _state.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        _state.value = SportsState()
    }

    fun refresh(force: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (_state.value.events.isEmpty()) _state.value = _state.value.copy(loading = true)
            val events = runCatching { container.sports.all(force = force) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                events = events,
                loading = false,
                failed = events.isEmpty(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun selectLeague(league: League?) {
        _state.value = _state.value.copy(league = league)
    }
}
