package com.crimson.core.filter

import com.crimson.core.model.Country
import com.crimson.core.text.Tokens
import com.crimson.core.text.phrase

/**
 * Works out which country a category or channel belongs to from its name.
 *
 * Every match is whole-token (see [com.crimson.core.text.Tokenizer]). Two extra rules keep the
 * broad tokens honest:
 *
 *  - **Negative phrases.** `AMERICA` is listed in the spec as a US token, but `LATIN AMERICA`,
 *    `SOUTH AMERICA` and `AMERICA LATINA` are emphatically not the United States, and `NORTH KOREA`
 *    is not South Korea. A negative phrase vetoes its country for that string.
 *  - **Prefix weighting.** A country token inside the first three tokens is a provider prefix and
 *    is trusted. The same token later in the string is weaker, and is only used once prefix
 *    matching on both the channel and the category name has failed.
 */
object CountryDetector {

    /** Tokens that positively identify a country. Longest phrases are checked first. */
    private val POSITIVE: Map<Country, List<List<String>>> = mapOf(
        Country.US to listOf("UNITED STATES", "USA", "US", "AMERICA").map(::phrase),
        Country.UK to listOf(
            "UNITED KINGDOM", "GREAT BRITAIN", "UK", "GB", "GBR", "BRITAIN", "ENGLAND",
        ).map(::phrase),
        Country.JP to listOf("JAPAN", "JPN", "JP").map(::phrase),
        Country.KR to listOf("SOUTH KOREA", "KOREA", "KOR", "KR", "SK").map(::phrase),
    )

    /**
     * Phrases that veto a country even when a positive token matched. These are the false
     * positives that showed up when the token lists were run against real category names.
     */
    private val NEGATIVE: Map<Country, List<List<String>>> = mapOf(
        Country.US to listOf(
            "LATIN AMERICA", "SOUTH AMERICA", "CENTRAL AMERICA", "AMERICA LATINA",
            "LATINO AMERICA", "AMERICA TV", "AMERICAS",
        ).map(::phrase),
        Country.KR to listOf("NORTH KOREA", "KOREA DPR", "DPRK", "SLOVAKIA", "SLOVAK", "SVK").map(::phrase),
        Country.UK to listOf("NEW ENGLAND").map(::phrase),
        Country.JP to emptyList(),
    )

    /**
     * Tokens weak enough that they are only trusted in prefix position. `US` and `GB` are real
     * English words or abbreviations that turn up mid-title; `USA` or `JAPAN` are not.
     */
    private val PREFIX_ONLY: Set<String> = setOf("US", "GB", "KR", "JP", "AMERICA", "SK")

    /** Result of a single-string lookup. */
    data class Hit(val country: Country, val token: String, val inPrefix: Boolean)

    /**
     * Finds a country in one tokenised string.
     *
     * @param prefixOnly when true, only prefix-position matches count. Used for the first, strict
     *   pass over channel and category names.
     */
    fun detect(tokens: Tokens, prefixOnly: Boolean): Hit? {
        if (tokens.isEmpty()) return null
        var best: Hit? = null
        for ((country, phrases) in POSITIVE) {
            if (isVetoed(country, tokens)) continue
            for (p in phrases) {
                val at = tokens.indexOfPhrase(p)
                if (at < 0) continue
                val inPrefix = at < Tokens.PREFIX_WINDOW
                val token = p.joinToString(" ")
                if (prefixOnly && !inPrefix) continue
                if (!inPrefix && token in PREFIX_ONLY) continue
                val hit = Hit(country, token, inPrefix)
                // Prefer the earliest, longest match: a prefix hit always beats a later one.
                if (best == null || (hit.inPrefix && !best.inPrefix)) best = hit
                break
            }
        }
        return best
    }

    private fun isVetoed(country: Country, tokens: Tokens): Boolean =
        NEGATIVE[country].orEmpty().any { tokens.hasPhrase(it) }

    /**
     * Resolves a channel's country from both its own name and its category's name.
     *
     * Order, most trustworthy first:
     *  1. country prefix on the channel name,
     *  2. country prefix on the category name,
     *  3. country anywhere in the channel name,
     *  4. country anywhere in the category name.
     *
     * Returning null means the country could not be determined, and the spec says to discard
     * those channels.
     */
    fun resolve(channelTokens: Tokens, categoryTokens: Tokens): Hit? =
        detect(channelTokens, prefixOnly = true)
            ?: detect(categoryTokens, prefixOnly = true)
            ?: detect(channelTokens, prefixOnly = false)
            ?: detect(categoryTokens, prefixOnly = false)

    /** Every token the detector would recognise, for the discovery report's "unrecognised" list. */
    fun allKnownTokens(): Set<String> =
        POSITIVE.values.flatten().map { it.joinToString(" ") }.toSet()
}
