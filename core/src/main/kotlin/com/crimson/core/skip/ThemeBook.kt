package com.crimson.core.skip

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * How one episode's first ten and last six minutes sounded, as [ChromaMeter] frames placed by
 * where they are in the video. Seeking and skipping leave gaps, which stay zeros and match
 * nothing; hearing a stretch twice overwrites it.
 */
class EpisodePrint(val episodeId: Long, val durationMs: Long) {

    val intro = ByteArray(INTRO_FRAMES * ThemePrints.BINS)
    val outro = ByteArray(OUTRO_FRAMES * ThemePrints.BINS)
    private val introHeard = BooleanArray(INTRO_FRAMES)
    private val outroHeard = BooleanArray(OUTRO_FRAMES)

    /** Where the closing window starts in the video; nothing is recorded there until the duration is known. */
    val outroStartMs: Long get() = (durationMs - OUTRO_WINDOW_MS).coerceAtLeast(INTRO_WINDOW_MS)

    fun record(videoMs: Long, frame: ByteArray) {
        if (videoMs < 0) return
        val introSlot = (videoMs / ThemePrints.FRAME_MS).toInt()
        if (introSlot < INTRO_FRAMES) {
            frame.copyInto(intro, introSlot * ThemePrints.BINS)
            introHeard[introSlot] = true
        }
        if (durationMs > INTRO_WINDOW_MS && videoMs >= outroStartMs) {
            val outroSlot = ((videoMs - outroStartMs) / ThemePrints.FRAME_MS).toInt()
            if (outroSlot < OUTRO_FRAMES) {
                frame.copyInto(outro, outroSlot * ThemePrints.BINS)
                outroHeard[outroSlot] = true
            }
        }
    }

    val introHeardMs: Long get() = introHeard.count { it } * ThemePrints.FRAME_MS
    val outroHeardMs: Long get() = outroHeard.count { it } * ThemePrints.FRAME_MS

    fun write(out: OutputStream) {
        val data = DataOutputStream(out)
        data.writeInt(MAGIC)
        data.writeLong(episodeId)
        data.writeLong(durationMs)
        data.write(intro); writeBits(data, introHeard)
        data.write(outro); writeBits(data, outroHeard)
        data.flush()
    }

    companion object {
        const val INTRO_WINDOW_MS = 600_000L
        const val OUTRO_WINDOW_MS = 360_000L
        val INTRO_FRAMES = (INTRO_WINDOW_MS / ThemePrints.FRAME_MS).toInt()
        val OUTRO_FRAMES = (OUTRO_WINDOW_MS / ThemePrints.FRAME_MS).toInt()
        private const val MAGIC = 0x43455031 // "CEP1"

        fun read(input: InputStream): EpisodePrint? = runCatching {
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return null
            val print = EpisodePrint(data.readLong(), data.readLong())
            data.readFully(print.intro); readBits(data, print.introHeard)
            data.readFully(print.outro); readBits(data, print.outroHeard)
            print
        }.getOrNull()

        private fun writeBits(out: DataOutputStream, bits: BooleanArray) {
            for (b in bits) out.writeBoolean(b)
        }

        private fun readBits(input: DataInputStream, bits: BooleanArray) {
            for (i in bits.indices) bits[i] = input.readBoolean()
        }
    }
}

/** A theme song as the app heard it: an opening or an ending, and its frames. */
class Theme(val kind: Kind, val frames: ByteArray, val learnedAt: Long) {
    enum class Kind { INTRO, OUTRO }

    val lengthMs: Long get() = ThemePrints.frames(frames) * ThemePrints.FRAME_MS
}

/**
 * The theme songs of one show, learned by comparing the episodes watched.
 *
 * Two episodes are enough: whatever stretch of at least twelve seconds of music the first ten
 * minutes of both have in common is the opening, and the same for the last six minutes and the
 * ending. A show can have several (a new season's arrangement, an anime's ending changing with
 * each arc); each is learned the same way when it first turns up in two episodes.
 */
object ThemeLearner {

    /** Enough of an episode's opening minutes heard to be worth comparing with. */
    const val MIN_HEARD_MS = 45_000L
    const val MAX_THEMES = 8

    /**
     * Themes in [latest] that another of [others] shares and [known] does not have yet, or has
     * only part of: a theme first learned from episodes where some of it was skipped over is
     * replaced by a fuller hearing of it (see [merge]).
     */
    fun learn(latest: EpisodePrint, others: List<EpisodePrint>, known: List<Theme>, now: Long): List<Theme> {
        val found = ArrayList<Theme>()
        for (other in others) {
            if (other.episodeId == latest.episodeId) continue
            for (kind in Theme.Kind.entries) {
                val a = section(latest, kind) ?: continue
                val b = section(other, kind) ?: continue
                for (stretch in ThemeFinder.common(a, b)) {
                    val frames = a.copyOfRange(stretch.aStart * ThemePrints.BINS, (stretch.aStart + stretch.length) * ThemePrints.BINS)
                    if (ThemePrints.tonalFraction(frames) < MIN_TONAL) continue
                    val fuller = ThemePrints.frames(frames) - FULLER_BY
                    if ((known + found).any { it.kind == kind && ThemePrints.frames(it.frames) >= fuller && same(it.frames, frames) }) continue
                    found += Theme(kind, frames, now)
                }
            }
        }
        return found
    }

    /** [known] with [learned] added, each replacing any shorter hearing of the same song. */
    fun merge(known: List<Theme>, learned: List<Theme>): List<Theme> =
        known.filterNot { k -> learned.any { it.kind == k.kind && same(it.frames, k.frames) } } + learned

    private fun section(print: EpisodePrint, kind: Theme.Kind): ByteArray? = when (kind) {
        Theme.Kind.INTRO -> print.intro.takeIf { print.introHeardMs >= MIN_HEARD_MS }
        Theme.Kind.OUTRO -> print.outro.takeIf { print.outroHeardMs >= MIN_HEARD_MS }
    }

    /** Whether two themes are (mostly) the same music: a long enough stretch in common. */
    fun same(a: ByteArray, b: ByteArray): Boolean {
        val shorter = minOf(ThemePrints.frames(a), ThemePrints.frames(b))
        return ThemeFinder.common(a, b, minFrames = (shorter / 2).coerceAtLeast(ThemeFinder.MIN_FRAMES / 2)).isNotEmpty()
    }

    fun write(themes: List<Theme>, out: OutputStream) {
        val data = DataOutputStream(out)
        data.writeInt(BOOK_MAGIC)
        data.writeInt(themes.size)
        for (t in themes) {
            data.writeByte(t.kind.ordinal)
            data.writeLong(t.learnedAt)
            data.writeInt(t.frames.size)
            data.write(t.frames)
        }
        data.flush()
    }

    fun read(input: InputStream): List<Theme> = runCatching {
        val data = DataInputStream(input)
        if (data.readInt() != BOOK_MAGIC) return emptyList()
        List(data.readInt()) {
            val kind = Theme.Kind.entries[data.readByte().toInt()]
            val at = data.readLong()
            val frames = ByteArray(data.readInt()).also { data.readFully(it) }
            Theme(kind, frames, at)
        }
    }.getOrDefault(emptyList())

    private const val MIN_TONAL = 0.6
    /** Two seconds longer than what is known counts as a fuller hearing. */
    private const val FULLER_BY = 16
    private const val BOOK_MAGIC = 0x43544231 // "CTB1"
}
