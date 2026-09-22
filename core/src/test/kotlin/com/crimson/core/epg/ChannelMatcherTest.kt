package com.crimson.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMatcherTest {

    @Test
    fun `provider spelling and guide spelling reduce to the same key`() {
        assertEquals("SKYSPORTSMAINEVENT", ChannelMatcher.key("UK| SKY SPORTS MAIN EVENT FHD"))
        assertEquals("SKYSPORTSMAINEVENT", ChannelMatcher.key("Sky Sports Main Event"))
        assertEquals("SKYSPORTSMAINEVENT", ChannelMatcher.key("Sky Sports Main Event HD"))
    }

    @Test
    fun `punctuation spacing and case are ignored`() {
        assertEquals("ESPN2", ChannelMatcher.key("US: ESPN 2"))
        assertEquals("ESPN2", ChannelMatcher.key("espn-2"))
        assertEquals("ESPN2", ChannelMatcher.key("ESPN 2 (Backup)"))
        assertEquals("ESPN2", ChannelMatcher.key("ESPN 2 [HD]"))
    }

    @Test
    fun `a group label before a pipe is not part of the name`() {
        assertEquals("AE", ChannelMatcher.key("TV| A&E ᴿᴬᵂ"))
        assertEquals("NEWS12NEWYORK", ChannelMatcher.key("TUBI| NEWS 12 NEW YORK ᴿᴬᵂ"))
        assertEquals("AE", ChannelMatcher.key("A&E"))
        assertEquals("AE", ChannelMatcher.key("US| A&E HD"))
    }

    @Test
    fun `a country suffix on an xmltv id is dropped`() {
        assertEquals("SKYNEWS", ChannelMatcher.keyOfId("SkyNews.uk"))
        assertEquals("ESPN", ChannelMatcher.keyOfId("ESPN.us"))
        assertEquals("BBCONE", ChannelMatcher.keyOfId("BBCOne.uk"))
        // A dot that is part of the name, not a suffix, stays in place.
        assertEquals("BBCONE", ChannelMatcher.keyOfId("bbc.one.uk"))
    }

    @Test
    fun `plus one channels stay distinct from their parent`() {
        assertEquals("5USA1", ChannelMatcher.key("5 USA +1"))
        assertEquals("5USA", ChannelMatcher.key("5 USA"))
    }

    @Test
    fun `names with nothing usable produce no key`() {
        assertEquals("", ChannelMatcher.key("HD"))
        assertEquals("", ChannelMatcher.key("TV"))
        assertEquals("", ChannelMatcher.key("  "))
    }

    @Test
    fun `an xmltv id declares its country`() {
        assertEquals("US", ChannelMatcher.countryOfId("CNN.us"))
        assertEquals("PT", ChannelMatcher.countryOfId("CNN.pt"))
        // The filter calls it UK; XMLTV files call it either.
        assertEquals("UK", ChannelMatcher.countryOfId("SkyNews.uk"))
        assertEquals("UK", ChannelMatcher.countryOfId("SkyNews.gb"))
    }

    @Test
    fun `a name or a long suffix is not a country`() {
        assertEquals(null, ChannelMatcher.countryOfId("CNN"))
        assertEquals(null, ChannelMatcher.countryOfId("cnn.portugal"))
        assertEquals(null, ChannelMatcher.countryOfId("ESPN.2"))
    }
}
