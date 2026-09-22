package com.crimson.core.catalog

/**
 * Orders search results the way a person expects.
 *
 * The database answers `LIKE '%nbc%'`, which is right for recall and useless for order: it
 * returns CNBC, MSNBC and NBC SPORTS BAY AREA alongside NBC, in whatever order the rows happen to
 * be. Someone who typed "nbc", or who picked a game on NBC, wants NBC. So results are scored:
 * an exact name first, then a name that starts with the query as a whole word, then one that
 * contains it as a whole word, and only then a bare substring match. Shorter names break ties,
 * because "NBC" is a better answer than "NBC Sports Philadelphia Plus".
 */
object SearchRank {

    fun score(name: String, query: String): Int {
        val n = normalise(name)
        val q = normalise(query)
        if (q.isEmpty() || n.isEmpty()) return 0
        val words = n.split(' ')
        val qWords = q.split(' ')
        val base = when {
            n == q -> 1000
            n.startsWith("$q ") -> 800
            containsWords(words, qWords) -> 600
            words.any { it.startsWith(q) } -> 400
            n.contains(q) -> 200
            else -> return 0
        }
        return base - n.length.coerceAtMost(100)
    }

    fun <T> rank(items: List<T>, query: String, nameOf: (T) -> String, bonus: (T) -> Int = { 0 }): List<T> =
        items.map { item -> item to score(nameOf(item), query).let { if (it > 0) it + bonus(item) else 0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun containsWords(words: List<String>, qWords: List<String>): Boolean {
        if (qWords.isEmpty() || qWords.size > words.size) return false
        for (i in 0..words.size - qWords.size) {
            if ((qWords.indices).all { words[i + it] == qWords[it] }) return true
        }
        return false
    }

    /** Uppercase, punctuation to spaces, "+" kept because ESPN+ is not ESPN. */
    fun normalise(text: String): String =
        text.uppercase()
            .map { if (it.isLetterOrDigit() || it == '+' || it == '&') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter(String::isNotEmpty)
            .joinToString(" ")
}
