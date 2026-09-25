package com.crimson.core.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * One caption: shown from [startMs] to [endMs], one line per `\n`.
 *
 * Italics survive parsing as `<i>` and `</i>` (the only markup kept); everything else a subtitle
 * file can carry — fonts, colours, ASS override blocks — is dropped, because the player draws
 * every caption in one legible style of its own.
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String) {
    /** The text without the italic markers, for measuring and for tests. */
    val plain: String get() = text.replace("<i>", "").replace("</i>", "")
}

/** A parsed subtitle file: cues sorted by start time, with a fast lookup by position. */
class SubtitleTrack(cues: List<SubtitleCue>) {

    val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }

    private val starts = LongArray(this.cues.size) { this.cues[it].startMs }

    /** The longest any cue lasts, which bounds how far back [at] has to look for overlaps. */
    private val longest = this.cues.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    val isEmpty: Boolean get() = cues.isEmpty()

    /** When the last caption ends, for telling whether a file belongs to this cut of the film. */
    val lastEndMs: Long get() = cues.maxOfOrNull { it.endMs } ?: 0L

    /** The cues on screen at [positionMs], in start order (overlapping cues stack). */
    fun at(positionMs: Long): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        // Last cue that starts at or before the position, then back over any that still show.
        var hi = starts.binarySearch(positionMs).let { if (it >= 0) lastIndexOfStart(it) else -it - 2 }
        if (hi < 0) return emptyList()
        val out = ArrayList<SubtitleCue>(2)
        while (hi >= 0 && positionMs - starts[hi] <= longest) {
            val cue = cues[hi]
            if (positionMs < cue.endMs) out.add(cue)
            hi--
        }
        out.reverse()
        return out
    }

    private fun lastIndexOfStart(index: Int): Int {
        var i = index
        while (i + 1 < starts.size && starts[i + 1] == starts[index]) i++
        return i
    }

    /** The same captions, retimed: `t' = t * scale + offsetMs`. */
    fun retimed(offsetMs: Long, scale: Double = 1.0): SubtitleTrack =
        if (offsetMs == 0L && scale == 1.0) this
        else SubtitleTrack(cues.map {
            it.copy(startMs = (it.startMs * scale).toLong() + offsetMs, endMs = (it.endMs * scale).toLong() + offsetMs)
        })

    fun map(transform: (SubtitleCue) -> SubtitleCue?): SubtitleTrack = SubtitleTrack(cues.mapNotNull(transform))
}

/**
 * Reads SubRip (`.srt`) and WebVTT, the two formats subtitle services hand out.
 *
 * Written to survive what real files contain rather than what the formats specify: a byte-order
 * mark, Windows-1252 bytes in a file that claims nothing, CRLF or bare CR line ends, a missing
 * blank line between cues, `.` for `,` in the milliseconds, a cue number stuck to the timing line,
 * `{\an8}` positioning blocks and `<font>` tags. A cue that cannot be read is skipped, never the
 * file.
 */
object SubtitleParser {

    fun parse(bytes: ByteArray): SubtitleTrack = parse(decode(bytes))

    fun parse(text: String): SubtitleTrack {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val timing = TIMING.find(lines[i])
            if (timing == null) { i++; continue }
            val start = time(timing.groupValues[1])
            val end = time(timing.groupValues[2])
            i++
            val body = ArrayList<String>(2)
            // The text runs to a blank line, or to the next timing line when the blank is missing
            // (in which case the last line read was that cue's number, not text).
            while (i < lines.size && lines[i].isNotBlank()) {
                if (TIMING.containsMatchIn(lines[i])) {
                    if (body.isNotEmpty() && body.last().trim().all(Char::isDigit)) body.removeAt(body.lastIndex)
                    break
                }
                body.add(lines[i])
                i++
            }
            if (start == null || end == null) continue
            val cleaned = body.mapNotNull { clean(it).takeIf(String::isNotBlank) }
            if (cleaned.isEmpty()) continue
            cues.add(SubtitleCue(start, if (end > start) end else start + DEFAULT_DURATION_MS, balanceItalics(cleaned.joinToString("\n"))))
        }
        return SubtitleTrack(cues)
    }

    /**
     * Bytes to text: a byte-order mark decides when there is one; otherwise UTF-8 if the bytes
     * are valid UTF-8, and Windows-1252 (what an English `.srt` from a Windows tool is) if not.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** `01:02:03,456`, `01:02:03.4`, `02:03.456` (WebVTT may leave the hours out). */
    fun time(text: String): Long? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val (a, b, c, frac) = m.destructured
        val hours = if (c.isEmpty()) 0L else a.toLong()
        val minutes = if (c.isEmpty()) a.toLong() else b.toLong()
        val seconds = if (c.isEmpty()) b.toLong() else c.toLong()
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    private fun clean(line: String): String {
        var s = line
        s = ASS_BLOCK.replace(s, "")
        s = s.replace(ITALIC_OPEN, "<i>").replace(ITALIC_CLOSE, "</i>")
        s = OTHER_TAG.replace(s) { if (it.value == "<i>" || it.value == "</i>") it.value else "" }
        s = s.replace("&amp;", "&").replace("&lt;", "‹").replace("&gt;", "›").replace("&nbsp;", " ").replace("\\N", " ")
        return s.trim()
    }

    /** Italics opened on one line and never closed would slant every caption after it. */
    private fun balanceItalics(text: String): String {
        val open = text.split("<i>").size - 1
        val close = text.split("</i>").size - 1
        return when {
            open > close -> text + "</i>".repeat(open - close)
            close > open -> "<i>".repeat(close - open) + text
            else -> text
        }
    }

    private const val DEFAULT_DURATION_MS = 2_000L
    private val TIMING = Regex("""(\d{1,2}:\d{2}(?::\d{2})?[,.:]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[,.:]\d{1,3})""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?[,.:](\d{1,3})""")
    private val ASS_BLOCK = Regex("""\{\\[^}]*\}""")
    private val ITALIC_OPEN = Regex("""<\s*i\s*>""", RegexOption.IGNORE_CASE)
    private val ITALIC_CLOSE = Regex("""<\s*/\s*i\s*>""", RegexOption.IGNORE_CASE)
    private val OTHER_TAG = Regex("""</?[a-zA-Z][^>]*>""")
}

/**
 * Removes the parts of hearing-impaired captions that are not dialogue: `[door slams]`,
 * `(laughing)`, `♪ ♪`, and the `JOHN:` speaker labels — for a viewer who wants subtitles, not
 * closed captions.
 *
 * Doing this on the app's side means the choice of file never has to be narrowed by it: the best
 * matched file is often the SDH one, and it serves both kinds of viewer.
 */
object SoundDescriptions {

    fun strip(track: SubtitleTrack): SubtitleTrack = track.map { cue ->
        val lines = cue.text.split('\n').mapNotNull(::stripLine)
        if (lines.isEmpty()) return@map null
        // "- Hi.\n- [door opens]" leaves one line that still starts with a speaker dash.
        val text = if (lines.size == 1) lines[0].removePrefix("- ").removePrefix("-").trim() else lines.joinToString("\n")
        if (text.replace("<i>", "").replace("</i>", "").isBlank()) null else cue.copy(text = text)
    }

    private fun stripLine(line: String): String? {
        var s = BRACKETED.replace(line, " ")
        s = SPEAKER.replace(s.trim(), "$1")
        s = s.replace(SPACES, " ").replace("<i> ", "<i>").replace(" </i>", "</i>").replace("<i></i>", "").trim()
        val bare = s.replace("<i>", "").replace("</i>", "").trim()
        if (bare.isEmpty() || bare.all { it in "♪♫#*-–— .,!?" }) return null
        return s
    }

    private val BRACKETED = Regex("""\[[^\]]*\]|\([^)]*\)""")
    private val SPACES = Regex(""" {2,}""")
    /** An upper-case name and a colon at the start of a line, keeping any dash or italic before it. */
    private val SPEAKER = Regex("""^((?:<i>)?-?\s*)[A-Z][A-Z0-9 .'#&-]{1,30}:\s+""")
}
