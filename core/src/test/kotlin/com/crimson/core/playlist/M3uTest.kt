package com.crimson.core.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class M3uTest {

    private fun parse(text: String): Pair<M3uHeader?, List<M3uEntry>> {
        var header: M3uHeader? = null
        val out = ArrayList<M3uEntry>()
        M3uParser.parse(StringReader(text), onHeader = { header = it }, onEntry = out::add)
        return header to out
    }

    @Test
    fun `a curated playlist entry keeps its guide id, group, logo and name`() {
        val (header, entries) = parse(
            """
            #EXTM3U url-tvg="https://example.org/epg6.xml.gz"
            #EXTINF:-1 tvg-id="ESPN.us" tvg-name="US: ESPN" tvg-logo="https://logo.example/espn.png" group-title="US Sports",ESPN
            http://line.example:8080/live/user/pass/12345.ts
            """.trimIndent()
        )
        assertEquals(listOf("https://example.org/epg6.xml.gz"), header!!.epgUrls)
        val e = entries.single()
        assertEquals("ESPN", e.name)
        assertEquals("ESPN.us", e.tvgId)
        assertEquals("US: ESPN", e.tvgName)
        assertEquals("https://logo.example/espn.png", e.logo)
        assertEquals("US Sports", e.group)
        assertEquals(EntryKind.LIVE, e.kind)
        assertEquals("http://line.example:8080/live/user/pass/12345.ts", e.url)
    }

    @Test
    fun `commas and equals signs inside quotes or the name do not break it`() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.us" group-title="News, Weather",Fox News, Live = Now
            http://h/u/p/1
            """.trimIndent()
        )
        val e = entries.single()
        assertEquals("News, Weather", e.group)
        assertEquals("Fox News, Live = Now", e.name)
        assertEquals("news.us", e.tvgId)
    }

    @Test
    fun `EXTGRP, EXTVLCOPT, blank lines, CRLF and a byte order mark are handled`() {
        val text = "﻿#EXTM3U x-tvg-url=\"https://a/1.xml,https://b/2.xml.gz\"\r\n\r\n" +
            "#EXTINF:-1 tvg-chno=\"7\",Channel Seven\r\n" +
            "#EXTGRP:Locals\r\n" +
            "#EXTVLCOPT:http-user-agent=TestAgent/1.0\r\n" +
            "#EXTVLCOPT:http-referrer=https://ref.example/\r\n" +
            "http://h/live/u/p/7.m3u8\r\n"
        val (header, entries) = parse(text)
        assertEquals(listOf("https://a/1.xml", "https://b/2.xml.gz"), header!!.epgUrls)
        val e = entries.single()
        assertEquals("Channel Seven", e.name)
        assertEquals("Locals", e.group)
        assertEquals(7, e.channelNumber)
        assertEquals("TestAgent/1.0", e.userAgent)
        assertEquals("https://ref.example/", e.referrer)
    }

    @Test
    fun `options from one entry do not leak into the next`() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTVLCOPT:http-user-agent=A
            #EXTGRP:G
            http://h/u/p/1
            #EXTINF:-1,Two
            http://h/u/p/2
            """.trimIndent()
        )
        assertEquals("A", entries[0].userAgent)
        assertNull(entries[1].userAgent)
        assertNull(entries[1].group)
    }

    @Test
    fun `films and series are told apart from channels`() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Film
            http://h/movie/u/p/10.mkv
            #EXTINF:-1,Episode
            http://h/series/u/p/11.mp4
            #EXTINF:-1,Channel
            http://h/u/p/12
            #EXTINF:-1,Other film
            https://cdn.example/files/film.mp4
            """.trimIndent()
        )
        assertEquals(listOf(EntryKind.MOVIE, EntryKind.SERIES, EntryKind.LIVE, EntryKind.MOVIE), entries.map { it.kind })
    }

    @Test
    fun `a URL with no EXTINF is named after its last segment`() {
        val (_, entries) = parse("#EXTM3U\nhttp://h/stream/news.m3u8?token=1\n")
        assertEquals("news.m3u8", entries.single().name)
    }

    @Test
    fun `section headings are told apart from channels`() {
        fun e(name: String) = M3uEntry(name = name, url = "http://h/u/p/1")
        assertTrue(e("### LOCAL NETWORKS ###").isSeparator)
        assertTrue(e("===== SPORTS =====").isSeparator)
        assertTrue(e("------").isSeparator)
        assertFalse(e("NBC [Scranton] 1080p").isSeparator)
        assertFalse(e("#1 Hits").isSeparator)
        assertFalse(e("(US) (ESPN+ 516) | Live").isSeparator)
    }

    @Test
    fun `tvg-chno of null is no number`() {
        val (_, entries) = parse("#EXTM3U\n#EXTINF:0 tvg-chno=\"null\" tvg-id=\"a.us\",A\nhttp://h/live/u/p/1.ts\n")
        assertNull(entries.single().channelNumber)
    }

    @Test
    fun `unquoted attributes are read`() {
        val attrs = M3uParser.attributes("#EXTINF:-1 tvg-id=abc.us tvg-chno=5,Name")
        assertEquals("abc.us", attrs["tvg-id"])
        assertEquals("5", attrs["tvg-chno"])
    }

    @Test
    fun `Xtream URLs are read in the long and short forms`() {
        val long = XtreamUrl.parse("http://line.example:8080/live/user/pa55/12345.ts")!!
        assertEquals("http://line.example:8080", long.base)
        assertEquals("user", long.username)
        assertEquals("pa55", long.password)
        assertEquals(12345L, long.streamId)
        assertEquals("ts", long.extension)

        val short = XtreamUrl.parse("http://line.example:8080/user/pa55/12345")!!
        assertEquals(12345L, short.streamId)
        assertEquals(EntryKind.LIVE, short.kind)
        assertEquals("", short.extension)

        assertNull(XtreamUrl.parse("https://cdn.example/hls/news/index.m3u8"))
        assertNull(XtreamUrl.parse("not a url"))
    }

    @Test
    fun `the stream format is switched only on Xtream live URLs`() {
        assertEquals(
            "http://h:80/live/u/p/9.m3u8",
            XtreamUrl.withFormat("http://h:80/live/u/p/9.ts", "m3u8"),
        )
        assertEquals(
            "http://h:80/live/u/p/9.ts",
            XtreamUrl.withFormat("http://h:80/live/u/p/9.m3u8", "ts"),
        )
        assertEquals("http://h/live/u/p/9.m3u8", XtreamUrl.withFormat("http://h/u/p/9", "m3u8"))
        assertEquals("http://h/u/p/9", XtreamUrl.withFormat("http://h/u/p/9", "ts"))
        assertEquals("http://h/movie/u/p/9.mkv", XtreamUrl.withFormat("http://h/movie/u/p/9.mkv", "m3u8"))
        assertEquals("https://cdn/x/index.m3u8", XtreamUrl.withFormat("https://cdn/x/index.m3u8", "ts"))
    }
}
