package com.crimson.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {

    @Test
    fun `title index lines parse and skip comments`() {
        val text = "# header\nInception\t2010\tM\t8.8\t2870755\tAdventure,Sci-Fi,Thriller\nbad line\n"
        val titles = ArrayList<IndexedTitle>()
        val count = TitleIndex.read(text.reader()) { titles += it }
        assertEquals(1, count)
        val t = titles.single()
        assertEquals(TitleKind.MOVIE, t.kind)
        assertEquals(2010, t.year)
        assertEquals(listOf("Adventure", "Sci-Fi", "Thriller"), t.genres)
        assertEquals(",Adventure,Sci-Fi,Thriller,", t.genreColumn)
    }

    @Test
    fun `genre filter builds exact comma patterns`() {
        val q = CatalogSql.build(
            CatalogFilter(TitleKind.MOVIE, genresAll = listOf("Crime", "Drama"), minRating = 7.5f),
            limit = 40,
        )
        assertTrue(q.sql.startsWith("SELECT * FROM vod WHERE"))
        assertTrue(q.sql.contains("isForeign = 0"))
        assertTrue(q.sql.contains("isAdult = 0"))
        assertTrue(q.sql.contains("imdbVotes IS NOT NULL"))
        assertEquals(listOf("%,Crime,%", "%,Drama,%", 7.5, 40), q.args)
        // One placeholder per argument.
        assertEquals(q.args.size, q.sql.count { it == '?' })
    }

    @Test
    fun `series queries use the series table and id column`() {
        val q = CatalogSql.build(
            CatalogFilter(TitleKind.SERIES, excludeIds = listOf(4L, 5L), sort = CatalogSort.SHUFFLE),
            limit = 10,
            seed = 7,
        )
        assertTrue(q.sql.contains("FROM series"))
        assertTrue(q.sql.contains("seriesId NOT IN (?,?)"))
        assertTrue(q.sql.contains("seriesId * 7"))
        assertEquals(q.args.size, q.sql.count { it == '?' })
    }

    @Test
    fun `category rows do not require an index entry`() {
        val q = CatalogSql.build(CatalogFilter(TitleKind.MOVIE, categoryId = "12", indexedOnly = false), 30)
        assertFalse(q.sql.contains("imdbVotes IS NOT NULL"))
        assertTrue(q.sql.contains("categoryId = ?"))
    }

    @Test
    fun `home feed puts personal rows first and ends in provider categories`() {
        val plan = FeedPlanner.plan(
            FeedPage.HOME,
            currentYear = 2026,
            history = listOf(WatchedTitle(9, "Heat", TitleKind.MOVIE, listOf("Action", "Crime", "Drama"))),
            categories = listOf(
                ProviderCategory("1", "EN - Action", TitleKind.MOVIE),
                ProviderCategory("2", "Crime Series", TitleKind.SERIES),
            ),
        )
        assertEquals("continue", plan.first().id)
        assertTrue(plan.any { it.title == "Because You Watched Heat" })
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
        val last = plan.takeLast(2).map { it.id }
        assertEquals(listOf("cat_MOVIE_1", "cat_SERIES_2"), last)
        val because = plan.first { it.title == "Because You Watched Heat" } as RowSpec.Catalog
        assertEquals(listOf("Action", "Crime"), because.filter.genresAll)
        assertEquals(listOf(9L), because.filter.excludeIds)
    }

    @Test
    fun `movies page only holds movie rows`() {
        val plan = FeedPlanner.plan(FeedPage.MOVIES, 2026, categories = listOf(ProviderCategory("2", "Crime Series", TitleKind.SERIES)))
        assertTrue(plan.filterIsInstance<RowSpec.Catalog>().all { it.filter.kind == TitleKind.MOVIE })
        assertTrue(plan.size > 30)
    }

    @Test
    fun `more like this drops drama when there is something more specific`() {
        val filter = FeedPlanner.moreLikeThis(WatchedTitle(1, "X", TitleKind.SERIES, listOf("Drama", "Mystery")))
        assertNotNull(filter)
        assertEquals(listOf("Mystery"), filter!!.genresAll)
        assertNull(FeedPlanner.moreLikeThis(WatchedTitle(1, "X", TitleKind.SERIES, emptyList())))
    }

    @Test
    fun `category titles lose their prefixes and shouting`() {
        assertEquals("Action", CategoryTitles.clean("EN - ACTION"))
        assertEquals("Netflix Movies", CategoryTitles.clean("EN | Netflix Movies"))
        assertEquals("Classic TV Shows", CategoryTitles.clean("CLASSIC TV SHOWS"))
    }

    @Test
    fun `adult categories are recognised`() {
        assertTrue(AdultContent.isAdult("XXX | Adults"))
        assertTrue(AdultContent.isAdult("For Adults 18+"))
        assertFalse(AdultContent.isAdult("Action"))
    }

    @Test
    fun `search ranking puts the exact channel first`() {
        val names = listOf("MSNBC", "NBC SPORTS BAY AREA", "CNBC", "NBC", "US: NBC NEWS NOW", "Weather")
        val ranked = SearchRank.rank(names, "nbc", { it })
        assertEquals("NBC", ranked.first())
        assertEquals("NBC SPORTS BAY AREA", ranked[1])
        assertFalse("Weather" in ranked)
        assertTrue(ranked.indexOf("US: NBC NEWS NOW") < ranked.indexOf("CNBC"))
    }

    @Test
    fun `espn plus is not espn`() {
        assertTrue(SearchRank.score("ESPN+ 01", "ESPN+") > SearchRank.score("ESPN 2", "ESPN+"))
    }
}
