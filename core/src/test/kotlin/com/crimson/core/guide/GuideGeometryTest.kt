package com.crimson.core.guide

import com.crimson.core.epg.ProgramCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideGeometryTest {

    private val base = 1_790_100_000_000L // an exact half hour
    private val window = TimeWindow(base, 2 * 60 * 60 * 1000L)
    private val gridLeft = 300f
    private val gridWidth = 1200f

    private fun slot(startMin: Int, endMin: Int, title: String = "Show") = ProgramSlot(
        id = startMin.toLong(),
        startMs = base + startMin * 60_000L,
        endMs = base + endMin * 60_000L,
        title = title,
    )

    @Test
    fun `a two hour window has four half hour columns`() {
        assertEquals(4, window.columnCount)
        assertEquals(5, window.columnBoundaries().size)
        assertEquals(base, window.columnBoundaries().first())
        assertEquals(base + 2 * 3600_000L, window.columnBoundaries().last())
    }

    @Test
    fun `snapping rounds down to the half hour`() {
        assertEquals(base, TimeWindow.snapToHalfHour(base))
        assertEquals(base, TimeWindow.snapToHalfHour(base + 1))
        assertEquals(base, TimeWindow.snapToHalfHour(base + 29 * 60_000L))
        assertEquals(base + HALF_HOUR_MS, TimeWindow.snapToHalfHour(base + 30 * 60_000L))
        // Snapping must round down before the epoch too, not toward zero.
        assertEquals(-HALF_HOUR_MS, TimeWindow.snapToHalfHour(-1))
    }

    @Test
    fun `time maps linearly across the grid`() {
        assertEquals(gridLeft, GuideGeometry.xForTime(base, window, gridLeft, gridWidth), 0.01f)
        assertEquals(
            gridLeft + gridWidth / 2,
            GuideGeometry.xForTime(base + 3600_000L, window, gridLeft, gridWidth),
            0.01f,
        )
        assertEquals(
            gridLeft + gridWidth,
            GuideGeometry.xForTime(base + 2 * 3600_000L, window, gridLeft, gridWidth),
            0.01f,
        )
    }

    @Test
    fun `times outside the window clamp to the edges`() {
        assertEquals(gridLeft, GuideGeometry.xForTime(base - 5_000_000L, window, gridLeft, gridWidth), 0.01f)
        assertEquals(
            gridLeft + gridWidth,
            GuideGeometry.xForTime(base + 99_000_000L, window, gridLeft, gridWidth),
            0.01f,
        )
    }

    @Test
    fun `a programme that started before the window gets a left notch`() {
        val cells = GuideGeometry.layoutRow(listOf(slot(-60, 30)), window, gridLeft, gridWidth)
        assertEquals(1, cells.size)
        assertTrue(cells[0].notchLeft)
        assertFalse(cells[0].notchRight)
        assertEquals(gridLeft, cells[0].left, 0.01f)
    }

    @Test
    fun `a programme that runs past the window gets a right notch`() {
        val cells = GuideGeometry.layoutRow(listOf(slot(90, 240)), window, gridLeft, gridWidth)
        assertEquals(1, cells.size)
        assertFalse(cells[0].notchLeft)
        assertTrue(cells[0].notchRight)
        assertEquals(gridLeft + gridWidth, cells[0].right, 0.01f)
    }

    @Test
    fun `a programme spanning the whole window gets both notches`() {
        val cells = GuideGeometry.layoutRow(listOf(slot(-120, 300)), window, gridLeft, gridWidth)
        assertTrue(cells[0].notchLeft)
        assertTrue(cells[0].notchRight)
    }

    @Test
    fun `programmes entirely outside the window are not drawn`() {
        val cells = GuideGeometry.layoutRow(
            listOf(slot(-180, -120), slot(300, 360)), window, gridLeft, gridWidth,
        )
        assertTrue(cells.isEmpty())
    }

    @Test
    fun `a sliver is widened to the minimum readable width`() {
        val cells = GuideGeometry.layoutRow(listOf(slot(119, 121)), window, gridLeft, gridWidth, minWidth = 40f)
        assertEquals(1, cells.size)
        assertTrue("expected at least the minimum width", cells[0].width >= 39f)
    }

    // ------------------------------------------------------------------ fillers

    @Test
    fun `an empty row is filled with half hour No Information blocks`() {
        val filled = GuideGeometry.withFillers(emptyList(), window)
        assertEquals(4, filled.size)
        assertTrue(filled.all { it.isFiller })
        assertTrue(filled.all { it.title == GuideGeometry.NO_INFORMATION })
        assertTrue(filled.all { it.durationMs == HALF_HOUR_MS })
        assertEquals(base, filled.first().startMs)
        assertEquals(window.endMs, filled.last().endMs)
    }

    @Test
    fun `gaps between programmes are filled and aligned to the half hour`() {
        // 19:00-19:30 programme, then a gap until 20:30.
        val filled = GuideGeometry.withFillers(listOf(slot(0, 30), slot(90, 120)), window)
        val fillers = filled.filter { it.isFiller }
        assertEquals(2, fillers.size)
        assertEquals(base + 30 * 60_000L, fillers[0].startMs)
        assertEquals(base + 60 * 60_000L, fillers[0].endMs)
        assertEquals(base + 60 * 60_000L, fillers[1].startMs)
        assertEquals(base + 90 * 60_000L, fillers[1].endMs)
    }

    @Test
    fun `a filled row covers the window with no holes or overlaps`() {
        val filled = GuideGeometry.withFillers(listOf(slot(15, 45), slot(75, 105)), window)
        var cursor = window.startMs
        for (s in filled) {
            assertEquals("gap or overlap before \"${s.title}\"", cursor, s.startMs)
            cursor = s.endMs
        }
        assertEquals(window.endMs, cursor)
    }

    @Test
    fun `overlapping programmes are trimmed rather than dropped`() {
        val filled = GuideGeometry.withFillers(listOf(slot(0, 60, "A"), slot(30, 90, "B")), window)
        val a = filled.first { it.title == "A" }
        val b = filled.first { it.title == "B" }
        assertEquals(a.endMs, b.startMs)
        assertTrue(b.durationMs > 0)
    }

    @Test
    fun `a real programme keeps its category while fillers do not`() {
        val movie = slot(0, 120).copy(category = ProgramCategory.MOVIE)
        val filled = GuideGeometry.withFillers(listOf(movie), window)
        assertEquals(1, filled.size)
        assertEquals(ProgramCategory.MOVIE, filled[0].category)
        assertFalse(filled[0].isFiller)
    }

    // ------------------------------------------------------------------ pointer hit-testing

    private fun hit(x: Float, y: Float) = GuideGeometry.hitTest(
        x = x, y = y, window = window,
        channelColumnWidth = gridLeft, gridWidth = gridWidth,
        headerHeight = 40f, rowHeight = 80f, rowGap = 5f, rowsDrawn = 5,
    )

    @Test
    fun `the header hits the date tab over the channel column and time tabs over the grid`() {
        assertEquals(GuideHit.Header(-1), hit(10f, 10f))
        assertEquals(GuideHit.Header(0), hit(gridLeft + 1f, 10f))
        assertEquals(GuideHit.Header(1), hit(gridLeft + 301f, 10f))
        assertEquals(GuideHit.Header(3), hit(gridLeft + gridWidth - 1f, 10f))
    }

    @Test
    fun `a channel cell hit carries the visible row offset`() {
        assertEquals(GuideHit.Channel(0), hit(10f, 40f))
        assertEquals(GuideHit.Channel(2), hit(10f, 40f + 2 * 85f + 10f))
    }

    @Test
    fun `a programme hit carries the time under the pointer`() {
        // One quarter of the way across a two-hour grid is thirty minutes in.
        val h = hit(gridLeft + 300f, 40f + 85f + 40f)
        assertTrue(h is GuideHit.Program)
        h as GuideHit.Program
        assertEquals(1, h.row)
        assertEquals(base + HALF_HOUR_MS, h.timeMs)
    }

    @Test
    fun `the gap between rows and the space below the last row hit nothing`() {
        assertEquals(GuideHit.Nothing, hit(gridLeft + 10f, 40f + 80f + 2f))
        assertEquals(GuideHit.Nothing, hit(gridLeft + 10f, 40f + 5 * 85f + 1f))
        assertEquals(GuideHit.Nothing, hit(-1f, 50f))
        assertEquals(GuideHit.Nothing, hit(gridLeft + gridWidth + 1f, 50f))
    }

    @Test
    fun `a programme hit at the right edge stays inside the window`() {
        val h = hit(gridLeft + gridWidth - 0.5f, 60f) as GuideHit.Program
        assertTrue(h.timeMs < window.endMs)
        assertTrue(h.timeMs >= window.startMs)
    }
}
