package com.crimson.tools

import com.crimson.core.filter.ChannelFilter
import com.crimson.core.filter.ForeignCountries
import com.crimson.core.json.JsonReader
import com.crimson.core.json.XtreamParser
import com.crimson.core.model.CategoryVerdict
import com.crimson.core.model.Country
import com.crimson.core.model.FilterOutcome
import com.crimson.core.model.FilterRules
import com.crimson.core.model.Market
import com.crimson.core.model.RawCategory
import com.crimson.core.model.RawChannel
import com.crimson.core.text.NameCleaner
import com.crimson.core.text.Tokenizer
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Runs the real filter engine against a live Xtream server and writes `reports/discovery.md`.
 *
 * This deliberately uses the same [ChannelFilter] and [XtreamParser] the app ships, rather than a
 * script that reimplements the rules. A report produced by a second implementation would only tell
 * you about the second implementation.
 *
 * Nothing identifying ever reaches the report: no URLs, no host names, no username, no password,
 * no stream links. Channel and category names are provider metadata and are what the report is
 * for, so those do appear.
 *
 * Config comes from `secrets/xtream.json`:
 *
 *     { "host": "http://server:port", "username": "...", "password": "..." }
 *
 * If that file is absent, the tool falls back to the local mock server so the report can still be
 * produced. Which source was used is stated at the top of the report.
 */
object Discover {

    private const val SAMPLES_PER_COUNTRY = 20

    data class Config(
        val host: String,
        val username: String,
        val password: String,
        val isMock: Boolean,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.getOrNull(0) ?: ".").absoluteFile
        val config = loadConfig(root)
        val outFile = File(root, "reports/discovery.md")
        outFile.parentFile.mkdirs()

        println("discover: querying ${if (config.isMock) "the mock server" else "the configured provider"}")

        val filter = ChannelFilter(FilterRules.DEFAULT)
        val report = Report(config.isMock)

        // ---------------------------------------------------------------- categories
        val categories = open(config, mapOf("action" to "get_live_categories")).use {
            XtreamParser.parseCategories(it)
        }
        println("discover: ${categories.size} categories")

        val verdicts = LinkedHashMap<String, CategoryVerdict>()
        for (category in categories) {
            val verdict = filter.classifyCategory(category)
            verdicts[category.categoryId] = verdict
            report.addCategory(verdict)
        }

        // ---------------------------------------------------------------- channels
        // The whole stream list is read once, streaming, so the report can state what is on the
        // server as well as what survives. The app itself never does this: it skips categories
        // that cannot contain a wanted channel, and the saving that produces is measured below.
        val total = open(config, mapOf("action" to "get_live_streams")).use { reader ->
            XtreamParser.streamChannels(reader) { raw ->
                val verdict = raw.categoryId?.let { verdicts[it] }
                val decision = filter.decide(raw, verdict)
                report.addChannel(raw, verdict, decision)
            }
        }
        report.totalOnServer = total
        println("discover: $total channels seen, ${report.kept} kept")

        // ---------------------------------------------------------------- scoring
        if (config.isMock) {
            runCatching { scoreAgainstTruth(config, filter, verdicts, report) }
                .onFailure { println("discover: ground truth unavailable (${it.message})") }
        }

        outFile.writeText(report.render())
        println("discover: wrote ${outFile.path}")
    }

    // ------------------------------------------------------------------ config and http

    private fun loadConfig(root: File): Config {
        val file = File(root, "secrets/xtream.json")
        if (!file.exists()) {
            return Config("http://127.0.0.1:8080", "testuser", "testpass", isMock = true)
        }
        var host = ""
        var user = ""
        var pass = ""
        JsonReader(file.reader()).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "host", "server", "url" -> host = json.nextString().orEmpty()
                    "username", "user" -> user = json.nextString().orEmpty()
                    "password", "pass" -> pass = json.nextString().orEmpty()
                    "port" -> {
                        val p = json.nextString().orEmpty()
                        if (p.isNotBlank() && host.indexOf(':', startIndex = 6) < 0) host = "$host:$p"
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        require(host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
            "secrets/xtream.json must contain host, username and password"
        }
        if (!host.startsWith("http")) host = "http://$host"
        return Config(host.trimEnd('/'), user, pass, isMock = false)
    }

    private fun open(config: Config, params: Map<String, String>, path: String = "/player_api.php"):
        InputStreamReader {
        val query = buildString {
            append("username=").append(enc(config.username))
            append("&password=").append(enc(config.password))
            for ((k, v) in params) append('&').append(k).append('=').append(enc(v))
        }
        val connection = URL("${config.host}$path?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("User-Agent", "crimson-discover/1.0")
        val raw = connection.inputStream
        val stream = if ((connection.contentEncoding ?: "").contains("gzip", ignoreCase = true)) {
            GZIPInputStream(raw)
        } else {
            raw
        }
        return InputStreamReader(stream.buffered(1 shl 16), Charsets.UTF_8)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ scoring

    /**
     * The mock server publishes the country and market it actually generated each channel with.
     * Comparing the filter's verdict against that turns "the kept list looks about right" into
     * precision and recall, and names every channel the filter got wrong.
     */
    private fun scoreAgainstTruth(
        config: Config,
        filter: ChannelFilter,
        verdicts: Map<String, CategoryVerdict>,
        report: Report,
    ) {
        val truth = HashMap<Long, Boolean>()
        val truthMarket = HashMap<Long, String?>()
        val raws = ArrayList<RawChannel>()

        URL("${config.host}/_truth").openStream().buffered(1 shl 16).use { stream ->
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() != "channels") { json.skipValue(); continue }
                    json.beginArray()
                    while (json.hasNext()) {
                        json.beginObject()
                        var id = 0L
                        var name = ""
                        var categoryId: String? = null
                        var kept = false
                        var market: String? = null
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "stream_id" -> id = json.nextLong()
                                "name" -> name = json.nextString().orEmpty()
                                "category_id" -> categoryId = json.nextString()
                                "truth_kept" -> kept = json.nextBoolean()
                                "truth_market" -> market = json.nextString()
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                        truth[id] = kept
                        truthMarket[id] = market
                        raws.add(RawChannel(id, name, categoryId))
                    }
                    json.endArray()
                }
                json.endObject()
            }
        }

        for (raw in raws) {
            val expected = truth[raw.streamId] ?: continue
            val decision = filter.decide(raw, raw.categoryId?.let { verdicts[it] })
            report.score(raw, expected, decision.isKept, truthMarket[raw.streamId], decision.market)
        }
    }

    // ------------------------------------------------------------------ report model

    class Report(private val isMock: Boolean) {

        var totalOnServer = 0
        var kept = 0

        private val categoryRows = ArrayList<String>()
        private var categoriesSkipped = 0
        private var channelsInSkippedCategories = 0

        private val outcomeCounts = LinkedHashMap<FilterOutcome, Int>()
        private val countryCounts = LinkedHashMap<String, Int>()
        private val samples = LinkedHashMap<String, MutableList<String>>()
        private val localRows = ArrayList<String>()
        private val unknownPrefixes = HashMap<String, Int>()
        private val marketCounts = LinkedHashMap<Market, Int>()
        private val skippedCategoryIds = HashSet<String>()

        // scoring
        private var truePositive = 0
        private var falsePositive = 0
        private var trueNegative = 0
        private var falseNegative = 0
        private var marketMismatch = 0
        private val mistakes = ArrayList<String>()

        fun addCategory(v: CategoryVerdict) {
            val country = v.country?.code ?: v.foreignMarker?.let { "other ($it)" } ?: "unknown"
            val locals = when {
                v.indicatesLocals && v.market != null -> "locals, ${v.market!!.displayName}"
                v.indicatesLocals -> "locals, market unknown"
                else -> "-"
            }
            val fetched = v.mayContainKeptChannels(FilterRules.DEFAULT)
            if (!fetched) {
                categoriesSkipped++
                skippedCategoryIds.add(v.category.categoryId)
            }
            categoryRows.add(
                "| ${escape(v.category.categoryName)} | $country | $locals | ${if (fetched) "yes" else "**no**"} |"
            )
        }

        fun addChannel(raw: RawChannel, verdict: CategoryVerdict?, decision: com.crimson.core.model.FilterDecision) {
            outcomeCounts[decision.outcome] = (outcomeCounts[decision.outcome] ?: 0) + 1
            if (raw.categoryId in skippedCategoryIds) channelsInSkippedCategories++

            if (decision.isKept) {
                kept++
                val code = decision.country?.code ?: "?"
                countryCounts[code] = (countryCounts[code] ?: 0) + 1
                val list = samples.getOrPut(code) { ArrayList() }
                if (list.size < SAMPLES_PER_COUNTRY) {
                    list.add("${escape(raw.name)}  ->  ${escape(NameCleaner.clean(raw.name))}")
                }
            }

            decision.market?.let { marketCounts[it] = (marketCounts[it] ?: 0) + 1 }

            val isLocal = decision.outcome == FilterOutcome.KEPT_LOCAL ||
                decision.outcome == FilterOutcome.DROPPED_MARKET_NOT_ALLOWED ||
                decision.outcome == FilterOutcome.DROPPED_MARKET_UNKNOWN
            if (isLocal) {
                val verdictText = when (decision.outcome) {
                    FilterOutcome.KEPT_LOCAL -> "kept, ${decision.market?.displayName}"
                    FilterOutcome.DROPPED_MARKET_NOT_ALLOWED ->
                        "dropped, ${decision.market?.displayName ?: "other market"} not allowed"
                    else -> "dropped, market could not be identified"
                }
                localRows.add(
                    "| ${escape(raw.name)} | ${escape(verdict?.category?.categoryName ?: "-")} | " +
                        "$verdictText | ${escape(decision.evidence.joinToString("; "))} |"
                )
            }

            if (decision.outcome == FilterOutcome.DROPPED_COUNTRY_UNKNOWN) {
                val tokens = Tokenizer.tokenize(raw.name).prefix(2)
                if (tokens.isNotEmpty()) {
                    val key = tokens.first()
                    unknownPrefixes[key] = (unknownPrefixes[key] ?: 0) + 1
                }
            }
        }

        fun score(raw: RawChannel, expected: Boolean, actual: Boolean, expectedMarket: String?, actualMarket: Market?) {
            when {
                expected && actual -> truePositive++
                !expected && !actual -> trueNegative++
                expected && !actual -> {
                    falseNegative++
                    if (mistakes.size < 60) {
                        mistakes.add("| ${escape(raw.name)} | should have been kept | dropped |")
                    }
                }
                else -> {
                    falsePositive++
                    if (mistakes.size < 60) {
                        mistakes.add("| ${escape(raw.name)} | should have been dropped | kept |")
                    }
                }
            }
            if (expected && actual && expectedMarket != null && expectedMarket != "OTHER") {
                val actualName = actualMarket?.name
                if (actualName != null && actualName != expectedMarket) {
                    marketMismatch++
                    if (mistakes.size < 60) {
                        mistakes.add(
                            "| ${escape(raw.name)} | market $expectedMarket | market $actualName |"
                        )
                    }
                }
            }
        }

        fun render(): String = buildString {
            appendLine("# Discovery report")
            appendLine()
            appendLine("Produced by `tools/discover`, which runs the shipping `ChannelFilter` and")
            appendLine("`XtreamParser` against a live server. No URLs, host names or credentials appear")
            appendLine("in this file; channel and category names are provider metadata and are the point")
            appendLine("of the report.")
            appendLine()
            appendLine("Source: **${if (isMock) "mock server (tools/mock-xtream)" else "configured provider"}**")
            appendLine()

            appendLine("## Totals")
            appendLine()
            appendLine("| Measure | Count |")
            appendLine("| --- | ---: |")
            appendLine("| Channels on the server | $totalOnServer |")
            appendLine("| Kept | $kept |")
            appendLine("| Dropped | ${totalOnServer - kept} |")
            for ((outcome, count) in outcomeCounts.entries.sortedByDescending { it.value }) {
                appendLine("| &nbsp;&nbsp;${humanise(outcome)} | $count |")
            }
            appendLine("| Categories | ${categoryRows.size} |")
            appendLine("| Categories never fetched | $categoriesSkipped |")
            appendLine("| Channels avoided by skipping those categories | $channelsInSkippedCategories |")
            appendLine()

            if (truePositive + falsePositive + trueNegative + falseNegative > 0) {
                val precision = if (truePositive + falsePositive == 0) 1.0
                    else truePositive.toDouble() / (truePositive + falsePositive)
                val recall = if (truePositive + falseNegative == 0) 1.0
                    else truePositive.toDouble() / (truePositive + falseNegative)
                appendLine("## Accuracy against generated ground truth")
                appendLine()
                appendLine("The mock server records the country and market it generated each channel with.")
                appendLine("These figures compare the filter's verdict against that.")
                appendLine()
                appendLine("| Measure | Value |")
                appendLine("| --- | ---: |")
                appendLine("| Correctly kept | $truePositive |")
                appendLine("| Correctly dropped | $trueNegative |")
                appendLine("| Wrongly kept | $falsePositive |")
                appendLine("| Wrongly dropped | $falseNegative |")
                appendLine("| Market mis-assigned | $marketMismatch |")
                appendLine("| Precision | ${"%.4f".format(precision)} |")
                appendLine("| Recall | ${"%.4f".format(recall)} |")
                appendLine()
                if (mistakes.isNotEmpty()) {
                    appendLine("### Every disagreement")
                    appendLine()
                    appendLine("| Channel | Expected | Filter said |")
                    appendLine("| --- | --- | --- |")
                    mistakes.forEach { appendLine(it) }
                    appendLine()
                } else {
                    appendLine("No disagreements.")
                    appendLine()
                }
            }

            appendLine("## Kept channels by country")
            appendLine()
            appendLine("| Country | Kept |")
            appendLine("| --- | ---: |")
            for ((code, count) in countryCounts.entries.sortedByDescending { it.value }) {
                appendLine("| $code | $count |")
            }
            appendLine()

            appendLine("## US markets seen")
            appendLine()
            appendLine("| Market | Channels |")
            appendLine("| --- | ---: |")
            for ((market, count) in marketCounts.entries.sortedByDescending { it.value }) {
                appendLine("| ${market.displayName} | $count |")
            }
            appendLine()

            appendLine("## Sample kept channel names, by country")
            appendLine()
            for (code in listOf("US", "UK", "JP", "KR")) {
                val list = samples[code] ?: continue
                appendLine("### $code")
                appendLine()
                appendLine("Original name, then the cleaned display name.")
                appendLine()
                list.forEach { appendLine("- `$it`") }
                appendLine()
            }

            appendLine("## Channels classified as US locals")
            appendLine()
            appendLine("Every channel the local detector fired on, with the market matched or the")
            appendLine("reason it was dropped.")
            appendLine()
            appendLine("| Channel | Category | Verdict | Evidence |")
            appendLine("| --- | --- | --- | --- |")
            localRows.forEach { appendLine(it) }
            appendLine()

            appendLine("## Unrecognised prefixes")
            appendLine()
            appendLine("Leading tokens on channels dropped because no country could be determined.")
            appendLine("A token appearing often here is a naming convention the country lists in")
            appendLine("`CountryDetector` or `ForeignCountries` should learn.")
            appendLine()
            appendLine("| Prefix token | Channels | Already a known foreign marker |")
            appendLine("| --- | ---: | --- |")
            val known = ForeignCountries.allTokens()
            unknownPrefixes.entries.sortedByDescending { it.value }.take(40).forEach { (token, count) ->
                appendLine("| `$token` | $count | ${if (token in known) "yes" else "no"} |")
            }
            appendLine()

            appendLine("## Every category and its classification")
            appendLine()
            appendLine("| Category | Country | Locals | Fetched during import |")
            appendLine("| --- | --- | --- | --- |")
            categoryRows.forEach { appendLine(it) }
            appendLine()
        }

        private fun humanise(outcome: FilterOutcome) = when (outcome) {
            FilterOutcome.KEPT_NATIONAL -> "kept: national"
            FilterOutcome.KEPT_LOCAL -> "kept: allowed local market"
            FilterOutcome.DROPPED_COUNTRY_NOT_ALLOWED -> "dropped: country not in the allowlist"
            FilterOutcome.DROPPED_COUNTRY_UNKNOWN -> "dropped: country could not be determined"
            FilterOutcome.DROPPED_MARKET_NOT_ALLOWED -> "dropped: US local, market not allowed"
            FilterOutcome.DROPPED_MARKET_UNKNOWN -> "dropped: US local, market unidentifiable"
            FilterOutcome.DROPPED_EXCLUDED_KEYWORD -> "dropped: matched an exclusion keyword"
        }

        private fun escape(s: String) = s.replace("|", "\\|").replace("\n", " ")
    }
}
