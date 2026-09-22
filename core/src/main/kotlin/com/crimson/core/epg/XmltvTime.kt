package com.crimson.core.epg

/**
 * Parses XMLTV timestamps.
 *
 * The format is `YYYYMMDDHHMMSS` followed by an optional ` +HHMM` offset:
 * `20260921200000 +0000`, `20260921200000 -0430`, or just `20260921200000`. Providers truncate it
 * too — `202609212000` and `2026092120` both turn up — and some omit the space before the offset.
 *
 * Returns epoch milliseconds, or null if the string cannot be read. Nothing here throws: a single
 * malformed `<programme>` in a fifty-megabyte guide must not abort the import.
 *
 * Implemented with plain arithmetic rather than `java.time` so the module stays free of anything
 * that would tie it to a particular Android API level, and so it can be tested on any JVM.
 */
object XmltvTime {

    /**
     * @param fallbackOffsetMinutes offset to assume when the timestamp carries none. This is where
     *   the user's manual time-offset setting lands, for providers that publish wrong or missing
     *   offsets.
     */
    fun parse(raw: String?, fallbackOffsetMinutes: Int = 0): Long? {
        if (raw == null) return null
        val s = raw.trim()
        if (s.length < 8) return null

        var i = 0
        fun digits(n: Int): Int? {
            if (i + n > s.length) return null
            var v = 0
            for (k in 0 until n) {
                val c = s[i + k]
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            i += n
            return v
        }

        val year = digits(4) ?: return null
        val month = digits(2) ?: return null
        val day = digits(2) ?: return null
        val hour = digits(2) ?: 0
        val minute = digits(2) ?: 0
        val second = digits(2) ?: 0

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
            return null
        }

        val offsetMinutes = parseOffset(s, i) ?: fallbackOffsetMinutes

        val days = daysFromCivil(year, month, day)
        val utcSeconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return utcSeconds * 1000L
    }

    /** Reads a trailing `+HHMM` / `-HH:MM` / `Z` offset, returning minutes east of UTC. */
    private fun parseOffset(s: String, from: Int): Int? {
        var i = from
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length) return null
        if (s[i] == 'Z' || s[i] == 'z') return 0

        val sign = when (s[i]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        i++
        val rest = s.substring(i).filter { it != ':' }
        if (rest.length < 4) return null
        val hh = rest.substring(0, 2).toIntOrNull() ?: return null
        val mm = rest.substring(2, 4).toIntOrNull() ?: return null
        if (hh > 14 || mm > 59) return null
        return sign * (hh * 60 + mm)
    }

    /**
     * Days since 1970-01-01 for a proleptic Gregorian date. Howard Hinnant's civil-from-days
     * algorithm, which is exact for every date this app will ever see and needs no library.
     */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    /** Range of the user-settable manual offset, in hours. */
    val MANUAL_OFFSET_HOURS = -12..12
}
