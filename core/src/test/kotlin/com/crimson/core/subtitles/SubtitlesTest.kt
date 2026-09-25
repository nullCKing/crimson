package com.crimson.core.subtitles

import com.crimson.core.catalog.TitleIndex
import com.crimson.core.catalog.TitleKind
import com.crimson.core.catalog.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import kotlin.math.abs
import kotlin.random.Random

class SubtitlesTest {

    // ------------------------------------------------------------------ parsing

    @Test
    fun `reads a messy srt`() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<font color=\"#ffff00\">Hello there.</font>\r\n\r\n" +
            "2\r\n00:00:04.2 --> 00:00:06,000 X1:40 X2:600\r\n{\\an8}<I>General Kenobi.</I>\r\n" +
            // No blank line before the next cue's number.
            "3\r\n00:00:07,000 --> 00:00:08,000\r\n- One.\r\n- Two.\r\n\r\n" +
            "4\r\n00:00:09,000 --> 00:00:09,000\r\nZero length.\r\n\r\n" +
            "5\r\nnot a timing line\r\nlost\r\n\r\n" +
            "6\r\n00:00:12,000 --> 00:00:13,000\r\n<i>Unclosed italics\r\n"
        val track = SubtitleParser.parse(srt.toByteArray(Charsets.UTF_8))
        assertEquals(5, track.cues.size)
        assertEquals(SubtitleCue(1_000, 3_500, "Hello there."), track.cues[0])
        assertEquals(SubtitleCue(4_200, 6_000, "<i>General Kenobi.</i>"), track.cues[1])
        assertEquals("- One.\n- Two.", track.cues[2].text)
        assertEquals(11_000, track.cues[3].endMs)
        assertEquals("<i>Unclosed italics</i>", track.cues[4].text)
    }

    @Test
    fun `reads webvtt, with and without hours`() {
        val vtt = """
            WEBVTT

            00:01.000 --> 00:02.500 align:start
            First &amp; foremost

            intro
            01:00:00.000 --> 01:00:01.000
            An hour in
        """.trimIndent()
        val track = SubtitleParser.parse(vtt)
        assertEquals(listOf(SubtitleCue(1_000, 2_500, "First & foremost"), SubtitleCue(3_600_000, 3_601_000, "An hour in")), track.cues)
    }

    @Test
    fun `decodes windows-1252 when the bytes are not utf-8`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nCafé – “quoted”\n".toByteArray(charset("windows-1252"))
        assertEquals("Café – “quoted”", SubtitleParser.parse(bytes).cues.single().text)
    }

    @Test
    fun `finds the cues on screen, overlaps included`() {
        val track = SubtitleTrack(listOf(
            SubtitleCue(0, 1_000, "a"),
            SubtitleCue(900, 5_000, "b"),
            SubtitleCue(2_000, 3_000, "c"),
            SubtitleCue(6_000, 7_000, "d"),
        ))
        assertEquals(listOf("a", "b"), track.at(950).map { it.text })
        assertEquals(listOf("b", "c"), track.at(2_000).map { it.text })
        assertTrue(track.at(5_500).isEmpty())
        assertTrue(track.at(-1).isEmpty())
        assertEquals(listOf("d"), track.at(6_999).map { it.text })
        assertTrue(track.at(7_000).isEmpty())
    }

    @Test
    fun `strips sound descriptions and speaker labels`() {
        val track = SubtitleTrack(listOf(
            SubtitleCue(0, 1, "[DOOR SLAMS]"),
            SubtitleCue(1, 2, "JOHN: Get down! (GUNSHOT)"),
            SubtitleCue(2, 3, "- Hi.\n- [laughs]"),
            SubtitleCue(3, 4, "♪ ♪"),
            SubtitleCue(4, 5, "<i>(whispering) Over here.</i>"),
            SubtitleCue(5, 6, "It's 5:30 A.M."),
        ))
        val stripped = SoundDescriptions.strip(track).cues.map { it.text }
        assertEquals(listOf("Get down!", "Hi.", "<i>Over here.</i>", "It's 5:30 A.M."), stripped)
    }

    // ------------------------------------------------------------------ search results and ranking

    @Test
    fun `reads an OpenSubtitles search, English only`() {
        val json = """
            {"total_count":3,"data":[
              {"id":"1","type":"subtitle","attributes":{"language":"en","download_count":5120,"hearing_impaired":true,
                "fps":23.976,"ratings":8.5,"from_trusted":true,"ai_translated":false,"machine_translated":false,
                "release":"The.Matrix.1999.1080p.BluRay","moviehash_match":false,
                "files":[{"file_id":111,"file_name":"matrix.srt"}]}},
              {"id":"2","type":"subtitle","attributes":{"language":"fr","download_count":9,"files":[{"file_id":222}]}},
              {"id":"3","type":"subtitle","attributes":{"language":"en","download_count":"12","machine_translated":true,
                "fps":0,"files":[{"file_id":333,"file_name":"x.srt"}]}}
            ]}
        """.trimIndent()
        val found = SubtitleResults.parseOpenSubtitles(StringReader(json))
        assertEquals(listOf("111", "333"), found.map { it.id })
        val first = found[0]
        assertEquals(5120, first.downloads)
        assertEquals(23.976, first.fps!!, 1e-9)
        assertTrue(first.hearingImpaired == true && first.trusted && !first.translated)
        assertEquals("The.Matrix.1999.1080p.BluRay", first.release)
        assertTrue(found[1].translated)
        assertNull(found[1].fps)
    }

    @Test
    fun `reads the addon's answer`() {
        val json = """{"subtitles":[
            {"id":"9","url":"https://subs/9","lang":"eng","m":"h","subtitleFileName":"Show.S01E01.SDH.srt","fpsMilli":25000},
            {"id":"8","url":"https://subs/8","lang":"ger"},
            {"id":"7","url":"","lang":"eng"}]}"""
        val found = SubtitleResults.parseAddon(StringReader(json))
        assertEquals(1, found.size)
        assertTrue(found[0].hashMatch)
        assertEquals(25.0, found[0].fps!!, 1e-9)
        assertEquals(true, found[0].hearingImpaired)
    }

    @Test
    fun `ranks a hash match first and machine translations last`() {
        val popular = SubtitleCandidate(SubtitleCandidate.Source.OPENSUBTITLES, "popular", downloads = 90_000, fps = 23.976)
        val hash = SubtitleCandidate(SubtitleCandidate.Source.OPENSUBTITLES, "hash", downloads = 10, hashMatch = true)
        val machine = SubtitleCandidate(SubtitleCandidate.Source.OPENSUBTITLES, "machine", downloads = 900_000, translated = true)
        val telecine = SubtitleCandidate(SubtitleCandidate.Source.OPENSUBTITLES, "telecine", downloads = 90_000, fps = 29.97)
        val cam = SubtitleCandidate(SubtitleCandidate.Source.OPENSUBTITLES, "cam", downloads = 90_000, fps = 23.976, release = "Film.2024.HDCAM.x264")
        val ranked = SubtitleRanking.rank(listOf(machine, cam, telecine, popular, hash), videoFps = 23.976, preferSdh = false)
        assertEquals(listOf("hash", "popular", "telecine", "cam", "machine"), ranked.map { it.id })
    }

    @Test
    fun `frame-rate corrections for the common pairs only`() {
        assertEquals(25.0 / 23.976, FrameRates.correction(25.0, 23.976)!!, 1e-9)
        assertEquals(23.976 / 25.0, FrameRates.correction(23.976, 25.0)!!, 1e-9)
        assertNull(FrameRates.correction(23.976, 23.976))
        // Telecine: a different frame rate, the same running time.
        assertNull(FrameRates.correction(29.97, 23.976))
        assertNull(FrameRates.correction(23.976, 17.0))
    }

    @Test
    fun `episode queries carry the series id and numbers`() {
        val q = SubtitleQuery("Breaking Bad", 2008, "tt0903747", season = 1, episode = 2)
        assertTrue(q.isEpisode)
        assertEquals("903747", q.imdbNumber)
        assertFalse(SubtitleQuery("X", null, null).isEpisode)
    }

    // ------------------------------------------------------------------ sync

    /** Speech-like audio: loud where [truth] has captions, with noise and loud non-speech bursts. */
    private fun speechFor(truth: SubtitleTrack, durationMs: Long, seed: Int = 7): SpeechTimeline {
        val random = Random(seed)
        val speech = SpeechTimeline(durationMs)
        var t = 0L
        while (t < durationMs) {
            val talking = truth.at(t).isNotEmpty()
            // Explosions and music: loud stretches with no captions.
            val burst = (t / 1000) % 97 < 4
            val level = when {
                talking -> -32f
                burst -> -30f
                else -> -52f
            } + random.nextFloat() * 10f - 5f
            speech.record(t, level)
            t += SpeechTimeline.BIN_MS
        }
        return speech
    }

    private fun dialogue(durationMs: Long, seed: Int = 3): SubtitleTrack {
        val random = Random(seed)
        val cues = ArrayList<SubtitleCue>()
        var t = 5_000L
        while (t < durationMs) {
            val length = 800L + random.nextLong(3_500)
            cues += SubtitleCue(t, t + length, "line")
            t += length + 300 + random.nextLong(6_000)
        }
        return SubtitleTrack(cues)
    }

    @Test
    fun `finds a fixed offset`() {
        val duration = 10 * 60_000L
        val truth = dialogue(duration)
        val speech = speechFor(truth, duration)
        // The file's captions run 4.3 s early.
        val file = truth.retimed(-4_300)
        val fix = SubtitleSync.estimate(file, speech)
        println("offset fix: $fix")
        assertNotNull(fix)
        assertEquals(4_300.0, fix!!.offsetMs.toDouble(), 150.0)
        assertEquals(1.0, fix.scale, 1e-9)
    }

    @Test
    fun `finds a PAL speed-up`() {
        val duration = 20 * 60_000L
        val truth = dialogue(duration, seed = 11)
        val speech = speechFor(truth, duration, seed = 5)
        // Timed against a 25 fps copy: every caption comes early by 4%.
        val file = truth.retimed(0, 23.976 / 25.0)
        val fix = SubtitleSync.estimate(file, speech)
        assertNotNull(fix)
        assertEquals(25.0 / 23.976, fix!!.scale, 1e-9)
        assertTrue("offset ${fix.offsetMs}", abs(fix.offsetMs) <= 200)
    }

    @Test
    fun `leaves captions alone when the audio says nothing useful`() {
        val duration = 10 * 60_000L
        val random = Random(1)
        val noise = SpeechTimeline(duration)
        var t = 0L
        while (t < duration) { noise.record(t, -40f + random.nextFloat() * 20f); t += 100 }
        val guess = SubtitleSync.estimate(dialogue(duration), noise, scales = SubtitleSync.SCALES)
        println("noise guess: $guess")
        assertNull(guess)
        // Too little heard to decide.
        val short = SpeechTimeline(duration)
        for (ms in 0 until 30_000L step 100) short.record(ms, -30f)
        assertNull(SubtitleSync.estimate(dialogue(duration), short))
    }

    // ------------------------------------------------------------------ IMDb ids

    @Test
    fun `the title index gives IMDb ids`() {
        val index = """
            # header
            The Matrix	1999	M	8.7	2000000	Action,Sci-Fi	tt0133093
            The Matrix	2021	M	5.6	300000	Action,Sci-Fi	tt10838180
            Breaking Bad	2008	S	9.5	2000000	Crime,Drama	tt0903747
            Old Line	2000	M	7.0	5000	Drama
        """.trimIndent()
        val key = TitleMatcher.key("The Matrix").text
        assertEquals("tt0133093", TitleIndex.findImdbId(StringReader(index), TitleKind.MOVIE, listOf(key), 1999))
        assertEquals("tt10838180", TitleIndex.findImdbId(StringReader(index), TitleKind.MOVIE, listOf(key), 2021))
        assertEquals("tt0903747", TitleIndex.findImdbId(StringReader(index), TitleKind.SERIES, listOf(TitleMatcher.key("Breaking Bad").text), null))
        assertNull(TitleIndex.findImdbId(StringReader(index), TitleKind.SERIES, listOf(key), null))
        assertNull(TitleIndex.parseLine("Old Line\t2000\tM\t7.0\t5000\tDrama")!!.imdbId)
    }
}
