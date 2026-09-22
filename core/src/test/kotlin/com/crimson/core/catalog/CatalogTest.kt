package com.crimson.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** Names below are real ones from a provider's video-on-demand list. */
class TitleMatcherTest {

    private fun key(s: String) = TitleMatcher.key(s)

    @Test
    fun `a provider's file label reduces to the plain title`() {
        val plain = key("The Dark Knight")
        for (spelling in listOf(
            "EN| The Dark Knight (2008) 4K",
            "The Dark Knight [MULTI]",
            "the dark knight",
            "The.Dark.Knight.2008",
            "4K| The Dark Knight UHD",
        )) {
            assertEquals(spelling, plain.text, key(spelling).text)
        }
    }

    @Test
    fun `a language label before a dash is not part of the title`() {
        val plain = key("The Shawshank Redemption").text
        for (spelling in listOf(
            "EN - The Shawshank Redemption (1994)",
            "AR - The Shawshank Redemption  (1994)",
            "FR-  The Shawshank Redemption",
            "4K - The Shawshank Redemption",
        )) {
            assertEquals(spelling, plain, key(spelling).text)
        }
    }

    @Test
    fun `a dash inside a title is left alone`() {
        assertEquals("SPIDERMAN", key("Spider-Man").text)
        assertEquals("XMEN", key("X-Men").text)
        // The prefix has to be one token, so a second word protects these.
        assertEquals("MADMAXFURYROAD", key("Mad Max: Fury Road").text)
        assertEquals("ALIENCOVENANT", key("Alien - Covenant").text)
    }

    @Test
    fun `a bracketed year is read and removed`() {
        val k = key("Heat (1995) 1080p")
        assertEquals("HEAT", k.text)
        assertEquals(1995, k.year)
    }

    @Test
    fun `a year after a file-name separator is packaging`() {
        val k = key("The.Dark.Knight.2008")
        assertEquals(2008, k.year)
        assertEquals("THEDARKKNIGHT", k.text)
    }

    @Test
    fun `a title that ends in a number keeps it, and is also indexed without it`() {
        // Nothing in the name says whether the number is a year, so both readings exist.
        assertEquals("BLADERUNNER2049", key("Blade Runner 2049").text)
        val variants = TitleMatcher.variants("Blade Runner 2049")
        assertEquals(listOf("BLADERUNNER2049", "BLADERUNNER"), variants.map { it.text })
        assertEquals(2049, variants[1].year)
        // And that second reading cannot be mistaken for the 1982 film, because the years differ.
        assertEquals(
            false,
            TitleMatcher.matches(variants[1], CuratedTitle("Blade Runner", 1982, TitleKind.MOVIE).key),
        )
    }

    @Test
    fun `a sort-order title is put back the right way round`() {
        assertEquals(key("The Matrix").text, key("Matrix, The").text)
        assertEquals(key("An American Werewolf in London").text, key("American Werewolf in London, An").text)
    }

    @Test
    fun `packaging words are dropped but only whole ones`() {
        assertEquals(key("Casino").text, key("Casino REMUX HDR").text)
        // "HD" inside a word is part of the word.
        assertTrue(key("HDTV Nation").text.contains("HDTV"))
    }

    @Test
    fun `a year only refuses a match when both sides disagree`() {
        val listed = CuratedTitle("The Parent Trap", 1961, TitleKind.MOVIE).key
        assertTrue(TitleMatcher.matches(key("The Parent Trap"), listed))
        assertTrue(TitleMatcher.matches(key("The Parent Trap (1962)"), listed))
        assertEquals(false, TitleMatcher.matches(key("The Parent Trap (1998)"), listed))
    }

    @Test
    fun `names with nothing usable produce no key`() {
        assertEquals("", key("4K").text)
        assertEquals("", key("   ").text)
    }
}

class CuratedListsTest {

    private val file = """
        # a comment line
        #top_movies	IMDb Top Rated Movies	The highest rated films
        The Shawshank Redemption	1994	M
        The Godfather	1972	M
        #top_series	IMDb Top Rated Series	The best television
        Breaking Bad	2008	S
    """.trimIndent()

    @Test
    fun `lists and their titles are read`() {
        val lists = CuratedLists.parse(StringReader(file))
        assertEquals(2, lists.size)
        assertEquals("top_movies", lists[0].id)
        assertEquals("IMDb Top Rated Movies", lists[0].title)
        assertEquals("The highest rated films", lists[0].subtitle)
        assertEquals(2, lists[0].titles.size)
        assertEquals(TitleKind.MOVIE, lists[0].kind)
        assertEquals(TitleKind.SERIES, lists[1].kind)
        assertEquals(2008, lists[1].titles[0].year)
    }

    @Test
    fun `the shipped list file parses and is not empty`() {
        val stream = javaClass.classLoader?.getResourceAsStream("curated_lists.txt")
        if (stream == null) return // the asset is only on the app's classpath; skip in :core
        val lists = CuratedLists.parse(stream.reader())
        assertTrue(lists.size > 10)
    }
}

class LibraryIndexTest {

    private data class Film(val id: Int, val name: String)

    private val library = listOf(
        Film(1, "EN| The Shawshank Redemption (1994) 4K"),
        Film(2, "The Godfather"),
        Film(3, "The Parent Trap (1998)"),
        Film(4, "Some Film Nobody Listed"),
    )
    private val index = LibraryIndex(library, nameOf = { it.name })

    @Test
    fun `a curated title finds the viewer's copy whatever it is called`() {
        assertEquals(1, index.find(CuratedTitle("The Shawshank Redemption", 1994, TitleKind.MOVIE))?.id)
        assertEquals(2, index.find(CuratedTitle("The Godfather", 1972, TitleKind.MOVIE))?.id)
    }

    @Test
    fun `a title the viewer does not have finds nothing`() {
        assertNull(index.find(CuratedTitle("Citizen Kane", 1941, TitleKind.MOVIE)))
    }

    @Test
    fun `the wrong year's remake is not offered`() {
        assertNull(index.find(CuratedTitle("The Parent Trap", 1961, TitleKind.MOVIE)))
        assertNotNull(index.find(CuratedTitle("The Parent Trap", 1998, TitleKind.MOVIE)))
    }

    @Test
    fun `an English copy is preferred over a dub`() {
        val copies = listOf(
            Film(1, "PL - PULP FICTION (1994)"),
            Film(2, "AR - Pulp Fiction"),
            Film(3, "EN - Pulp Fiction (1994)"),
        )
        val prefer = LibraryIndex(
            items = copies,
            nameOf = { it.name },
            preferenceOf = { film -> if (film.name.startsWith("EN")) 0 else 2 },
        )
        assertEquals(3, prefer.find(CuratedTitle("Pulp Fiction", 1994, TitleKind.MOVIE))?.id)
    }

    @Test
    fun `a list resolves to only what the viewer has, in the list's order`() {
        val list = CuratedList(
            "x", "Top", "",
            listOf(
                CuratedTitle("Citizen Kane", 1941, TitleKind.MOVIE),
                CuratedTitle("The Godfather", 1972, TitleKind.MOVIE),
                CuratedTitle("The Shawshank Redemption", 1994, TitleKind.MOVIE),
            ),
        )
        assertEquals(listOf(2, 1), index.resolve(list).map { it.id })
    }
}

class ChannelPackageTest {

    private data class Channel(val number: Int, val name: String)

    @Test
    fun `the american lineup is the size a cable guide was`() {
        val pkg = ChannelPackages.AMERICAN_CABLE
        assertTrue("expected about 60-80 channels, got ${pkg.size}", pkg.size in 55..80)
        assertTrue(pkg.sections.any { it.name == "Sports" })
        // No entry may be unmatchable: every one has to reduce to a usable key.
        for (entry in pkg.channels) {
            assertTrue("no key for ${entry.name}", entry.keys.isNotEmpty())
        }
    }

    @Test
    fun `the japanese and korean lineups have valid keys and sections`() {
        for (pkg in listOf(ChannelPackages.JAPANESE_TV, ChannelPackages.KOREAN_TV)) {
            assertTrue("${pkg.name} should have channels", pkg.size in 25..55)
            for (entry in pkg.channels) {
                assertTrue("no key for ${entry.name} in ${pkg.name}", entry.keys.isNotEmpty())
            }
        }
    }

    @Test
    fun `japanese and korean channels resolve to their lineups`() {
        val jpMine = listOf(
            Channel(7001, "JP| NHK GENERAL TV"),
            Channel(7002, "JP| FUJI TELEVISION"),
            Channel(7003, "JP| J SPORTS 1 SPORTS"),
            Channel(7004, "JP| ANIMAX"),
        )
        val jpResolved = PackageResolver(jpMine) { it.name }
            .resolve(ChannelPackages.JAPANESE_TV).toMap().mapKeys { it.key.name }
        assertEquals(7001, jpResolved["NHK General TV"]?.number)
        assertEquals(7002, jpResolved["Fuji Television"]?.number)
        assertEquals(7003, jpResolved["J Sports 1"]?.number)
        assertEquals(7004, jpResolved["Animax"]?.number)

        val krMine = listOf(
            Channel(8001, "SKR| KBS1 TV"),
            Channel(8002, "SKR| MBC"),
            Channel(8003, "SKR| SBS"),
            Channel(8004, "SKR| TVN"),
        )
        val krResolved = PackageResolver(krMine) { it.name }
            .resolve(ChannelPackages.KOREAN_TV).toMap().mapKeys { it.key.name }
        assertEquals(8001, krResolved["KBS1"]?.number)
        assertEquals(8002, krResolved["MBC"]?.number)
        assertEquals(8003, krResolved["SBS"]?.number)
        assertEquals(8004, krResolved["tvN"]?.number)
    }

    @Test
    fun `a provider's spellings resolve to the lineup`() {
        val mine = listOf(
            Channel(1001, "US| ESPN HD"),
            Channel(1002, "US| ESPN 2 FHD"),
            Channel(1003, "US| CNN HD"),
            Channel(1004, "US| SOME PPV SLOT 12"),
            Channel(1005, "US| FS1 HD"),
            Channel(1006, "US| PARAMOUNT NETWORK"),
            Channel(1007, "US| DISNEY XD HD"),
            Channel(1008, "US| CBS SPORTS NETWORK HD"),
            Channel(1009, "US| ESPN U"),
        )
        val resolver = PackageResolver(mine) { it.name }
        val resolved = resolver.resolve(ChannelPackages.AMERICAN_CABLE).toMap()
            .mapKeys { it.key.name }
        assertEquals(1001, resolved["ESPN"]?.number)
        assertEquals(1002, resolved["ESPN2"]?.number)
        assertEquals(1003, resolved["CNN"]?.number)
        // Matched through an alias.
        assertEquals(1005, resolved["Fox Sports 1"]?.number)
        assertEquals(1006, resolved["Paramount Network"]?.number)
        assertEquals(1007, resolved["Disney XD"]?.number)
        assertEquals(1008, resolved["CBS Sports Network"]?.number)
        assertEquals(1009, resolved["ESPNU"]?.number)
        // The PPV slot belongs to no lineup entry.
        assertTrue(resolved.values.none { it.number == 1004 })
    }

    @Test
    fun `one channel is never used for two lineup entries`() {
        // "ESPN" would also be the first candidate for nothing else, but a provider that carries
        // only one ESPN must not have it appear twice in the lineup.
        val mine = listOf(Channel(1, "ESPN"))
        val resolved = PackageResolver(mine) { it.name }.resolve(ChannelPackages.AMERICAN_CABLE)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `the caller chooses between duplicates`() {
        val mine = listOf(Channel(9000, "US| ESPN SD"), Channel(1001, "US| ESPN FHD"))
        val resolved = PackageResolver(mine) { it.name }
            .resolve(ChannelPackages.AMERICAN_CABLE) { _, found -> found.minByOrNull { it.number } }
            .toMap().mapKeys { it.key.name }
        assertEquals(1001, resolved["ESPN"]?.number)
    }
}
