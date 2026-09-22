package com.crimson.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class NameCleanerTest {

    @Test
    fun `country prefixes are stripped`() {
        assertEquals("ESPN", NameCleaner.clean("US| ESPN"))
        assertEquals("ESPN", NameCleaner.clean("|US| ESPN"))
        assertEquals("ESPN", NameCleaner.clean("US: ESPN"))
        assertEquals("ESPN", NameCleaner.clean("USA - ESPN"))
        assertEquals("BBC One", NameCleaner.clean("UK| BBC One"))
        assertEquals("NHK G", NameCleaner.clean("JP ▎NHK G"))
        assertEquals("KBS 1", NameCleaner.clean("KR: KBS 1"))
    }

    @Test
    fun `quality tags are stripped wherever they appear`() {
        assertEquals("ESPN 2", NameCleaner.clean("US| ESPN 2 ᴴᴰ"))
        assertEquals("ESPN 2", NameCleaner.clean("US| ESPN 2 FHD"))
        assertEquals("ESPN 2", NameCleaner.clean("US - ESPN 2 [4K]"))
        assertEquals("ESPN 2", NameCleaner.clean("US VIP ESPN 2 HEVC"))
        assertEquals("Discovery", NameCleaner.clean("US| Discovery UHD"))
        assertEquals("AMC", NameCleaner.clean("US| AMC HD 1080p"))
    }

    @Test
    fun `a country word that is part of the real name survives`() {
        // The prefix stripper stops at the first token that is not a tag, so a trailing
        // "JAPAN" belongs to the channel and is kept.
        assertEquals("TV JAPAN", NameCleaner.clean("US| TV JAPAN"))
        assertEquals("America's Test Kitchen", NameCleaner.clean("US| America's Test Kitchen"))
    }

    @Test
    fun `a name made only of tags falls back to the original`() {
        assertEquals("US| HD", NameCleaner.clean("US| HD"))
    }

    @Test
    fun `SKR is recognised as a Korea prefix`() {
        assertEquals("MBC SPORTS TV", NameCleaner.clean("SKR| MBC SPORTS  TV"))
        assertEquals("KBS1 TV", NameCleaner.clean("SKR| KBS1 TV"))
    }

    @Test
    fun `a category word the provider appended to its own name is dropped`() {
        // The real defect this guards: a provider's panel appends the category ("Sports") to
        // the channel name it already carries, which silently changed the matching key and
        // broke EPG lookups for every J Sports channel.
        assertEquals("J Sports 1", NameCleaner.clean("JP| J Sports 1 Sports"))
        assertEquals("NHK News", NameCleaner.clean("JP| NHK News News"))
        // A single occurrence is the channel's real name and must survive untouched.
        assertEquals("Fox Sports", NameCleaner.clean("US| Fox Sports"))
        assertEquals("ESPN News", NameCleaner.clean("US| ESPN News"))
    }

    @Test
    fun `short name drops whole words before cutting letters`() {
        assertEquals("ESPN", NameCleaner.shortName("ESPN"))
        assertEquals("Cartoon", NameCleaner.shortName("Cartoon Network"))
        assertEquals("Discovery", NameCleaner.shortName("Discovery Channel"))
        // A single word longer than the limit is cut.
        assertEquals("Supercalif", NameCleaner.shortName("Supercalifragilistic"))
    }
}
