package com.crimson.core.subtitles

import com.crimson.core.json.JsonReader
import java.io.Reader
import kotlin.math.abs
import kotlin.math.ln

/** What is being watched, as a subtitle search needs it. */
data class SubtitleQuery(
    val title: String,
    val year: Int?,
    /** `tt0133093` for a film, or the series' id for an episode. */
    val imdbId: String?,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null

    /** The digits of [imdbId], which is how OpenSubtitles takes it. */
    val imdbNumber: String? get() = imdbId?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

/** One subtitle file a service offered. */
data class SubtitleCandidate(
    val source: Source,
    /** OpenSubtitles' file id (what its download call takes), or the addon's subtitle id. */
    val id: String,
    /** A direct link, when the service gives one without a download call. */
    val url: String? = null,
    /** The release or file name the subtitle was made for. */
    val release: String = "",
    val fps: Double? = null,
    val downloads: Int = 0,
    val hearingImpaired: Boolean? = null,
    /** Machine- or AI-translated: never as good as a human's English. */
    val translated: Boolean = false,
    val trusted: Boolean = false,
    /** Matched on the video's own hash, so made for this exact file. */
    val hashMatch: Boolean = false,
    val rating: Double = 0.0,
    /** Where the service ranked it, for services that rank but say nothing else. */
    val position: Int = 0,
) {
    enum class Source(val label: String) { OPENSUBTITLES("OpenSubtitles"), ADDON("OpenSubtitles (via Stremio)") }
}

/**
 * Orders the files a search found, best first.
 *
 * A subtitle is only as good as its timing, and its timing is only right for the release it was
 * made from. A hash match was made from this exact file. Failing that, the most downloaded file is
 * the one most people found in sync, which usually means it fits the most common release — the one
 * an IPTV provider most likely has. A frame rate that matches the video is a strong sign of the
 * same master; the common mismatches (25 against 23.976) are corrected when the file is loaded, so
 * they cost little. Machine translations are last whatever else they have going for them.
 */
object SubtitleRanking {

    fun rank(candidates: List<SubtitleCandidate>, videoFps: Double?, preferSdh: Boolean): List<SubtitleCandidate> =
        candidates.distinctBy { it.source to it.id }.sortedByDescending { score(it, videoFps, preferSdh) }

    fun score(c: SubtitleCandidate, videoFps: Double?, preferSdh: Boolean): Double {
        var s = 0.0
        if (c.hashMatch) s += 1_000
        if (c.translated) s -= 500
        if (c.trusted) s += 30
        s += ln(c.downloads + 1.0) * 22
        s += c.rating * 3
        s -= c.position * 4
        if (videoFps != null && c.fps != null && c.fps > 0) {
            s += when {
                abs(c.fps - videoFps) < 0.02 -> 60.0
                FrameRates.sameSpeed(c.fps, videoFps) -> 20.0
                FrameRates.correction(c.fps, videoFps) != null -> 10.0
                // A rate with no known relation drifts all the way through: worse than any rip.
                else -> -90.0
            }
        }
        // SDH files carry both kinds of text and the dialogue-only reading is made from them, so
        // they are never a bad choice; they are the right one when captions were asked for.
        if (c.hearingImpaired == true && preferSdh) s += 25
        if (RELEASE_BAD.containsMatchIn(c.release)) s -= 60
        return s
    }

    /** Releases whose timing rarely matches a clean copy: camcorder and telesync rips. */
    private val RELEASE_BAD = Regex("""(?i)\b(cam|camrip|hdcam|ts|telesync|hdts|tc|telecine|workprint)\b""")
}

/**
 * Frame rates as they affect subtitle timing.
 *
 * What matters is the speed the film runs at, not the frame rate on the label: a 29.97 fps NTSC
 * transfer of a 23.976 film runs the same length (telecine adds frames, not time), so its
 * subtitles fit. A PAL transfer runs 4% fast at 25 fps, so its subtitles drift a minute and a half
 * behind over a film unless they are stretched back.
 */
object FrameRates {

    /** Rates grouped by the speed the film runs at. */
    private val SPEEDS = mapOf(
        23.976 to 23.976, 29.97 to 23.976, 59.94 to 23.976,
        24.0 to 24.0, 30.0 to 24.0, 60.0 to 24.0,
        25.0 to 25.0, 50.0 to 25.0,
    )

    /** The speed a stated frame rate implies, or null for a rate that is not a standard one. */
    fun speedOf(fps: Double): Double? = SPEEDS.entries.firstOrNull { abs(it.key - fps) < 0.02 }?.value

    /**
     * The factor to multiply a subtitle's times by when it was timed against a copy at
     * [subtitleFps] and the video is at [videoFps], or null when they run at the same speed (or
     * either rate is unknown).
     */
    fun correction(subtitleFps: Double, videoFps: Double): Double? {
        val from = speedOf(subtitleFps) ?: return null
        val to = speedOf(videoFps) ?: return null
        return if (from == to) null else from / to
    }

    fun sameSpeed(a: Double, b: Double): Boolean {
        val x = speedOf(a) ?: return false
        return x == speedOf(b)
    }
}

/**
 * Reads the two services' search answers.
 *
 * OpenSubtitles.com's REST API (`/api/v1/subtitles`) is the real thing: download counts, frame
 * rates, hash matches and translation flags. It needs an API key. The public Stremio addon in
 * front of the same database needs none but says much less; it is the fallback when no key was
 * built in.
 */
object SubtitleResults {

    fun parseOpenSubtitles(reader: Reader): List<SubtitleCandidate> {
        val out = ArrayList<SubtitleCandidate>()
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "data" || json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); continue }
                json.beginArray()
                var position = 0
                while (json.hasNext()) {
                    readOpenSubtitle(json, position)?.let(out::add)
                    position++
                }
                json.endArray()
            }
            json.endObject()
        }
        return out
    }

    private fun readOpenSubtitle(json: JsonReader, position: Int): SubtitleCandidate? {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return null }
        var language: String? = null
        var fileId: String? = null
        var fileName = ""
        var release = ""
        var fps: Double? = null
        var downloads = 0
        var hi: Boolean? = null
        var translated = false
        var trusted = false
        var hash = false
        var rating = 0.0
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != "attributes" || json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "language" -> language = json.nextString()
                    "download_count" -> downloads = json.nextString()?.toDoubleOrNull()?.toInt() ?: 0
                    "hearing_impaired" -> hi = json.nextString() == "true"
                    "fps" -> fps = json.nextString()?.toDoubleOrNull()?.takeIf { it > 0 }
                    "ratings" -> rating = json.nextString()?.toDoubleOrNull() ?: 0.0
                    "from_trusted" -> trusted = json.nextString() == "true"
                    "ai_translated", "machine_translated" -> if (json.nextString() == "true") translated = true
                    "moviehash_match" -> hash = json.nextString() == "true"
                    "release" -> release = json.nextString().orEmpty()
                    "files" -> {
                        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); continue }
                        json.beginArray()
                        while (json.hasNext()) {
                            if (json.peek() != JsonReader.Token.BEGIN_OBJECT || fileId != null) { json.skipValue(); continue }
                            json.beginObject()
                            while (json.hasNext()) {
                                when (json.nextName()) {
                                    "file_id" -> fileId = json.nextString()
                                    "file_name" -> fileName = json.nextString().orEmpty()
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
        }
        json.endObject()
        if (fileId.isNullOrBlank() || !isEnglish(language)) return null
        return SubtitleCandidate(
            source = SubtitleCandidate.Source.OPENSUBTITLES,
            id = fileId!!,
            release = release.ifBlank { fileName },
            fps = fps,
            downloads = downloads,
            hearingImpaired = hi,
            translated = translated,
            trusted = trusted,
            hashMatch = hash,
            rating = rating,
            position = position,
        )
    }

    /** `{"subtitles":[{"id","url","lang":"eng","m":"i|h","fpsMilli",...}]}` */
    fun parseAddon(reader: Reader): List<SubtitleCandidate> {
        val out = ArrayList<SubtitleCandidate>()
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "subtitles" || json.peek() != JsonReader.Token.BEGIN_ARRAY) { json.skipValue(); continue }
                json.beginArray()
                var position = 0
                while (json.hasNext()) {
                    if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
                    var id: String? = null
                    var url: String? = null
                    var lang: String? = null
                    var match: String? = null
                    var release = ""
                    var file = ""
                    var fps: Double? = null
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "id" -> id = json.nextString()
                            "url" -> url = json.nextString()
                            "lang" -> lang = json.nextString()
                            "m" -> match = json.nextString()
                            "movieReleaseName" -> release = json.nextString().orEmpty()
                            "subtitleFileName" -> file = json.nextString().orEmpty()
                            "fpsMilli" -> fps = json.nextString()?.toDoubleOrNull()?.takeIf { it > 0 }?.div(1000.0)
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    if (id != null && !url.isNullOrBlank() && isEnglish(lang)) {
                        out += SubtitleCandidate(
                            source = SubtitleCandidate.Source.ADDON,
                            id = id!!,
                            url = url,
                            release = release.ifBlank { file },
                            fps = fps,
                            hashMatch = match == "h",
                            hearingImpaired = if (HI_NAME.containsMatchIn(file)) true else null,
                            position = position,
                        )
                    }
                    position++
                }
                json.endArray()
            }
            json.endObject()
        }
        return out
    }

    /** OpenSubtitles says `en`; the addon says `eng`. Only English is wanted for now. */
    fun isEnglish(language: String?): Boolean =
        language != null && language.lowercase().let { it == "en" || it == "eng" || it == "en-us" || it == "en-gb" }

    private val HI_NAME = Regex("""(?i)(\bsdh\b|\bhi\b|hearing|\.cc\.)""")
}
