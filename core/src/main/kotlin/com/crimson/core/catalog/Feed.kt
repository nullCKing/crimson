package com.crimson.core.catalog

/**
 * The recommendation rows, as data.
 *
 * Every row on the Home, Movies and TV Shows pages is one of these. A [RowSpec.Catalog] is a
 * query over the viewer's cached catalogue — "crime dramas rated above 7.5, most popular first" —
 * which [CatalogSql] turns into SQL, so a row costs one indexed query and never loads the
 * catalogue into memory. The special rows (continue watching, live channels, sports) are resolved
 * by the app from other sources.
 *
 * Keeping the rows as plain data here, rather than as code in the view model, is what lets the
 * feed be long — effectively endless — without being a wall of hand-written queries, and what
 * lets its shape be unit-tested.
 */
sealed class RowSpec {
    abstract val id: String
    abstract val title: String

    /** A query over the catalogue. */
    data class Catalog(
        override val id: String,
        override val title: String,
        val filter: CatalogFilter,
        val style: RowStyle = RowStyle.POSTER,
    ) : RowSpec()

    /** Rows whose items come from somewhere other than a catalogue query. */
    data class Special(
        override val id: String,
        override val title: String,
        val kind: SpecialRow,
    ) : RowSpec()
}

enum class RowStyle { POSTER, TOP10, WIDE }

enum class SpecialRow {
    CONTINUE_WATCHING,
    MY_LIST,
    /** The recommended live lineup, with what each channel is showing now. */
    LIVE_NOW,
    /** Games on today, from the sports scoreboard. */
    LIVE_SPORTS,
}

enum class CatalogSort { POPULAR, TOP_RATED, RECENTLY_ADDED, SHUFFLE }

/** What a catalogue row asks for. Every field narrows; nulls and empties mean "any". */
data class CatalogFilter(
    val kind: TitleKind,
    /** Every one of these genres. */
    val genresAll: List<String> = emptyList(),
    /** At least one of these. */
    val genresAny: List<String> = emptyList(),
    /** None of these. */
    val genresNone: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Float? = null,
    val minVotes: Int? = null,
    val maxVotes: Int? = null,
    /** A provider category, for the rows made from the provider's own grouping. */
    val categoryId: String? = null,
    /** Titles never to show, such as the one a "more like this" row is about. */
    val excludeIds: List<Long> = emptyList(),
    val sort: CatalogSort = CatalogSort.POPULAR,
    /**
     * Only titles joined to the IMDb index. True for every genre row, since a title with no
     * index entry has no genres to match; false for provider categories and "recently added".
     */
    val indexedOnly: Boolean = true,
)

/**
 * SQL for a [CatalogFilter].
 *
 * The tables are `vod` and `series`, which share the columns used here. Foreign-language and
 * adult entries are always excluded: the feed is somewhere to browse, and those are reached
 * through search and the provider's categories when they are wanted.
 */
object CatalogSql {

    data class Query(val sql: String, val args: List<Any>)

    fun build(filter: CatalogFilter, limit: Int, seed: Int = 1): Query {
        val table = if (filter.kind == TitleKind.MOVIE) "vod" else "series"
        val idColumn = if (filter.kind == TitleKind.MOVIE) "streamId" else "seriesId"
        val where = ArrayList<String>()
        val args = ArrayList<Any>()

        where += "isForeign = 0"
        where += "isAdult = 0"
        if (filter.indexedOnly) where += "imdbVotes IS NOT NULL"
        for (g in filter.genresAll) {
            where += "genres LIKE ?"; args += Genres.like(g)
        }
        if (filter.genresAny.isNotEmpty()) {
            where += filter.genresAny.joinToString(" OR ", "(", ")") { "genres LIKE ?" }
            filter.genresAny.forEach { args += Genres.like(it) }
        }
        for (g in filter.genresNone) {
            where += "(genres IS NULL OR genres NOT LIKE ?)"; args += Genres.like(g)
        }
        filter.yearFrom?.let { where += "titleYear >= ?"; args += it }
        filter.yearTo?.let { where += "titleYear <= ?"; args += it }
        filter.minRating?.let { where += "imdbRating >= ?"; args += it.toDouble() }
        filter.minVotes?.let { where += "imdbVotes >= ?"; args += it }
        filter.maxVotes?.let { where += "imdbVotes <= ?"; args += it }
        filter.categoryId?.let { where += "categoryId = ?"; args += it }
        if (filter.excludeIds.isNotEmpty()) {
            where += filter.excludeIds.joinToString(",", "$idColumn NOT IN (", ")") { "?" }
            filter.excludeIds.forEach { args += it }
        }

        // No NULLS LAST before SQLite 3.30, which is newer than a Fire OS 6 device has; sorting
        // on "IS NULL" first does the same job everywhere.
        val order = when (filter.sort) {
            CatalogSort.POPULAR -> "imdbVotes IS NULL, imdbVotes DESC, added DESC"
            CatalogSort.TOP_RATED -> "imdbRating IS NULL, imdbRating DESC, imdbVotes DESC"
            CatalogSort.RECENTLY_ADDED -> "added IS NULL, added DESC"
            // A cheap deterministic shuffle: different on every visit (the seed changes) but
            // stable while the viewer scrolls, which RANDOM() would not be.
            CatalogSort.SHUFFLE -> "(($idColumn * ${seed.coerceAtLeast(1)}) % 1000003)"
        }
        args += limit
        val sql = "SELECT * FROM $table WHERE ${where.joinToString(" AND ")} ORDER BY $order LIMIT ?"
        return Query(sql, args)
    }
}

/** Which page a feed is for. */
enum class FeedPage { HOME, MOVIES, SHOWS }

/** Something the viewer watched, as the planner needs it for "Because you watched". */
data class WatchedTitle(val id: Long, val name: String, val kind: TitleKind, val genres: List<String>)

/** A provider category that can become a row at the endless tail of a feed. */
data class ProviderCategory(val id: String, val name: String, val kind: TitleKind)

/**
 * The order of rows on each page.
 *
 * The rule of thumb Netflix made familiar: the rows that are about *you* first (continue
 * watching, your list, because you watched), then what is popular, then an interleave of moods
 * and genres, and then — so the page never runs out — every one of the provider's own categories.
 * Rows that turn out empty for this account are skipped by the caller, so the plan can afford to
 * ask for more than any one catalogue will have.
 */
object FeedPlanner {

    fun plan(
        page: FeedPage,
        currentYear: Int,
        history: List<WatchedTitle> = emptyList(),
        categories: List<ProviderCategory> = emptyList(),
    ): List<RowSpec> {
        val movies = movieRows(currentYear)
        val shows = showRows(currentYear)
        val because = history.distinctBy { it.kind to it.id }.take(3).mapNotNull(::becauseYouWatched)
        val out = ArrayList<RowSpec>(256)

        when (page) {
            FeedPage.HOME -> {
                out += RowSpec.Special("continue", "Continue Watching", SpecialRow.CONTINUE_WATCHING)
                out += RowSpec.Special("sports", "Live Sports", SpecialRow.LIVE_SPORTS)
                out += top10(TitleKind.MOVIE, currentYear)
                out += RowSpec.Special("live", "Live on American Cable", SpecialRow.LIVE_NOW)
                out += top10(TitleKind.SERIES, currentYear)
                out += RowSpec.Special("mylist", "My List", SpecialRow.MY_LIST)
                because.firstOrNull()?.let { out += it }
                out += recentlyAdded(TitleKind.MOVIE)
                out += interleave(interleave(movies, shows), because.drop(1), every = 5)
            }
            FeedPage.MOVIES -> {
                out += RowSpec.Special("continue", "Continue Watching", SpecialRow.CONTINUE_WATCHING)
                out += top10(TitleKind.MOVIE, currentYear)
                out += recentlyAdded(TitleKind.MOVIE)
                out += interleave(movies, because.filter { it.filter.kind == TitleKind.MOVIE }, every = 4)
            }
            FeedPage.SHOWS -> {
                out += RowSpec.Special("continue", "Continue Watching", SpecialRow.CONTINUE_WATCHING)
                out += top10(TitleKind.SERIES, currentYear)
                out += recentlyAdded(TitleKind.SERIES)
                out += interleave(shows, because.filter { it.filter.kind == TitleKind.SERIES }, every = 4)
            }
        }

        val tailKinds = when (page) {
            FeedPage.HOME -> setOf(TitleKind.MOVIE, TitleKind.SERIES)
            FeedPage.MOVIES -> setOf(TitleKind.MOVIE)
            FeedPage.SHOWS -> setOf(TitleKind.SERIES)
        }
        val tail = categories.filter { it.kind in tailKinds }.map { category ->
            RowSpec.Catalog(
                id = "cat_${category.kind.name}_${category.id}",
                title = CategoryTitles.clean(category.name),
                filter = CatalogFilter(
                    kind = category.kind,
                    categoryId = category.id,
                    indexedOnly = false,
                ),
            )
        }
        out += if (page == FeedPage.HOME) {
            interleave(tail.filter { it.filter.kind == TitleKind.MOVIE }, tail.filter { it.filter.kind == TitleKind.SERIES }, every = 1)
        } else tail

        return out.distinctBy { it.id }
    }

    /** "More like this": the same kind, sharing its strongest genres, most popular first. */
    fun moreLikeThis(title: WatchedTitle, limitGenres: Int = 2): CatalogFilter? {
        val genres = title.genres.filterNot { it == Genres.DRAMA && title.genres.size > 1 }.take(limitGenres)
            .ifEmpty { title.genres.take(1) }
        if (genres.isEmpty()) return null
        return CatalogFilter(
            kind = title.kind,
            genresAll = genres,
            minVotes = 15_000,
            excludeIds = listOf(title.id),
            sort = CatalogSort.SHUFFLE,
        )
    }

    private fun becauseYouWatched(title: WatchedTitle): RowSpec.Catalog? {
        val filter = moreLikeThis(title) ?: return null
        return RowSpec.Catalog(
            id = "because_${title.kind.name}_${title.id}",
            title = "Because You Watched ${title.name}",
            filter = filter,
        )
    }

    private fun top10(kind: TitleKind, year: Int) = RowSpec.Catalog(
        id = "top10_${kind.name}",
        title = if (kind == TitleKind.MOVIE) "Top 10 Movies" else "Top 10 TV Shows",
        filter = CatalogFilter(
            kind = kind,
            yearFrom = year - if (kind == TitleKind.MOVIE) 2 else 3,
            sort = CatalogSort.POPULAR,
        ),
        style = RowStyle.TOP10,
    )

    private fun recentlyAdded(kind: TitleKind) = RowSpec.Catalog(
        id = "added_${kind.name}",
        title = if (kind == TitleKind.MOVIE) "Recently Added Movies" else "New Episodes & Series",
        filter = CatalogFilter(kind = kind, sort = CatalogSort.RECENTLY_ADDED, indexedOnly = false),
    )

    private fun m(id: String, title: String, filter: CatalogFilter.() -> CatalogFilter) =
        RowSpec.Catalog("m_$id", title, CatalogFilter(TitleKind.MOVIE).filter())

    private fun s(id: String, title: String, filter: CatalogFilter.() -> CatalogFilter) =
        RowSpec.Catalog("s_$id", title, CatalogFilter(TitleKind.SERIES).filter())

    fun movieRows(year: Int): List<RowSpec.Catalog> = with(Genres) {
        listOf(
            m("new", "New Releases") { copy(yearFrom = year - 1) },
            m("blockbuster", "Blockbuster Action") { copy(genresAll = listOf(ACTION), minVotes = 150_000) },
            m("acclaimed", "Critically Acclaimed Films") { copy(minRating = 8.0f, minVotes = 80_000, sort = CatalogSort.SHUFFLE) },
            m("crime_thriller", "Crime Thrillers") { copy(genresAll = listOf(CRIME, THRILLER)) },
            m("comedy", "Laugh-Out-Loud Comedies") { copy(genresAll = listOf(COMEDY), genresNone = listOf(DRAMA, ROMANCE), minRating = 6.3f) },
            m("scifi", "Sci-Fi & Fantasy") { copy(genresAny = listOf(SCIFI, FANTASY)) },
            m("horror", "Heart-Pounding Horror") { copy(genresAll = listOf(HORROR)) },
            m("romcom", "Romantic Comedies") { copy(genresAll = listOf(COMEDY, ROMANCE)) },
            m("family", "Family Movie Night") { copy(genresAll = listOf(FAMILY)) },
            m("gems", "Hidden Gems") { copy(minRating = 7.3f, minVotes = 4_000, maxVotes = 60_000, sort = CatalogSort.SHUFFLE) },
            m("mystery", "Mind-Bending Mysteries") { copy(genresAll = listOf(MYSTERY), minRating = 6.8f) },
            m("true", "Based on a True Story") { copy(genresAny = listOf(BIOGRAPHY, HISTORY), genresNone = listOf(DOCUMENTARY)) },
            m("adventure", "Epic Adventures") { copy(genresAll = listOf(ADVENTURE), minVotes = 120_000) },
            m("drama", "Award-Worthy Dramas") { copy(genresAll = listOf(DRAMA), minRating = 7.8f, sort = CatalogSort.SHUFFLE) },
            m("animation", "Animated Adventures") { copy(genresAll = listOf(ANIMATION)) },
            m("90s", "'90s Favorites") { copy(yearFrom = 1990, yearTo = 1999) },
            m("docs", "Documentaries") { copy(genresAll = listOf(DOCUMENTARY)) },
            m("gritty", "Dark & Gritty Crime") { copy(genresAll = listOf(CRIME, DRAMA), minRating = 7.4f, sort = CatalogSort.SHUFFLE) },
            m("80s", "'80s Classics") { copy(yearFrom = 1980, yearTo = 1989) },
            m("thriller", "Edge-of-Your-Seat Thrillers") { copy(genresAll = listOf(THRILLER), genresNone = listOf(HORROR)) },
            m("feelgood", "Feel-Good Movies") { copy(genresAll = listOf(COMEDY), genresAny = listOf(FAMILY, ROMANCE, MUSIC)) },
            m("war", "War Epics") { copy(genresAll = listOf(WAR)) },
            m("2000s", "2000s Hits") { copy(yearFrom = 2000, yearTo = 2009) },
            m("sport", "Sports Movies") { copy(genresAll = listOf(SPORT)) },
            m("action90", "'90s Action") { copy(genresAll = listOf(ACTION), yearFrom = 1990, yearTo = 1999) },
            m("music", "Music & Musicals") { copy(genresAny = listOf(MUSIC, MUSICAL), genresNone = listOf(DOCUMENTARY)) },
            m("2010s", "2010s Hits") { copy(yearFrom = 2010, yearTo = 2019) },
            m("classic", "Classic Cinema") { copy(yearTo = 1969, minRating = 7.5f) },
            m("western", "Westerns") { copy(genresAll = listOf(WESTERN)) },
            m("cult", "Cult Classics") { copy(yearTo = 1999, minRating = 7.2f, minVotes = 30_000, maxVotes = 250_000, sort = CatalogSort.SHUFFLE) },
            m("scifi_action", "Sci-Fi Action") { copy(genresAll = listOf(SCIFI, ACTION)) },
            m("comedy80", "'80s Comedies") { copy(genresAll = listOf(COMEDY), yearFrom = 1980, yearTo = 1989) },
            m("horror_new", "Modern Horror") { copy(genresAll = listOf(HORROR), yearFrom = year - 8) },
            m("heist", "Crime Capers") { copy(genresAll = listOf(CRIME, COMEDY)) },
            m("romance", "Romantic Dramas") { copy(genresAll = listOf(ROMANCE, DRAMA)) },
            m("toprated", "All-Time Top Rated") { copy(minVotes = 150_000, sort = CatalogSort.TOP_RATED) },
            m("history", "History & War Dramas") { copy(genresAny = listOf(HISTORY, WAR), genresAll = listOf(DRAMA)) },
            m("kids_anim", "Animated Family Favorites") { copy(genresAll = listOf(ANIMATION, FAMILY)) },
            m("thriller2000", "2000s Thrillers") { copy(genresAll = listOf(THRILLER), yearFrom = 2000, yearTo = 2009) },
            m("fantasy", "Fantasy Worlds") { copy(genresAll = listOf(FANTASY, ADVENTURE)) },
        )
    }

    fun showRows(year: Int): List<RowSpec.Catalog> = with(Genres) {
        listOf(
            s("binge", "Binge-Worthy TV Shows") { copy(minRating = 8.2f, minVotes = 60_000, sort = CatalogSort.SHUFFLE) },
            s("crime", "Crime TV Shows") { copy(genresAll = listOf(CRIME)) },
            s("comedy", "TV Comedies") { copy(genresAll = listOf(COMEDY), genresNone = listOf(ANIMATION)) },
            s("scifi", "Sci-Fi & Fantasy Series") { copy(genresAny = listOf(SCIFI, FANTASY)) },
            s("drama", "TV Dramas") { copy(genresAll = listOf(DRAMA), genresNone = listOf(CRIME, COMEDY)) },
            s("new", "New on TV") { copy(yearFrom = year - 2) },
            s("docs", "Docuseries") { copy(genresAll = listOf(DOCUMENTARY)) },
            s("mystery", "Mystery & Suspense Series") { copy(genresAny = listOf(MYSTERY, THRILLER)) },
            s("reality", "Reality TV") { copy(genresAll = listOf(REALITY)) },
            s("animated", "Animated Series") { copy(genresAll = listOf(ANIMATION)) },
            s("action", "Action & Adventure Series") { copy(genresAll = listOf(ACTION)) },
            s("gems", "Hidden Gem Series") { copy(minRating = 7.8f, minVotes = 2_000, maxVotes = 30_000, sort = CatalogSort.SHUFFLE) },
            s("classic", "Classic TV") { copy(yearTo = 1999) },
            s("family", "Kids & Family TV") { copy(genresAll = listOf(FAMILY)) },
            s("2000s", "2000s TV") { copy(yearFrom = 2000, yearTo = 2009) },
            s("crime_drama", "Gripping Crime Dramas") { copy(genresAll = listOf(CRIME, DRAMA), minRating = 8.0f) },
            s("sitcom", "Sitcoms") { copy(genresAll = listOf(COMEDY), genresNone = listOf(DRAMA, ANIMATION, ACTION)) },
            s("2010s", "2010s TV") { copy(yearFrom = 2010, yearTo = 2019) },
            s("toprated", "Top Rated Series") { copy(minVotes = 100_000, sort = CatalogSort.TOP_RATED) },
            s("history", "History Series") { copy(genresAny = listOf(HISTORY, WAR)) },
            s("horror", "Horror Series") { copy(genresAll = listOf(HORROR)) },
            s("romance", "Romantic TV") { copy(genresAll = listOf(ROMANCE)) },
        )
    }

    /** Inserts one of [extra] after every [every] rows of [base], then appends what is left. */
    private fun <T> interleave(base: List<T>, extra: List<T>, every: Int = 1): List<T> {
        if (extra.isEmpty()) return base
        val out = ArrayList<T>(base.size + extra.size)
        val it = extra.iterator()
        base.forEachIndexed { i, row ->
            out += row
            if ((i + 1) % every == 0 && it.hasNext()) out += it.next()
        }
        while (it.hasNext()) out += it.next()
        return out
    }
}

/** Turns a provider's category label into a row heading. */
object CategoryTitles {
    private val PREFIX = Regex("""^\s*(?:[A-Z]{2,3}|[A-Z]{2,3}\s*[-|:]\s*[A-Z]{2,3})\s*[-|:]\s*""")
    private val DECORATION = Regex("""[|★☆•◆◇▶►■□▪▫]+""")

    fun clean(name: String): String {
        var text = name.replace(PREFIX, "").replace(DECORATION, " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) text = name.trim()
        // SHOUTING CATEGORY NAMES become title case; mixed case is left as the provider wrote it.
        return if (text.any { it.isLowerCase() }) text
        else text.lowercase().split(' ').joinToString(" ") { word ->
            if (word.length <= 3 && word in SMALL_UPPER) word.uppercase()
            else word.replaceFirstChar { it.titlecase() }
        }
    }

    private val SMALL_UPPER = setOf("tv", "hd", "uhd", "4k", "usa", "uk", "vod", "hbo", "amc", "bbc", "fx")
}

/** Category names that must never surface in a browsing feed. */
object AdultContent {
    private val MARKERS = listOf("XXX", "ADULT", "18+", "PORN", "EROTIC", "PLAYBOY", "HUSTLER", "BRAZZERS")

    fun isAdult(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val upper = label.uppercase()
        return MARKERS.any { upper.contains(it) }
    }
}
