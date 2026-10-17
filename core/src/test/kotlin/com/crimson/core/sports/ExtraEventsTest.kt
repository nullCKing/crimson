package com.crimson.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ExtraEventsTest {

    private val hour = 3_600_000L

    @Test
    fun `the published file is read, and broken entries are dropped`() {
        val json = """
            {"generated": 1792200000000, "model": "claude-haiku-5-5", "events": [
              {"c": "wbredt.us", "s": 1792200000000, "e": 1792207200000, "t": "AEW Collision", "k": "Pro Wrestling", "l": true, "m": false, "d": "Live from Toronto."},
              {"c": "", "s": 1, "e": 2, "t": "No channel"},
              {"c": "x.us", "s": 5, "e": 4, "t": "Ends before it starts"},
              {"c": "y.us", "s": 1792200000000, "e": 1792203600000, "t": "Rodeo", "k": null, "extra": [1, 2]}
            ]}
        """.trimIndent()
        val file = ExtraEventsParser.parse(StringReader(json))
        assertEquals(1792200000000L, file.generatedAtMs)
        assertEquals(2, file.events.size)
        val e = file.events[0]
        assertEquals("wbredt.us", e.channelId)
        assertEquals("AEW Collision", e.title)
        assertEquals("Pro Wrestling", e.kind)
        assertTrue(e.live)
        assertEquals("Live Event", file.events[1].kind)
    }

    @Test
    fun `events group into on now, later today and tomorrow, without finished or mainstream ones`() {
        val now = 1_792_000_000_000L - (1_792_000_000_000L % ExtraEventRows.DAY) + 12 * hour // noon UTC
        fun ev(title: String, startH: Long, lenH: Long = 2, mainstream: Boolean = false) =
            ExtraEvent("c.us", now + startH * hour, now + (startH + lenH) * hour, title, "Kind", true, mainstream)
        val events = listOf(
            ev("finished", -5),
            ev("on now", -1),
            ev("tonight", 6),
            ev("big league", 7, mainstream = true),
            ev("tomorrow", 20),
            ev("in two days", 40),
        )
        val groups = ExtraEventRows.group(events, { it }, now) { 0L }
        assertEquals(
            listOf(
                ExtraEventRows.Group.OnNow to listOf("on now"),
                ExtraEventRows.Group.Day(0, now + 6 * hour) to listOf("tonight"),
                ExtraEventRows.Group.Day(1, now + 20 * hour) to listOf("tomorrow"),
                ExtraEventRows.Group.Day(2, now + 40 * hour) to listOf("in two days"),
            ),
            groups.map { (g, list) -> g to list.map { it.title } },
        )
    }
}
