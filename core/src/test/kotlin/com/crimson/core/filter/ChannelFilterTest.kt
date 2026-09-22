package com.crimson.core.filter

import com.crimson.core.model.Country
import com.crimson.core.model.FilterOutcome
import com.crimson.core.model.FilterRules
import com.crimson.core.model.Market
import com.crimson.core.model.RawCategory
import com.crimson.core.model.RawChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFilterTest {

    private val filter = ChannelFilter()
    private var nextId = 1L

    private fun channel(name: String, categoryName: String? = null): FilterOutcomeWithDetail {
        val category = categoryName?.let { RawCategory("c", it) }
        val verdict = category?.let { filter.classifyCategory(it) }
        val raw = RawChannel(nextId++, name, category?.categoryId)
        val decision = filter.decide(raw, verdict)
        return FilterOutcomeWithDetail(decision.outcome, decision.country, decision.market, decision.evidence)
    }

    data class FilterOutcomeWithDetail(
        val outcome: FilterOutcome,
        val country: Country?,
        val market: Market?,
        val evidence: List<String>,
    )

    private fun assertKept(name: String, country: Country, categoryName: String? = null) {
        val r = channel(name, categoryName)
        assertTrue("expected \"$name\" to be kept, got ${r.outcome} ${r.evidence}", r.outcome.isKept)
        assertEquals("wrong country for \"$name\"", country, r.country)
    }

    private fun assertDropped(name: String, categoryName: String? = null, because: FilterOutcome? = null) {
        val r = channel(name, categoryName)
        assertFalse("expected \"$name\" to be dropped, got ${r.outcome} ${r.evidence}", r.outcome.isKept)
        if (because != null) assertEquals("wrong drop reason for \"$name\"", because, r.outcome)
    }

    // ------------------------------------------------------------------ prefix styles

    @Test
    fun `every documented prefix style is recognised as US`() {
        val styles = listOf(
            "US| ESPN",
            "|US| ESPN",
            "US: ESPN",
            "US - ESPN",
            "US -ESPN",
            "USA ESPN",
            "[US] ESPN",
            "US ▎ESPN",
            "US VIP ESPN",
            "US 4K ESPN",
            "US FHD ESPN",
            "US.ESPN",
            "US•ESPN",
            "US ● ESPN",
            "(US) ESPN",
            "US  ESPN  HD",
            "UNITED STATES ESPN",
        )
        for (s in styles) assertKept(s, Country.US)
    }

    @Test
    fun `uk jp and kr tokens are recognised`() {
        for (s in listOf("UK| BBC ONE", "|GB| BBC ONE", "GBR: BBC ONE", "BRITAIN BBC ONE",
            "ENGLAND BBC ONE", "UNITED KINGDOM BBC ONE")) {
            assertKept(s, Country.UK)
        }
        for (s in listOf("JP| NHK G", "JPN: NHK G", "JAPAN NHK G", "[JP] NHK G")) {
            assertKept(s, Country.JP)
        }
        for (s in listOf("KR| KBS 1", "KOR: KBS 1", "KOREA KBS 1", "SOUTH KOREA KBS 1", "SK| KBS 1")) {
            assertKept(s, Country.KR)
        }
    }

    @Test
    fun `regional and foreign category prefixes are dropped`() {
        assertDropped("GENERAL TV", categoryName = "AFR| GENERAL")
        assertDropped("YUPP EXCLUSIVE", categoryName = "ASIA | YUPP TV EXCLUSIVE")
        assertDropped("CARIBBEAN SPORTS", categoryName = "CRB| SPORTS")
        assertDropped("LATINO NOVELA", categoryName = "LAT| GENERAL")
    }

    @Test
    fun `country token in the category carries to the channel`() {
        assertKept("ESPN", Country.US, categoryName = "US | SPORTS")
        assertKept("BBC ONE", Country.UK, categoryName = "UK ENTERTAINMENT")
        assertKept("NHK G", Country.JP, categoryName = "JAPAN | GENERAL")
    }

    // ------------------------------------------------------------------ whole-token matching

    @Test
    fun `LA does not match inside LATINO`() {
        val r = channel("US| LATINO MUSIC")
        assertTrue(r.outcome.isKept)
        assertEquals(FilterOutcome.KEPT_NATIONAL, r.outcome)
        assertNull("LATINO must not be read as Los Angeles", r.market)
    }

    @Test
    fun `NY does not match inside SONY`() {
        val r = channel("US| SONY MOVIES")
        assertEquals(FilterOutcome.KEPT_NATIONAL, r.outcome)
        assertNull("SONY must not be read as New York", r.market)
    }

    @Test
    fun `US does not match inside other words`() {
        // "AUSTRALIA" contains "us"; "PLUS" ends in "us". Neither is the United States.
        // Australia is a recognised country, just not an allowed one, so the reasons differ.
        assertDropped("AUSTRALIA NEWS", because = FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED)
        assertDropped("CANAL PLUS PREMIERE", because = FilterOutcome.DROPPED_COUNTRY_UNKNOWN)
    }

    @Test
    fun `LA LIGA is never Los Angeles`() {
        val r = channel("US| LA LIGA TV", "US | SPORTS")
        assertEquals(FilterOutcome.KEPT_NATIONAL, r.outcome)
        assertNull(r.market)
    }

    @Test
    fun `AMERICA tokens that are not the United States are dropped`() {
        assertDropped("LATIN AMERICA TV", because = FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED)
        assertDropped("SOUTH AMERICA SPORTS", because = FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED)
    }

    @Test
    fun `north korea is not south korea`() {
        assertDropped("NORTH KOREA KCTV", because = FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED)
    }

    @Test
    fun `short country codes only count when punctuation closes them`() {
        // "IN|" is India. "IN THE MIX" is a sentence, and must not be read as a country at all.
        val india = filter.classifyCategory(RawCategory("1", "IN| BOLLYWOOD"))
        assertEquals("IN", india.foreignMarker)
        assertFalse(india.mayContainKeptChannels(FilterRules.DEFAULT))

        val mix = filter.classifyCategory(RawCategory("2", "IN THE MIX"))
        assertNull(mix.foreignMarker)
        assertTrue("a category with no country marker must still be fetched",
            mix.mayContainKeptChannels(FilterRules.DEFAULT))

        for (sentence in listOf("IT IS WHAT IT IS", "NO LIMITS TV", "AT HOME NETWORK",
            "IS IT REAL", "DO IT YOURSELF")) {
            val v = filter.classifyCategory(RawCategory("x", sentence))
            assertNull("\"$sentence\" misread as a country marker", v.foreignMarker)
        }
    }

    // ------------------------------------------------------------------ national channels

    @Test
    fun `national US channels are kept regardless of city`() {
        val nationals = listOf(
            "US| ESPN", "US| CNN", "US| HBO", "US| DISCOVERY", "US| FOX NEWS",
            "US| ABC NEWS LIVE", "US| CBS SPORTS NETWORK", "US| NBC SPORTS",
            "US| USA NETWORK", "US| CARTOON NETWORK", "US| FOOD NETWORK",
            "US| TNT", "US| AMC", "US| FX", "US| BRAVO", "US| PARAMOUNT NETWORK",
        )
        for (n in nationals) {
            val r = channel(n)
            assertEquals("$n should be kept as national, evidence=${r.evidence}",
                FilterOutcome.KEPT_NATIONAL, r.outcome)
        }
    }

    @Test
    fun `bare network names do not make a channel local`() {
        // A network name with no city and no call sign is a national feed.
        for (n in listOf("US| FOX NEWS", "US| ABC NEWS", "US| CBS NEWS", "US| NBC NEWS NOW",
            "US| TELEMUNDO", "US| UNIVISION")) {
            val r = channel(n)
            assertEquals("$n misread as local: ${r.evidence}", FilterOutcome.KEPT_NATIONAL, r.outcome)
        }
    }

    // ------------------------------------------------------------------ allowed markets

    @Test
    fun `allowed market locals are kept with the right market`() {
        val cases = listOf(
            "US| KTLA 5 LOS ANGELES" to Market.LOS_ANGELES,
            "US| KABC ABC 7 LOS ANGELES" to Market.LOS_ANGELES,
            "US LOCALS| KCBS CBS 2" to Market.LOS_ANGELES,
            "US| WABC ABC 7 NEW YORK" to Market.NEW_YORK,
            "US| WNBC NBC 4 NEW YORK" to Market.NEW_YORK,
            "US| WPIX 11 NEW YORK" to Market.NEW_YORK,
            "US| WSVN FOX 7 MIAMI" to Market.MIAMI,
            "US| WPLG ABC 10 MIAMI" to Market.MIAMI,
            "US| WTVJ NBC 6 FORT LAUDERDALE" to Market.MIAMI,
            "US| WFLA NBC 8 TAMPA" to Market.TAMPA,
            "US| WTVT FOX 13 TAMPA BAY" to Market.TAMPA,
            "US| WTSP CBS 10 ST. PETERSBURG" to Market.TAMPA,
        )
        for ((name, market) in cases) {
            val r = channel(name, "US | LOCALS")
            assertEquals("$name should be kept: ${r.evidence}", FilterOutcome.KEPT_LOCAL, r.outcome)
            assertEquals("$name wrong market: ${r.evidence}", market, r.market)
        }
    }

    @Test
    fun `st petersburg period form matches tampa`() {
        val r = channel("US| ST. PETERSBURG NEWS", "US | LOCALS")
        assertEquals(FilterOutcome.KEPT_LOCAL, r.outcome)
        assertEquals(Market.TAMPA, r.market)
    }

    @Test
    fun `ambiguous LA alias counts when the category says locals`() {
        val r = channel("US| KCOP 13 LA", "US | LOCAL CHANNELS")
        assertEquals(FilterOutcome.KEPT_LOCAL, r.outcome)
        assertEquals(Market.LOS_ANGELES, r.market)
    }

    // ------------------------------------------------------------------ disallowed markets

    @Test
    fun `locals from other markets are dropped`() {
        val others = listOf(
            "US| WGN 9 CHICAGO", "US| WLS ABC 7 CHICAGO", "US| KPRC NBC 2 HOUSTON",
            "US| WFAA ABC 8 DALLAS", "US| KOMO ABC 4 SEATTLE", "US| WCVB ABC 5 BOSTON",
            "US| KING 5 SEATTLE", "US| WXYZ ABC 7 DETROIT", "US| KTVU FOX 2 SAN FRANCISCO",
            "US| WSB ABC 2 ATLANTA", "US| KDKA CBS 2 PITTSBURGH", "US| WHDH 7 BOSTON",
        )
        for (n in others) {
            val r = channel(n, "US | LOCALS")
            assertEquals("$n should be dropped: ${r.evidence}",
                FilterOutcome.DROPPED_MARKET_NOT_ALLOWED, r.outcome)
        }
    }

    @Test
    fun `an unidentifiable local is dropped as unknown market`() {
        val r = channel("US| WQQQ CBS 12", "US | LOCALS")
        assertEquals(FilterOutcome.DROPPED_MARKET_UNKNOWN, r.outcome)
        assertEquals(Market.UNKNOWN, r.market)
    }

    @Test
    fun `call sign lookalikes that are ordinary words are not treated as locals`() {
        // Each of these would match the [KW]xxx call-sign pattern.
        val safe = listOf("US| KIDS TV", "US| WEST COAST SPORTS", "US| WILD EARTH",
            "US| WAVE MUSIC", "US| WORK LIFE NETWORK", "US| KIND TV")
        for (n in safe) {
            val r = channel(n)
            assertEquals("$n misread as a call sign: ${r.evidence}",
                FilterOutcome.KEPT_NATIONAL, r.outcome)
        }
    }

    // ------------------------------------------------------------------ unknown country

    @Test
    fun `channels with no determinable country are dropped`() {
        val unknown = listOf(
            "RTL TELEVISION", "CANAL 5", "ZDF NEO", "TF1", "RAI UNO", "TVE 1",
            "STAR PLUS", "SBT", "GLOBO", "ANTENA 3",
        )
        for (n in unknown) {
            assertDropped(n, because = FilterOutcome.DROPPED_COUNTRY_UNKNOWN)
        }
    }

    @Test
    fun `channels from countries outside the allowlist are dropped`() {
        val r = channel("DE| SKY SPORT", "DE | SPORTS")
        assertFalse(r.outcome.isKept)
    }

    // ------------------------------------------------------------------ rule changes

    @Test
    fun `removing a country drops its channels`() {
        val noJapan = ChannelFilter(FilterRules(countries = setOf(Country.US, Country.UK, Country.KR)))
        val d = noJapan.decide(RawChannel(1, "JP| NHK G", null), null)
        assertEquals(FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED, d.outcome)
    }

    @Test
    fun `removing a market drops that market's locals but keeps the others`() {
        val noMiami = ChannelFilter(
            FilterRules(markets = setOf(Market.LOS_ANGELES, Market.NEW_YORK, Market.TAMPA)),
        )
        val cat = noMiami.classifyCategory(RawCategory("c", "US | LOCALS"))
        assertEquals(
            FilterOutcome.DROPPED_MARKET_NOT_ALLOWED,
            noMiami.decide(RawChannel(1, "US| WSVN FOX 7 MIAMI", "c"), cat).outcome,
        )
        assertEquals(
            FilterOutcome.KEPT_LOCAL,
            noMiami.decide(RawChannel(2, "US| WABC ABC 7 NEW YORK", "c"), cat).outcome,
        )
    }

    @Test
    fun `exclusion keywords remove matching channels`() {
        val f = ChannelFilter(FilterRules(excludeKeywords = listOf("ppv", "adult")))
        assertEquals(
            FilterOutcome.DROPPED_EXCLUDED_KEYWORD,
            f.decide(RawChannel(1, "US| PPV EVENT 1", null), null).outcome,
        )
        assertEquals(
            FilterOutcome.KEPT_NATIONAL,
            f.decide(RawChannel(2, "US| ESPN", null), null).outcome,
        )
    }

    @Test
    fun `exclusion keywords match whole tokens only`() {
        val f = ChannelFilter(FilterRules(excludeKeywords = listOf("art")))
        // "ART" must not knock out "SMART" or "EARTH".
        assertEquals(
            FilterOutcome.KEPT_NATIONAL,
            f.decide(RawChannel(1, "US| SMART TV", null), null).outcome,
        )
        assertEquals(
            FilterOutcome.DROPPED_EXCLUDED_KEYWORD,
            f.decide(RawChannel(2, "US| ART CHANNEL", null), null).outcome,
        )
    }

    // ------------------------------------------------------------------ category short-circuit

    @Test
    fun `categories for disallowed countries are never fetched`() {
        val rules = FilterRules.DEFAULT
        val germany = filter.classifyCategory(RawCategory("1", "DE | SPORT"))
        assertFalse(germany.mayContainKeptChannels(rules))

        val us = filter.classifyCategory(RawCategory("2", "US | SPORT"))
        assertTrue(us.mayContainKeptChannels(rules))

        // An unknown country must still be fetched: the channel names may identify it.
        val mystery = filter.classifyCategory(RawCategory("3", "24/7 CHANNELS"))
        assertTrue(mystery.mayContainKeptChannels(rules))
    }

    @Test
    fun `a locals category for a disallowed market is never fetched`() {
        val chicago = filter.classifyCategory(RawCategory("4", "US | CHICAGO LOCALS"))
        assertTrue(chicago.indicatesLocals)
        assertEquals(Market.OTHER, chicago.market)
        assertFalse(chicago.mayContainKeptChannels(FilterRules.DEFAULT))
    }

    @Test
    fun `a locals category for an allowed market is fetched`() {
        val ny = filter.classifyCategory(RawCategory("5", "US | NEW YORK LOCALS"))
        assertTrue(ny.indicatesLocals)
        assertEquals(Market.NEW_YORK, ny.market)
        assertTrue(ny.mayContainKeptChannels(FilterRules.DEFAULT))
    }

    // ------------------------------------------------------------------ streaming services

    @Test
    fun `streaming service categories are never fetched`() {
        val netflix = filter.classifyCategory(RawCategory("10", "US | NETFLIX"))
        assertFalse(netflix.mayContainKeptChannels(FilterRules.DEFAULT))

        val disneyPlus = filter.classifyCategory(RawCategory("11", "US | DISNEY+"))
        assertFalse(disneyPlus.mayContainKeptChannels(FilterRules.DEFAULT))

        val paramountPlus = filter.classifyCategory(RawCategory("12", "US | PARAMOUNT+"))
        assertFalse(paramountPlus.mayContainKeptChannels(FilterRules.DEFAULT))

        val hboMax = filter.classifyCategory(RawCategory("13", "US | HBO MAX"))
        assertFalse(hboMax.mayContainKeptChannels(FilterRules.DEFAULT))
    }

    @Test
    fun `streaming service channels are dropped while linear cable channels are kept`() {
        // Streaming channels dropped
        assertDropped("US| NETFLIX: Stranger Things")
        assertDropped("US| DISNEY+: The Mandalorian")
        assertDropped("US| PARAMOUNT+: Tulsa King")
        assertDropped("US| HBO MAX: House of the Dragon")
        assertDropped("US| PEACOCK: The Office")

        // Real cable channels KEPT
        assertKept("US| DISNEY CHANNEL EAST HD", Country.US)
        assertKept("US| DISNEY JUNIOR", Country.US)
        assertKept("US| DISNEY XD HD", Country.US)
        assertKept("US| PARAMOUNT NETWORK HD", Country.US)
        assertKept("US| HBO EAST HD", Country.US)
        assertKept("US| HBO 2 HD", Country.US)
        assertKept("US| HBO SIGNATURE", Country.US)
        assertKept("US| ESPN HD", Country.US)
        assertKept("US| ESPN 2", Country.US)
        assertKept("US| ESPNU", Country.US)
    }
}
