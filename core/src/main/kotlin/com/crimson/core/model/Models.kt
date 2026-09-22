package com.crimson.core.model

/** The four countries this build keeps. Everything else is discarded at import time. */
enum class Country(val code: String, val displayName: String) {
    US("US", "United States"),
    UK("UK", "United Kingdom"),
    JP("JP", "Japan"),
    KR("KR", "South Korea");

    companion object {
        fun fromCode(code: String?): Country? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * A US broadcast market. [OTHER] means the channel was recognised as a local affiliate but for a
 * market outside the allowed four, and [UNKNOWN] means it looked local but no market could be
 * identified. Both are dropped; they are distinct so `reports/discovery.md` can say which.
 */
enum class Market(val displayName: String) {
    LOS_ANGELES("Los Angeles"),
    NEW_YORK("New York"),
    MIAMI("Miami"),
    TAMPA("Tampa"),
    OTHER("Other market"),
    UNKNOWN("Unidentified market");

    val isAllowedByDefault: Boolean
        get() = this == LOS_ANGELES || this == NEW_YORK || this == MIAMI || this == TAMPA

    companion object {
        val DEFAULT_ALLOWED: Set<Market> = setOf(LOS_ANGELES, NEW_YORK, MIAMI, TAMPA)
    }
}

/** A live category exactly as `get_live_categories` returned it. */
data class RawCategory(
    val categoryId: String,
    val categoryName: String,
)

/** A live stream exactly as `get_live_streams` returned it, before any filtering. */
data class RawChannel(
    val streamId: Long,
    val name: String,
    val categoryId: String?,
    val epgChannelId: String? = null,
    val streamIcon: String? = null,
    val tvArchive: Int = 0,
)

/** What the filter decided about a category, computed once per category and reused per channel. */
data class CategoryVerdict(
    val category: RawCategory,
    val country: Country?,
    /** The category name itself says these are local affiliates. */
    val indicatesLocals: Boolean,
    /** A market named by the category itself, e.g. `US | NEW YORK LOCALS`. */
    val market: Market?,
    /** A country outside the allowlist named by the category, e.g. the `DE` in `DE | SPORT`. */
    val foreignMarker: String? = null,
    val isStreamingService: Boolean = false,
    val evidence: List<String> = emptyList(),
) {
    /**
     * Whether this category can contain a channel the current rules would keep. Categories that
     * fail this are never fetched at all, which is the single biggest saving in the import: a
     * provider with thirty countries has most of its stream list skipped before a byte of it is
     * downloaded.
     *
     * A category with no country marker of any kind still has to be fetched, because its channel
     * names may carry the country individually.
     */
    fun mayContainKeptChannels(rules: FilterRules): Boolean {
        if (isStreamingService) return false
        if (country == null) return foreignMarker == null
        if (country !in rules.countries) return false
        if (country == Country.US && market != null && market !in rules.markets) return false
        return true
    }
}

/** Why a channel was kept or dropped. Carried into the discovery report. */
enum class FilterOutcome {
    KEPT_NATIONAL,
    KEPT_LOCAL,
    DROPPED_COUNTRY_NOT_ALLOWED,
    DROPPED_COUNTRY_UNKNOWN,
    DROPPED_MARKET_NOT_ALLOWED,
    DROPPED_MARKET_UNKNOWN,
    DROPPED_EXCLUDED_KEYWORD,
    ;

    val isKept: Boolean get() = this == KEPT_NATIONAL || this == KEPT_LOCAL
}

/** The filter's decision about one channel. */
data class FilterDecision(
    val outcome: FilterOutcome,
    val country: Country? = null,
    val market: Market? = null,
    /** Human-readable trail of what matched, for the discovery report. Never contains URLs. */
    val evidence: List<String> = emptyList(),
) {
    val isKept: Boolean get() = outcome.isKept
}

/** A channel that survived the filter, ready to be written to the database. */
data class KeptChannel(
    val streamId: Long,
    val originalName: String,
    val displayName: String,
    val country: Country,
    val market: Market?,
    val categoryId: String?,
    val categoryName: String?,
    val epgChannelId: String?,
    val streamIcon: String?,
    val tvArchive: Boolean,
)

/**
 * The user-editable filter rules. Defaults are the rules in the build spec: four countries, and
 * for US locals only the four allowed markets.
 *
 * This is a plain data class with no Android dependency so the whole engine is unit-testable on
 * the JVM. The settings screen persists it through DataStore and hands a copy here.
 */
data class FilterRules(
    val countries: Set<Country> = setOf(Country.US, Country.UK, Country.JP, Country.KR),
    val markets: Set<Market> = Market.DEFAULT_ALLOWED,
    /** Free-text keywords; a channel whose name contains any of them as a token is dropped. */
    val excludeKeywords: List<String> = emptyList(),
) {
    /** Exclusion keywords pre-tokenised once, so the per-channel path does no allocation. */
    val excludePhrases: List<List<String>> =
        excludeKeywords.mapNotNull { kw ->
            com.crimson.core.text.phrase(kw).takeIf { it.isNotEmpty() }
        }

    companion object {
        val DEFAULT = FilterRules()
    }
}

/** A movie (VOD) item from `get_vod_streams`. */
data class RawVodStream(
    val streamId: Long,
    val name: String,
    val categoryId: String?,
    val streamIcon: String? = null,
    val rating: String? = null,
    val containerExtension: String? = null,
)

/** Detailed movie information from `get_vod_info`. */
data class RawVodInfo(
    val streamId: Long,
    val name: String,
    val description: String? = null,
    val duration: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val coverUrl: String? = null,
    val backdropUrl: String? = null,
    val containerExtension: String? = null,
)

/** A TV Series item from `get_series`. */
data class RawSeries(
    val seriesId: Long,
    val name: String,
    val categoryId: String?,
    val cover: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val releaseDate: String? = null,
)

/** One episode in a season from `get_series_info`. */
data class RawEpisode(
    val id: Long,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val containerExtension: String? = null,
    val info: String? = null,
)

/** Detailed series information and episode list from `get_series_info`. */
data class RawSeriesInfo(
    val seriesId: Long,
    val name: String,
    val cover: String? = null,
    val plot: String? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: Map<Int, List<RawEpisode>> = emptyMap(),
)

