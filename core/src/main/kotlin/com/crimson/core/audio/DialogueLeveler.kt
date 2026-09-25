package com.crimson.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Dialogue boost": keeps explosions from drowning out the lines, the way a TV's night mode or a
 * soundbar's voice mode does.
 *
 * Four stages, on interleaved 16-bit PCM:
 *
 * 1. **Channel balance.** In a 5.1 mix the dialogue lives in the centre channel, so it is raised
 *    and the LFE (where most of an explosion is) and the surrounds are lowered. Stereo has no
 *    centre to find and is left as it is.
 * 2. **Voice EQ.** A low shelf takes the rumble down and a broad peak around 2 kHz brings up the
 *    consonants that make speech intelligible.
 * 3. **Compression.** One gain for all channels, following the loudest of them: anything above
 *    the threshold is turned down at 4:1, then the whole signal is turned up. Quiet speech ends up
 *    louder and a sudden blast much quieter, so the volume can stay where the dialogue is audible.
 * 4. **Limiting.** A peak limiter at −1 dBFS so the turned-up signal never clips.
 *
 * [enabled] can change at any time; the effect fades in and out over 60 ms rather than clicking.
 * Off, the samples pass through untouched, bit for bit.
 */
class DialogueLeveler(private val sampleRate: Int, private val channels: Int) {

    @Volatile
    var enabled: Boolean = false

    private var mix = 0f
    private val mixStep = 1f / (sampleRate * 0.06f)

    private val weights = FloatArray(channels) { 1f }
    private val lfe = if (channels >= 6) 3 else -1
    private val shelf = Array(channels) { Biquad.lowShelf(sampleRate, 180.0, -5.0) }
    private val presence = Array(channels) { Biquad.peaking(sampleRate, 2_200.0, 4.0, 0.9) }

    private val attack = coefficient(5.0)
    private val release = coefficient(160.0)
    private val limiterRelease = coefficient(60.0)
    private var envelope = 0f
    private var limiterGain = 1f
    private val makeup = dbToGain(MAKEUP_DB)
    private val wet = FloatArray(channels)

    init {
        if (channels >= 6) {
            // WAVE order, which is what Android's decoders produce: FL FR FC LFE BL BR (SL SR).
            weights[0] = 0.8f; weights[1] = 0.8f
            weights[2] = 1.5f
            weights[3] = 0.35f
            for (c in 4 until channels) weights[c] = 0.6f
        }
    }

    /** Processes [frames] frames of [samples] in place. */
    fun process(samples: ShortArray, frames: Int) {
        val on = enabled
        // Off and fully faded out: the samples are left exactly as they came.
        if (!on && mix == 0f) return
        var i = 0
        for (f in 0 until frames) {
            mix = if (on) min(1f, mix + mixStep) else max(0f, mix - mixStep)

            // 1 and 2: balance and EQ, and the loudest channel for the detector.
            var peak = 0f
            for (c in 0 until channels) {
                var x = samples[i + c] / 32768f * weights[c]
                if (c != lfe) x = presence[c].process(shelf[c].process(x))
                wet[c] = x
                val a = abs(x)
                if (a > peak) peak = a
            }

            // 3: compression, one gain for every channel so the image does not shift.
            val coeff = if (peak > envelope) attack else release
            envelope = coeff * envelope + (1 - coeff) * peak
            val gain = compressorGain(envelope) * makeup

            // 4: limiting, instant on the way down and released gently.
            val out = peak * gain
            val needed = if (out > CEILING) CEILING / out else 1f
            limiterGain = min(needed, limiterRelease * limiterGain + (1 - limiterRelease))
            val total = gain * limiterGain

            for (c in 0 until channels) {
                val dry = samples[i + c] / 32768f
                val y = dry + mix * (wet[c] * total - dry)
                samples[i + c] = (y * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            }
            i += channels
        }
        if (mix == 0f) reset()
    }

    /** Forgets filter and envelope state, after a seek or when the effect has faded out. */
    fun reset() {
        shelf.forEach(Biquad::reset)
        presence.forEach(Biquad::reset)
        envelope = 0f
        limiterGain = 1f
    }

    private fun compressorGain(level: Float): Float {
        if (level <= 1e-6f) return 1f
        val db = 20 * ln(level.toDouble()) / LN10
        val over = db - THRESHOLD_DB
        val slope = 1.0 / RATIO - 1.0
        val reduction = when {
            over <= -KNEE_DB / 2 -> 0.0
            over >= KNEE_DB / 2 -> slope * over
            else -> slope * (over + KNEE_DB / 2).pow(2) / (2 * KNEE_DB)
        }
        return dbToGain(reduction)
    }

    private fun coefficient(ms: Double): Float = exp(-1.0 / (sampleRate * ms / 1000.0)).toFloat()

    companion object {
        const val THRESHOLD_DB = -26.0
        const val RATIO = 4.0
        const val KNEE_DB = 8.0
        const val MAKEUP_DB = 9.0
        /** −1 dBFS. */
        const val CEILING = 0.891f
        private val LN10 = ln(10.0)

        fun dbToGain(db: Double): Float = 10.0.pow(db / 20).toFloat()
    }
}

/** A second-order IIR filter (RBJ's cookbook), in transposed direct form II. */
class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    companion object {
        fun lowShelf(rate: Int, hz: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val sa = 2 * sqrt(a) * alpha
            return normalise(
                a * ((a + 1) - (a - 1) * c + sa),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - sa,
            )
        }

        fun peaking(rate: Int, hz: Double, gainDb: Double, q: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalise(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun highPass(rate: Int, hz: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalise((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowPass(rate: Int, hz: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalise((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        private fun normalise(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())
    }
}

/**
 * How much voice-band energy (300 Hz to 3.4 kHz) each tenth of a second of audio holds, in
 * decibels: the audio side of automatic caption sync. The dialogue channel is used where there is
 * one (the centre of a 5.1 mix, which is nearly all voice); otherwise the mid of left and right.
 *
 * (Comparing the voice band with the hiss above 4 kHz, to tell a voice from an explosion by its
 * shape, was tried against the caption test film and did worse with these filters than the plain
 * level; [com.crimson.core.subtitles.SubtitleSync] takes out slow drifts instead.)
 */
class SpeechMeter(private val sampleRate: Int, private val channels: Int) {

    private val highPass = Biquad.highPass(sampleRate, 300.0)
    private val lowPass = Biquad.lowPass(sampleRate, 3_400.0)
    private val windowFrames = max(1, sampleRate / (1000 / WINDOW_MS).toInt())
    private var energy = 0.0
    private var count = 0

    /**
     * Feeds [frames] frames; [onWindow] receives the frame index (within this call) at which each
     * completed window ended and the window's level in dB.
     */
    fun feed(samples: ShortArray, frames: Int, onWindow: (endFrame: Int, levelDb: Float) -> Unit) {
        var i = 0
        for (f in 0 until frames) {
            val voice = when {
                channels >= 6 -> samples[i + 2] / 32768f
                channels >= 2 -> (samples[i] + samples[i + 1]) / 65536f
                else -> samples[i] / 32768f
            }
            val y = lowPass.process(highPass.process(voice))
            energy += (y * y).toDouble()
            if (++count == windowFrames) {
                onWindow(f + 1, (10 * ln(energy / count + 1e-10) / LN10).toFloat())
                energy = 0.0
                count = 0
            }
            i += channels
        }
    }

    fun reset() {
        highPass.reset(); lowPass.reset()
        energy = 0.0; count = 0
    }

    companion object {
        const val WINDOW_MS = 100L
        private val LN10 = ln(10.0)
    }
}
