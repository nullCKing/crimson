package com.crimson.core.filter

import com.crimson.core.model.CategoryVerdict
import com.crimson.core.model.Country
import com.crimson.core.model.FilterDecision
import com.crimson.core.model.FilterOutcome
import com.crimson.core.model.FilterRules
import com.crimson.core.model.KeptChannel
import com.crimson.core.model.Market
import com.crimson.core.model.RawCategory
import com.crimson.core.model.RawChannel
import com.crimson.core.text.NameCleaner
import com.crimson.core.text.Tokenizer
import com.crimson.core.text.Tokens

/**
 * The filter engine. Pure Kotlin, no Android types, no I/O — the import pipeline feeds channels
 * through it one at a time as they are read off the wire, and only survivors are ever allocated
 * into the database.
 *
 * Usage:
 * ```
 * val filter = ChannelFilter(rules)
 * val verdict = filter.classifyCategory(category)
 * if (!verdict.mayContainKeptChannels(rules)) return   // category never fetched at all
 * for (raw in streamingParser) {
 *     val kept = filter.evaluate(raw, verdict) ?: continue
 *     batch.add(kept)
 * }
 * ```
 */
class ChannelFilter(val rules: FilterRules = FilterRules.DEFAULT) {

    /** Category classification is done once and reused for every channel in that category. */
    fun classifyCategory(category: RawCategory): CategoryVerdict {
        val tokens = Tokenizer.tokenize(category.categoryName)
        val evidence = ArrayList<String>(3)

        val countryHit = CountryDetector.detect(tokens, prefixOnly = true)
            ?: CountryDetector.detect(tokens, prefixOnly = false)
        countryHit?.let {
            evidence.add("country ${it.country.code} from \"${it.token}\"" +
                if (it.inPrefix) " (prefix)" else "")
        }

        val foreign = if (countryHit == null) ForeignCountries.detect(tokens) else null
        foreign?.let { evidence.add("foreign country marker \"$it\": category skipped") }

        val local = if (countryHit?.country == Country.US || (countryHit == null && foreign == null)) {
            LocalDetector.detect(Tokens.EMPTY, tokens)
        } else {
            LocalDetector.Result.NOT_LOCAL
        }
        if (local.isLocal) evidence.addAll(local.evidence)

        val isStreaming = StreamingServiceDetector.isStreamingService(category.categoryName)
        if (isStreaming) evidence.add("streaming service: category skipped")

        return CategoryVerdict(
            category = category,
            country = countryHit?.country,
            indicatesLocals = local.isLocal,
            market = local.market.takeIf { local.isLocal && it != Market.UNKNOWN },
            foreignMarker = foreign,
            isStreamingService = isStreaming,
            evidence = evidence,
        )
    }

    /**
     * Evaluates one channel. Returns null when it is dropped.
     *
     * [decide] carries the same logic but reports why, which is what the discovery tool needs.
     */
    fun evaluate(raw: RawChannel, categoryVerdict: CategoryVerdict?): KeptChannel? {
        val decision = decide(raw, categoryVerdict)
        if (!decision.isKept) return null
        val country = decision.country ?: return null
        return KeptChannel(
            streamId = raw.streamId,
            originalName = raw.name,
            displayName = NameCleaner.clean(raw.name),
            country = country,
            market = decision.market,
            categoryId = raw.categoryId,
            categoryName = categoryVerdict?.category?.categoryName,
            epgChannelId = raw.epgChannelId?.takeIf { it.isNotBlank() },
            streamIcon = raw.streamIcon?.takeIf { it.isNotBlank() },
            tvArchive = raw.tvArchive != 0,
        )
    }

    fun decide(raw: RawChannel, categoryVerdict: CategoryVerdict?): FilterDecision {
        val channelTokens = Tokenizer.tokenize(raw.name)
        val categoryTokens = categoryVerdict
            ?.let { Tokenizer.tokenize(it.category.categoryName) }
            ?: Tokens.EMPTY

        if (StreamingServiceDetector.isStreamingService(raw.name) ||
            StreamingServiceDetector.isStreamingService(categoryVerdict?.category?.categoryName)) {
            return FilterDecision(
                FilterOutcome.DROPPED_EXCLUDED_KEYWORD,
                evidence = listOf("dropped streaming service"),
            )
        }

        // Free-text exclusions run first: they are the user's explicit veto.
        for (p in rules.excludePhrases) {
            if (channelTokens.hasPhrase(p)) {
                return FilterDecision(
                    FilterOutcome.DROPPED_EXCLUDED_KEYWORD,
                    evidence = listOf("excluded by keyword \"${p.joinToString(" ")}\""),
                )
            }
        }

        val evidence = ArrayList<String>(4)

        val hit = CountryDetector.resolve(channelTokens, categoryTokens)
            ?: return dropWithoutCountry(channelTokens, categoryTokens)
        evidence.add("country ${hit.country.code} from \"${hit.token}\"" +
            if (hit.inPrefix) " (prefix)" else " (inline)")

        if (hit.country !in rules.countries) {
            return FilterDecision(
                FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED,
                country = hit.country,
                evidence = evidence,
            )
        }

        // The local rules apply to US channels only. A UK, JP or KR channel is kept on country
        // alone, which is what the spec asks for.
        if (hit.country != Country.US) {
            return FilterDecision(FilterOutcome.KEPT_NATIONAL, hit.country, null, evidence)
        }

        val local = LocalDetector.detect(channelTokens, categoryTokens)
        if (!local.isLocal) {
            evidence.add("no local signal: treated as national US")
            return FilterDecision(FilterOutcome.KEPT_NATIONAL, Country.US, null, evidence)
        }
        evidence.addAll(local.evidence)

        return when (val market = local.market) {
            null, Market.UNKNOWN -> FilterDecision(
                FilterOutcome.DROPPED_MARKET_UNKNOWN, Country.US, Market.UNKNOWN, evidence,
            )
            Market.OTHER -> FilterDecision(
                FilterOutcome.DROPPED_MARKET_NOT_ALLOWED, Country.US, Market.OTHER, evidence,
            )
            else ->
                if (market in rules.markets) {
                    FilterDecision(FilterOutcome.KEPT_LOCAL, Country.US, market, evidence)
                } else {
                    FilterDecision(
                        FilterOutcome.DROPPED_MARKET_NOT_ALLOWED, Country.US, market, evidence,
                    )
                }
        }
    }

    /**
     * No allowlisted country matched. Separating "this is Germany" from "no idea what this is"
     * costs nothing here and makes the discovery report far more useful: the unknown pile is then
     * exactly the set of naming conventions the token lists still need to learn.
     */
    private fun dropWithoutCountry(channel: Tokens, category: Tokens): FilterDecision {
        val foreign = ForeignCountries.detect(channel) ?: ForeignCountries.detect(category)
        return if (foreign != null) {
            FilterDecision(
                FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED,
                evidence = listOf("foreign country marker \"$foreign\""),
            )
        } else {
            FilterDecision(
                FilterOutcome.DROPPED_COUNTRY_UNKNOWN,
                evidence = listOf("no country token in channel or category name"),
            )
        }
    }
}
