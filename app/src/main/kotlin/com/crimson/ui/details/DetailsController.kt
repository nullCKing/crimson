package com.crimson.ui.details

import androidx.sqlite.db.SimpleSQLiteQuery
import com.crimson.AppContainer
import com.crimson.core.catalog.CatalogSql
import com.crimson.core.catalog.FeedPlanner
import com.crimson.core.catalog.Genres
import com.crimson.core.catalog.TitleKind
import com.crimson.core.catalog.WatchedTitle
import com.crimson.core.model.RawEpisode
import com.crimson.data.db.MyListEntity
import com.crimson.data.db.WatchProgressEntity
import com.crimson.ui.InfoCache
import com.crimson.ui.Mappers
import com.crimson.ui.TitleInfo
import com.crimson.ui.components.TitleTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EpisodeUi(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val image: String?,
    val runtime: String?,
    val plot: String?,
    val fraction: Float,
    val positionMs: Long,
    val containerExtension: String?,
)

/** What the big Play button will do. */
data class PlayAction(
    val label: String,
    val episode: EpisodeUi? = null,
    val positionMs: Long = 0L,
    val fraction: Float = 0f,
)

data class DetailsState(
    val kind: TitleKind = TitleKind.MOVIE,
    val id: Long = 0L,
    val loading: Boolean = true,
    val name: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val year: Int? = null,
    val rating: Float? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val inMyList: Boolean = false,
    val play: PlayAction = PlayAction("Play"),
    val seasons: List<Int> = emptyList(),
    val season: Int = 1,
    val episodes: List<EpisodeUi> = emptyList(),
    val moreLikeThis: List<TitleTile> = emptyList(),
    val containerExtension: String? = null,
    val error: String? = null,
)

/**
 * The details page for a film or a series: artwork, the facts, the synopsis, Play or Resume, My
 * List, the episodes season by season, and "More Like This".
 *
 * It opens instantly from what the database already holds and fills in from the provider's info
 * call, so the page is never a spinner.
 */
class DetailsController(
    private val container: AppContainer,
    private val info: InfoCache,
) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state.asStateFlow()
    private var job: Job? = null
    private var episodesBySeason: Map<Int, List<RawEpisode>> = emptyMap()

    fun reset() {
        job?.cancel()
        _state.value = DetailsState()
        episodesBySeason = emptyMap()
    }

    fun open(kind: TitleKind, id: Long) {
        job?.cancel()
        val keepSeason = _state.value.takeIf { it.kind == kind && it.id == id }?.season
        _state.value = DetailsState(kind = kind, id = id, loading = true)
        job = scope.launch {
            val db = container.database
            val base: TitleTile? = withContext(Dispatchers.IO) {
                if (kind == TitleKind.MOVIE) db.libraryDao().vod(id)?.let(Mappers::tile)
                else db.libraryDao().series(id)?.let(Mappers::tile)
            }
            val inList = withContext(Dispatchers.IO) { db.userDao().isInList(kind.name, id) }
            if (base != null) {
                _state.value = _state.value.copy(
                    name = base.name,
                    poster = base.poster,
                    backdrop = base.backdrop,
                    year = base.year,
                    rating = base.rating,
                    genres = base.genres.take(3),
                    plot = base.plot,
                    inMyList = inList,
                    containerExtension = base.containerExtension,
                )
            }
            launch { loadMoreLikeThis(kind, id, base) }

            val details = info.get(kind, id)
            if (details == null && base == null) {
                _state.value = _state.value.copy(loading = false, error = "This title could not be loaded.")
                return@launch
            }
            applyDetails(details, base)
            if (kind == TitleKind.SERIES) {
                episodesBySeason = details?.series?.episodes.orEmpty()
                val seasons = episodesBySeason.keys.sorted().ifEmpty { details?.series?.seasons.orEmpty() }
                val progress = withContext(Dispatchers.IO) { db.userDao().progressForSeries(id) }
                val resumeEpisode = progress.firstOrNull()
                val season = keepSeason
                    ?: resumeEpisode?.season?.takeIf { it in seasons }
                    ?: seasons.firstOrNull() ?: 1
                _state.value = _state.value.copy(seasons = seasons, loading = false)
                showSeason(season, progress)
                _state.value = _state.value.copy(play = seriesPlayAction(progress))
            } else {
                val progress = withContext(Dispatchers.IO) { db.userDao().progress(WatchProgressEntity.movieKey(id)) }
                _state.value = _state.value.copy(
                    loading = false,
                    play = if (progress != null && !progress.isFinished && progress.positionMs > 30_000L) {
                        PlayAction("Resume", positionMs = progress.positionMs, fraction = progress.fraction)
                    } else PlayAction("Play"),
                )
            }
        }
    }

    private fun applyDetails(details: TitleInfo?, base: TitleTile?) {
        if (details == null) {
            _state.value = _state.value.copy(loading = false)
            return
        }
        val s = _state.value
        _state.value = s.copy(
            name = s.name.ifBlank { details.name.orEmpty() },
            poster = s.poster ?: details.poster,
            backdrop = s.backdrop ?: details.backdrop ?: details.poster ?: s.poster,
            year = s.year ?: details.year,
            ageRating = details.ageRating,
            runtime = details.runtime,
            genres = s.genres.ifEmpty { details.genres },
            plot = details.plot ?: s.plot,
            cast = details.cast,
            director = details.director,
            containerExtension = details.containerExtension ?: base?.containerExtension,
        )
    }

    fun selectSeason(season: Int) {
        if (season == _state.value.season && _state.value.episodes.isNotEmpty()) return
        scope.launch {
            val progress = withContext(Dispatchers.IO) { container.database.userDao().progressForSeries(_state.value.id) }
            showSeason(season, progress)
        }
    }

    private fun showSeason(season: Int, progress: List<WatchProgressEntity>) {
        val byEpisode = progress.associateBy { it.itemId }
        val list = episodesBySeason[season].orEmpty().sortedBy { it.episodeNum }.map { ep ->
            val p = byEpisode[ep.id]
            EpisodeUi(
                id = ep.id,
                season = season,
                number = ep.episodeNum,
                title = ep.title.let(::cleanEpisodeTitle).ifBlank { "Episode ${ep.episodeNum}" },
                image = ep.image,
                runtime = Mappers.runtime(ep.durationSecs, null),
                plot = ep.info,
                fraction = p?.fraction ?: 0f,
                positionMs = p?.positionMs ?: 0L,
                containerExtension = ep.containerExtension,
            )
        }
        _state.value = _state.value.copy(season = season, episodes = list)
    }

    /**
     * Resume the episode the viewer is on; if they finished it, the next one; if they never
     * started, the first episode of the first season.
     */
    private fun seriesPlayAction(progress: List<WatchProgressEntity>): PlayAction {
        val all = episodesBySeason.toSortedMap().flatMap { (season, eps) -> eps.sortedBy { it.episodeNum }.map { season to it } }
        if (all.isEmpty()) return PlayAction("Play")
        fun ui(season: Int, ep: RawEpisode, p: WatchProgressEntity?) = EpisodeUi(
            ep.id, season, ep.episodeNum, cleanEpisodeTitle(ep.title), ep.image,
            Mappers.runtime(ep.durationSecs, null), ep.info, p?.fraction ?: 0f, p?.positionMs ?: 0L, ep.containerExtension,
        )
        val last = progress.firstOrNull()
        if (last != null) {
            val index = all.indexOfFirst { it.second.id == last.itemId }
            if (index >= 0) {
                val (season, ep) = all[index]
                if (!last.isFinished) {
                    return PlayAction("Resume S$season:E${ep.episodeNum}", ui(season, ep, last), last.positionMs, last.fraction)
                }
                all.getOrNull(index + 1)?.let { (s2, next) ->
                    return PlayAction("Play S$s2:E${next.episodeNum}", ui(s2, next, null))
                }
            }
        }
        val (season, first) = all.first()
        return PlayAction(if (all.size == 1) "Play" else "Play S$season:E${first.episodeNum}", ui(season, first, null))
    }

    /** The episode after [episodeId] in this series, if the details for it are loaded. */
    fun episodeAfter(seriesId: Long, episodeId: Long): EpisodeUi? {
        val all = (info.cached(TitleKind.SERIES, seriesId)?.series?.episodes ?: return null)
            .toSortedMap()
            .flatMap { (season, eps) -> eps.sortedBy { it.episodeNum }.map { season to it } }
        val index = all.indexOfFirst { it.second.id == episodeId }
        val (season, ep) = all.getOrNull(index + 1) ?: return null
        return EpisodeUi(ep.id, season, ep.episodeNum, cleanEpisodeTitle(ep.title), ep.image, null, ep.info, 0f, 0L, ep.containerExtension)
    }

    fun toggleMyList() {
        val s = _state.value
        scope.launch {
            val dao = container.database.userDao()
            withContext(Dispatchers.IO) {
                if (s.inMyList) dao.removeFromList(s.kind.name, s.id)
                else dao.addToList(MyListEntity(s.kind.name, s.id, s.name, s.poster))
            }
            _state.value = _state.value.copy(inMyList = !s.inMyList)
        }
    }

    private suspend fun loadMoreLikeThis(kind: TitleKind, id: Long, base: TitleTile?) {
        val lib = container.database.libraryDao()
        val genres = withContext(Dispatchers.IO) {
            if (kind == TitleKind.MOVIE) Genres.parse(lib.vod(id)?.genres) else Genres.parse(lib.series(id)?.genres)
        }
        val filter = FeedPlanner.moreLikeThis(WatchedTitle(id, base?.name ?: "", kind, genres)) ?: return
        val q = CatalogSql.build(filter, limit = 60, seed = (id % 997).toInt() + 1)
        val query = SimpleSQLiteQuery(q.sql, q.args.toTypedArray())
        val tiles = withContext(Dispatchers.IO) {
            if (kind == TitleKind.MOVIE) Mappers.dedupe(lib.vodQuery(query), { it.name }, { it.titleYear }).map(Mappers::tile)
            else Mappers.dedupe(lib.seriesQuery(query), { it.name }, { it.titleYear }).map(Mappers::tile)
        }.filterNot { it.name.equals(base?.name, ignoreCase = true) }.take(24)
        if (_state.value.id == id) _state.value = _state.value.copy(moreLikeThis = tiles)
    }

    companion object {
        /** Panels often title an episode "Show Name - S01E02 - Actual Title"; keep the last part. */
        fun cleanEpisodeTitle(raw: String): String {
            val parts = raw.split(" - ").map(String::trim).filter(String::isNotEmpty)
            val tagged = parts.indexOfLast { Regex("(?i)^S\\d{1,2}\\s?E\\d{1,3}$").matches(it) }
            return when {
                tagged >= 0 && tagged < parts.lastIndex -> parts.drop(tagged + 1).joinToString(" - ")
                tagged >= 0 -> ""
                else -> raw.trim()
            }
        }
    }
}
