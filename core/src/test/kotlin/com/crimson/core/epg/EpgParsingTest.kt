package com.crimson.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvTimeTest {

    /** 2026-09-21T20:00:00Z as epoch millis, computed independently of the parser. */
    private val sept21_2000Z = 1_790_020_800_000L

    @Test
    fun `civil day arithmetic matches known epochs`() {
        assertEquals(0L, XmltvTime.daysFromCivil(1970, 1, 1))
        assertEquals(1L, XmltvTime.daysFromCivil(1970, 1, 2))
        assertEquals(-1L, XmltvTime.daysFromCivil(1969, 12, 31))
        // 2000-03-01, just past a leap day in a century that is a leap year.
        assertEquals(11017L, XmltvTime.daysFromCivil(2000, 3, 1))
    }

    @Test
    fun `utc timestamps parse`() {
        assertEquals(sept21_2000Z, XmltvTime.parse("20260921200000 +0000"))
        assertEquals(sept21_2000Z, XmltvTime.parse("20260921200000"))
        assertEquals(sept21_2000Z, XmltvTime.parse("20260921200000 Z"))
    }

    @Test
    fun `offsets shift the result the right way`() {
        // 20:00 at +0200 is 18:00 UTC.
        assertEquals(sept21_2000Z - 2 * 3600_000L, XmltvTime.parse("20260921200000 +0200"))
        // 20:00 at -0500 is 01:00 UTC the next day.
        assertEquals(sept21_2000Z + 5 * 3600_000L, XmltvTime.parse("20260921200000 -0500"))
        // Half-hour and 45-minute zones.
        assertEquals(sept21_2000Z - 330 * 60_000L, XmltvTime.parse("20260921200000 +0530"))
        assertEquals(sept21_2000Z + 270 * 60_000L, XmltvTime.parse("20260921200000 -0430"))
        // Colon form.
        assertEquals(sept21_2000Z - 2 * 3600_000L, XmltvTime.parse("20260921200000 +02:00"))
        // No space before the offset.
        assertEquals(sept21_2000Z - 2 * 3600_000L, XmltvTime.parse("20260921200000+0200"))
    }

    @Test
    fun `the manual offset applies only when the stamp carries none`() {
        val oneHour = 60
        // Provider publishes no offset and is known to be an hour out.
        assertEquals(
            sept21_2000Z - 3600_000L,
            XmltvTime.parse("20260921200000", fallbackOffsetMinutes = oneHour),
        )
        // An explicit offset in the data always wins.
        assertEquals(
            sept21_2000Z,
            XmltvTime.parse("20260921200000 +0000", fallbackOffsetMinutes = oneHour),
        )
    }

    @Test
    fun `truncated stamps are accepted`() {
        assertEquals(sept21_2000Z, XmltvTime.parse("202609212000 +0000"))
        assertEquals(sept21_2000Z, XmltvTime.parse("2026092120 +0000"))
        assertNotNull(XmltvTime.parse("20260921"))
    }

    @Test
    fun `malformed stamps return null rather than throwing`() {
        assertNull(XmltvTime.parse(null))
        assertNull(XmltvTime.parse(""))
        assertNull(XmltvTime.parse("not a time"))
        assertNull(XmltvTime.parse("2026"))
        assertNull(XmltvTime.parse("20261321200000"))  // month 13
        assertNull(XmltvTime.parse("20260921990000"))  // hour 99
    }
}

class ShortEpgTest {

    @Test
    fun `base64 fields decode`() {
        assertEquals("Monday Night Football", ShortEpg.decodeField("TW9uZGF5IE5pZ2h0IEZvb3RiYWxs"))
        assertEquals("News at Ten", ShortEpg.decodeField("TmV3cyBhdCBUZW4="))
    }

    @Test
    fun `padding and whitespace are tolerated`() {
        assertEquals("abc", ShortEpg.decodeField("YWJj"))
        assertEquals("abcd", ShortEpg.decodeField("YWJjZA=="))
        assertEquals("abcde", ShortEpg.decodeField("YWJjZGU="))
        assertEquals("Hello", ShortEpg.decodeField(" SGVsbG8= "))
    }

    @Test
    fun `utf8 survives the round trip`() {
        val text = "ニュース7"
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, ShortEpg.decodeField(encoded))
    }

    @Test
    fun `a field that is not base64 is passed through unchanged`() {
        // Providers are inconsistent about encoding; plain text must survive.
        assertEquals("Live: Yankees vs Red Sox", ShortEpg.decodeField("Live: Yankees vs Red Sox"))
        assertEquals("News @ 10", ShortEpg.decodeField("News @ 10"))
    }

    @Test
    fun `empty input gives empty output`() {
        assertEquals("", ShortEpg.decodeField(null))
        assertEquals("", ShortEpg.decodeField(""))
        assertEquals("", ShortEpg.decodeField("   "))
    }

    @Test
    fun `text that happens to look like base64 but decodes to bytes is kept as text`() {
        // "Sports" is a valid base64 alphabet string but decodes to control bytes.
        val decoded = ShortEpg.decodeField("Sports")
        assertTrue("expected the original text back, got \"$decoded\"", decoded == "Sports")
    }
}

class ProgramCategoryTest {

    @Test
    fun `xmltv categories map to colour buckets`() {
        assertEquals(ProgramCategory.MOVIE, ProgramCategory.fromXmltv(listOf("Movie")))
        assertEquals(ProgramCategory.MOVIE, ProgramCategory.fromXmltv(listOf("Film", "Drama")))
        assertEquals(ProgramCategory.SPORTS, ProgramCategory.fromXmltv(listOf("Sports")))
        assertEquals(ProgramCategory.SPORTS, ProgramCategory.fromXmltv(listOf("Football")))
        assertEquals(ProgramCategory.NEWS, ProgramCategory.fromXmltv(listOf("News")))
        assertEquals(ProgramCategory.KIDS, ProgramCategory.fromXmltv(listOf("Children's")))
        assertEquals(ProgramCategory.KIDS, ProgramCategory.fromXmltv(listOf("Cartoon")))
    }

    @Test
    fun `movie wins over a co-tagged genre`() {
        assertEquals(ProgramCategory.MOVIE, ProgramCategory.fromXmltv(listOf("Drama", "Movie")))
    }

    @Test
    fun `unknown and missing categories fall through to the default`() {
        assertEquals(ProgramCategory.SERIES_OTHER, ProgramCategory.fromXmltv(emptyList()))
        assertEquals(ProgramCategory.SERIES_OTHER, ProgramCategory.fromXmltv(listOf("Talk show")))
        assertEquals(ProgramCategory.SERIES_OTHER, ProgramCategory.fromXmltv(listOf("")))
    }
}
