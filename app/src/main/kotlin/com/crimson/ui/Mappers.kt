package com.crimson.ui

import com.crimson.core.catalog.Genres
import com.crimson.core.catalog.TitleKind
import com.crimson.core.catalog.TitleMatcher
import com.crimson.core.guide.ProgramSlot
import com.crimson.data.db.ProgramEntity
import com.crimson.data.db.SeriesEntity
import com.crimson.data.db.VodEntity
import com.crimson.domain.GuideChannel
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.TitleTile

/** Turning database rows into cards. */
object Mappers {

    fun tile(vod: VodEntity) = TitleTile(
        kind = TitleKind.MOVIE,
        id = vod.streamId,
        name = TitleMatcher.displayTitle(vod.name),
        poster = vod.icon?.takeIf { it.isNotBlank() },
        year = vod.titleYear,
        rating = vod.imdbRating ?: vod.rating?.toFloatOrNull()?.takeIf { it in 0.5f..10f },
        genres = Genres.parse(vod.genres),
        containerExtension = vod.containerExtension,
    )

    fun tile(series: SeriesEntity) = TitleTile(
        kind = TitleKind.SERIES,
        id = series.seriesId,
        name = TitleMatcher.displayTitle(series.name),
        poster = series.cover?.takeIf { it.isNotBlank() },
        backdrop = series.backdrop?.takeIf { it.isNotBlank() },
        year = series.titleYear,
        rating = series.imdbRating ?: series.rating?.toFloatOrNull()?.takeIf { it in 0.5f..10f },
        genres = Genres.parse(series.genres),
        plot = series.plot?.takeIf { it.isNotBlank() },
    )

    fun channelTile(channel: GuideChannel, now: ProgramSlot? = null, next: ProgramSlot? = null) = ChannelTile(
        streamId = channel.streamId,
        number = channel.number,
        name = channel.name,
        logo = channel.logoUrl?.takeIf { it.isNotBlank() },
        nowTitle = now?.title?.takeIf { !now.isFiller && it.isNotBlank() },
        nowStartMs = now?.startMs ?: 0L,
        nowEndMs = now?.endMs ?: 0L,
        nowDescription = now?.description?.takeIf { it.isNotBlank() },
        nextTitle = next?.title,
        isFavorite = channel.isFavorite,
        category = channel.categoryName,
    )

    fun slot(row: ProgramEntity) = ProgramSlot(
        id = row.id,
        startMs = row.startMs,
        endMs = row.endMs,
        title = row.title,
        category = row.categoryEnum,
        description = row.description,
        rating = row.rating,
    )

    /** Labels that mean the copy is in English. */
    private val ENGLISH_PREFIXES = setOf("EN", "US", "UK", "GB", "ENG", "USA")

    /**
     * How much the app would rather show one copy of a title than another. The catalogue often
     * carries the same film once per language under the same title key: an English label wins,
     * no label is next (usually the main entry), anything else is a dub.
     */
    fun languagePreference(name: String): Int {
        val prefix = name.takeWhile { it.isLetterOrDigit() || it == '+' }.uppercase()
        return when {
            prefix in ENGLISH_PREFIXES -> 0
            prefix.length !in 2..4 -> 1
            else -> 2
        }
    }

    /**
     * One card per title, keeping the order of the query and the best-language copy of each.
     */
    fun <T> dedupe(items: List<T>, name: (T) -> String, year: (T) -> Int?): List<T> {
        val best = LinkedHashMap<String, T>(items.size)
        for (item in items) {
            val key = TitleMatcher.key(name(item)).text + "|" + (year(item) ?: "")
            val current = best[key]
            if (current == null || languagePreference(name(item)) < languagePreference(name(current))) {
                best[key] = item
            }
        }
        return best.values.toList()
    }

    /** "1h 52m" from minutes or seconds, whichever the panel gave. */
    fun runtime(durationSecs: Int?, duration: String?): String? {
        val minutes = when {
            durationSecs != null && durationSecs > 0 -> durationSecs / 60
            duration != null -> parseDuration(duration)
            else -> null
        } ?: return null
        if (minutes <= 0) return null
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    /** Panels write "108", "01:48:00" or "1h 48m". */
    private fun parseDuration(text: String): Int? {
        val t = text.trim()
        t.toIntOrNull()?.let { return it }
        val parts = t.split(':').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 3) return parts[0] * 60 + parts[1]
        if (parts.size == 2) return parts[0] * 60 + parts[1]
        val h = Regex("(\\d+)\\s*h").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    fun year(releaseDate: String?): Int? =
        releaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    fun genres(genre: String?): List<String> =
        genre.orEmpty().split(',', '/', '|').map(String::trim).filter(String::isNotEmpty).take(3)
}
