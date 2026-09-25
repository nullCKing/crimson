package com.crimson.core.skip

import com.crimson.core.catalog.TitleMatcher

/** Whether to skip a show's opening theme, its ending theme, or both; chosen per show. */
data class ThemeSkipChoice(val intro: Boolean = false, val ending: Boolean = false) {
    val any: Boolean get() = intro || ending

    fun encode(show: String): String = "$show|${if (intro) 1 else 0}|${if (ending) 1 else 0}"

    companion object {
        val OFF = ThemeSkipChoice()

        /** The key a show's choice and themes are kept under: its title, reduced as the catalogue reduces titles. */
        fun showKey(seriesName: String): String =
            TitleMatcher.key(seriesName).text.ifEmpty { seriesName.uppercase().filter(Char::isLetterOrDigit) }

        /**
         * The shows the viewer asked for from the start: *The Office* and *South Park* skip their
         * openings, *Hunter × Hunter* its opening and its ending. Everything else is off until
         * switched on in the player's menu.
         */
        fun defaultFor(show: String): ThemeSkipChoice = when {
            "HUNTERXHUNTER" in show || show.startsWith("HUNTERHUNTER") -> ThemeSkipChoice(intro = true, ending = true)
            "SOUTHPARK" in show -> ThemeSkipChoice(intro = true)
            show in OFFICE -> ThemeSkipChoice(intro = true)
            else -> OFF
        }

        private val OFFICE = setOf("OFFICE", "THEOFFICE", "OFFICEUS", "THEOFFICEUS")

        /** Reads "SHOW|1|0" entries, as [encode] writes them. */
        fun decode(entries: Set<String>): Map<String, ThemeSkipChoice> = entries.mapNotNull { e ->
            val parts = e.split('|')
            if (parts.size != 3 || parts[0].isEmpty()) null else parts[0] to ThemeSkipChoice(parts[1] == "1", parts[2] == "1")
        }.toMap()
    }
}
