package com.crimson.core.sports

import com.crimson.core.json.JsonReader
import java.io.Reader

/**
 * One live event found in the guide by `tools/events/find_events.py`: a wrestling card on a
 * local station, a rodeo, a high school game, a bowling final. Things worth watching live that
 * no scoreboard lists and that are otherwise buried among thousands of channels.
 *
 * [channelId] is the XMLTV channel id, which for a playlist channel is its `tvg-id`, so an event
 * attaches to the viewer's channels exactly as the guide does.
 */
data class ExtraEvent(
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    /** The event's own name, cleaned of guide decoration ("ᴸᶦᵛᵉ", "(L)"). */
    val title: String,
    /** What it is, in a word or two: "Pro Wrestling", "High School Football", "Rodeo". */
    val kind: String,
    /** The guide says it is broadcast live. */
    val live: Boolean,
    /** A major league or big college game, which the scoreboard tabs already cover. */
    val mainstream: Boolean,
    val description: String = "",
) {
    fun isOn(nowMs: Long): Boolean = nowMs in startMs until endMs
}

/** The file the events job publishes, as the app reads it. */
data class ExtraEventsFile(val generatedAtMs: Long, val events: List<ExtraEvent>)

/**
 * Reads `events.json`: `{"generated": ms, "events": [{"c","s","e","t","k","l","m","d"}, ...]}`.
 *
 * Every field is treated as optional, like ESPN's scoreboard: an event missing its channel or
 * times is dropped rather than failing the file.
 */
object ExtraEventsParser {

    fun parse(reader: Reader): ExtraEventsFile {
        var generated = 0L
        val out = ArrayList<ExtraEvent>()
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return ExtraEventsFile(0L, emptyList())
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "generated" -> generated = runCatching { json.nextLong() }.getOrDefault(0L)
                    "events" -> if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        json.beginArray()
                        while (json.hasNext()) readEvent(json)?.let(out::add)
                        json.endArray()
                    } else json.skipValue()
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        return ExtraEventsFile(generated, out)
    }

    private fun readEvent(json: JsonReader): ExtraEvent? {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return null }
        var channel = ""
        var start = 0L
        var end = 0L
        var title = ""
        var kind = ""
        var live = false
        var mainstream = false
        var desc = ""
        json.beginObject()
        while (json.hasNext()) {
            val name = json.nextName()
            if (json.peek() == JsonReader.Token.NULL) { json.nextNull(); continue }
            when (name) {
                "c" -> channel = json.nextString().orEmpty()
                "s" -> start = runCatching { json.nextLong() }.getOrDefault(0L)
                "e" -> end = runCatching { json.nextLong() }.getOrDefault(0L)
                "t" -> title = json.nextString().orEmpty()
                "k" -> kind = json.nextString().orEmpty()
                "l" -> live = runCatching { json.nextBoolean() }.getOrDefault(false)
                "m" -> mainstream = runCatching { json.nextBoolean() }.getOrDefault(false)
                "d" -> desc = json.nextString().orEmpty()
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (channel.isBlank() || title.isBlank() || start <= 0L || end <= start) return null
        return ExtraEvent(channel, start, end, title.trim(), kind.trim().ifEmpty { "Live Event" }, live, mainstream, desc.trim())
    }
}

/** How the Extra tab lays events out. */
object ExtraEventRows {

    const val DAY = 86_400_000L

    /**
     * Events still worth showing at [nowMs], grouped as the tab reads: on now, then later today,
     * tomorrow and the days after, each in start order. Finished events, and the mainstream ones
     * the scoreboard tabs already have, are left out.
     */
    fun <T> group(
        items: List<T>,
        eventOf: (T) -> ExtraEvent,
        nowMs: Long,
        zoneOffsetOf: (Long) -> Long,
    ): List<Pair<Group, List<T>>> {
        fun dayOf(ms: Long) = Math.floorDiv(ms + zoneOffsetOf(ms), DAY)
        val today = dayOf(nowMs)
        val shown = items.filter { val e = eventOf(it); !e.mainstream && e.endMs > nowMs }
            .sortedWith(compareBy({ eventOf(it).startMs }, { eventOf(it).title }))
        val out = ArrayList<Pair<Group, List<T>>>()
        val onNow = shown.filter { eventOf(it).isOn(nowMs) }
        if (onNow.isNotEmpty()) out += Group.OnNow to onNow
        shown.filter { eventOf(it).startMs > nowMs }
            .groupBy { dayOf(eventOf(it).startMs) - today }
            .toSortedMap()
            .forEach { (offset, list) -> out += Group.Day(offset, eventOf(list.first()).startMs) to list }
        return out
    }

    sealed interface Group {
        data object OnNow : Group
        /** [daysAhead] 0 is later today, 1 tomorrow. [firstStartMs] is for naming other days. */
        data class Day(val daysAhead: Long, val firstStartMs: Long) : Group
    }
}
