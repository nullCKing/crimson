package com.crimson.ui.feed

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.crimson.AppContainer
import com.crimson.core.catalog.AdultContent
import com.crimson.core.catalog.CatalogSql
import com.crimson.core.catalog.FeedPage
import com.crimson.core.catalog.FeedPlanner
import com.crimson.core.catalog.Genres
import com.crimson.core.catalog.ProviderCategory
import com.crimson.core.catalog.RowSpec
import com.crimson.core.catalog.RowStyle
import com.crimson.core.catalog.SpecialRow
import com.crimson.core.catalog.TitleKind
import com.crimson.core.catalog.WatchedTitle
import com.crimson.core.filter.ForeignCountries
import com.crimson.core.sports.EventState
import com.crimson.core.text.Tokenizer
import com.crimson.data.db.WatchProgressEntity
import com.crimson.ui.InfoCache
import com.crimson.ui.Mappers
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.ContinueTile
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.GameTile
import com.crimson.ui.components.RowKind
import com.crimson.ui.components.Tile
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
import java.util.Calendar
import kotlin.random.Random

/** One page's feed. */
data class FeedState(
    val page: FeedPage,
    val rows: List<FeedRow> = emptyList(),
    val hero: TitleTile? = null,
    val loading: Boolean = true,
    val exhausted: Boolean = false,
    /** Said when there is nothing to show yet: the catalogue is still being read, say. */
    val note: String? = null,
)

/**
 * What the top of a page shows: the featured title, or whatever card has focus.
 *
 * Filled in two steps — at once from what the card already knows, then with the synopsis and
 * artwork once they arrive — so the title changes with the cursor and the picture follows.
 */
data class Spotlight(
    val key: String = "",
    val overline: String? = null,
    val title: String = "",
    val description: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    val rating: Float? = null,
    val year: Int? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val isLive: Boolean = false,
    val liveProgress: Float = 0f,
    val logo: String? = null,
    val tile: Tile? = null,
)

/**
 * The Home, TV Shows and Movies pages.
 *
 * Each page is a plan — an ordered list of [RowSpec]s from [FeedPlanner] — resolved a few rows at
 * a time as the viewer scrolls towards the end, which is what makes the page feel endless without
 * querying two hundred rows up front. Rows that come back too short for this catalogue are
 * skipped, so the plan can ask for far more than any one account will fill.
 */
class FeedController(
    private val container: AppContainer,
    private val info: InfoCache,
    private val liveNow: suspend () -> List<ChannelTile>,
) {
    lateinit var scope: CoroutineScope

    private val states = FeedPage.entries.associateWith { MutableStateFlow(FeedState(it)) }
    private val plans = HashMap<FeedPage, List<RowSpec>>()
    private val cursors = HashMap<FeedPage, Int>()
    private val jobs = HashMap<FeedPage, Job>()
    /** How often each title has appeared on a page, so later rows can favour fresh ones. */
    private val seen = HashMap<FeedPage, HashMap<String, Int>>()
    private var seed = Random.nextInt(1, 1_000_000)

    private val _spotlight = MutableStateFlow(Spotlight())
    val spotlight: StateFlow<Spotlight> = _spotlight.asStateFlow()
    private var spotlightJob: Job? = null

    fun state(page: FeedPage): StateFlow<FeedState> = states.getValue(page).asStateFlow()

    fun reset() {
        FeedPage.entries.forEach { states.getValue(it).value = FeedState(it) }
        plans.clear()
        cursors.clear()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        seen.clear()
        _spotlight.value = Spotlight()
        seed = Random.nextInt(1, 1_000_000)
    }

    /** Opens a page, building it the first time. */
    fun open(page: FeedPage) {
        val state = states.getValue(page).value
        if (plans[page] == null || (state.rows.isEmpty() && !state.loading)) rebuild(page)
        else state.hero?.let { showHero(it) }
    }

    /**
     * Rebuilds every page that has been built, keeping what is on screen until the new rows
     * arrive. Called when the catalogue finishes importing or progress changes.
     */
    fun refreshAll() {
        FeedPage.entries.filter { plans[it] != null }.forEach { rebuild(it, keepRows = true) }
    }

    /** Only the personal rows: continue watching and My List, after the viewer watched something. */
    fun refreshPersonal() {
        FeedPage.entries.filter { plans[it] != null }.forEach { page ->
            scope.launch {
                val flow = states.getValue(page)
                val updated = flow.value.rows.map { row ->
                    when (row.id) {
                        "continue" -> continueRow() ?: row.copy(tiles = emptyList())
                        "mylist" -> myListRow() ?: row.copy(tiles = emptyList())
                        else -> row
                    }
                }.filter { it.tiles.isNotEmpty() }
                val hasContinue = updated.any { it.id == "continue" }
                val withContinue = if (!hasContinue) listOfNotNull(continueRow()) + updated else updated
                flow.value = flow.value.copy(rows = withContinue)
            }
        }
    }

    private fun rebuild(page: FeedPage, keepRows: Boolean = false) {
        jobs[page]?.cancel()
        val flow = states.getValue(page)
        if (!keepRows) flow.value = FeedState(page, loading = true)
        jobs[page] = scope.launch {
            val plan = withContext(Dispatchers.IO) { buildPlan(page) }
            plans[page] = plan
            cursors[page] = 0
            seen[page] = HashMap()
            val fresh = ArrayList<FeedRow>()
            resolveMore(page, plan, fresh, INITIAL_ROWS)
            val hero = pickHero(page)
            val cached = runCatching {
                container.database.libraryDao().vodCount() + container.database.libraryDao().seriesCount()
            }.getOrDefault(0)
            flow.value = FeedState(
                page = page,
                rows = fresh,
                hero = hero,
                loading = false,
                exhausted = (cursors[page] ?: 0) >= plan.size,
                note = if (cached == 0) "Your library is being prepared. Rows appear here as soon as it is ready." else null,
            )
            hero?.let { showHero(it) }
        }
    }

    /** Called as the viewer nears the bottom of a page. */
    fun loadMore(page: FeedPage) {
        if (jobs[page]?.isActive == true) return
        val plan = plans[page] ?: return
        val flow = states.getValue(page)
        if (flow.value.exhausted) return
        jobs[page] = scope.launch {
            val more = ArrayList<FeedRow>()
            resolveMore(page, plan, more, PAGE_ROWS)
            flow.value = flow.value.copy(
                rows = (flow.value.rows + more).distinctBy { it.id },
                exhausted = (cursors[page] ?: 0) >= plan.size,
            )
        }
    }

    private suspend fun resolveMore(page: FeedPage, plan: List<RowSpec>, into: MutableList<FeedRow>, want: Int) {
        var cursor = cursors[page] ?: 0
        var added = 0
        while (cursor < plan.size && added < want) {
            val spec = plan[cursor++]
            val row = runCatching { resolve(page, spec) }
                .onFailure { Log.w(TAG, "row ${spec.id} failed", it) }
                .getOrNull()
            if (row != null && row.tiles.size >= minTiles(row)) {
                into += row
                added++
            }
        }
        cursors[page] = cursor
    }

    private fun minTiles(row: FeedRow) = when (row.kind) {
        RowKind.CONTINUE, RowKind.SPORTS, RowKind.LIVE -> 1
        RowKind.TOP10 -> 5
        RowKind.POSTER -> if (row.id == "mylist") 1 else MIN_ROW
    }

    private suspend fun buildPlan(page: FeedPage): List<RowSpec> {
        val db = container.database
        val history = db.userDao().recent(12).mapNotNull { progress -> watchedTitle(progress) }
        val lib = db.libraryDao()
        val categories = (lib.vodCategories().map { ProviderCategory(it.categoryId, it.categoryName ?: "", TitleKind.MOVIE) } +
            lib.seriesCategories().map { ProviderCategory(it.categoryId, it.categoryName ?: "", TitleKind.SERIES) })
            .filter { it.name.isNotBlank() && !AdultContent.isAdult(it.name) && !isForeign(it.name) }
        return FeedPlanner.plan(page, Calendar.getInstance().get(Calendar.YEAR), history, categories)
    }

    private fun isForeign(name: String) = ForeignCountries.detectPrefix(Tokenizer.tokenize(name)) != null

    private suspend fun watchedTitle(p: WatchProgressEntity): WatchedTitle? {
        val lib = container.database.libraryDao()
        return if (p.kind == "MOVIE") {
            val v = lib.vod(p.itemId) ?: return null
            WatchedTitle(v.streamId, Mappers.tile(v).name, TitleKind.MOVIE, Genres.parse(v.genres))
        } else {
            val s = lib.series(p.seriesId ?: return null) ?: return null
            WatchedTitle(s.seriesId, Mappers.tile(s).name, TitleKind.SERIES, Genres.parse(s.genres))
        }
    }

    private suspend fun resolve(page: FeedPage, spec: RowSpec): FeedRow? = when (spec) {
        is RowSpec.Special -> when (spec.kind) {
            SpecialRow.CONTINUE_WATCHING -> continueRow()
            SpecialRow.MY_LIST -> myListRow()
            SpecialRow.LIVE_NOW -> liveNow().takeIf { it.isNotEmpty() }?.let {
                FeedRow(spec.id, spec.title, RowKind.LIVE, it.take(ROW_SIZE), subtitle = "Channels on now")
            }
            SpecialRow.LIVE_SPORTS -> sportsRow(spec.id, spec.title)
        }
        is RowSpec.Catalog -> catalogRow(page, spec)
    }

    private suspend fun catalogRow(page: FeedPage, spec: RowSpec.Catalog): FeedRow? = withContext(Dispatchers.IO) {
        val top10 = spec.style == RowStyle.TOP10
        val q = CatalogSql.build(spec.filter, limit = if (top10) 40 else ROW_FETCH, seed = seed)
        val query = SimpleSQLiteQuery(q.sql, q.args.toTypedArray())
        val lib = container.database.libraryDao()
        var tiles: List<TitleTile> = if (spec.filter.kind == TitleKind.MOVIE) {
            Mappers.dedupe(lib.vodQuery(query), { it.name }, { it.titleYear }).map(Mappers::tile)
        } else {
            Mappers.dedupe(lib.seriesQuery(query), { it.name }, { it.titleYear }).map(Mappers::tile)
        }
        val counts = seen.getOrPut(page) { HashMap() }
        if (!top10) {
            // Titles already shown twice on this page go to the back of the row, so scrolling
            // keeps turning up something new instead of the same twenty blockbusters.
            val (fresh, repeats) = tiles.partition { (counts[it.key] ?: 0) < 2 }
            tiles = fresh + repeats
        }
        tiles = tiles.take(if (top10) 10 else ROW_SIZE)
        tiles.forEach { counts[it.key] = (counts[it.key] ?: 0) + 1 }
        FeedRow(spec.id, spec.title, if (top10) RowKind.TOP10 else RowKind.POSTER, tiles)
    }

    private suspend fun continueRow(): FeedRow? = withContext(Dispatchers.IO) {
        val recent = container.database.userDao().recent(40)
        val seriesSeen = HashSet<Long>()
        val tiles = recent.filter { p ->
            if (p.isFinished || p.positionMs < 30_000L) return@filter false
            if (p.kind == "EPISODE") seriesSeen.add(p.seriesId ?: -1L) else true
        }.take(ROW_SIZE).map { p ->
            ContinueTile(
                progressKey = p.key,
                kind = if (p.kind == "MOVIE") TitleKind.MOVIE else TitleKind.SERIES,
                titleId = if (p.kind == "MOVIE") p.itemId else (p.seriesId ?: p.itemId),
                episodeId = if (p.kind == "EPISODE") p.itemId else null,
                name = p.title,
                subtitle = p.subtitle,
                image = p.image,
                backdrop = p.backdrop,
                fraction = p.fraction,
                positionMs = p.positionMs,
                containerExtension = p.containerExtension,
            )
        }
        if (tiles.isEmpty()) null else FeedRow("continue", "Continue Watching", RowKind.CONTINUE, tiles)
    }

    private suspend fun myListRow(): FeedRow? = withContext(Dispatchers.IO) {
        val items = container.database.userDao().list()
        if (items.isEmpty()) return@withContext null
        val lib = container.database.libraryDao()
        val movies = lib.vodByIds(items.filter { it.kind == TitleKind.MOVIE.name }.map { it.itemId }).associateBy { it.streamId }
        val shows = lib.seriesByIds(items.filter { it.kind == TitleKind.SERIES.name }.map { it.itemId }).associateBy { it.seriesId }
        val tiles = items.mapNotNull { item ->
            if (item.kind == TitleKind.MOVIE.name) movies[item.itemId]?.let(Mappers::tile)
                ?: TitleTile(TitleKind.MOVIE, item.itemId, item.name, item.image)
            else shows[item.itemId]?.let(Mappers::tile)
                ?: TitleTile(TitleKind.SERIES, item.itemId, item.name, item.image)
        }
        FeedRow("mylist", "My List", RowKind.POSTER, tiles)
    }

    private suspend fun sportsRow(id: String, title: String): FeedRow? {
        val events = runCatching { container.sports.headline() }.getOrDefault(emptyList())
        if (events.isEmpty()) return null
        val live = events.count { it.state == EventState.LIVE }
        return FeedRow(
            id, title, RowKind.SPORTS,
            events.take(ROW_SIZE).map(::GameTile),
            subtitle = if (live > 0) "$live live now · select a game to find its channel" else "Select a game to find its channel",
        )
    }

    // ------------------------------------------------------------------ hero and spotlight

    private suspend fun pickHero(page: FeedPage): TitleTile? = withContext(Dispatchers.IO) {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val lib = container.database.libraryDao()
        fun recent(kind: TitleKind): SimpleSQLiteQuery {
            val q = CatalogSql.build(
                com.crimson.core.catalog.CatalogFilter(kind, yearFrom = year - 3, minRating = 6.5f),
                limit = 24,
            )
            return SimpleSQLiteQuery(q.sql, q.args.toTypedArray())
        }
        val movies = if (page != FeedPage.SHOWS) Mappers.dedupe(lib.vodQuery(recent(TitleKind.MOVIE)), { it.name }, { it.titleYear }).map(Mappers::tile) else emptyList()
        val shows = if (page != FeedPage.MOVIES) Mappers.dedupe(lib.seriesQuery(recent(TitleKind.SERIES)), { it.name }, { it.titleYear }).map(Mappers::tile) else emptyList()
        val pool = (movies.take(8) + shows.take(8)).filter { it.poster != null }
        pool.randomOrNull(Random(seed + page.ordinal))
    }

    private fun showHero(hero: TitleTile) {
        focus(hero, overline = if (hero.kind == TitleKind.SERIES) "SERIES" else "FILM", immediate = true)
    }

    /**
     * The viewer's cursor moved to [tile]. The spotlight changes at once to what the card knows,
     * and fills in the synopsis and artwork after the cursor has rested for a moment.
     */
    fun focus(tile: Tile, overline: String? = null, immediate: Boolean = false) {
        spotlightJob?.cancel()
        when (tile) {
            is TitleTile -> {
                val cached = info.cached(tile.kind, tile.id)
                _spotlight.value = spotlightFor(tile, cached, overline)
                if (cached != null && (cached.backdrop != null || tile.backdrop != null)) return
                spotlightJob = scope.launch {
                    if (!immediate) delay(SPOTLIGHT_DELAY_MS)
                    val details = info.get(tile.kind, tile.id) ?: return@launch
                    if (_spotlight.value.key == tile.key) _spotlight.value = spotlightFor(tile, details, overline)
                }
            }
            is ContinueTile -> _spotlight.value = Spotlight(
                key = tile.key,
                overline = "CONTINUE WATCHING",
                title = tile.name,
                description = tile.subtitle,
                backdrop = tile.backdrop ?: tile.image,
                poster = tile.image,
                tile = tile,
            )
            is ChannelTile -> _spotlight.value = Spotlight(
                key = tile.key,
                overline = "ON NOW · ${tile.name.uppercase()}",
                title = tile.nowTitle ?: tile.name,
                description = tile.nowDescription ?: tile.nextTitle?.let { "Up next: $it" },
                isLive = true,
                liveProgress = tile.progress(System.currentTimeMillis()),
                logo = tile.logo,
                tile = tile,
            )
            is GameTile -> {
                val e = tile.event
                _spotlight.value = Spotlight(
                    key = tile.key,
                    overline = e.league.name.uppercase(),
                    title = listOfNotNull(e.away?.name, e.home?.name).joinToString(" at ").ifBlank { e.name },
                    description = listOfNotNull(
                        e.statusText.takeIf { it.isNotBlank() },
                        e.networks.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "On $it" },
                        e.venue,
                    ).joinToString("  ·  "),
                    isLive = e.state == EventState.LIVE,
                    tile = tile,
                )
            }
        }
    }

    private fun spotlightFor(tile: TitleTile, details: com.crimson.ui.TitleInfo?, overline: String?) = Spotlight(
        key = tile.key,
        overline = overline,
        title = tile.name,
        description = details?.plot ?: tile.plot,
        backdrop = tile.backdrop ?: details?.backdrop,
        poster = tile.poster ?: details?.poster,
        rating = tile.rating,
        year = tile.year ?: details?.year,
        ageRating = details?.ageRating,
        runtime = details?.runtime,
        genres = tile.genres.ifEmpty { details?.genres.orEmpty() }.take(3),
        tile = tile,
    )

    companion object {
        private const val TAG = "CrimsonFeed"
        const val INITIAL_ROWS = 7
        const val PAGE_ROWS = 5
        const val ROW_SIZE = 30
        const val ROW_FETCH = 90
        const val MIN_ROW = 5
        const val SPOTLIGHT_DELAY_MS = 320L
    }
}
