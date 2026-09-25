package com.crimson.ui

import android.util.LruCache
import com.crimson.core.catalog.TitleKind
import com.crimson.core.model.RawSeriesInfo
import com.crimson.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The details a card does not carry: synopsis, artwork, runtime, cast. */
data class TitleInfo(
    val kind: TitleKind,
    val id: Long,
    val name: String? = null,
    val plot: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val containerExtension: String? = null,
    val series: RawSeriesInfo? = null,
    val tmdbId: String? = null,
)

/**
 * `get_vod_info` and `get_series_info`, remembered.
 *
 * The spotlight asks for a title's details every time a poster keeps focus for a moment, and a
 * viewer scrolling back and forth along a row asks for the same few again and again; a round
 * trip to a slow panel each time would make the artwork lag the cursor. Films are small and many
 * are kept; a series' info carries its whole episode list, so fewer of those are.
 */
class InfoCache(private val client: () -> XtreamClient?) {

    private val movies = LruCache<Long, TitleInfo>(120)
    private val shows = LruCache<Long, TitleInfo>(16)

    fun cached(kind: TitleKind, id: Long): TitleInfo? =
        if (kind == TitleKind.MOVIE) movies.get(id) else shows.get(id)

    suspend fun get(kind: TitleKind, id: Long): TitleInfo? =
        if (kind == TitleKind.MOVIE) movie(id) else series(id)

    suspend fun movie(id: Long): TitleInfo? {
        movies.get(id)?.let { return it }
        val c = client() ?: return null
        val raw = withContext(Dispatchers.IO) { runCatching { c.vodInfo(id) }.getOrNull() } ?: return null
        val info = TitleInfo(
            kind = TitleKind.MOVIE,
            id = id,
            name = raw.name.takeIf { it.isNotBlank() },
            plot = raw.description?.takeIf { it.isNotBlank() },
            backdrop = raw.backdropUrl?.takeIf { it.isNotBlank() },
            poster = raw.coverUrl?.takeIf { it.isNotBlank() },
            ageRating = raw.mpaa?.takeIf { it.isNotBlank() && it.length <= 8 },
            runtime = Mappers.runtime(raw.durationSecs, raw.duration),
            cast = raw.cast?.takeIf { it.isNotBlank() },
            director = raw.director?.takeIf { it.isNotBlank() },
            genres = Mappers.genres(raw.genre),
            year = Mappers.year(raw.releaseDate),
            containerExtension = raw.containerExtension,
            tmdbId = raw.tmdbId,
        )
        movies.put(id, info)
        return info
    }

    suspend fun series(id: Long): TitleInfo? {
        shows.get(id)?.let { return it }
        val c = client() ?: return null
        val raw = withContext(Dispatchers.IO) { runCatching { c.seriesInfo(id) }.getOrNull() } ?: return null
        val info = TitleInfo(
            kind = TitleKind.SERIES,
            id = id,
            name = raw.name.takeIf { it.isNotBlank() },
            plot = raw.plot?.takeIf { it.isNotBlank() },
            backdrop = raw.backdrop?.takeIf { it.isNotBlank() },
            poster = raw.cover?.takeIf { it.isNotBlank() },
            cast = raw.cast?.takeIf { it.isNotBlank() },
            director = raw.director?.takeIf { it.isNotBlank() },
            genres = Mappers.genres(raw.genre),
            year = Mappers.year(raw.releaseDate),
            series = raw,
            tmdbId = raw.tmdbId,
        )
        shows.put(id, info)
        return info
    }

    fun clear() {
        movies.evictAll()
        shows.evictAll()
    }
}
