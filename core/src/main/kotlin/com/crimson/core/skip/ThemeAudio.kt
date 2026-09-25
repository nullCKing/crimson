package com.crimson.core.skip

import com.crimson.core.audio.Biquad
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a moment of music sounds like, eight times a second, in twelve numbers: how much of each
 * note of the scale (C, C♯ … B) is sounding, whatever the octave — a *chroma* vector, the feature
 * song-recognition services start from.
 *
 * A theme song is the same recording in every episode, so its chroma sequence repeats almost
 * exactly from one episode to the next, through different encodes, loudness and channel layouts;
 * talk, sound effects and other music do not. Each vector is centred (its average removed) and
 * scaled to length [ThemePrints.UNIT], so comparing two is a correlation: about 1 for the same
 * music, about 0 for unrelated sound. Silence and noise with no notes in it come out as zeros,
 * which match nothing.
 */
class ChromaMeter(sampleRate: Int, private val channels: Int) {

    /** Decimated to about 12 kHz after a 4.5 kHz low-pass: the notes are all below 3 kHz. */
    private val decimation = max(1, sampleRate / 11_025)
    private val rate = sampleRate.toDouble() / decimation
    private val hop = max(1, (rate * ThemePrints.FRAME_MS / 1000).roundToInt())
    private val lowPass1 = Biquad.lowPass(sampleRate, 4_500.0)
    private val lowPass2 = Biquad.lowPass(sampleRate, 4_500.0)

    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var filled = 0
    private var sinceFrame = 0
    private var decimCount = 0
    private var decimSum = 0f

    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val chroma = DoubleArray(12)
    private val binClass = IntArray(FFT_SIZE / 2) { -1 }

    init {
        for (k in 1 until FFT_SIZE / 2) {
            val hz = k * rate / FFT_SIZE
            if (hz < MIN_HZ || hz > MAX_HZ) continue
            val midi = 69 + 12 * ln(hz / 440.0) / LN2
            binClass[k] = Math.floorMod(midi.roundToInt(), 12)
        }
    }

    /** How far behind the newest sample a frame's centre is, in microseconds. */
    val centreLagUs: Long = (FFT_SIZE / 2 * decimation * 1_000_000L) / sampleRate

    /**
     * Feeds [frames] interleaved frames. [onFrame] receives, for each chroma frame completed, the
     * index (within this call) of the last sample frame in it, and the frame itself, which the
     * receiver may keep.
     */
    fun feed(samples: ShortArray, frames: Int, onFrame: (endFrame: Int, chroma: ByteArray) -> Unit) {
        var i = 0
        for (f in 0 until frames) {
            var mono = 0f
            val used = min(channels, 2)
            for (c in 0 until used) mono += samples[i + c]
            // 5.1: the centre carries the singing as much as the fronts do.
            if (channels >= 6) mono += samples[i + 2]
            mono /= 32768f * (used + if (channels >= 6) 1 else 0)
            val y = lowPass2.process(lowPass1.process(mono))
            decimSum += y
            if (++decimCount == decimation) {
                push(decimSum / decimation)
                decimSum = 0f
                decimCount = 0
                if (filled == FFT_SIZE && ++sinceFrame >= hop) {
                    sinceFrame = 0
                    onFrame(f + 1, frame())
                }
            }
            i += channels
        }
    }

    fun reset() {
        lowPass1.reset(); lowPass2.reset()
        ring.fill(0f); ringPos = 0; filled = 0; sinceFrame = 0; decimCount = 0; decimSum = 0f
    }

    private fun push(x: Float) {
        ring[ringPos] = x
        ringPos = (ringPos + 1) % FFT_SIZE
        if (filled < FFT_SIZE) filled++
    }

    private fun frame(): ByteArray {
        var energy = 0.0
        for (n in 0 until FFT_SIZE) {
            val x = ring[(ringPos + n) % FFT_SIZE].toDouble()
            energy += x * x
            re[n] = x * WINDOW[n]
            im[n] = 0.0
        }
        if (energy / FFT_SIZE < SILENCE_POWER) return ByteArray(12)
        fft(re, im)
        chroma.fill(0.0)
        for (k in binClass.indices) {
            val c = binClass[k]
            if (c >= 0) chroma[c] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        return ThemePrints.normalise(chroma)
    }

    companion object {
        const val FFT_SIZE = 2048
        private const val MIN_HZ = 110.0
        private const val MAX_HZ = 3_000.0
        /** About −60 dBFS: quieter than that is silence, and has no notes worth comparing. */
        private const val SILENCE_POWER = 1e-6
        private val LN2 = ln(2.0)
        private val WINDOW = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        private val COS = DoubleArray(FFT_SIZE / 2) { cos(2 * PI * it / FFT_SIZE) }
        private val SIN = DoubleArray(FFT_SIZE / 2) { -sin(2 * PI * it / FFT_SIZE) }

        /** In-place radix-2 FFT of [FFT_SIZE] points. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val step = n / len
                val half = len / 2
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = SIN[k * step]
                        val a = start + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                    }
                    start += len
                }
                len = len shl 1
            }
        }
    }
}

/** Sequences of chroma frames, [BINS] bytes each, laid end to end in one array. */
object ThemePrints {
    const val FRAME_MS = 125L
    const val BINS = 12
    /** A frame's length after [normalise]; a frame compared with itself scores UNIT². */
    const val UNIT = 100
    const val UNIT2 = UNIT * UNIT

    /** Centred and scaled to [UNIT]; zeros when there is no note in it to speak of. */
    fun normalise(chroma: DoubleArray): ByteArray {
        var sum = 0.0
        for (v in chroma) sum += v
        if (sum <= 0.0) return ByteArray(BINS)
        val mean = sum / BINS
        var norm = 0.0
        for (v in chroma) norm += (v - mean) * (v - mean)
        norm = sqrt(norm)
        // Flat: every note about as loud as every other, which is noise or a crowd, not music.
        if (norm < sum * FLATNESS) return ByteArray(BINS)
        return ByteArray(BINS) { ((chroma[it] - mean) / norm * UNIT).roundToInt().coerceIn(-127, 127).toByte() }
    }

    /** Similarity of frame [i] of [a] and frame [j] of [b], out of [UNIT2]. */
    fun dot(a: ByteArray, i: Int, b: ByteArray, j: Int): Int {
        var s = 0
        val x = i * BINS
        val y = j * BINS
        for (k in 0 until BINS) s += a[x + k] * b[y + k]
        return s
    }

    fun frames(print: ByteArray): Int = print.size / BINS

    /** Fraction of frames that carry notes; a print of silence and talk has almost none. */
    fun tonalFraction(print: ByteArray): Double {
        val n = frames(print)
        if (n == 0) return 0.0
        var tonal = 0
        for (f in 0 until n) {
            for (k in 0 until BINS) if (print[f * BINS + k].toInt() != 0) { tonal++; break }
        }
        return tonal.toDouble() / n
    }

    private const val FLATNESS = 0.08
}

/** A stretch of sound found in two prints: where it starts in each, and how long it is, in frames. */
data class CommonStretch(val aStart: Int, val bStart: Int, val length: Int, val score: Double)

/**
 * Finds a theme song by finding what two episodes have in common.
 *
 * The alignments of the two prints that could share something (see [candidateDiagonals]) are
 * compared frame by frame; along each, a stretch counts as shared where at least six frames in
 * every eight are near-identical.
 * The same recording through two encodes scores about 0.8–0.95 per frame; unrelated sound scores
 * around zero, and music in the same key occasionally more, but never for twelve seconds on end.
 */
object ThemeFinder {

    /** Shorter than this is a jingle or a logo; the shortest theme asked about is about 30 s. */
    const val MIN_FRAMES = (12_000 / ThemePrints.FRAME_MS).toInt()
    /** An opening longer than this is not a theme song. */
    const val MAX_FRAMES = (150_000 / ThemePrints.FRAME_MS).toInt()

    private const val WINDOW = 8
    private const val WINDOW_HITS = 6
    private val HIT = (0.5 * ThemePrints.UNIT2).toInt()

    fun common(a: ByteArray, b: ByteArray, minFrames: Int = MIN_FRAMES, maxFrames: Int = MAX_FRAMES): List<CommonStretch> {
        val na = ThemePrints.frames(a)
        val nb = ThemePrints.frames(b)
        if (na < minFrames || nb < minFrames) return emptyList()
        val found = ArrayList<CommonStretch>()
        val sims = IntArray(min(na, nb))
        for (d in candidateDiagonals(a, na, b, nb)) {
            val i0 = max(0, d)
            val i1 = min(na, nb + d)
            val n = i1 - i0
            if (n < minFrames) continue
            for (t in 0 until n) sims[t] = ThemePrints.dot(a, i0 + t, b, i0 + t - d)
            runs(sims, n, minFrames) { start, length, score ->
                found.add(CommonStretch(i0 + start, i0 + start - d, min(length, maxFrames), score))
            }
        }
        // Neighbouring alignments find the same stretch again; keep the best of each.
        found.sortWith(compareByDescending<CommonStretch> { it.length * it.score })
        val kept = ArrayList<CommonStretch>()
        for (c in found) {
            if (kept.none { overlaps(it.aStart, it.length, c.aStart, c.length) || overlaps(it.bStart, it.length, c.bStart, c.length) }) kept.add(c)
        }
        return kept
    }

    private fun overlaps(s1: Int, l1: Int, s2: Int, l2: Int) = s1 < s2 + l2 && s2 < s1 + l1

    /**
     * The alignments worth comparing in full. Comparing every frame of ten minutes with every
     * frame of another ten is 23 million comparisons, a minute's work on a Fire TV. Instead each
     * frame is reduced to its two strongest notes, frames with the same two vote for the
     * alignment that pairs them, and only alignments where a run of frames voted together (a
     * shared stretch piles its votes onto one alignment; chance scatters them) are compared.
     */
    private fun candidateDiagonals(a: ByteArray, na: Int, b: ByteArray, nb: Int): IntArray {
        val codesB = IntArray(nb) { code(b, it) }
        val byCode = Array(CODES) { IntArray(0) }
        val counts = IntArray(CODES)
        for (c in codesB) if (c >= 0) counts[c]++
        for (c in 0 until CODES) byCode[c] = IntArray(counts[c])
        counts.fill(0)
        for ((j, c) in codesB.withIndex()) if (c >= 0) byCode[c][counts[c]++] = j
        val blocks = (na + BLOCK - 1) / BLOCK
        val diagonals = na + nb - 1
        val votes = IntArray(diagonals * blocks)
        for (i in 0 until na) {
            val c = code(a, i)
            if (c < 0) continue
            val block = i / BLOCK
            for (j in byCode[c]) votes[(i - j + nb - 1) * blocks + block]++
        }
        val best = IntArray(diagonals)
        for (d in 0 until diagonals) {
            var m = 0
            for (k in 0 until blocks) {
                // A stretch can straddle two blocks.
                val v = votes[d * blocks + k] + if (k + 1 < blocks) votes[d * blocks + k + 1] else 0
                if (v > m) m = v
            }
            best[d] = m
        }
        val chosen = java.util.TreeSet<Int>()
        (0 until diagonals).filter { best[it] >= MIN_VOTES }
            .sortedByDescending { best[it] }
            .take(MAX_CANDIDATES)
            .forEach { d -> for (n in d - 1..d + 1) if (n in 0 until diagonals) chosen.add(n - (nb - 1)) }
        return chosen.toIntArray()
    }

    /** A frame's two strongest notes, in order; −1 for a frame with no notes. */
    private fun code(print: ByteArray, frame: Int): Int {
        val base = frame * ThemePrints.BINS
        var first = -1
        var second = -1
        for (k in 0 until ThemePrints.BINS) {
            val v = print[base + k]
            if (first < 0 || v > print[base + first]) { second = first; first = k }
            else if (second < 0 || v > print[base + second]) second = k
        }
        if (print[base + first].toInt() == 0) return -1
        return first * ThemePrints.BINS + second
    }

    private const val CODES = ThemePrints.BINS * ThemePrints.BINS
    private const val BLOCK = 64
    private const val MIN_VOTES = 14
    private const val MAX_CANDIDATES = 120

    /** Calls [onRun] for each shared stretch along one diagonal. */
    private inline fun runs(sims: IntArray, n: Int, minFrames: Int, onRun: (start: Int, length: Int, score: Double) -> Unit) {
        var hits = 0
        var runStart = -1
        var runEnd = -1
        for (t in 0 until n) {
            if (sims[t] >= HIT) hits++
            if (t >= WINDOW && sims[t - WINDOW] >= HIT) hits--
            if (t < WINDOW - 1) continue
            val inWindow = hits >= WINDOW_HITS
            if (inWindow) {
                if (runStart < 0) runStart = t - WINDOW + 1
                runEnd = t
            }
            if ((!inWindow || t == n - 1) && runStart >= 0) {
                var s = runStart
                var e = runEnd
                while (s < e && sims[s] < HIT) s++
                while (e > s && sims[e] < HIT) e--
                val length = e - s + 1
                if (length >= minFrames) {
                    var total = 0L
                    for (k in s..e) total += sims[k]
                    onRun(s, length, total.toDouble() / length / ThemePrints.UNIT2)
                }
                runStart = -1
            }
        }
    }
}

/**
 * Listens for known theme songs as the audio plays.
 *
 * Each new frame is compared, with the frames of the three seconds before it, against every
 * position in every theme: three seconds that line up with a theme is a match, and says exactly
 * how much of it is left to skip. A second and a half that lines up closely with a theme's first
 * seconds is a likely one, enough to turn the sound down while the rest decides.
 */
class ThemeMatcher(private val themes: List<ByteArray>) {

    data class Match(
        val theme: Int,
        /** The theme frame the newest frame is. */
        val frame: Int,
        val framesLeft: Int,
        val confirmed: Boolean,
    )

    /** The last [HISTORY] frames, oldest first; the newest is at HISTORY − 1. */
    private val recent = ByteArray(HISTORY * ThemePrints.BINS)
    private var count = 0

    fun reset() { count = 0 }

    /** Adds the newest frame; a match, if the latest seconds are a theme (or likely the start of one). */
    fun push(frame: ByteArray): Match? {
        System.arraycopy(recent, ThemePrints.BINS, recent, 0, recent.size - ThemePrints.BINS)
        System.arraycopy(frame, 0, recent, recent.size - ThemePrints.BINS, ThemePrints.BINS)
        if (count < HISTORY) count++
        if (ThemePrints.tonalFraction(frame) == 0.0) return null

        val candidates = ArrayList<Candidate>()
        var likely: Match? = null
        var likelyScore = 0
        val window = min(count, CONFIRM)
        for ((index, theme) in themes.withIndex()) {
            val length = ThemePrints.frames(theme)
            for (k in 0 until length) {
                // The newest frame as theme frame k, looking back over the window.
                val back = min(window, k + 1)
                if (back < LIKELY) continue
                var total = 0
                var hits = 0
                for (m in 0 until back) {
                    val s = ThemePrints.dot(recent, HISTORY - 1 - m, theme, k - m)
                    total += s
                    if (s >= HIT) hits++
                }
                if (back >= CONFIRM && hits >= CONFIRM_HITS && total >= CONFIRM_MEAN * back) {
                    candidates += Candidate(index, k, length, total)
                } else if (k < LIKELY_START && back >= LIKELY && hits >= LIKELY_HITS && total >= LIKELY_MEAN * back) {
                    // Only near a theme's start, where turning the sound down is worth it.
                    if (total > likelyScore) { likelyScore = total; likely = Match(index, k, length - 1 - k, false) }
                }
            }
        }
        if (candidates.isEmpty()) return likely
        // Songs repeat themselves (a chorus, a riff), so three seconds can line up with more than
        // one place in the theme. The right place is the one that, followed back through what
        // was heard, runs unbroken to the theme's first frame: a later repeat runs back into
        // whatever played before the theme instead. When everything heard lines up (the viewer
        // jumped into the middle of it), the latest place, which skips the least.
        var reaching: Candidate? = null
        var joined: Candidate? = null
        for (c in candidates) {
            val run = run(themes[c.theme], c.k)
            when {
                c.k + 1 - run <= START_SLACK -> if (reaching == null || c.score > reaching.score) reaching = c
                // Everything heard lines up: playback joined the theme part way.
                run >= count - START_SLACK -> if (joined == null || c.k > joined.k) joined = c
                // Otherwise it runs back into something else: a repeat further on in the theme,
                // matched before the true place has had three seconds to line up. Wait for that.
            }
        }
        val best = reaching ?: joined ?: return likely
        return Match(best.theme, best.k, best.length - 1 - best.k, true)
    }

    private class Candidate(val theme: Int, val k: Int, val length: Int, val score: Int)

    /** How many frames back from the newest the alignment with theme frame [k] holds. */
    private fun run(theme: ByteArray, k: Int): Int {
        val limit = min(count, k + 1)
        var hits = 0
        var last = 0
        for (m in 0 until limit) {
            if (ThemePrints.dot(recent, HISTORY - 1 - m, theme, k - m) >= HIT) { hits++; last = m + 1 }
            if (m >= RUN_WINDOW) {
                // Six in every eight, as when learning: short gaps are encoding, not a different place.
                val old = ThemePrints.dot(recent, HISTORY - 1 - (m - RUN_WINDOW), theme, k - (m - RUN_WINDOW)) >= HIT
                if (old) hits--
            }
            if (m >= RUN_WINDOW - 1 && hits < RUN_HITS) break
        }
        return last
    }

    companion object {
        /** Three seconds: two was not enough to tell a theme from music in the same key. */
        const val CONFIRM = 24
        const val LIKELY = 12
        private const val CONFIRM_HITS = 20
        private const val LIKELY_HITS = 11
        private const val LIKELY_START = 20
        private val HIT = (0.5 * ThemePrints.UNIT2).toInt()
        private val CONFIRM_MEAN = (0.8 * ThemePrints.UNIT2).toInt()
        private val LIKELY_MEAN = (0.88 * ThemePrints.UNIT2).toInt()
        /** Thirty seconds heard, to follow a match back to where the theme began. */
        private const val HISTORY = 240
        private const val RUN_WINDOW = 8
        private const val RUN_HITS = 6
        private const val START_SLACK = 3
    }
}
