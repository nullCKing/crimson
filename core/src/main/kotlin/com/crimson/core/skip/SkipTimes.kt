package com.crimson.core.skip

import com.crimson.core.json.JsonReader
import java.io.StringReader

/** Where an episode's intro and ending are, in milliseconds; an end of null runs to the end. */
data class SkipTimes(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val outroStartMs: Long? = null,
    val outroEndMs: Long? = null,
) {
    val hasIntro: Boolean get() = introEndMs != null
    val hasOutro: Boolean get() = outroStartMs != null

    companion object {
        val NONE = SkipTimes()
    }
}

/**
 * Intro and ending times from the two public, crowd-sourced databases, and which to believe.
 *
 * Both are timed on whatever copy a contributor had, which is not always the copy a provider
 * serves: for *The Office* 2×7 one says the intro is at 0:30 and the other 1:26. So a time is
 * used only when the databases agree to within a few seconds, or only one of them has it; and
 * only until the app has heard the theme for itself (see [ThemeFinder]), after which the sound
 * decides and the databases are not consulted.
 */
object SkipTimesParser {

    /** IntroDB (`api.introdb.app/segments`): `intro`/`outro` objects with `start_ms`/`end_ms`. */
    fun parseIntroDb(json: String): SkipTimes = runCatching {
        var intro: Map<String, Long?>? = null
        var outro: Map<String, Long?>? = null
        JsonReader(StringReader(json)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "intro" -> intro = flat(r)
                    "outro" -> outro = flat(r)
                    else -> r.skipValue()
                }
            }
        }
        SkipTimes(
            introStartMs = intro?.let { it["start_ms"] ?: 0L },
            introEndMs = intro?.get("end_ms"),
            outroStartMs = outro?.get("start_ms"),
            outroEndMs = outro?.get("end_ms"),
        )
    }.getOrDefault(SkipTimes.NONE)

    /**
     * TheIntroDB (`api.theintrodb.org/v3/media`): `intro` and `credits` arrays of
     * `{start_ms, end_ms}`, where a null start means the beginning and a null end the end.
     */
    fun parseTheIntroDb(json: String): SkipTimes = runCatching {
        var intro: Map<String, Long?>? = null
        var credits: Map<String, Long?>? = null
        JsonReader(StringReader(json)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "intro" -> intro = firstOf(r)
                    "credits" -> credits = firstOf(r)
                    else -> r.skipValue()
                }
            }
        }
        val introEnd = intro?.get("end_ms")
        SkipTimes(
            introStartMs = if (introEnd != null) intro?.get("start_ms") ?: 0L else null,
            introEndMs = introEnd,
            outroStartMs = credits?.get("start_ms"),
            outroEndMs = credits?.get("end_ms"),
        )
    }.getOrDefault(SkipTimes.NONE)

    /** What to use: agreement, or the only answer; nothing when they disagree. */
    fun combine(a: SkipTimes, b: SkipTimes, durationMs: Long): SkipTimes {
        val intro = pick(a.introStartMs, a.introEndMs, b.introStartMs, b.introEndMs)
            ?.takeIf { (s, e) -> e - s in MIN_LENGTH_MS..MAX_LENGTH_MS && (durationMs <= 0 || e < durationMs / 2) }
        val outro = pick(a.outroStartMs, a.outroEndMs ?: -1, b.outroStartMs, b.outroEndMs ?: -1)
            ?.let { (s, e) -> s to e.takeIf { it >= 0 } }
            ?.takeIf { (s, e) -> (durationMs <= 0 || s > durationMs / 2) && (e == null || e - s in MIN_LENGTH_MS..MAX_OUTRO_MS) }
        return SkipTimes(intro?.first, intro?.second, outro?.first, outro?.second)
    }

    private fun pick(aStart: Long?, aEnd: Long?, bStart: Long?, bEnd: Long?): Pair<Long, Long>? {
        val a = if (aStart != null && aEnd != null) aStart to aEnd else null
        val b = if (bStart != null && bEnd != null) bStart to bEnd else null
        if (a != null && b != null) {
            val agree = kotlin.math.abs(a.first - b.first) <= AGREE_MS &&
                (a.second < 0 || b.second < 0 || kotlin.math.abs(a.second - b.second) <= AGREE_MS)
            return if (agree) a else null
        }
        return a ?: b
    }

    /** An object of numbers (or nulls), by name; null when the value is not an object. */
    private fun flat(r: JsonReader): Map<String, Long?>? {
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); return null }
        val out = HashMap<String, Long?>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                JsonReader.Token.NUMBER, JsonReader.Token.STRING, JsonReader.Token.NULL ->
                    out[name] = r.nextString()?.toDoubleOrNull()?.toLong()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return out
    }

    /** The first object of an array. */
    private fun firstOf(r: JsonReader): Map<String, Long?>? {
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) { r.skipValue(); return null }
        var first: Map<String, Long?>? = null
        r.beginArray()
        while (r.hasNext()) {
            val o = flat(r)
            if (first == null) first = o
        }
        r.endArray()
        return first
    }

    private const val AGREE_MS = 5_000L
    private const val MIN_LENGTH_MS = 5_000L
    private const val MAX_LENGTH_MS = 150_000L
    private const val MAX_OUTRO_MS = 240_000L
}
