package com.crimson.core.subtitles

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The speech level of what has been played so far, a tenth of a second per slot, indexed by
 * position in the video. Slots never heard (skipped over, or not reached yet) are NaN.
 */
class SpeechTimeline(durationMs: Long) {

    private val levels = FloatArray(((durationMs / BIN_MS) + 1).toInt().coerceIn(1, MAX_BINS)) { Float.NaN }

    val size: Int get() = levels.size

    operator fun get(bin: Int): Float = levels.getOrElse(bin) { Float.NaN }

    fun record(positionMs: Long, levelDb: Float) {
        val bin = (positionMs / BIN_MS).toInt()
        if (bin in levels.indices && !levelDb.isNaN()) levels[bin] = levelDb
    }

    /** How many slots have been heard. */
    fun heard(): Int = levels.count { !it.isNaN() }

    companion object {
        const val BIN_MS = 100L
        /** Four hours at a tenth of a second. */
        const val MAX_BINS = 144_000
    }
}

/** A correction to apply to a subtitle's times: `t' = t * scale + offsetMs`. */
data class SyncFix(val offsetMs: Long, val scale: Double, val confidence: Double) {
    val isIdentity: Boolean get() = offsetMs == 0L && scale == 1.0
}

/**
 * Lines captions up with the dialogue, the way `ffsubsync` does.
 *
 * Where the captions say someone is speaking, the speech band of the audio should be louder than
 * where they say no one is. Sliding the captions against the audio and scoring that agreement at
 * each offset gives a curve with a sharp peak at the true offset; trying the common frame-rate
 * conversions as well catches a subtitle made for a PAL release, which drifts instead of sitting
 * at a fixed offset. The peak is only believed when it stands well clear of the rest of the curve
 * — music, crowds and explosions are loud too, and a flat curve means "cannot tell", which leaves
 * the captions as they were.
 */
object SubtitleSync {

    /** The frame-rate conversions worth trying, as time scales. */
    val SCALES = doubleArrayOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    const val MAX_OFFSET_MS = 60_000L
    /** Less heard audio than this and there is not enough dialogue to go on. */
    const val MIN_HEARD_MS = 90_000L
    /** How far above the curve's typical score the peak has to stand, in standard deviations. */
    const val MIN_PEAK_Z = 5.0
    private const val MAX_SLOTS = 12_000
    /** Three seconds either side of a peak count as the peak itself. */
    private const val SHOULDER_BINS = 30

    /**
     * The best correction for [track] against [speech], or null when the audio heard so far does
     * not settle it. [scales] defaults to all of [SCALES]; pass `[1.0]` when the frame rate is
     * already known to match.
     */
    fun estimate(track: SubtitleTrack, speech: SpeechTimeline, scales: DoubleArray = SCALES): SyncFix? =
        bestFit(track, speech, scales)?.takeIf { it.confidence >= MIN_PEAK_Z }

    /** The best-scoring correction however weakly it stands out; [estimate] is this, believed. */
    fun bestFit(track: SubtitleTrack, speech: SpeechTimeline, scales: DoubleArray = SCALES): SyncFix? {
        val bin = SpeechTimeline.BIN_MS
        val all = ArrayList<Int>()
        for (i in 0 until speech.size) if (!speech[i].isNaN()) all.add(i)
        if (all.size * bin < MIN_HEARD_MS || track.isEmpty) return null
        // Twenty minutes of audio settles any offset; beyond that, every nth slot keeps the work
        // (slots x offsets x scales) to a second or so on a Fire TV.
        val stride = (all.size + MAX_SLOTS - 1) / MAX_SLOTS
        val heard = if (stride <= 1) all else all.filterIndexed { i, _ -> i % stride == 0 }

        // Audio as a zero-mean, unit-spread score per slot, so loud films and quiet ones compare,
        // and so a caption covering more time is not rewarded for it. Slow drifts (a scene's
        // music, a room's tone) are taken out first, leaving the rise and fall of the lines.
        val values = detrend(FloatArray(heard.size) { speech[heard[it]] })
        val sorted = values.sortedArray()
        val median = sorted[sorted.size / 2]
        val spread = max(1f, sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)] - sorted[(sorted.size * 0.1).toInt()])
        val audio = FloatArray(values.size) { ((values[it] - median) / spread).coerceIn(-1.5f, 1.5f) }

        val maxOffsetBins = (MAX_OFFSET_MS / bin).toInt()
        var best: SyncFix? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (scale in scales) {
            // Caption coverage in video time for this scale (offset applied below by shifting).
            val span = speech.size + maxOffsetBins + 1
            val covered = BooleanArray(span)
            for (cue in track.cues) {
                val from = ((cue.startMs * scale) / bin).toInt()
                val to = ((cue.endMs * scale) / bin).toInt()
                for (b in max(0, from) until min(span, to)) covered[b] = true
            }
            val scores = DoubleArray(2 * maxOffsetBins + 1)
            for (o in -maxOffsetBins..maxOffsetBins) {
                var sum = 0.0
                for (k in heard.indices) {
                    val source = heard[k] - o
                    if (source in covered.indices && covered[source]) sum += audio[k]
                }
                scores[o + maxOffsetBins] = sum
            }
            var peak = 0
            for (i in scores.indices) if (scores[i] > scores[peak]) peak = i
            // How far the peak stands above the rest of the curve, the peak's own shoulders left
            // out: a true offset is a sharp spike, and its slopes are not "the rest".
            var n = 0
            var sum = 0.0
            var squares = 0.0
            for (i in scores.indices) {
                if (kotlin.math.abs(i - peak) <= SHOULDER_BINS) continue
                n++; sum += scores[i]; squares += scores[i] * scores[i]
            }
            val mean = sum / n
            val sd = sqrt((squares / n - mean * mean).coerceAtLeast(0.0)).coerceAtLeast(1e-6)
            val z = (scores[peak] - mean) / sd
            if (scores[peak] > bestScore) {
                bestScore = scores[peak]
                best = SyncFix(((peak - maxOffsetBins) * bin), scale, z)
            }
        }
        return best
    }

    /** Each value less the median of the three seconds around it. */
    private fun detrend(values: FloatArray): FloatArray {
        val half = 15
        val window = FloatArray(2 * half + 1)
        return FloatArray(values.size) { i ->
            val from = max(0, i - half)
            val to = min(values.size - 1, i + half)
            val n = to - from + 1
            for (k in 0 until n) window[k] = values[from + k]
            java.util.Arrays.sort(window, 0, n)
            values[i] - window[n / 2]
        }
    }

    /** [fix] applied to [track]. */
    fun apply(track: SubtitleTrack, fix: SyncFix): SubtitleTrack = track.retimed(fix.offsetMs, fix.scale)

    fun describe(fix: SyncFix): String {
        val seconds = fix.offsetMs / 1000.0
        val offset = (if (seconds >= 0) "+" else "−") + String.format("%.1f s", kotlin.math.abs(seconds))
        return if (fix.scale == 1.0) offset else "$offset, frame rate corrected"
    }
}
