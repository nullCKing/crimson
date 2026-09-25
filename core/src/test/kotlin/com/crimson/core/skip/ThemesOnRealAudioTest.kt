package com.crimson.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Theme learning and recognition on the mock server's theme test episodes
 * (`tools/mock-xtream/make_themes.py`), through AAC as the app hears them: the episodes' audio
 * decoded back to WAV (`media/tts/theme_epN.decoded.wav`, see make_themes.py). Not committed, so
 * skipped where absent.
 */
class ThemesOnRealAudioTest {

    private val media = File("../tools/mock-xtream/media")
    private val rate = 48_000

    /** Where the opening and ending really are, from themes.json. */
    private fun truth(n: Int): Pair<LongArray, LongArray> {
        val json = File(media, "themes.json").readText()
        val block = Regex("\"$n\": \\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(json)!!.groupValues[1]
        fun pair(key: String) = Regex("\"$key\": \\[\\s*(\\d+),\\s*(\\d+)").find(block)!!.groupValues.let { longArrayOf(it[1].toLong(), it[2].toLong()) }
        return pair("intro") to pair("outro")
    }

    /** Feeds [fromMs, toMs) of episode [n] through the meter, as the audio tap would. */
    private fun hear(n: Int, fromMs: Long, toMs: Long, onFrame: (timeMs: Long, frame: ByteArray) -> Unit) {
        RandomAccessFile(File(media, "tts/theme_ep$n.decoded.wav"), "r").use { f ->
            val header = ByteArray(4096).also { f.readFully(it) }
            var at = 12
            var dataStart = -1L
            while (at < header.size - 8) {
                val id = String(header, at, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(header, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") { dataStart = at + 8L; break }
                at += 8 + size
            }
            val meter = ChromaMeter(rate, 2)
            val firstFrame = fromMs * rate / 1000
            f.seek(dataStart + firstFrame * 4)
            val chunk = 4096
            val bytes = ByteArray(chunk * 4)
            var frame = firstFrame
            val lastFrame = toMs * rate / 1000
            while (frame < lastFrame) {
                val n2 = minOf(chunk.toLong(), lastFrame - frame).toInt()
                f.readFully(bytes, 0, n2 * 4)
                val pcm = ShortArray(n2 * 2)
                ByteBuffer.wrap(bytes, 0, n2 * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                val base = frame
                meter.feed(pcm, n2) { end, chroma -> onFrame((base + end) * 1000 / rate - meter.centreLagUs / 1000, chroma) }
                frame += n2
            }
        }
    }

    private fun print(n: Int): EpisodePrint {
        val p = EpisodePrint(n.toLong(), 17 * 60_000L)
        hear(n, 0, EpisodePrint.INTRO_WINDOW_MS) { t, f -> p.record(t, f) }
        hear(n, p.outroStartMs, p.durationMs) { t, f -> p.record(t, f) }
        return p
    }

    @Test
    fun `learns both themes from two episodes and skips them in a third`() {
        assumeTrue("theme test media not generated", File(media, "tts/theme_ep4.decoded.wav").isFile && File(media, "themes.json").isFile)
        val two = print(2)
        val three = print(3)
        ThemeLearner.learn(two, listOf(three), emptyList(), now = 0) // warm up the JIT
        val started = System.nanoTime()
        val learned = ThemeLearner.learn(two, listOf(three), emptyList(), now = 0)
        println("learned ${learned.map { "${it.kind} ${it.lengthMs} ms" }} in ${(System.nanoTime() - started) / 1_000_000} ms")
        val intro = learned.single { it.kind == Theme.Kind.INTRO }
        val outro = learned.single { it.kind == Theme.Kind.OUTRO }
        assertEquals(2, learned.size)
        assertTrue("intro ${intro.lengthMs}", intro.lengthMs in 28_000..31_000)
        assertTrue("outro ${outro.lengthMs}", outro.lengthMs in 43_000..46_000)

        for (n in listOf(1, 4)) {
            val (open, end) = truth(n)
            for ((theme, window) in listOf(intro to open, outro to end)) {
                val matcher = ThemeMatcher(listOf(theme.frames))
                var skipTo: Long? = null
                var heardAt: Long? = null
                val from = (window[0] - 60_000).coerceAtLeast(0)
                hear(n, from, window[1] + 60_000) { t, f ->
                    val m = matcher.push(f)
                    if (m?.confirmed == true && skipTo == null) {
                        heardAt = t
                        skipTo = t + m.framesLeft * ThemePrints.FRAME_MS + ThemePrints.FRAME_MS / 2
                    }
                }
                println("episode $n ${theme.kind}: theme ${window[0]}–${window[1]}, heard at $heardAt, skip to $skipTo")
                assertNotNull(skipTo)
                assertTrue("heard ${heardAt!! - window[0]} ms in", heardAt!! - window[0] in 0..3_600)
                assertTrue("skips to $skipTo for ${window[1]}", kotlin.math.abs(skipTo!! - window[1]) <= 700)
            }
        }
    }
}
