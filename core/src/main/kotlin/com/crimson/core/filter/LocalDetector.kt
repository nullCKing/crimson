package com.crimson.core.filter

import com.crimson.core.model.Market
import com.crimson.core.text.Tokens

/**
 * Decides whether a US channel is a broadcast local, and if so which market it serves.
 *
 * The asymmetry that drives every choice here: wrongly calling a national channel "local" deletes
 * something the user wanted (ESPN, CNN, HBO), while wrongly calling a local "national" leaves one
 * extra row in the guide. So a local verdict needs real evidence, and the weakest signals only
 * count when something else corroborates them.
 *
 * Signal strength, strongest first:
 *  1. a call sign from a known market list (`KTLA`, `WSVN`) — decisive,
 *  2. an unambiguous city phrase (`LOS ANGELES`, `FORT LAUDERDALE`) — decisive,
 *  3. a call sign matching the generic `[KW]xxx` pattern — needs corroboration,
 *  4. an ambiguous alias (`LA`, `NY`) — needs corroboration,
 *  5. the category saying `LOCALS` / `AFFILIATES` — local, but market unknown.
 */
object LocalDetector {

    data class Result(
        val isLocal: Boolean,
        val market: Market?,
        val evidence: List<String>,
    ) {
        companion object {
            val NOT_LOCAL = Result(false, null, emptyList())
        }
    }

    fun detect(channel: Tokens, category: Tokens): Result {
        val evidence = ArrayList<String>(4)

        val categorySaysLocal = category.hasAny(UsMarkets.CATEGORY_LOCAL_TOKENS)
        if (categorySaysLocal) evidence.add("category names locals")

        val hasNetworkName = UsMarkets.NETWORK_NAMES.any { channel.hasPhrase(it) }
        if (hasNetworkName) evidence.add("network name in channel")

        // A handful of genuine call signs are also ordinary words: WAVE (Louisville), KING
        // (Seattle), WISH (Indianapolis), WOOD (Grand Rapids). Outside a locals category those
        // tokens are far more likely to be the word, and reading "WAVE MUSIC" as a Louisville
        // affiliate would delete a national channel. So word-like call signs only count when the
        // category has already said these are locals.
        val allowWordLikeCallsigns = categorySaysLocal

        // 1. Known call sign for an allowed market. Decisive, and names the market outright.
        findKnownCallsign(channel, allowWordLikeCallsigns)?.let { (sign, market) ->
            evidence.add("call sign $sign")
            return Result(true, market, evidence)
        }
        findKnownCallsign(category, allowWordLikeCallsigns)?.let { (sign, market) ->
            evidence.add("call sign $sign in category")
            return Result(true, market, evidence)
        }

        // 2. Unambiguous city phrase, in either the channel name or the category name.
        findAllowedCity(channel)?.let { (market, city) ->
            evidence.add("city \"$city\"")
            return Result(true, market, evidence)
        }
        findAllowedCity(category)?.let { (market, city) ->
            evidence.add("city \"$city\" in category")
            return Result(true, market, evidence)
        }

        // 3. A call sign or city belonging to a market the user did not ask for.
        findOtherMarket(channel, allowWordLikeCallsigns)?.let {
            evidence.add(it)
            return Result(true, Market.OTHER, evidence)
        }
        findOtherMarket(category, allowWordLikeCallsigns)?.let {
            evidence.add("$it in category")
            return Result(true, Market.OTHER, evidence)
        }

        // 4. A generic call sign the lists do not know. Corroboration required.
        val genericCallsign = findGenericCallsign(channel)
        if (genericCallsign != null && (categorySaysLocal || hasNetworkName ||
                hasCallsignSuffix(channel, genericCallsign))
        ) {
            evidence.add("unlisted call sign $genericCallsign")
            return Result(true, Market.UNKNOWN, evidence)
        }

        // 5. An ambiguous alias such as LA or NY. Only counts with corroboration, and never when
        //    a veto phrase like LA LIGA is present.
        if (!isAmbiguousVetoed(channel)) {
            findAmbiguousCity(channel)?.let { (market, alias) ->
                val corroborated = categorySaysLocal || hasNetworkName || genericCallsign != null
                if (corroborated) {
                    evidence.add("ambiguous alias \"$alias\" with corroboration")
                    return Result(true, market, evidence)
                }
            }
        }

        // 6. The category says these are locals but nothing identifies the market.
        if (categorySaysLocal) {
            return Result(true, Market.UNKNOWN, evidence)
        }

        return Result.NOT_LOCAL
    }

    private fun findKnownCallsign(tokens: Tokens, allowWordLike: Boolean): Pair<String, Market>? {
        for (t in tokens.list) {
            if (!allowWordLike && t in UsMarkets.CALLSIGN_STOPWORDS) continue
            UsMarkets.CALLSIGN_TO_MARKET[t]?.let { return t to it }
        }
        return null
    }

    private fun findAllowedCity(tokens: Tokens): Pair<Market, String>? {
        for ((market, phrases) in UsMarkets.ALLOWED_CITY) {
            for (p in phrases) {
                if (tokens.hasPhrase(p)) return market to p.joinToString(" ")
            }
        }
        return null
    }

    private fun findAmbiguousCity(tokens: Tokens): Pair<Market, String>? {
        for ((market, phrases) in UsMarkets.AMBIGUOUS_CITY) {
            for (p in phrases) {
                if (tokens.hasPhrase(p)) return market to p.joinToString(" ")
            }
        }
        return null
    }

    private fun isAmbiguousVetoed(tokens: Tokens): Boolean =
        UsMarkets.AMBIGUOUS_VETO.any { tokens.hasPhrase(it) }

    private fun findOtherMarket(tokens: Tokens, allowWordLike: Boolean): String? {
        for (t in tokens.list) {
            if (!allowWordLike && t in UsMarkets.CALLSIGN_STOPWORDS) continue
            if (t in UsMarkets.OTHER_CALLSIGNS) return "call sign $t (other market)"
        }
        for (p in UsMarkets.OTHER_CITY) {
            if (tokens.hasPhrase(p)) return "city \"${p.joinToString(" ")}\" (other market)"
        }
        return null
    }

    private fun findGenericCallsign(tokens: Tokens): String? {
        for (t in tokens.list) {
            if (t.length != 4) continue
            if (t in UsMarkets.CALLSIGN_STOPWORDS) continue
            if (UsMarkets.CALLSIGN_PATTERN.matches(t)) return t
        }
        return null
    }

    /** True when the token after [callsign] is a broadcast suffix such as `TV` or `DT`. */
    private fun hasCallsignSuffix(tokens: Tokens, callsign: String): Boolean {
        val i = tokens.list.indexOf(callsign)
        if (i < 0 || i + 1 >= tokens.size) return false
        return tokens.list[i + 1] in UsMarkets.CALLSIGN_SUFFIXES
    }
}
