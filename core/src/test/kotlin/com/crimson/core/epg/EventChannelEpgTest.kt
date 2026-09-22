package com.crimson.core.epg

import com.crimson.core.epg.EventChannelEpg.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names below are real ones from a provider's list, with the tags they came with. */
class EventChannelEpgTest {

    private val noOffset: (Long) -> Long = { 0L }

    /** 2026-09-21 19:00 UTC. */
    private val sep21at19 = 1_790_017_200_000L

    @Test
    fun `an ESPN plus slot becomes an event at the time in its name`() {
        val kind = EventChannelEpg.classify(
            "US (ESPN+ 049) | NHL: BUF vs. PIT • NYR vs. NJ (2026-09-21 19:00:50)", noOffset,
        )
        assertEquals(Kind.Event("NHL: BUF vs. PIT • NYR vs. NJ", sep21at19), kind)
    }

    @Test
    fun `the local offset is subtracted from the wall-clock stamp`() {
        val fiveHoursBehind: (Long) -> Long = { -5 * 3600_000L }
        val kind = EventChannelEpg.classify("US (ESPN+ 049) | NHL: BUF vs. PIT (2026-09-21 19:00:50)", fiveHoursBehind)
        assertEquals(sep21at19 + 5 * 3600_000L, (kind as Kind.Event).startMs)
    }

    @Test
    fun `a FLO slot loses its label sport prefix and underscores`() {
        val kind = EventChannelEpg.classify(
            "(FLSP 451) | live:  Assumption vs Southern Connecticut _ Field Hockey (Assumption vs SCSU) (2026-09-25 16:00:20)",
            noOffset,
        )
        assertEquals("Assumption vs Southern Connecticut Field Hockey (Assumption vs SCSU)", (kind as Kind.Event).title)
    }

    @Test
    fun `a STAN slot with a two-letter country keeps its title`() {
        val kind = EventChannelEpg.classify(
            "AU (STAN 72) | Crosscountry Olympic: Lake Placid  UCI Mountain Bike World Series 2026 (2026-10-04 04:45:29)",
            noOffset,
        )
        assertEquals("Crosscountry Olympic: Lake Placid UCI Mountain Bike World Series 2026", (kind as Kind.Event).title)
    }

    @Test
    fun `idle slots and placeholder dates are idle`() {
        assertEquals(Kind.Idle, EventChannelEpg.classify("NO EVENT STREAMING NOW - | 8K EXCLUSIVE | US: SOCCER PPV 95", noOffset))
        assertEquals(Kind.Idle, EventChannelEpg.classify("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ESPN+ PPV 823", noOffset))
        assertEquals(Kind.Idle, EventChannelEpg.classify("(Victory+ 045) |  (2098-12-31 08:00:44)", noOffset))
        assertEquals(Kind.Idle, EventChannelEpg.classify("##### DISNEY+ ORIGINAL #####", noOffset))
    }

    @Test
    fun `a 24-7 channel is a loop of its title`() {
        assertEquals(Kind.Loop("JAMES BOND"), EventChannelEpg.classify("US| 24/7 JAMES BOND", noOffset))
        assertEquals(Kind.Loop("THE SIMPSONS"), EventChannelEpg.classify("24/7 THE SIMPSONS", noOffset))
        assertEquals(Kind.Loop("SIMPSONS"), EventChannelEpg.classify("UK| 24/7 SIMPSONS", noOffset))
    }

    @Test
    fun `a channel in a loop category is a loop named after the show`() {
        assertEquals(
            Kind.Loop("CITADEL"),
            EventChannelEpg.classify("US| CITADEL ᴿᴬᵂ", noOffset, categoryName = "24/7 PRIME VIDEO ᴿᴬᵂ ⁶⁰ᶠᵖˢ"),
        )
        assertEquals(
            Kind.Loop("BLACK MIRROR"),
            EventChannelEpg.classify("US| BLACK MIRROR ᴿᴬᵂ", noOffset, categoryName = "US| NETFLIX ON AIR ᴿᴬᵂ ⁶⁰ᶠᵖˢ"),
        )
    }

    @Test
    fun `ordinary channels are left alone`() {
        assertEquals(Kind.None, EventChannelEpg.classify("US| ESPN 2 FHD", noOffset, categoryName = "US| SPORTS NETWORK"))
        assertEquals(Kind.None, EventChannelEpg.classify("US| FITE TV 24/7 HD", noOffset))
        assertEquals(Kind.None, EventChannelEpg.classify("TV| A&E ᴿᴬᵂ", noOffset, categoryName = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertFalse(EventChannelEpg.isSynthetic("US| CNN HD"))
        assertTrue(EventChannelEpg.isSynthetic("US| 24/7 JAMES BOND"))
    }

    @Test
    fun `an event produces one three-hour programme inside the window and nothing outside it`() {
        val name = "US (ESPN+ 049) | NHL: BUF vs. PIT (2026-09-21 19:00:50)"
        val inWindow = EventChannelEpg.programs(name, sep21at19 - 3600_000L, sep21at19 + 3600_000L, noOffset)
        assertEquals(1, inWindow.size)
        assertEquals(sep21at19, inWindow[0].startMs)
        assertEquals(sep21at19 + EventChannelEpg.EVENT_LENGTH_MS, inWindow[0].endMs)
        assertTrue(inWindow[0].id < 0)
        val later = EventChannelEpg.programs(name, sep21at19 + 4 * 3600_000L, sep21at19 + 6 * 3600_000L, noOffset)
        assertTrue(later.isEmpty())
    }

    @Test
    fun `a loop fills the whole window`() {
        val slots = EventChannelEpg.programs("US| 24/7 JAMES BOND", 1_000L, 5_000L, noOffset)
        assertEquals(1, slots.size)
        assertEquals(1_000L, slots[0].startMs)
        assertEquals(5_000L, slots[0].endMs)
    }

    // ------------------------------------------------------------------ names with no ISO stamp

    /** 2026-09-22 12:00 UTC, a Tuesday, used as "now" for the undated forms. */
    private val now = 1_790_078_400_000L

    @Test
    fun `a day and month with no year is read as an event`() {
        val kind = EventChannelEpg.classify(
            "BTN+ 2 HD (D): B1G+ | Soccer (W) | Michigan at Maryland | Thu 16 Oct 19:00",
            noOffset, nowMs = now,
        )
        kind as Kind.Event
        assertEquals("B1G+ | Soccer (W) | Michigan at Maryland", kind.title)
        // 16 October 2026, 19:00 UTC.
        assertEquals(1_792_177_200_000L, kind.startMs)
    }

    @Test
    fun `a day and month already past is next year`() {
        val january = EventChannelEpg.classify("Cup Final | Sat 10 Jan 15:00", noOffset, nowMs = now) as Kind.Event
        assertTrue("January is after September", january.startMs > now)
    }

    @Test
    fun `a time of day with no date is today`() {
        val kind = EventChannelEpg.classify("LIVE EVENT 01 - 8pm WWE Monday Night RAW", noOffset, nowMs = now) as Kind.Event
        assertEquals("WWE Monday Night RAW", kind.title)
        assertEquals(now - 12 * 3600_000L + 20 * 3600_000L, kind.startMs)
    }

    @Test
    fun `a time of day with minutes is read exactly`() {
        val kind = EventChannelEpg.classify("NHL | 06 - 7:30pm Kraken at Flames", noOffset, nowMs = now) as Kind.Event
        assertEquals("Kraken at Flames", kind.title)
        assertEquals(now - 12 * 3600_000L + 19 * 3600_000L + 30 * 60_000L, kind.startMs)
    }

    @Test
    fun `an ended event is idle`() {
        assertEquals(
            Kind.Idle,
            EventChannelEpg.classify(
                "ENDED | HANOVER VS. GLEN ALLEN | Mon 21 Sep 17:30 EDT (US) | 8K EXCLUSIVE",
                noOffset, nowMs = now,
            ),
        )
    }

    @Test
    fun `a show name in a streaming category becomes an all-day loop`() {
        assertEquals(
            Kind.Loop("LAW AND ORDER SPECIAL VICTIMS UNIT"),
            EventChannelEpg.classify("US| LAW AND ORDER SPECIAL VICTIMS UNIT FHD", noOffset,
                categoryName = "US| CINEMANIA TV SHOWS", nowMs = now),
        )
        assertEquals(
            // The full stop goes the way every other full stop in a channel name goes.
            Kind.Loop("DR PHIL PRIMETIME ON MERIT STREET"),
            EventChannelEpg.classify("PRIME| DR. PHIL PRIMETIME ON MERIT STREET ᴿᴬᵂ", noOffset,
                categoryName = "US| PRIME", nowMs = now),
        )
    }

    @Test
    fun `a numbered slot is left as No Information`() {
        // These say nothing the channel column has not already said.
        for (name in listOf("UK|AMAZON UK EVENT 5", "US| FITE TV 3 HD", "US| NETFLIX PREMIERE 6 ᴴᴰ",
                            "US| ESPN PLAY EVENTS 33 HD", "US| DISNEY+ SERIES 1 ᴴᴰ")) {
            val kind = EventChannelEpg.classify(name, noOffset, categoryName = "US| PRIME", nowMs = now)
            assertEquals("$name should stay unlisted", Kind.None, kind)
        }
    }

    @Test
    fun `a name that is only a long number is a title, only a short one is a slot`() {
        assertEquals(
            Kind.Loop("90210"),
            EventChannelEpg.classify("US| 90210 FHD", noOffset,
                categoryName = "US| CINEMANIA TV SHOWS", nowMs = now),
        )
        assertEquals(
            Kind.None,
            EventChannelEpg.classify("US| 12 FHD", noOffset,
                categoryName = "US| CINEMANIA TV SHOWS", nowMs = now),
        )
    }

    @Test
    fun `a title that merely ends in a number is still a title`() {
        assertEquals(
            Kind.Loop("LAW AND ORDER 2"),
            EventChannelEpg.classify("US| LAW AND ORDER 2", noOffset,
                categoryName = "US| CINEMANIA TV SHOWS", nowMs = now),
        )
    }

    @Test
    fun `an ordinary channel in an ordinary category is still left alone`() {
        assertEquals(
            Kind.None,
            EventChannelEpg.classify("UK| SKY SPORTS MAIN EVENT FHD", noOffset,
                categoryName = "UK| SPORTS", nowMs = now),
        )
    }
}
