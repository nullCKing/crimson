package com.crimson.core.text

/**
 * Whole-token text matching for provider strings.
 *
 * Xtream providers glue country codes, quality tags and separators onto category and channel
 * names with no consistent delimiter: `US| ESPN`, `|US|ESPN`, `US: ESPN`, `US - ESPN`, `[US] ESPN`,
 * `US ▎ESPN`, `US VIP ESPN`, `USA ESPN HD`.
 *
 * The rule that keeps this from turning into a substring free-for-all is that every match is on a
 * whole token. `LA` must not match inside `LATINO`, and `NY` must not match inside `SONY`.
 *
 * Tokenising:
 *  1. upper-case, folding full-width letters and the superscript `ᴴᴰ` marks to ASCII,
 *  2. runs of letters and digits become tokens; everything else is a boundary,
 *  3. a boundary made of punctuation (`|`, `:`, `-`, `[`, `]`, `▎`, …) is recorded as a *hard*
 *     boundary, while plain whitespace is a soft one,
 *  4. periods are resolved per token: `L.A.` and `U.S.A.` are initialisms and collapse to `LA` and
 *     `USA`, whereas `US.ESPN` splits into two tokens because its parts are multi-letter.
 *
 * Step 3 is what lets a two-letter country code be told apart from an English word. `DE| SKY` has
 * a hard boundary after `DE` and is a German prefix; `IN THE MIX` has a soft one after `IN` and is
 * just a sentence. Without that distinction, every category starting with "In", "It", "At" or "No"
 * would be mistaken for a foreign country and skipped.
 */
object Tokenizer {

    fun tokenize(raw: String?): Tokens {
        if (raw.isNullOrEmpty()) return Tokens.EMPTY

        val tokens = ArrayList<String>(8)
        val hard = ArrayList<Boolean>(8)

        fun commit(text: String, hardBoundary: Boolean) {
            val parts = splitDotted(text)
            for ((i, p) in parts.withIndex()) {
                tokens.add(p)
                // Only the last fragment of a dotted run carries the real boundary; the internal
                // splits of `US.ESPN` are hard boundaries in their own right.
                hard.add(if (i == parts.lastIndex) hardBoundary else true)
            }
        }

        val current = StringBuilder(16)
        // A finished token whose boundary has not been classified yet. The boundary run after a
        // token can mix spaces and punctuation ("DE | SPORT"), so the token is held back until
        // the next one starts and the whole run has been seen.
        var pending: String? = null
        var boundaryHasPunctuation = false

        for (ch in raw) {
            val mapped = fold(ch)
            if (mapped in 'A'..'Z' || mapped in '0'..'9' || mapped == '.') {
                if (current.isEmpty()) {
                    pending?.let { commit(it, boundaryHasPunctuation) }
                    pending = null
                    boundaryHasPunctuation = false
                }
                current.append(mapped)
            } else {
                if (current.isNotEmpty()) {
                    pending = current.toString()
                    current.setLength(0)
                }
                if (mapped != ' ') boundaryHasPunctuation = true
            }
        }
        when {
            current.isNotEmpty() -> commit(current.toString(), false)
            pending != null -> commit(pending, boundaryHasPunctuation)
        }

        return Tokens(tokens, BooleanArray(hard.size) { hard[it] })
    }

    /**
     * Resolves periods inside one piece. `L.A.` -> `LA` (initialism, all fragments single letters),
     * `US.ESPN` -> `US`, `ESPN` (separator), `ST.` -> `ST`.
     */
    private fun splitDotted(text: String): List<String> {
        if (text.indexOf('.') < 0) return listOf(text)
        val parts = text.split('.').filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> emptyList()
            parts.size == 1 -> parts
            parts.all { it.length == 1 } -> listOf(parts.joinToString(""))
            else -> parts
        }
    }

    /** Upper-cases and folds the non-ASCII characters providers use as letters. */
    private fun fold(ch: Char): Char = when {
        ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' -> ch
        ch in 'a'..'z' -> ch.uppercaseChar()
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == ' ' -> ' '
        ch in 'Ａ'..'Ｚ' -> ch - 0xFEE0
        ch in 'ａ'..'ｚ' -> (ch - 0xFEE0).uppercaseChar()
        ch == 'ᴴ' -> 'H'
        ch == 'ᴰ' -> 'D'
        ch == 'ᵁ' -> 'U'
        ch == 'ᴷ' -> 'K'
        ch == 'ᴿ' -> 'R'
        else -> '\u0001' // any other character is punctuation
    }
}

/**
 * A tokenised string. Holds the ordered token list for phrase matching, a set for O(1) single-token
 * lookups, and the hard-boundary flags described in [Tokenizer].
 */
class Tokens(val list: List<String>, private val hardBoundary: BooleanArray = BooleanArray(0)) {

    private val set: Set<String> = if (list.isEmpty()) emptySet() else HashSet(list)

    val size: Int get() = list.size
    fun isEmpty(): Boolean = list.isEmpty()

    /** True when [token] appears as a complete token. */
    fun has(token: String): Boolean = set.contains(token)

    /** True when any of [tokens] appears as a complete token. */
    fun hasAny(tokens: Collection<String>): Boolean {
        for (t in tokens) if (set.contains(t)) return true
        return false
    }

    /**
     * True when the whole word sequence [phrase] appears consecutively.
     * A single-word phrase degrades to [has].
     */
    fun hasPhrase(phrase: List<String>): Boolean = indexOfPhrase(phrase) >= 0

    /** Index of the first token of [phrase], or -1. */
    fun indexOfPhrase(phrase: List<String>): Int {
        if (phrase.isEmpty() || phrase.size > list.size) return -1
        outer@ for (i in 0..list.size - phrase.size) {
            for (j in phrase.indices) {
                if (list[i + j] != phrase[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * True when [phrase] appears within the first [window] tokens. A country code in prefix
     * position is far stronger evidence than the same code buried in the middle of a title.
     */
    fun hasPhraseInPrefix(phrase: List<String>, window: Int = PREFIX_WINDOW): Boolean {
        val at = indexOfPhrase(phrase)
        return at in 0 until window
    }

    /**
     * True when the token at [index] was closed by punctuation rather than whitespace — the
     * signature of a provider prefix such as `DE|`, `FR:` or `[IT]`.
     */
    fun isHardBoundary(index: Int): Boolean =
        index >= 0 && index < hardBoundary.size && hardBoundary[index]

    /** The first [n] tokens, for reporting unrecognised prefixes. */
    fun prefix(n: Int): List<String> = list.take(n)

    override fun toString(): String = list.joinToString(" ")

    companion object {
        /**
         * How many leading tokens count as "prefix position". Three covers `US`, `US VIP`,
         * `US 4K`, and `US FHD` before the channel's actual name starts.
         */
        const val PREFIX_WINDOW = 3

        val EMPTY = Tokens(emptyList())
    }
}

/** Splits a literal like `"SOUTH KOREA"` into the token form phrase matching expects. */
fun phrase(literal: String): List<String> = Tokenizer.tokenize(literal).list
