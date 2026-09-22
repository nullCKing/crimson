package com.crimson.core.catalog

/**
 * Matches a curated list's title to whatever the provider happens to call the same film.
 *
 * A provider's video-on-demand names are not catalogue entries, they are file labels:
 * `EN| The Dark Knight (2008) 4K`, `The Dark Knight [MULTI]`, `Dark Knight, The`. The curated
 * lists, which come from IMDb's published data, hold the plain title and the year. So both sides
 * are reduced to a key that keeps the words and throws away everything a provider adds.
 *
 * The year is kept separately rather than folded into the key, because it is the only thing that
 * tells the 1961 *Parent Trap* from the 1998 one, and a provider very often omits it. So a match
 * on the key alone is accepted, and the year is used to choose between candidates when there are
 * several.
 */
object TitleMatcher {

    /** A title reduced to letters and digits, with a year if the name carried one. */
    data class Key(val text: String, val year: Int?)

    /**
     * The comparison key for a name. Returns an empty [Key.text] for names with nothing usable
     * in them, which callers must skip rather than match on.
     *
     * A year in brackets is read and removed. A trailing year is only removed when a file-name
     * separator put it there — `The.Dark.Knight.2008` — and never when a space did, because
     * `Blade Runner 2049` and `2012` are titles that end in a number. See [variants] for the
     * space-separated case, which cannot be decided from the name alone.
     */
    fun key(rawName: String): Key {
        var name = rawName.trim()

        // "EN| ", "FR - ", "4K| " — the language or packaging label the provider prefixes.
        GROUP_PREFIX.find(name)?.let {
            val rest = name.substring(it.range.last + 1)
            if (rest.isNotBlank()) name = rest
        }

        // A year in brackets, and everything after it: "(2008)", "[2008]".
        var year: Int? = null
        YEAR_BRACKETED.find(name)?.let { m ->
            year = m.groupValues[1].toIntOrNull()
            name = name.removeRange(m.range)
        }
        // "Title.2008" — a separator no title would contain, so the year is packaging.
        if (year == null) {
            YEAR_AFTER_SEPARATOR.find(name)?.let { m ->
                year = m.groupValues[1].toIntOrNull()
                name = name.removeRange(m.range)
            }
        }

        // "Dark Knight, The" — a library's sort order, not a title.
        TRAILING_ARTICLE.find(name)?.let { m ->
            name = m.groupValues[2] + " " + m.groupValues[1]
        }

        return Key(condense(name), year)
    }

    /**
     * Every reading of a name worth indexing.
     *
     * `Blade Runner 2049` is a title; `The Dark Knight 2008` is a title and a year. Nothing in
     * either name says which, so both readings are indexed and the year decides: a curated
     * *Blade Runner* of 1982 will not match the `2049` reading, because [matches] refuses a
     * two-year gap, while a curated *Dark Knight* of 2008 matches its stripped reading exactly.
     */
    fun variants(rawName: String): List<Key> {
        val primary = key(rawName)
        val trailing = YEAR_AFTER_SPACE.find(rawName.trim()) ?: return listOf(primary)
        val year = trailing.groupValues[1].toIntOrNull() ?: return listOf(primary)
        val stripped = key(rawName.trim().removeRange(trailing.range)).copy(year = year)
        return if (stripped.text.isEmpty() || stripped.text == primary.text) listOf(primary)
        else listOf(primary, stripped)
    }

    /**
     * Whether two keys are the same title. Equal text is required; a year is only allowed to
     * *refuse* a match, and only when both sides have one and they are more than a year apart —
     * providers routinely label a film with its release year in one country and its premiere in
     * another.
     */
    fun matches(a: Key, b: Key): Boolean {
        if (a.text.isEmpty() || a.text != b.text) return false
        val ya = a.year
        val yb = b.year
        return ya == null || yb == null || kotlin.math.abs(ya - yb) <= 1
    }

    /**
     * The name as a viewer should see it: the provider's label, year and packaging removed, but
     * the words and their capitalisation left alone.
     *
     * `EN - The Shawshank Redemption (1994) 4K` becomes `The Shawshank Redemption`. This is not
     * the matching key — that one is upper-cased and stripped of punctuation, which is right for
     * comparing and wrong for reading.
     */
    fun displayTitle(rawName: String): String {
        var name = rawName.trim()
        GROUP_PREFIX.find(name)?.let {
            val rest = name.substring(it.range.last + 1)
            if (rest.isNotBlank()) name = rest
        }
        YEAR_BRACKETED.find(name)?.let { name = name.removeRange(it.range) }
        YEAR_AFTER_SEPARATOR.find(name)?.let { name = name.removeRange(it.range) }
        val words = name.split(' ').filter { it.isNotBlank() }
            .filterNot { word ->
                word.trim { !it.isLetterOrDigit() }.uppercase() in NOISE
            }
        val cleaned = words.joinToString(" ").trim { it.isWhitespace() || it in "-|:_." }
        return cleaned.ifBlank { rawName.trim() }
    }

    /** Upper-cased letters and digits, with the provider's packaging words dropped. */
    private fun condense(name: String): String {
        val sb = StringBuilder(name.length)
        for (word in SPLIT.split(name)) {
            if (word.isBlank()) continue
            val upper = word.uppercase()
            if (upper in NOISE) continue
            for (ch in upper) if (ch.isLetterOrDigit()) sb.append(ch)
        }
        val text = sb.toString()
        return if (text.length < 2) "" else text
    }

    /**
     * The label a provider puts in front of a title: `EN| `, `FR - `, `4K: `, `D+- `.
     *
     * It must be a single token — the character class has no space in it — which is what keeps
     * `Mad Max: Fury Road` and `Alien - Covenant` intact: the regex cannot skip a second word to
     * reach the separator. A dash is the riskiest separator, since titles contain them, so it is
     * held to four characters and must be followed by whitespace; `Spider-Man` and `X-Men` have
     * neither a short enough prefix nor a space after the dash.
     */
    private val GROUP_PREFIX = Regex("""^\s*[A-Za-z0-9+&]{1,8}\s*[|:]\s*|^\s*[A-Za-z0-9+&]{1,4}\s*-\s+""")
    private val YEAR_BRACKETED = Regex("""[\[(](19\d{2}|20\d{2})[\])].*$""")
    private val YEAR_AFTER_SEPARATOR = Regex("""[._-](19\d{2}|20\d{2})\s*$""")
    private val YEAR_AFTER_SPACE = Regex("""\s(19\d{2}|20\d{2})\s*$""")
    private val TRAILING_ARTICLE = Regex("""^(.*?),\s*(The|A|An)\s*$""", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("""[\s\-_.:|/\\\[\]()]+""")

    /**
     * Words a provider adds that are not part of any title. Deliberately short: every word here
     * is one that can never be the whole of a title's meaning, and anything longer risks eating
     * a real word — `HDR` is packaging, but `HD` on its own could be the film *HD*, so the rule
     * is that these only ever drop *whole* words, never substrings.
     */
    private val NOISE = setOf(
        "HD", "SD", "FHD", "UHD", "4K", "8K", "HDR", "HEVC", "H264", "H265", "X264", "X265",
        "1080P", "1080", "720P", "720", "480P", "2160P", "MULTI", "DUAL", "SUB", "SUBS",
        "DUBBED", "VOSTFR", "TRUEFRENCH", "REMUX", "BLURAY", "WEBRIP", "WEB", "DL", "RIP",
        "IMAX", "EXTENDED", "UNRATED", "REMASTERED", "AAC", "AC3", "DTS", "ATMOS",
    )
}
