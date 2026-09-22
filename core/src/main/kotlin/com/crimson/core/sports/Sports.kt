package com.crimson.core.sports

import com.crimson.core.json.JsonReader
import java.io.Reader
import java.time.OffsetDateTime

/** A league the Sports page follows, and where ESPN's public scoreboard keeps it. */
data class League(
    val id: String,
    val name: String,
    /** The short label on a chip or a card: "NFL", "EPL". */
    val label: String,
    /** The path segment under `site.api.espn.com/apis/site/v2/sports/`. */
    val path: String,
)

object Leagues {
    val NFL = League("nfl", "NFL", "NFL", "football/nfl")
    val NCAAF = League("ncaaf", "College Football", "NCAAF", "football/college-football")
    val NBA = League("nba", "NBA", "NBA", "basketball/nba")
    val WNBA = League("wnba", "WNBA", "WNBA", "basketball/wnba")
    val NCAAM = League("ncaam", "College Basketball", "NCAAM", "basketball/mens-college-basketball")
    val MLB = League("mlb", "MLB", "MLB", "baseball/mlb")
    val NHL = League("nhl", "NHL", "NHL", "hockey/nhl")
    val MLS = League("mls", "MLS", "MLS", "soccer/usa.1")
    val EPL = League("epl", "Premier League", "EPL", "soccer/eng.1")
    val UCL = League("ucl", "Champions League", "UCL", "soccer/uefa.champions")
    val LALIGA = League("laliga", "LaLiga", "LALIGA", "soccer/esp.1")
    val BUNDESLIGA = League("bundesliga", "Bundesliga", "BUND", "soccer/ger.1")
    val SERIEA = League("seriea", "Serie A", "SERIE A", "soccer/ita.1")
    val LIGAMX = League("ligamx", "Liga MX", "LIGA MX", "soccer/mex.1")

    val ALL = listOf(NFL, NCAAF, NBA, MLB, NHL, WNBA, NCAAM, MLS, EPL, UCL, LALIGA, BUNDESLIGA, SERIEA, LIGAMX)

    fun byId(id: String): League? = ALL.firstOrNull { it.id == id }
}

enum class EventState { UPCOMING, LIVE, FINAL }

data class Competitor(
    val name: String,
    val shortName: String,
    val abbreviation: String,
    val logoUrl: String?,
    val score: String?,
    /** Team colour as ESPN gives it, six hex digits without the hash. */
    val color: String?,
    val record: String?,
    val isHome: Boolean,
    val isWinner: Boolean,
)

data class SportsEvent(
    val id: String,
    val league: League,
    val startMs: Long,
    val name: String,
    val shortName: String,
    val state: EventState,
    /** "Q3 5:21", "Final/OT", "Sun, September 21st at 1:00 PM EDT", as ESPN phrases it. */
    val statusText: String,
    val home: Competitor?,
    val away: Competitor?,
    /** National TV first, then everything else ESPN lists, deduplicated. */
    val networks: List<String>,
    val venue: String?,
) {
    val network: String? get() = networks.firstOrNull()
}

/**
 * Reads ESPN's public scoreboard JSON (`/apis/site/v2/sports/{sport}/{league}/scoreboard`).
 *
 * The endpoint is unauthenticated and free, which is why it was chosen, and it is also
 * undocumented, which is why this parser takes only the handful of fields it needs and treats
 * every one of them as optional. A shape change should cost a card its score, not the page.
 */
object EspnScoreboard {

    fun parse(reader: Reader, league: League): List<SportsEvent> {
        val out = ArrayList<SportsEvent>()
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "events" && json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                    json.beginArray()
                    while (json.hasNext()) readEvent(json, league)?.let(out::add)
                    json.endArray()
                } else {
                    json.skipValue()
                }
            }
            json.endObject()
        }
        return out
    }

    private class Status(var state: String? = null, var shortDetail: String? = null, var detail: String? = null)

    private fun readEvent(json: JsonReader, league: League): SportsEvent? {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return null }
        var id = ""
        var date: String? = null
        var name = ""
        var shortName = ""
        val competitors = ArrayList<Competitor>(2)
        val national = LinkedHashSet<String>()
        val other = LinkedHashSet<String>()
        val status = Status()
        var venue: String? = null

        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "id" -> id = json.nextString().orEmpty()
                "date" -> date = json.nextString()
                "name" -> name = json.nextString().orEmpty()
                "shortName" -> shortName = json.nextString().orEmpty()
                "status" -> readStatus(json, status)
                "competitions" -> {
                    if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); continue }
                    json.beginArray()
                    var first = true
                    while (json.hasNext()) {
                        if (!first || json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
                        first = false
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "competitors" -> readCompetitors(json, competitors)
                                "broadcasts" -> readBroadcasts(json, national, other)
                                "geoBroadcasts" -> readGeoBroadcasts(json, national, other)
                                "status" -> readStatus(json, status)
                                "venue" -> venue = readVenue(json)
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()

        val start = date?.let(::parseTime) ?: return null
        if (id.isEmpty()) return null
        val state = when (status.state) {
            "in" -> EventState.LIVE
            "post" -> EventState.FINAL
            else -> EventState.UPCOMING
        }
        return SportsEvent(
            id = id,
            league = league,
            startMs = start,
            name = name,
            shortName = shortName,
            state = state,
            statusText = status.shortDetail ?: status.detail ?: "",
            home = competitors.firstOrNull { it.isHome },
            away = competitors.firstOrNull { !it.isHome },
            networks = (national + other).toList(),
            venue = venue,
        )
    }

    private fun readStatus(json: JsonReader, into: Status) {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return }
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "type" && json.peek() == JsonReader.Token.BEGIN_OBJECT) {
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "state" -> into.state = json.nextString()
                        "shortDetail" -> into.shortDetail = json.nextString()
                        "detail" -> into.detail = json.nextString()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
            } else {
                json.skipValue()
            }
        }
        json.endObject()
    }

    private fun readVenue(json: JsonReader): String? {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return null }
        var name: String? = null
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "fullName") name = json.nextString() else json.skipValue()
        }
        json.endObject()
        return name
    }

    private fun readCompetitors(json: JsonReader, into: MutableList<Competitor>) {
        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); return }
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
            var home = false
            var winner = false
            var score: String? = null
            var record: String? = null
            var name = ""
            var short = ""
            var abbr = ""
            var logo: String? = null
            var color: String? = null
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "homeAway" -> home = json.nextString() == "home"
                    "winner" -> winner = json.nextString() == "true"
                    "score" -> score = json.nextString()
                    "records" -> record = readFirstRecord(json)
                    // Soccer, tennis and golf put a person where a team would be.
                    "team", "athlete" -> {
                        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "displayName" -> name = json.nextString().orEmpty()
                                "shortDisplayName" -> short = json.nextString().orEmpty()
                                "abbreviation" -> abbr = json.nextString().orEmpty()
                                "logo" -> logo = json.nextString()
                                "color" -> color = json.nextString()
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
            into += Competitor(
                name = name,
                shortName = short.ifEmpty { name },
                abbreviation = abbr.ifEmpty { short.take(4).uppercase() },
                logoUrl = logo,
                score = score,
                color = color,
                record = record,
                isHome = home,
                isWinner = winner,
            )
        }
        json.endArray()
    }

    private fun readFirstRecord(json: JsonReader): String? {
        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); return null }
        var summary: String? = null
        json.beginArray()
        while (json.hasNext()) {
            if (summary != null || json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "summary") summary = json.nextString() else json.skipValue()
            }
            json.endObject()
        }
        json.endArray()
        return summary
    }

    private fun readBroadcasts(json: JsonReader, national: MutableSet<String>, other: MutableSet<String>) {
        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); return }
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
            var market: String? = null
            val names = ArrayList<String>(2)
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "market" -> market = json.nextString()
                    "names" -> {
                        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); continue }
                        json.beginArray()
                        while (json.hasNext()) json.nextString()?.takeIf { it.isNotBlank() }?.let(names::add)
                        json.endArray()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (market.equals("national", ignoreCase = true)) national += names else other += names
        }
        json.endArray()
    }

    private fun readGeoBroadcasts(json: JsonReader, national: MutableSet<String>, other: MutableSet<String>) {
        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); return }
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
            var market: String? = null
            var media: String? = null
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "market" -> market = readField(json, "type")
                    "media" -> media = readField(json, "shortName")
                    else -> json.skipValue()
                }
            }
            json.endObject()
            val m = media?.takeIf { it.isNotBlank() } ?: continue
            if (market.equals("national", ignoreCase = true)) national += m else other += m
        }
        json.endArray()
    }

    private fun readField(json: JsonReader, field: String): String? {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return null }
        var value: String? = null
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == field) value = json.nextString() else json.skipValue()
        }
        json.endObject()
        return value
    }

    /** ESPN writes `2026-09-18T00:15Z`, without seconds; ISO offset parsing accepts that. */
    fun parseTime(text: String): Long? =
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * What to search the live channels for when a game is selected.
 *
 * The network is what finds the channel: "NBC" lands on the viewer's NBC. A few broadcasters are
 * streaming services, which providers carry as event channels under a shorter name, so those are
 * trimmed to the word the channel list will actually contain. With no network at all, the home
 * team's name is the next best thing, because providers name event channels after the teams.
 */
object BroadcastSearch {

    private val ALIASES = mapOf(
        "PRIME VIDEO" to "Prime",
        "AMAZON PRIME VIDEO" to "Prime",
        "PEACOCK" to "Peacock",
        "PARAMOUNT+" to "Paramount",
        "APPLE TV" to "Apple",
        "APPLE TV+" to "Apple",
        "MLS SEASON PASS" to "MLS",
        "ESPN+" to "ESPN+",
        "ESPN DEPORTES" to "ESPN Deportes",
        "NFL NET" to "NFL Network",
        "NFL NETWORK" to "NFL Network",
        "CBSSN" to "CBS Sports",
        "CBS SPORTS NETWORK" to "CBS Sports",
        "FS1" to "FS1",
        "FS2" to "FS2",
        "BTN" to "Big Ten",
        "BIG TEN NETWORK" to "Big Ten",
        "SECN" to "SEC Network",
        "SEC NETWORK" to "SEC Network",
        "ACCN" to "ACC Network",
        "ACC NETWORK" to "ACC Network",
        "USA NET" to "USA Network",
        "TRUTV" to "truTV",
        "NHL NET" to "NHL Network",
        "MLB NET" to "MLB Network",
        "NBA TV" to "NBA TV",
        "UNIVERSO" to "Universo",
        "TELEMUNDO" to "Telemundo",
        "UNIVISION" to "Univision",
        "TUDN" to "TUDN",
    )

    /** Streaming-only broadcasters that are worth less than a regular channel when both are listed. */
    private val STREAMING = setOf("ESPN+", "PEACOCK", "PARAMOUNT+", "PRIME VIDEO", "APPLE TV", "APPLE TV+", "MLS SEASON PASS", "NFL+", "DAZN")

    fun termFor(event: SportsEvent): String {
        val preferred = event.networks.firstOrNull { it.uppercase() !in STREAMING } ?: event.networks.firstOrNull()
        if (preferred != null) return normalise(preferred)
        return event.home?.shortName?.takeIf { it.isNotBlank() }
            ?: event.home?.name?.takeIf { it.isNotBlank() }
            ?: event.shortName
    }

    fun normalise(network: String): String =
        ALIASES[network.trim().uppercase()] ?: network.trim()
}
