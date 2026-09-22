package com.crimson.core.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideNavigatorTest {

    private val base = 1_790_100_000_000L // an exact half hour; treat as "now"
    private val hour = 3600_000L

    /**
     * Ten channels. Row n carries programmes n+1 half-hours long, so the rows deliberately do not
     * line up with each other — which is the case that makes Up/Down interesting.
     */
    private val source = object : GuideSource {
        override val channelCount = 10

        override fun programsFor(channelIndex: Int, window: TimeWindow): List<ProgramSlot> {
            val lengthMs = HALF_HOUR_MS * (channelIndex % 3 + 1)
            val from = TimeWindow.snapToHalfHour(window.startMs) - 4 * HALF_HOUR_MS
            val slots = ArrayList<ProgramSlot>()
            var t = from
            var i = 0
            while (t < window.endMs + 4 * HALF_HOUR_MS) {
                slots.add(
                    ProgramSlot(
                        id = channelIndex * 1000L + i,
                        startMs = t,
                        endMs = t + lengthMs,
                        title = "ch$channelIndex #$i",
                    )
                )
                t += lengthMs
                i++
            }
            return slots
        }
    }

    private val nav = GuideNavigator(source, visibleRows = 5)

    @Test
    fun `the guide opens on the current half hour`() {
        val c = nav.initial(base + 7 * 60_000L)
        assertEquals(base, c.window.startMs)
        assertEquals(base, c.anchorMs)
        assertEquals(0, c.channelIndex)
        assertEquals(0, c.firstVisibleRow)
    }

    // ------------------------------------------------------------------ up and down

    @Test
    fun `down moves one channel and does not scroll inside the visible band`() {
        var c = nav.initial(base)
        repeat(4) { c = nav.moveDown(c) }
        assertEquals(4, c.channelIndex)
        assertEquals("row 4 is the last visible row, no scroll yet", 0, c.firstVisibleRow)
    }

    @Test
    fun `down scrolls once the highlight reaches the bottom row`() {
        var c = nav.initial(base)
        repeat(5) { c = nav.moveDown(c) }
        assertEquals(5, c.channelIndex)
        assertEquals(1, c.firstVisibleRow)
    }

    @Test
    fun `up from the top row wraps to the end of the list`() {
        val c = nav.moveUp(nav.initial(base))
        assertEquals(9, c.channelIndex)
        assertEquals("the last page must be shown", 5, c.firstVisibleRow)
    }

    @Test
    fun `moving down then up returns to the same row and scroll position`() {
        val start = nav.initial(base)
        val there = (1..7).fold(start) { c, _ -> nav.moveDown(c) }
        val back = (1..7).fold(there) { c, _ -> nav.moveUp(c) }
        assertEquals(start.channelIndex, back.channelIndex)
        assertEquals(start.firstVisibleRow, back.firstVisibleRow)
    }

    @Test
    fun `changing channel keeps the moment in time`() {
        var c = nav.initial(base)
        c = nav.moveRight(c, base)       // move off the first programme
        val anchor = c.anchorMs
        c = nav.moveDown(c)
        assertEquals("the anchor time must not move when changing row", anchor, c.anchorMs)
        assertNotNull("the row below must have something at that moment", nav.selected(c))
    }

    // ------------------------------------------------------------------ left and right

    @Test
    fun `right steps to the next programme`() {
        val c = nav.initial(base)
        val first = nav.selected(c)!!
        val next = nav.selected(nav.moveRight(c, base))!!
        assertEquals(first.endMs, next.startMs)
    }

    @Test
    fun `left steps back to the previous programme`() {
        var c = nav.initial(base)
        c = nav.moveRight(c, base)
        c = nav.moveRight(c, base)
        val before = nav.selected(c)!!
        c = nav.moveLeft(c, base)
        val after = nav.selected(c)!!
        assertEquals(after.endMs, before.startMs)
    }

    @Test
    fun `left cannot walk into the past`() {
        val c = nav.initial(base)
        val moved = nav.moveLeft(c, base)
        assertEquals("already in the current slot: nothing should move", c.anchorMs, moved.anchorMs)
        assertEquals(c.window.startMs, moved.window.startMs)
        assertTrue(nav.selected(moved)!!.endMs > base)
    }

    @Test
    fun `right scrolls the window when it runs off the edge`() {
        var c = nav.initial(base)
        val originalStart = c.window.startMs
        repeat(12) { c = nav.moveRight(c, base) }
        assertTrue("the window must have advanced", c.window.startMs > originalStart)
        assertTrue("the selection must stay visible", c.anchorMs < c.window.endMs)
        assertTrue(c.anchorMs >= c.window.startMs)
        assertEquals("the window must stay on a half hour", 0L, c.window.startMs % HALF_HOUR_MS)
    }

    // ------------------------------------------------------------------ paging

    @Test
    fun `fast forward pages the window two hours`() {
        val c = nav.initial(base)
        val paged = nav.pageForward(c)
        assertEquals(base + 2 * hour, paged.window.startMs)
        assertEquals(paged.window.startMs, paged.anchorMs)
    }

    @Test
    fun `rewind pages back but stops at the current half hour`() {
        var c = nav.initial(base)
        c = nav.pageForward(c)
        c = nav.pageForward(c)
        assertEquals(base + 4 * hour, c.window.startMs)

        c = nav.pageBack(c, base)
        assertEquals(base + 2 * hour, c.window.startMs)
        c = nav.pageBack(c, base)
        assertEquals(base, c.window.startMs)
        c = nav.pageBack(c, base)
        assertEquals("must not scroll before now", base, c.window.startMs)
    }

    @Test
    fun `rewind clamps to the current half hour rather than overshooting`() {
        var c = nav.initial(base)
        c = c.copy(window = c.window.copy(startMs = base + hour))
        c = nav.pageBack(c, base)
        assertEquals(base, c.window.startMs)
    }

    // ------------------------------------------------------------------ select behaviour

    @Test
    fun `select tunes on a current programme and opens details on a future one`() {
        val c = nav.initial(base)
        val current = nav.selected(c)!!
        assertTrue(nav.isPlayable(current, base))
        assertFalse(nav.isFuture(current, base))

        val later = nav.selected(nav.pageForward(c))!!
        assertFalse(nav.isPlayable(later, base))
        assertTrue("a future programme shows the message box", nav.isFuture(later, base))
    }

    @Test
    fun `a filler cell is never playable`() {
        val filler = ProgramSlot(-1, base, base + HALF_HOUR_MS, "No Information", isFiller = true)
        assertFalse(nav.isPlayable(filler, base))
    }

    @Test
    fun `an empty channel list does not crash navigation`() {
        val empty = GuideNavigator(object : GuideSource {
            override val channelCount = 0
            override fun programsFor(channelIndex: Int, window: TimeWindow) = emptyList<ProgramSlot>()
        })
        val c = empty.initial(base)
        assertEquals(c, empty.moveDown(c))
        assertEquals(c, empty.moveUp(c))
        assertEquals(c, empty.moveRight(c, base))
    }

    // ------------------------------------------------------------------ pointer

    @Test
    fun `a pointer puts the cursor on the row and moment it is over`() {
        val c = nav.moveTo(nav.initial(base), channelIndex = 4, anchorMs = base + 70 * 60_000L, nowMs = base)
        assertEquals(4, c.channelIndex)
        assertEquals(base + 70 * 60_000L, c.anchorMs)
        assertEquals(0, c.firstVisibleRow)
        // Row 4 shows hour-long programmes starting two hours before the window, so 70 minutes
        // into the window is the fourth one.
        assertEquals(4000L + 3, nav.selected(c)!!.id)
    }

    @Test
    fun `a pointer can never select the past or a moment outside the window`() {
        val start = nav.initial(base + 20 * 60_000L)
        val early = nav.moveTo(start, 0, base - hour, nowMs = base + 20 * 60_000L)
        assertEquals(base, early.anchorMs)
        val late = nav.moveTo(start, 0, base + 10 * hour, nowMs = base)
        assertTrue(late.anchorMs < start.window.endMs)
        assertEquals(start.window, late.window)
    }

    @Test
    fun `a pointer on a row scrolls the band only when the row is off screen`() {
        val c = nav.moveTo(nav.initial(base), 7, base, base)
        assertEquals(3, c.firstVisibleRow)
        val again = nav.moveTo(c, 5, base, base)
        assertEquals(3, again.firstVisibleRow)
    }
}
