package com.crimson.core.epg

import com.crimson.core.guide.ProgramSlot
import com.crimson.core.text.NameCleaner

/**
 * Guide data made from a channel's own name, for the channels no guide feed will ever cover.
 *
 * On a real account most of the list is not television channels at all. Of 11,130 channels
 * measured, only about 1,700 could be filled from any guide feed, public or the provider's own;
 * the rest are event slots and loops whose name *is* the listing, rewritten by the provider each
 * time the booking changes:
 *
 * ```
 * US (ESPN+ 049) | NHL: BUF vs. PIT • NYR vs. NJ (2026-09-21 19:00:50)
 * BTN+ 2 HD (D): B1G+ | Soccer (W) | Michigan at Maryland | Thu 16 Oct 19:00
 * LIVE EVENT 01 - 8pm WWE Monday Night RAW
 * NHL | 06 - 8pm Kraken at Flames
 * US| 24/7 JAMES BOND          US| LAW AND ORDER SPECIAL VICTIMS UNIT FHD
 * NO EVENT STREAMING NOW - | 8K EXCLUSIVE | US: SOCCER PPV 95
 * ```
 *
 * So this reads the name: a date or a time in it becomes a programme at that moment, a loop
 * becomes an all-day programme, and a slot with nothing booked stays "No Information". The grid
 * then shows what the channel is actually carrying instead of an empty band.
 *
 * The one rule that keeps this honest: **a name that is only a brand and a number is a slot, not
 * a title.** `FITE TV 3` and `AMAZON UK EVENT 5` say nothing a viewer does not already see in the
 * channel column, so they are left alone; `LAW AND ORDER SPECIAL VICTIMS UNIT` is a real title
 * and becomes one.
 *
 * Pure Kotlin: the parsing is the part worth testing and the part that does not need a device.
 */
object EventChannelEpg {

    /** What a name turned out to be. */
    sealed class Kind {
        /** A dated event: the slot carries one programme starting at [startMs]. */
        data class Event(val title: String, val startMs: Long) : Kind()
        /** A round-the-clock loop of one title. */
        data class Loop(val title: String) : Kind()
        /** An event slot with nothing scheduled. Gets fillers like any other empty row. */
        data object Idle : Kind()
        /** An ordinary channel; nothing to synthesise. */
        data object None : Kind()
    }

    /**
     * Classifies a channel name. [localOffsetMs] is the offset to apply to a wall-clock stamp in
     * the name, which providers write in their own local time with no zone; the caller passes the
     * device's zone offset (plus the manual guide offset) for that instant. [nowMs] anchors the
     * names that give a time but no date ("8pm Kraken at Flames") to today.
     */
    fun classify(
        rawName: String,
        localOffsetMs: (utcMsGuess: Long) -> Long,
        categoryName: String? = null,
        nowMs: Long = 0L,
    ): Kind {
        val name = rawName.trim()
        if (name.isEmpty()) return Kind.None
        val upper = name.uppercase()

        if (IDLE_MARKERS.any { upper.contains(it) }) return Kind.Idle

        // "2026-09-21 19:00:50" — a full stamp, the commonest form.
        ISO_STAMP.find(name)?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            val year = y.toInt()
            // A placeholder date, like 2098-12-31, is a provider's way of saying "unscheduled".
            if (year > 2090) return Kind.Idle
            val wall = civilToMs(year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
            val title = cleanTitle(name.removeRange(m.range))
            return if (title.isEmpty()) Kind.Idle else Kind.Event(title, wall - localOffsetMs(wall))
        }

        // "Thu 16 Oct 19:00" or "Mon 21 Sep 17:30 EDT" — a day and month, no year.
        if (nowMs > 0L) {
            DAY_MONTH_STAMP.find(name)?.let { m ->
                val (_, dayStr, monStr, hourStr, minStr) = m.destructured
                val month = MONTHS[monStr.take(3).uppercase()]
                if (month != null) {
                    val year = yearFor(month, dayStr.toInt(), nowMs, localOffsetMs)
                    val wall = civilToMs(year, month, dayStr.toInt(), hourStr.toInt(), minStr.toInt())
                    val title = cleanTitle(name.removeRange(m.range))
                    return if (title.isEmpty()) Kind.Idle else Kind.Event(title, wall - localOffsetMs(wall))
                }
            }
        }

        val loop = LOOP_PREFIX.find(name)
        if (loop != null) {
            val title = cleanTitle(name.substring(loop.range.last + 1))
            return if (title.isEmpty()) Kind.None else Kind.Loop(title)
        }

        // "LIVE EVENT 01 - 8pm WWE Monday Night RAW", "NHL | 06 - 8pm Kraken at Flames" — a time
        // of day and a fixture, which is today's booking for that slot.
        if (nowMs > 0L) {
            TIME_OF_DAY.find(name)?.let { m ->
                val title = cleanTitle(name.substring(m.range.last + 1))
                if (title.isNotEmpty()) {
                    val (hourStr, minStr, meridiem) = m.destructured
                    var hour = hourStr.toInt() % 12
                    if (meridiem.uppercase().startsWith("P")) hour += 12
                    val minute = minStr.toIntOrNull() ?: 0
                    val startOfDay = startOfLocalDay(nowMs, localOffsetMs)
                    return Kind.Event(title, startOfDay + (hour * 60L + minute) * 60_000L)
                }
            }
        }

        // A category that says "this channel loops one title": 24/7 groups, and the streaming
        // shelves a provider dresses up as channels. The title has to look like a title.
        if (categoryName != null && LOOP_CATEGORY.containsMatchIn(categoryName)) {
            val title = cleanTitle(NameCleaner.clean(ChannelMatcher.stripGroupPrefix(name)))
            if (title.isNotEmpty() && !isBrandAndNumber(title)) return Kind.Loop(title)
        }

        return Kind.None
    }

    /**
     * The programmes to draw for a name over [windowStartMs]..[windowEndMs], or an empty list for
     * an ordinary channel or an idle slot. Ids are negative and derived from the name so they stay
     * stable across refreshes and never collide with database ids.
     */
    fun programs(
        rawName: String,
        windowStartMs: Long,
        windowEndMs: Long,
        localOffsetMs: (utcMsGuess: Long) -> Long,
        categoryName: String? = null,
        nowMs: Long = windowStartMs,
    ): List<ProgramSlot> {
        val id = -(rawName.hashCode().toLong() and 0x7FFF_FFFFL) - 1_000L
        return when (val kind = classify(rawName, localOffsetMs, categoryName, nowMs)) {
            is Kind.Event -> {
                val end = kind.startMs + EVENT_LENGTH_MS
                if (end <= windowStartMs || kind.startMs >= windowEndMs) {
                    emptyList()
                } else {
                    listOf(
                        ProgramSlot(
                            id = id,
                            startMs = kind.startMs,
                            endMs = end,
                            title = kind.title,
                            category = ProgramCategory.fromXmltv(listOf("Sports")),
                            description = "Event time taken from the channel name; the provider gives no guide data for this slot.",
                        )
                    )
                }
            }
            is Kind.Loop -> listOf(
                ProgramSlot(
                    id = id,
                    startMs = windowStartMs,
                    endMs = windowEndMs,
                    title = kind.title,
                    category = ProgramCategory.SERIES_OTHER,
                    description = "Plays around the clock.",
                    isAllDay = true,
                )
            )
            Kind.Idle, Kind.None -> emptyList()
        }
    }

    /** True when the channel is a slot or loop that a guide feed will never cover. */
    fun isSynthetic(rawName: String, categoryName: String? = null): Boolean =
        classify(rawName, { 0L }, categoryName, nowMs = 1L) != Kind.None

    // ------------------------------------------------------------------ pieces

    /**
     * Strips the provider's slot bookkeeping from around the event title: the `(ESPN+ 049) |`
     * prefix, a `US` country tag, the sport prefix `fc:` or `live:`, stray separators, and
     * underscores used in place of spaces.
     */
    private fun cleanTitle(s: String): String {
        var t = s.replace('_', ' ')
        // "(FLSP 451) |", "US (ESPN+ 049) |", ":Paramount+  88", "TC+ 12:" — the slot label.
        t = SLOT_LABEL.replace(t, "")
        t = SPORT_PREFIX.replace(t, "")
        t = t.replace(Regex("\\(\\s*\\)"), "")
        t = t.trim { it.isWhitespace() || it in "|:-–•·" }
        return t.replace(Regex("\\s{2,}"), " ")
    }

    /**
     * True for a name that is a slot number rather than a title — `FITE TV 3`,
     * `AMAZON UK EVENT 5`, `ESPN PLAY EVENTS 33`, `NETFLIX PREMIERE 6`, `DISNEY SERIES 1`.
     * Putting these on the grid would tell the viewer nothing the channel column has not already
     * told them, so they stay "No Information".
     *
     * A name qualifies when it ends in a bare number *and* what comes before it is either a word
     * that means "numbered slot" or nothing but brand names. `LAW AND ORDER 2` passes neither
     * test and stays a title.
     */
    private fun isBrandAndNumber(title: String): Boolean {
        val words = title.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val number = words.last()
        if (!number.all { it.isDigit() }) return false
        val head = words.dropLast(1).map { it.uppercase() }
        // A name that is nothing but a number is a slot index when it is short and a title when
        // it is not: `3` is the third feed of something, `90210` is a programme.
        if (head.isEmpty()) return number.length <= 3
        return head.any { it in SLOT_MARKERS } || head.all { it in BRAND_WORDS }
    }

    /** Words that say the number after them is a slot index. */
    private val SLOT_MARKERS = setOf(
        "EVENT", "EVENTS", "CHANNEL", "CH", "SLOT", "STREAM", "FEED", "REPLAY", "PREMIERE",
        "PREMIERES", "SERIES", "ORIGINAL", "ORIGINALS", "PPV", "LIVE",
    )

    /** Names of services, which on their own are not a programme title. */
    private val BRAND_WORDS = setOf(
        "TV", "UK", "US", "USA", "AMAZON", "NETFLIX", "DISNEY", "PEACOCK", "PARAMOUNT", "PRIME",
        "HULU", "MAX", "APPLE", "FITE", "STAN", "DAZN", "VIAPLAY", "PLUTO", "PLEX", "ROKU",
        "SAMSUNG", "PLAY", "PLUS", "NETWORK", "MOVIE", "MOVIES",
    )

    /** `24/7 Friends`, `24-7 Friends`, `UK| 24/7 Simpsons`. */
    private val LOOP_PREFIX = Regex("""^\s*(?:[A-Z]{2,3}\s*[|:]\s*)?24\s*[/x\-]\s*7\s*[|:\-–]?\s*""", RegexOption.IGNORE_CASE)

    /** A category that says every channel in it loops one title. */
    private val LOOP_CATEGORY = Regex(
        """24\s*[/x\-]\s*7|ON AIR|CINEMANIA|NETFLIX|DISNEY\+|PEACOCK|\bPRIME\b|HULU|MOVIES & SERIES""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The slot label a provider puts in front of the booking: `US (ESPN+ 049) |`, `(FLSP 451) |`,
     * `:Paramount+  80`, `TC+ 12:`, `BTN+ 2 HD (D):`. It names the slot, not the programme.
     */
    private val SLOT_LABEL = Regex(
        """^\s*(?:[A-Z]{2}\s*)?\([A-Za-z+ ]+\d+\)\s*\|?""" +
            """|^\s*:[A-Za-z+ ]+\d+\s*\|?""" +
            """|^\s*[A-Za-z+]+\s+\d+[A-Za-z0-9 ()+.-]{0,15}?[:|]"""
    )

    private val SPORT_PREFIX = Regex(
        """^\s*(?:live|fc|football|soccer|basketball|volleyball|hockey|grappling|wrestling|baseball|""" +
            """softball|tennis|golf|mma|boxing|racing|lacrosse|rugby|cricket|bikes)\s*:\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val IDLE_MARKERS = listOf("NO EVENT", "NO STREAM", "OFF AIR", "OFFLINE", "#####", "ENDED |")

    /** `(2026-09-21 19:00:50)` or `2026-09-21 19:00`, anywhere in the name. */
    private val ISO_STAMP = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::\d{2})?""")

    /** `Thu 16 Oct 19:00`, `Mon 21 Sep 17:30 EDT` — day and month, no year. */
    private val DAY_MONTH_STAMP = Regex(
        """\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*\s+(\d{1,2})\s+""" +
            """(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(\d{1,2})[:.](\d{2})""" +
            """(?:\s*[A-Z]{2,4})?""",
        RegexOption.IGNORE_CASE,
    )

    /** `- 8pm WWE Monday Night RAW`, `| 06 - 7:30pm Kraken at Flames`. */
    private val TIME_OF_DAY = Regex("""(?:^|[\s|\-])(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?\b""", RegexOption.IGNORE_CASE)

    private val MONTHS = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
    )

    /**
     * The year a day-and-month without one means: this year, unless that would put the event
     * more than a month in the past, in which case the provider means next year.
     */
    private fun yearFor(month: Int, day: Int, nowMs: Long, localOffsetMs: (Long) -> Long): Int {
        val local = nowMs + localOffsetMs(nowMs)
        val thisYear = civilFromDays(Math.floorDiv(local, 86_400_000L)).first
        val guess = civilToMs(thisYear, month, day, 12, 0)
        return when {
            guess < local - 30 * 86_400_000L -> thisYear + 1
            guess > local + 300 * 86_400_000L -> thisYear - 1
            else -> thisYear
        }
    }

    /** Midnight of the day [nowMs] falls in, in the caller's zone, as a UTC instant. */
    private fun startOfLocalDay(nowMs: Long, localOffsetMs: (Long) -> Long): Long {
        val offset = localOffsetMs(nowMs)
        val local = nowMs + offset
        return Math.floorDiv(local, 86_400_000L) * 86_400_000L - offset
    }

    /** Inverse of [civilToMs]'s date part: days since the epoch to (year, month, day). */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        // Howard Hinnant's civil_from_days, the inverse of the arithmetic in civilToMs.
        val z = days + 719468L
        val era = Math.floorDiv(if (z >= 0) z else z - 146096L, 146097L)
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }

    /** How long an event is assumed to run when the name gives only a start. */
    const val EVENT_LENGTH_MS = 3 * 60 * 60 * 1000L

    /** Days-from-civil, as in XmltvTime, so this stays free of java.time. */
    private fun civilToMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return ((days * 24 + hour) * 60 + minute) * 60_000L
    }
}
