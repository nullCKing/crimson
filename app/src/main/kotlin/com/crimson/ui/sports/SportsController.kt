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
    /**
     * Grouped the way a scores page reads: live, then by day, then recent results. A group too
     * big to browse as one row (a college Saturday is well over a hundred games) becomes a row
     * per league and division; so does every group when one college league is picked.
     */
    val rows: List<FeedRow>
        get() {
            val now = System.currentTimeMillis()
            val shown = events.filter { league == null || it.league.id == league.id }
            val ranked = SportsRepository.rank(shown, now)
            val out = ArrayList<FeedRow>()
            fun add(id: String, title: String, games: List<SportsEvent>, order: Comparator<SportsEvent>?) {
                if (games.isEmpty()) return
                val split = games.size > SPLIT_OVER || league?.divisions?.isNotEmpty() == true
                val sections = if (split) games.groupBy(::sectionOf).entries.sortedBy { sectionOrder(it.value.first()) }.map { it.key to it.value }
                else listOf("" to games)
                for ((section, list) in sections) {
                    val sorted = if (order != null) list.sortedWith(order) else list
                    out += FeedRow(
                        if (section.isEmpty()) id else "${id}_$section",
                        if (section.isEmpty()) title else "$title · $section",
                        RowKind.SPORTS,
                        sorted.map(::GameTile),
                        subtitle = if (list.size == 1) "1 game" else "${list.size} games",
                    )
                }
            }
            add("sports_live", "Live Now", ranked.filter { it.state == EventState.LIVE }, null)
            val dayFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            val zone = TimeZone.getDefault()
            fun dayOf(ms: Long) = Math.floorDiv(ms + zone.getOffset(ms), DAY)
            val today = dayOf(now)
            // Kick-off order; at the same kick-off, a ranked team's game first.
            val byStart = compareBy<SportsEvent>({ it.startMs }, { if (it.hasRankedTeam) 0 else 1 })
            ranked.filter { it.state == EventState.UPCOMING && it.startMs - now < 8 * DAY }
                .groupBy { dayOf(it.startMs) }
                .toSortedMap()
                .forEach { (day, games) ->
                    val title = when (day - today) {
                        0L -> "Later Today"
                        1L -> "Tomorrow"
                        else -> dayFormat.format(Date(games.first().startMs))
                    }
                    add("sports_day_$day", title, games, byStart)
                }
            add("sports_final", "Final Scores", ranked.filter { it.state == EventState.FINAL && now - it.startMs < 2 * DAY }, null)
            return out
        }

    val leaguesWithGames: List<League>
        get() = Leagues.ALL.filter { l -> events.any { it.league.id == l.id } }

    companion object {
        const val DAY = 86_400_000L
        /** More games than this in one row and it is split into a row per league. */
        const val SPLIT_OVER = 24

        /** "College Football · FCS", or the league's name. */
        fun sectionOf(event: SportsEvent): String =
            event.division?.let { "${event.league.name} (${it})" } ?: event.league.name

        /** Leagues in the Sports page's own order, a league's divisions in theirs. */
        private fun sectionOrder(event: SportsEvent): Int =
            com.crimson.core.sports.Leagues.ALL.indexOfFirst { it.id == event.league.id } * 10 +
                (event.league.divisions.indexOfFirst { it.label == event.division }.takeIf { it >= 0 } ?: 0)
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
