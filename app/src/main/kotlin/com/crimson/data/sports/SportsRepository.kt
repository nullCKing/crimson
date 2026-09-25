package com.crimson.data.sports

import android.util.Log
import com.crimson.core.sports.Division
import com.crimson.core.sports.EspnScoreboard
import com.crimson.core.sports.EventState
import com.crimson.core.sports.League
import com.crimson.core.sports.Leagues
import com.crimson.core.sports.SportsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Scores and schedules from ESPN's public scoreboard.
 *
 * Chosen because it is free and needs no key, and because it names the TV network for each game,
 * which is the whole point here: a game is a way into the live channel that is showing it. It is
 * also unofficial and could change shape without notice, so everything here degrades to "no
 * games" rather than to an error, and the Sports page says so plainly when that happens.
 *
 * Responses are cached for [CACHE_MS] per league, so moving between tabs does not refetch, while
 * a live score is never more than a minute old.
 */
class SportsRepository(private val http: OkHttpClient) {

    private data class Entry(val at: Long, val events: List<SportsEvent>)

    private val cache = HashMap<String, Entry>()

    suspend fun events(league: League, force: Boolean = false): List<SportsEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[league.id]?.takeIf { !force && now - it.at < CACHE_MS }?.let { return@withContext it.events }
        }
        val events = if (league.divisions.isEmpty()) scoreboard(league, null) else coroutineScope {
            // Every division's scoreboard at once. A game between divisions (FBS against FCS)
            // is on both; it is kept once, under the first (higher) division.
            val parts = league.divisions.map { d -> async { scoreboard(league, d) } }.awaitAll()
            if (parts.all { it == null }) null
            else parts.filterNotNull().flatten().distinctBy { it.id }
        }
        if (events != null) synchronized(cache) { cache[league.id] = Entry(now, events) }
        events ?: synchronized(cache) { cache[league.id]?.events }.orEmpty()
    }

    private fun scoreboard(league: League, division: Division?): List<SportsEvent>? = runCatching {
        val query = division?.let { "?groups=${it.group}&limit=$DIVISION_LIMIT" }.orEmpty()
        val request = Request.Builder()
            .url("$BASE/${league.path}/scoreboard$query")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.charStream()?.use { EspnScoreboard.parse(it, league, division?.label) }.orEmpty()
        }
    }.onFailure { Log.w(TAG, "scoreboard for ${league.id} ${division?.label.orEmpty()} failed", it) }.getOrNull()

    /** Every followed league at once, in parallel. */
    suspend fun all(leagues: List<League> = Leagues.ALL, force: Boolean = false): List<SportsEvent> = coroutineScope {
        leagues.map { async { events(it, force) } }.awaitAll().flatten()
    }

    /**
     * What is worth a card on the Home page: games in progress, then the next day's games, and
     * results from the last few hours. A week-old final is not "live sports".
     */
    suspend fun headline(now: Long = System.currentTimeMillis()): List<SportsEvent> =
        rank(all(HEADLINE_LEAGUES), now).filter {
            // Home keeps to the college games that used to be all there was: a ranked team
            // playing. Every other game is on the Sports page.
            if (it.league.divisions.isNotEmpty() && !it.hasRankedTeam) return@filter false
            when (it.state) {
                EventState.LIVE -> true
                EventState.UPCOMING -> it.startMs - now < 30 * HOUR
                EventState.FINAL -> now - it.startMs < 8 * HOUR
            }
        }

    companion object {
        private const val TAG = "CrimsonSports"
        private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
        const val CACHE_MS = 60_000L
        private const val HOUR = 3_600_000L
        /** More than a division ever plays in a week; ESPN's default page size is smaller. */
        private const val DIVISION_LIMIT = 500

        val HEADLINE_LEAGUES = listOf(
            Leagues.NFL, Leagues.NCAAF, Leagues.NBA, Leagues.MLB, Leagues.NHL,
            Leagues.WNBA, Leagues.MLS, Leagues.EPL, Leagues.UCL,
        )

        /** Live first, then soonest upcoming, then most recent finals. */
        fun rank(events: List<SportsEvent>, now: Long): List<SportsEvent> =
            events.sortedWith(
                compareBy<SportsEvent> {
                    when (it.state) {
                        EventState.LIVE -> 0
                        EventState.UPCOMING -> 1
                        EventState.FINAL -> 2
                    }
                }.thenBy {
                    if (it.state == EventState.FINAL) now - it.startMs else it.startMs - now
                }
            )
    }
}
