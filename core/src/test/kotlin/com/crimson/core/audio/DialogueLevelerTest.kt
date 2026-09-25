package com.crimson.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DialogueLevelerTest {

    private val rate = 48_000

    private fun tone(seconds: Double, hz: Double, amplitude: Double, channels: Int, only: Int? = null): ShortArray {
        val frames = (rate * seconds).toInt()
        val out = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val v = (sin(2 * PI * hz * f / rate) * amplitude * 32767).toInt().toShort()
            for (c in 0 until channels) if (only == null || only == c) out[f * channels + c] = v
        }
        return out
    }

    private fun rmsDb(samples: ShortArray, channels: Int, channel: Int, fromFrame: Int = 0): Double {
        var sum = 0.0
        var n = 0
        var i = fromFrame * channels + channel
        while (i < samples.size) { val x = samples[i] / 32768.0; sum += x * x; n++; i += channels }
        return 20 * log10(sqrt(sum / n) + 1e-12)
    }

    @Test
    fun `off, it changes nothing`() {
        val input = tone(0.5, 440.0, 0.8, 2)
        val copy = input.copyOf()
        DialogueLeveler(rate, 2).process(copy, copy.size / 2)
        assertArrayEquals(input, copy)
    }

    @Test
    fun `on, quiet speech comes up and a blast comes down`() {
        val quiet = tone(1.0, 1_000.0, 0.03, 2)   // about -33 dBFS: a quiet line
        val loud = tone(1.0, 1_000.0, 0.95, 2)    // about -0.5 dBFS: an explosion
        val before = rmsDb(loud, 2, 0) - rmsDb(quiet, 2, 0)

        val leveler = DialogueLeveler(rate, 2).apply { enabled = true }
        leveler.process(quiet, quiet.size / 2)
        leveler.process(loud, loud.size / 2)
        val skip = rate / 4 // past the fade-in and the envelope settling
        val after = rmsDb(loud, 2, 0, skip) - rmsDb(quiet, 2, 0, skip)

        assertTrue("quiet raised: ${rmsDb(quiet, 2, 0, skip)}", rmsDb(quiet, 2, 0, skip) > -33 + 5)
        assertTrue("gap was $before dB, now $after dB", after < before - 15)
    }

    @Test
    fun `never clips`() {
        val loud = tone(1.0, 60.0, 1.0, 2)
        val leveler = DialogueLeveler(rate, 2).apply { enabled = true }
        leveler.process(loud, loud.size / 2)
        // After the 60 ms fade-in, during which some of the untouched signal is still mixed in.
        val peak = loud.drop(2 * rate / 10).maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("peak $peak", peak <= 0.9)
    }

    @Test
    fun `in 5_1 the centre channel is favoured over the LFE`() {
        val channels = 6
        val centre = tone(1.0, 1_000.0, 0.05, channels, only = 2)
        val lfe = tone(1.0, 1_000.0, 0.05, channels, only = 3)
        DialogueLeveler(rate, channels).apply { enabled = true }.process(centre, centre.size / channels)
        DialogueLeveler(rate, channels).apply { enabled = true }.process(lfe, lfe.size / channels)
        val skip = rate / 4
        assertTrue(rmsDb(centre, channels, 2, skip) - rmsDb(lfe, channels, 3, skip) > 10)
    }

    @Test
    fun `the speech meter hears the voice band`() {
        val voice = tone(1.0, 1_000.0, 0.3, 2)
        val rumble = tone(1.0, 40.0, 0.3, 2)
        val levels = HashMap<String, Float>()
        SpeechMeter(rate, 2).feed(voice, voice.size / 2) { _, db -> levels["voice"] = db }
        SpeechMeter(rate, 2).feed(rumble, rumble.size / 2) { _, db -> levels["rumble"] = db }
        assertTrue("$levels", levels.getValue("voice") - levels.getValue("rumble") > 20)
    }
}
