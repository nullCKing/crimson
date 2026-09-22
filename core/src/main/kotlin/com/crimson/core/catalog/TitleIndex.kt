package com.crimson.core.catalog

import java.io.BufferedReader
import java.io.Reader

/** What IMDb knows about one title, as `tools/make-title-index.py` wrote it. */
data class IndexedTitle(
    val name: String,
    val year: Int,
    val kind: TitleKind,
    val rating: Float,
    val votes: Int,
    val genres: List<String>,
) {
    /** The matching key, with IMDb's year standing in for the one a file name may lack. */
    val key: TitleMatcher.Key get() = TitleMatcher.key(name).copy(year = year)

    /**
     * Genres as stored in the database: comma-delimited with a comma at each end, so a
     * `LIKE '%,Crime,%'` matches Crime and never "Crime" inside some longer word.
     */
    val genreColumn: String get() = Genres.column(genres)
}

/**
 * Reads `assets/title_index.tsv`.
 *
 * Streamed one line at a time on purpose: the file holds some thirty thousand titles, and the
 * only consumer applies each one to the database and forgets it. Holding the whole index would
 * cost several megabytes of heap on a device that has a gigabyte for everything.
 *
 * ```
 * <name>\t<year>\t<M|S>\t<rating>\t<votes>\t<Genre,Genre>
 * ```
 */
object TitleIndex {

    fun read(reader: Reader, onTitle: (IndexedTitle) -> Unit): Int {
        var count = 0
        BufferedReader(reader).forEachLine { line ->
            parseLine(line)?.let { onTitle(it); count++ }
        }
        return count
    }

    fun parseLine(line: String): IndexedTitle? {
        if (line.isBlank() || line.startsWith("#")) return null
        val parts = line.split('\t')
        if (parts.size < 5) return null
        val year = parts[1].toIntOrNull() ?: return null
        val kind = when (parts[2]) {
            "M" -> TitleKind.MOVIE
            "S" -> TitleKind.SERIES
            else -> return null
        }
        val rating = parts[3].toFloatOrNull() ?: return null
        val votes = parts[4].toIntOrNull() ?: return null
        val genres = parts.getOrNull(5).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        return IndexedTitle(parts[0], year, kind, rating, votes, genres)
    }
}

/** IMDb's genre vocabulary, and the column encoding the queries rely on. */
object Genres {
    const val ACTION = "Action"
    const val ADVENTURE = "Adventure"
    const val ANIMATION = "Animation"
    const val BIOGRAPHY = "Biography"
    const val COMEDY = "Comedy"
    const val CRIME = "Crime"
    const val DOCUMENTARY = "Documentary"
    const val DRAMA = "Drama"
    const val FAMILY = "Family"
    const val FANTASY = "Fantasy"
    const val HISTORY = "History"
    const val HORROR = "Horror"
    const val MUSIC = "Music"
    const val MUSICAL = "Musical"
    const val MYSTERY = "Mystery"
    const val REALITY = "Reality-TV"
    const val ROMANCE = "Romance"
    const val SCIFI = "Sci-Fi"
    const val SPORT = "Sport"
    const val THRILLER = "Thriller"
    const val WAR = "War"
    const val WESTERN = "Western"

    fun column(genres: List<String>): String =
        if (genres.isEmpty()) "" else genres.joinToString(",", prefix = ",", postfix = ",")

    fun parse(column: String?): List<String> =
        column.orEmpty().split(',').filter(String::isNotEmpty)

    /** The pattern that matches one genre inside a [column] value. */
    fun like(genre: String): String = "%,$genre,%"
}
