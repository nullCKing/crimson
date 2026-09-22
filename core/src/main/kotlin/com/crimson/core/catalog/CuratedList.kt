package com.crimson.core.catalog

import java.io.BufferedReader
import java.io.Reader

/** Which half of the library a curated entry belongs to. */
enum class TitleKind { MOVIE, SERIES }

/** One title in a curated list, as IMDb's data gave it. */
data class CuratedTitle(
    val name: String,
    val year: Int,
    val kind: TitleKind,
) {
    val key: TitleMatcher.Key get() = TitleMatcher.key(name).copy(year = year)
}

/** A row of titles: "Best of the 1980s", "Action Movies", "IMDb Top Rated Series". */
data class CuratedList(
    val id: String,
    val title: String,
    val subtitle: String,
    val titles: List<CuratedTitle>,
) {
    val kind: TitleKind get() = titles.firstOrNull()?.kind ?: TitleKind.MOVIE
}

/**
 * Reads `assets/curated_lists.txt`, which `tools/make-curated-lists.py` generates from IMDb's
 * published datasets.
 *
 * The file is deliberately a flat tab-separated text file rather than JSON: it is read once at
 * startup on a device with a gigabyte of RAM, and a line-by-line reader costs nothing next to
 * standing up a JSON parser for 2,700 records. The format is its own documentation:
 *
 * ```
 * #<list id>\t<title>\t<subtitle>
 * <name>\t<year>\tM        M for a film, S for a series
 * ```
 */
object CuratedLists {

    fun parse(reader: Reader): List<CuratedList> {
        val out = ArrayList<CuratedList>(64)
        var id: String? = null
        var title = ""
        var subtitle = ""
        var titles = ArrayList<CuratedTitle>(64)

        fun flush() {
            val current = id ?: return
            if (titles.isNotEmpty()) out.add(CuratedList(current, title, subtitle, titles))
        }

        BufferedReader(reader).forEachLine { line ->
            when {
                line.isBlank() -> Unit
                // "# " with a space is a comment; "#id" starts a list.
                line.startsWith("# ") -> Unit
                line.startsWith("#") -> {
                    flush()
                    val parts = line.substring(1).split('\t')
                    id = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                    title = parts.getOrNull(1).orEmpty()
                    subtitle = parts.getOrNull(2).orEmpty()
                    titles = ArrayList(64)
                }
                else -> {
                    val parts = line.split('\t')
                    val name = parts.getOrNull(0).orEmpty()
                    val year = parts.getOrNull(1)?.toIntOrNull()
                    val kind = if (parts.getOrNull(2) == "S") TitleKind.SERIES else TitleKind.MOVIE
                    if (name.isNotBlank() && year != null) {
                        titles.add(CuratedTitle(name, year, kind))
                    }
                }
            }
        }
        flush()
        return out
    }
}

/**
 * An index of what the viewer actually has, so a curated list can be shown as the subset of it
 * they can watch.
 *
 * Built once from the library and reused for every list: 46 lists over a 20,000-title library is
 * 2,700 lookups against a hash map, not 46 scans of the library.
 */
class LibraryIndex<T>(
    items: List<T>,
    nameOf: (T) -> String,
    /**
     * How much the caller would rather have one copy than another, lower being better.
     *
     * A provider carries the same film once per language — `EN - Pulp Fiction`,
     * `AR - Pulp Fiction`, `PL - PULP FICTION` — and they all reduce to the same key. Without
     * this the viewer gets whichever the database returned first, which is as likely to be the
     * Polish dub as not.
     */
    private val preferenceOf: (T) -> Int = { 0 },
) {

    private val byKey = HashMap<String, MutableList<Pair<TitleMatcher.Key, T>>>(items.size)

    init {
        for (item in items) {
            // Both readings of a name that ends in a number are indexed; see TitleMatcher.variants.
            for (key in TitleMatcher.variants(nameOf(item))) {
                if (key.text.isEmpty()) continue
                byKey.getOrPut(key.text) { ArrayList(1) }.add(key to item)
            }
        }
    }

    /** What the viewer has for this curated title, or null. Prefers the closest year. */
    fun find(title: CuratedTitle): T? {
        val candidates = byKey[title.key.text] ?: return null
        val wanted = title.key
        val exact = candidates.filter { TitleMatcher.matches(it.first, wanted) }
        if (exact.isEmpty()) return null
        // Several copies of the same film: the caller's preference first, then the nearest year.
        return exact.minWithOrNull(
            compareBy(
                { (_, item) -> preferenceOf(item) },
                { (key, _) -> key.year?.let { kotlin.math.abs(it - title.year) } ?: 1 },
            )
        )?.second
    }

    /** The viewer's copies of everything in [list], in the list's order, skipping what they lack. */
    fun resolve(list: CuratedList, limit: Int = Int.MAX_VALUE): List<T> {
        val out = ArrayList<T>(minOf(list.titles.size, 64))
        for (title in list.titles) {
            val found = find(title) ?: continue
            out.add(found)
            if (out.size >= limit) break
        }
        return out
    }
}
