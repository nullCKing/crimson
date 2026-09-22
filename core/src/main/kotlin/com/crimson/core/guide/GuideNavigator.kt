package com.crimson.core.guide

/**
 * The guide's cursor, as a pure state machine.
 *
 * Remote-control navigation is the part of a TV app that is easiest to get subtly wrong and
 * hardest to check by looking at a screenshot: the highlight moving one row too far, the window
 * failing to scroll at the edge, the user walking backwards into last week. Keeping it here, with
 * no Compose or Android types, means every rule in section 5.3 of the spec is an assertion.
 *
 * The cursor is identified by a channel index and an *anchor time* rather than by a programme id.
 * Anchoring on time is what makes Up and Down behave the way a cable guide does: moving down a
 * row keeps you at the same moment in the evening, landing on whatever that channel is showing
 * then, even though the programme boundaries do not line up between rows.
 */
data class GuideCursor(
    val channelIndex: Int,
    val anchorMs: Long,
    val window: TimeWindow,
    val firstVisibleRow: Int,
)

/** What the guide needs to know about the world to move the cursor. */
interface GuideSource {
    val channelCount: Int

    /** Programmes for one row, sorted by start time, filler blocks included. */
    fun programsFor(channelIndex: Int, window: TimeWindow): List<ProgramSlot>
}

class GuideNavigator(
    private val source: GuideSource,
    /** How many channel rows the grid shows at once. */
    val visibleRows: Int = 5,
    /** How far Rewind and Fast Forward jump. */
    val pageMs: Long = 2 * 60 * 60 * 1000L,
) {

    /** The programme currently highlighted, or null when the row has nothing at the anchor. */
    fun selected(cursor: GuideCursor): ProgramSlot? {
        val programs = source.programsFor(cursor.channelIndex, cursor.window)
        return programs.firstOrNull { it.containsTime(cursor.anchorMs) }
            ?: programs.firstOrNull { it.startMs >= cursor.anchorMs }
    }

    fun initial(nowMs: Long, channelIndex: Int = 0): GuideCursor {
        val window = TimeWindow.around(nowMs)
        return GuideCursor(
            channelIndex = channelIndex.coerceIn(0, maxOf(0, source.channelCount - 1)),
            anchorMs = window.startMs,
            window = window,
            firstVisibleRow = firstRowFor(channelIndex, 0),
        )
    }

    // ------------------------------------------------------------------ vertical

    fun moveUp(cursor: GuideCursor): GuideCursor = moveChannel(cursor, -1)

    fun moveDown(cursor: GuideCursor): GuideCursor = moveChannel(cursor, +1)

    /**
     * Moves one channel. The list wraps, which is what every cable box does and what makes
     * holding the button down feel right at the ends.
     */
    private fun moveChannel(cursor: GuideCursor, delta: Int): GuideCursor {
        val count = source.channelCount
        if (count == 0) return cursor
        val next = Math.floorMod(cursor.channelIndex + delta, count)
        return cursor.copy(
            channelIndex = next,
            firstVisibleRow = firstRowFor(next, cursor.firstVisibleRow),
        )
    }

    /**
     * Scrolls only when the highlight would leave the visible band, so moving within the band
     * leaves the grid still.
     */
    private fun firstRowFor(channelIndex: Int, currentFirst: Int): Int {
        val count = source.channelCount
        if (count <= visibleRows) return 0
        val maxFirst = count - visibleRows
        return when {
            channelIndex < currentFirst -> channelIndex
            channelIndex >= currentFirst + visibleRows -> channelIndex - visibleRows + 1
            else -> currentFirst
        }.coerceIn(0, maxFirst)
    }

    // ------------------------------------------------------------------ horizontal

    /**
     * Moves to the previous programme on this row, never earlier than the current half hour.
     * [nowMs] is passed in rather than read from the clock so the behaviour is testable.
     */
    fun moveLeft(cursor: GuideCursor, nowMs: Long): GuideCursor {
        val floor = TimeWindow.snapToHalfHour(nowMs)
        val current = selected(cursor) ?: return cursor
        if (current.startMs <= floor) {
            // Already in the earliest reachable slot: stay put rather than scrolling into the past.
            return cursor
        }
        val target = maxOf(current.startMs - 1, floor)
        return retarget(cursor, target, nowMs)
    }

    /** Moves to the next programme on this row, scrolling the window forward when needed. */
    fun moveRight(cursor: GuideCursor, nowMs: Long): GuideCursor {
        val current = selected(cursor) ?: return cursor
        return retarget(cursor, current.endMs, nowMs)
    }

    /** Rewind: page the window back by [pageMs], stopping at the current half hour. */
    fun pageBack(cursor: GuideCursor, nowMs: Long): GuideCursor {
        val floor = TimeWindow.snapToHalfHour(nowMs)
        val newStart = maxOf(cursor.window.startMs - pageMs, floor)
        if (newStart == cursor.window.startMs) return cursor
        val window = cursor.window.copy(startMs = newStart)
        return cursor.copy(window = window, anchorMs = maxOf(newStart, floor))
    }

    /** Fast forward: page the window on by [pageMs]. */
    fun pageForward(cursor: GuideCursor): GuideCursor {
        val window = cursor.window.shiftedBy(pageMs)
        return cursor.copy(window = window, anchorMs = window.startMs)
    }

    /**
     * Puts the anchor at [targetMs] and scrolls the window if that moment is no longer visible.
     * The window always lands on a half-hour boundary so the time header stays aligned.
     */
    private fun retarget(cursor: GuideCursor, targetMs: Long, nowMs: Long): GuideCursor {
        val floor = TimeWindow.snapToHalfHour(nowMs)
        val anchor = maxOf(targetMs, floor)
        var window = cursor.window
        if (anchor < window.startMs) {
            window = window.copy(startMs = maxOf(TimeWindow.snapToHalfHour(anchor), floor))
        } else if (anchor >= window.endMs) {
            // Scroll just far enough that the new anchor is the leading column.
            window = window.copy(startMs = TimeWindow.snapToHalfHour(anchor))
        }
        return cursor.copy(anchorMs = anchor, window = window)
    }

    // ------------------------------------------------------------------ pointer

    /**
     * Puts the cursor on a row and a moment directly, for a mouse or a touch on the grid.
     *
     * The anchor is clamped to the visible window and to the current half hour exactly as a
     * D-pad move is, so a pointer can never select a moment the remote could not reach. The
     * window never scrolls here: the pointer is, by definition, over something already on screen.
     */
    fun moveTo(cursor: GuideCursor, channelIndex: Int, anchorMs: Long, nowMs: Long): GuideCursor {
        val count = source.channelCount
        if (count == 0) return cursor
        val floor = TimeWindow.snapToHalfHour(nowMs)
        val index = channelIndex.coerceIn(0, count - 1)
        val anchor = anchorMs
            .coerceIn(cursor.window.startMs, cursor.window.endMs - 1)
            .coerceAtLeast(floor)
        return cursor.copy(
            channelIndex = index,
            anchorMs = anchor,
            firstVisibleRow = firstRowFor(index, cursor.firstVisibleRow),
        )
    }

    // ------------------------------------------------------------------ selection

    /** True when Select should tune rather than open the future-programme dialog. */
    fun isPlayable(slot: ProgramSlot?, nowMs: Long): Boolean =
        slot != null && !slot.isFiller && slot.startMs <= nowMs

    /** True when the preview window should show the "Future program" message box. */
    fun isFuture(slot: ProgramSlot?, nowMs: Long): Boolean =
        slot != null && slot.startMs > nowMs
}
