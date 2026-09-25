package com.crimson.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic episodes: talk-like noise, and music made of chords on one instrument. That music is
 * harder to tell apart than real music (same timbre, few chords), which is the point.
 */
class ThemeSkipTest {

    private val rate = 48_000

    /** Music: a chord every half second or so, notes with overtones, and a noisy beat. */
    private fun music(seconds: Double, seed: Int, gain: Double = 0.25): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray((seconds * rate).toInt())
        var t = 0
        while (t < out.size) {
            val len = (rate * (0.4 + rnd.nextDouble() * 0.6)).toInt()
            val root = 45 + rnd.nextInt(12)
            val chord = listOf(root, root + if (rnd.nextBoolean()) 4 else 3, root + 7, root + 12 + rnd.nextInt(3) * 2)
            for (n in 0 until len) {
                if (t + n >= out.size) break
                var x = 0.0
                for (note in chord) {
                    val hz = 440.0 * 2.0.pow((note - 69) / 12.0)
                    for (h in 1..3) x += sin(2 * PI * hz * h * (t + n) / rate) / (h * h)
                }
                val beat = if (n < rate / 30) (rnd.nextDouble() - 0.5) * 0.8 else 0.0
                out[t + n] = (gain * (x / chord.size + beat)).toFloat()
            }
            t += len
        }
        return out
    }

    /** Not music: talk-like noise bursts and gaps. */
    private fun talk(seconds: Double, seed: Int): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray((seconds * rate).toInt())
        var t = 0
        while (t < out.size) {
            val len = (rate * (0.1 + rnd.nextDouble() * 0.3)).toInt()
            val loud = if (rnd.nextInt(4) == 0) 0.0 else 0.1 + rnd.nextDouble() * 0.1
            val pitch = 100 + rnd.nextDouble() * 120
            for (n in 0 until len) {
                if (t + n >= out.size) break
                val voiced = sin(2 * PI * pitch * (t + n) / rate) * 0.5 + (rnd.nextDouble() - 0.5)
                out[t + n] = (loud * voiced).toFloat()
            }
            t += len
        }
        return out
    }

    private fun join(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** The theme as another copy would carry it: quieter, with a little hiss. */
    private fun reencoded(x: FloatArray, gain: Float, seed: Int): FloatArray {
        val rnd = Random(seed)
        return FloatArray(x.size) { x[it] * gain + ((rnd.nextFloat() - 0.5f) * 0.004f) }
    }

    /** Stereo 16-bit through the meter, as the audio sink hands it over, in odd-sized buffers. */
    private fun frames(mono: FloatArray, onFrame: (timeMs: Long, frame: ByteArray) -> Unit) {
        val meter = ChromaMeter(rate, 2)
        var at = 0
        val sizes = intArrayOf(1024, 4096, 1536, 2048)
        var s = 0
        while (at < mono.size) {
            val n = minOf(sizes[s++ % sizes.size], mono.size - at)
            val pcm = ShortArray(n * 2)
            for (i in 0 until n) {
                val v = (mono[at + i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                pcm[2 * i] = v; pcm[2 * i + 1] = v
            }
            val base = at
            meter.feed(pcm, n) { end, frame ->
                val timeMs = (base + end) * 1000L / rate - meter.centreLagUs / 1000
                onFrame(timeMs, frame)
            }
            at += n
        }
    }

    private fun print(id: Long, mono: FloatArray): EpisodePrint {
        val p = EpisodePrint(id, mono.size * 1000L / rate)
        frames(mono) { t, f -> p.record(t, f) }
        return p
    }

    private val theme = music(30.0, seed = 7)

    @Test
    fun `the theme two episodes share is found, where it is in each`() {
        val a = print(1, join(talk(40.0, 1), theme, talk(60.0, 2)))
        val b = print(2, join(talk(95.0, 3), reencoded(theme, 0.5f, 9), talk(50.0, 4)))
        val learned = ThemeLearner.learn(a, listOf(b), emptyList(), now = 0)
        assertEquals(1, learned.size)
        val t = learned.single()
        assertEquals(Theme.Kind.INTRO, t.kind)
        assertTrue("length ${t.lengthMs}", abs(t.lengthMs - 30_000) <= 1_000)
    }

    @Test
    fun `ten minutes against ten minutes is quick`() {
        val a = print(1, join(music(200.0, 31), talk(100.0, 32), theme, music(150.0, 33), talk(120.0, 34)))
        val b = print(2, join(talk(150.0, 35), music(140.0, 36), reencoded(theme, 0.5f, 2), talk(100.0, 37), music(180.0, 38)))
        val started = System.nanoTime()
        val learned = ThemeLearner.learn(a, listOf(b), emptyList(), now = 0)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("learned ${learned.map { it.lengthMs }} in $ms ms")
        assertEquals(1, learned.size)
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test
    fun `a theme learned in part is replaced by a fuller hearing of it`() {
        // The first time, one episode had its first eight seconds skipped over (a seek).
        val partial = print(1, join(talk(40.0, 1), theme, talk(60.0, 2)))
        val skippedOver = print(2, join(talk(95.0, 3), theme, talk(50.0, 4)))
        for (slot in (95_000 / ThemePrints.FRAME_MS).toInt() until (103_000 / ThemePrints.FRAME_MS).toInt()) {
            ByteArray(ThemePrints.BINS).copyInto(skippedOver.intro, slot * ThemePrints.BINS)
        }
        val first = ThemeLearner.learn(partial, listOf(skippedOver), emptyList(), now = 0).single()
        assertTrue("first ${first.lengthMs}", first.lengthMs in 20_000..23_000)
        // Then two episodes heard it all.
        val whole = ThemeLearner.learn(print(3, join(talk(60.0, 5), theme, talk(30.0, 6))), listOf(partial), listOf(first), now = 1)
        assertEquals(1, whole.size)
        val merged = ThemeLearner.merge(listOf(first), whole)
        assertEquals(1, merged.size)
        assertTrue("merged ${merged.single().lengthMs}", merged.single().lengthMs >= 29_000)
        // The same again teaches nothing new.
        assertTrue(ThemeLearner.learn(print(4, join(talk(30.0, 7), theme, talk(30.0, 8))), listOf(partial), merged, now = 2).isEmpty())
    }

    @Test
    fun `episodes with nothing in common teach nothing`() {
        val a = print(1, join(talk(40.0, 1), music(30.0, 11), talk(60.0, 2)))
        val b = print(2, join(talk(95.0, 3), music(30.0, 12), talk(50.0, 4)))
        assertTrue(ThemeLearner.learn(a, listOf(b), emptyList(), now = 0).isEmpty())
    }

    @Test
    fun `a known theme is heard within three seconds and its end is known`() {
        val learned = ThemeLearner.learn(
            print(1, join(talk(40.0, 1), theme, talk(60.0, 2))),
            listOf(print(2, join(talk(95.0, 3), theme, talk(50.0, 4)))),
            emptyList(), now = 0,
        ).single()
        val matcher = ThemeMatcher(listOf(learned.frames))
        val startMs = 70_000L
        val episode = join(talk(70.0, 5), reencoded(theme, 0.7f, 3), music(60.0, 13), talk(30.0, 6))
        var confirmedAt: Long? = null
        var endAt: Long? = null
        var likelyAt: Long? = null
        frames(episode) { t, f ->
            val m = matcher.push(f) ?: return@frames
            if (!m.confirmed && likelyAt == null) likelyAt = t
            if (m.confirmed && confirmedAt == null) {
                confirmedAt = t
                endAt = t + m.framesLeft * ThemePrints.FRAME_MS
            }
            assertTrue("matched outside the theme at $t", t in startMs..startMs + 31_000)
        }
        assertNotNull(confirmedAt)
        assertTrue("confirmed ${confirmedAt!! - startMs} ms in", confirmedAt!! - startMs in 2_000..3_600)
        assertTrue("likely at $likelyAt", likelyAt != null && likelyAt!! < confirmedAt!!)
        assertTrue("ends at $endAt", abs(endAt!! - (startMs + 30_000)) <= 500)
    }

    @Test
    fun `jumping into the middle of a theme still skips to its end`() {
        val matcher = ThemeMatcher(listOf(frameSeq(theme)))
        // Playback starts twelve seconds into the theme, as after a seek (the matcher is reset).
        val from = 12 * rate
        var endAt: Long? = null
        frames(join(theme.copyOfRange(from, theme.size), talk(20.0, 8))) { t, f ->
            val m = matcher.push(f)
            if (m?.confirmed == true && endAt == null) endAt = 12_000 + t + m.framesLeft * ThemePrints.FRAME_MS
        }
        assertTrue("ends at $endAt", endAt != null && abs(endAt!! - 30_000) <= 500)
    }

    @Test
    fun `other music is not taken for the theme`() {
        val matcher = ThemeMatcher(listOf(frameSeq(theme)))
        var matched = 0
        var likely = 0
        frames(join(music(300.0, 21), talk(60.0, 22), music(200.0, 23))) { _, f ->
            val m = matcher.push(f)
            if (m?.confirmed == true) matched++
            if (m?.confirmed == false) likely++
        }
        assertEquals(0, matched)
        // Music built from the same few chords as the theme, on the same instrument, can sound
        // like its opening for a moment; that only turns the sound down briefly.
        assertTrue("likely $likely times", likely <= 10)
    }

    @Test
    fun `prints and themes survive being saved`() {
        val p = print(3, join(talk(20.0, 1), theme))
        val bytes = java.io.ByteArrayOutputStream().also { p.write(it) }.toByteArray()
        val back = EpisodePrint.read(bytes.inputStream())!!
        assertEquals(p.durationMs, back.durationMs)
        assertEquals(p.introHeardMs, back.introHeardMs)
        assertTrue(p.intro.contentEquals(back.intro))
        val t = Theme(Theme.Kind.OUTRO, frameSeq(theme), 42)
        val book = java.io.ByteArrayOutputStream().also { ThemeLearner.write(listOf(t), it) }.toByteArray()
        val read = ThemeLearner.read(book.inputStream()).single()
        assertEquals(Theme.Kind.OUTRO, read.kind)
        assertTrue(read.frames.contentEquals(t.frames))
    }

    private fun frameSeq(x: FloatArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        frames(x) { _, f -> out.write(f) }
        return out.toByteArray()
    }

    @Test
    fun `database times are used when they agree or stand alone`() {
        val introDb = SkipTimesParser.parseIntroDb(
            """{"imdb_id":"tt0386676","season":3,"episode":5,"intro":{"start_sec":37,"end_sec":68,"start_ms":37000,"end_ms":68000,"confidence":1},"recap":null,"outro":{"start_ms":1242000,"end_ms":1292000},"post_credits":null}""",
        )
        assertEquals(SkipTimes(37_000, 68_000, 1_242_000, 1_292_000), introDb)
        val tidb = SkipTimesParser.parseTheIntroDb(
            """{"tmdb_id":2190,"type":"tv","season":5,"episode":4,"intro":[{"start_ms":null,"end_ms":33000}],"recap":[{"start_ms":0,"end_ms":0}],"credits":[{"start_ms":1296000,"end_ms":null}],"preview":[{"start_ms":0,"end_ms":0}]}""",
        )
        assertEquals(SkipTimes(0, 33_000, 1_296_000, null), tidb)
        assertEquals(SkipTimes.NONE, SkipTimesParser.parseIntroDb("""{"error":"media not found"}"""))
        assertEquals(SkipTimes.NONE, SkipTimesParser.parseIntroDb("""{"intro":null,"outro":null}"""))

        // Alone: used. Agreeing: used. Disagreeing (a different copy): neither.
        assertEquals(37_000L, SkipTimesParser.combine(introDb, SkipTimes.NONE, 1_320_000).introStartMs)
        assertEquals(37_000L, SkipTimesParser.combine(introDb, SkipTimes(39_000, 70_000), 1_320_000).introStartMs)
        assertNull(SkipTimesParser.combine(SkipTimes(30_000, 65_000), SkipTimes(86_000, 109_000), 1_320_000).introStartMs)
        // Credits that run to the end are an ending with no end.
        val sp = SkipTimesParser.combine(SkipTimes.NONE, tidb, 1_320_000)
        assertEquals(1_296_000L, sp.outroStartMs)
        assertNull(sp.outroEndMs)
    }

    @Test
    fun `the shows asked for skip from the start, under whatever name the provider uses`() {
        fun choice(name: String) = ThemeSkipChoice.defaultFor(ThemeSkipChoice.showKey(name))
        for (name in listOf("The Office", "EN| The Office (US)", "The Office US", "The.Office.2005", "Office, The")) {
            assertEquals(name, ThemeSkipChoice(intro = true), choice(name))
        }
        for (name in listOf("South Park", "4K| South Park (1997)")) assertEquals(name, ThemeSkipChoice(intro = true), choice(name))
        for (name in listOf("Hunter x Hunter", "Hunter x Hunter (2011)", "Hunter × Hunter", "HUNTER X HUNTER [MULTI]")) {
            assertEquals(name, ThemeSkipChoice(intro = true, ending = true), choice(name))
        }
        for (name in listOf("Breaking Bad", "The Office Ladies", "Office Space", "South Pacific")) {
            assertEquals(name, ThemeSkipChoice.OFF, choice(name))
        }
        val saved = setOf(ThemeSkipChoice(intro = false, ending = true).encode("SOUTHPARK"))
        assertEquals(mapOf("SOUTHPARK" to ThemeSkipChoice(false, true)), ThemeSkipChoice.decode(saved))
    }
}
