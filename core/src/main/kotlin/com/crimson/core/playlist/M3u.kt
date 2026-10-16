package com.crimson.core.playlist

import java.io.BufferedReader
import java.io.Reader

/** What an M3U entry plays, read from its URL. */
enum class EntryKind { LIVE, MOVIE, SERIES }

/**
 * One channel (or film) from an M3U playlist, as the playlist describes it.
 *
 * Curated playlists such as EPGenius's are the reason this exists: they rename the provider's
 * channels, put them in sensible groups and, most usefully, give every channel a `tvg-id` that
 * matches a channel id in their own XMLTV guide exactly, so the guide attaches by id instead of
 * by guessing from names.
 */
data class M3uEntry(
    /** The text after the comma in `#EXTINF`, which players show. */
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    /** `group-title`, or an `#EXTGRP` line. */
    val group: String? = null,
    /** `tvg-chno`: a channel number the playlist asks for. */
    val channelNumber: Int? = null,
    /** From `#EXTVLCOPT:http-user-agent=` or a `user-agent` attribute. */
    val userAgent: String? = null,
    /** From `#EXTVLCOPT:http-referrer=`. */
    val referrer: String? = null,
    val kind: EntryKind = EntryKind.LIVE,
) {
    /**
     * A section heading dressed up as a channel: `### LOCAL NETWORKS ###`, `===== SPORTS =====`.
     * Curated playlists use these to divide a long group in players that show one flat list; they
     * point at an arbitrary stream and are not channels.
     */
    val isSeparator: Boolean get() = SEPARATOR.matches(name.trim())

    private companion object {
        val SEPARATOR = Regex("""^([#=*~_\-]{2,}).*\1$|^[#=*~_\-\s]+$""")
    }
}

/** The `#EXTM3U` line's attributes. */
data class M3uHeader(
    /** Guide URLs from `url-tvg` / `x-tvg-url`; several may be given, comma-separated. */
    val epgUrls: List<String> = emptyList(),
)

/**
 * A streaming M3U reader: one entry at a time, never the whole playlist.
 *
 * A provider's full M3U, films and series included, is commonly 100,000+ entries and tens of
 * megabytes; a Fire Stick Lite has no room to hold it as a list. Each entry goes to the callback
 * and out of scope.
 *
 * The format is informal, so this is tolerant: attributes in any order, single or double quotes,
 * `#EXTGRP` and `#EXTVLCOPT` lines between `#EXTINF` and the URL, blank lines, Windows line ends,
 * a byte-order mark, and URLs with no `#EXTINF` before them (named after their last path segment).
 */
object M3uParser {

    /** Parses [reader] to the end. Returns how many entries were handed to [onEntry]. */
    fun parse(
        reader: Reader,
        onHeader: (M3uHeader) -> Unit = {},
        onEntry: (M3uEntry) -> Unit,
    ): Int {
        val lines = if (reader is BufferedReader) reader else BufferedReader(reader, 1 shl 16)
        var count = 0
        var first = true

        var info: String? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        while (true) {
            var line = lines.readLine() ?: break
            if (first) {
                line = line.trimStart('﻿')
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> onHeader(header(line))
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    group = null
                    userAgent = null
                    referrer = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    group = line.substring(8).trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substring(11).trim()
                    val eq = option.indexOf('=')
                    if (eq > 0) {
                        val value = option.substring(eq + 1).trim().ifEmpty { null }
                        when (option.substring(0, eq).trim().lowercase()) {
                            "http-user-agent" -> userAgent = value
                            "http-referrer", "http-referer" -> referrer = value
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    onEntry(entry(info, line, group, userAgent, referrer))
                    count++
                    info = null
                    group = null
                    userAgent = null
                    referrer = null
                }
            }
        }
        return count
    }

    private fun header(line: String): M3uHeader {
        val attrs = attributes(line)
        val urls = sequenceOf("url-tvg", "x-tvg-url", "tvg-url")
            .mapNotNull { attrs[it] }
            .flatMap { it.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
            .toList()
        return M3uHeader(urls)
    }

    private fun entry(info: String?, url: String, extGroup: String?, ua: String?, referrer: String?): M3uEntry {
        val comma = info?.let(::nameComma) ?: -1
        val attrs = when {
            info == null -> emptyMap()
            comma >= 0 -> attributes(info.substring(0, comma))
            else -> attributes(info)
        }
        val name = info?.takeIf { comma >= 0 }?.substring(comma + 1)?.trim()?.ifEmpty { null }
            ?: attrs["tvg-name"]
            ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return M3uEntry(
            name = name,
            url = url,
            tvgId = attrs["tvg-id"]?.ifEmpty { null },
            tvgName = attrs["tvg-name"]?.ifEmpty { null },
            logo = attrs["tvg-logo"]?.ifEmpty { null },
            group = attrs["group-title"]?.ifEmpty { null } ?: extGroup,
            channelNumber = (attrs["tvg-chno"] ?: attrs["channel-number"])?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            userAgent = ua ?: attrs["user-agent"]?.ifEmpty { null } ?: attrs["http-user-agent"]?.ifEmpty { null },
            referrer = referrer,
            kind = XtreamUrl.kindOf(url),
        )
    }

    /**
     * The name after the attributes: the text after the first comma that is not inside quotes.
     * Group titles and names both contain commas often enough ("News, Weather") that the first
     * comma on the line is not good enough.
     */
    fun displayName(info: String): String =
        nameComma(info).let { if (it < 0) "" else info.substring(it + 1).trim() }

    private fun nameComma(info: String): Int {
        var quote: Char? = null
        for (i in info.indices) {
            val c = info[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || (c == '\'' && i > 0 && info[i - 1] == '=') -> quote = c
                c == ',' -> return i
            }
        }
        return -1
    }

    /** `key="value"` pairs on an `#EXTINF` or `#EXTM3U` line, keys lower-cased. */
    fun attributes(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = line.length
        while (i < n) {
            val eq = line.indexOf('=', i)
            if (eq < 0) break
            // The key is the run of name characters right before '='.
            var k = eq - 1
            while (k >= 0 && (line[k].isLetterOrDigit() || line[k] == '-' || line[k] == '_')) k--
            val key = line.substring(k + 1, eq).lowercase()
            var v = eq + 1
            if (v >= n) break
            val value: String
            val q = line[v]
            if (q == '"' || q == '\'') {
                val end = line.indexOf(q, v + 1).let { if (it < 0) n else it }
                value = line.substring(v + 1, end)
                i = end + 1
            } else {
                // Unquoted: up to the next space or comma.
                while (v < n && line[v] != ' ' && line[v] != ',') v++
                value = line.substring(eq + 1, v)
                i = v
                // A bare comma ends the attributes: what follows is the display name.
                if (v < n && line[v] == ',') {
                    if (key.isNotEmpty()) out[key] = value
                    break
                }
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }
}

/**
 * The parts of an Xtream Codes stream URL, where the account is in the path:
 * `http://host:port/live/USER/PASS/123.ts`, `/movie/USER/PASS/123.mkv`, or the short live form
 * `http://host:port/USER/PASS/123`.
 */
data class XtreamUrl(
    val base: String,
    val username: String,
    val password: String,
    val streamId: Long,
    val kind: EntryKind,
    /** `ts`, `m3u8`, `mkv`… or empty for the short form. */
    val extension: String,
) {
    companion object {
        private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

        /** Reads [url], or returns null when it is not shaped like an Xtream stream URL. */
        fun parse(url: String): XtreamUrl? {
            val scheme = url.indexOf("://")
            if (scheme <= 0) return null
            val pathStart = url.indexOf('/', scheme + 3)
            if (pathStart < 0) return null
            val base = url.substring(0, pathStart)
            val path = url.substring(pathStart + 1).substringBefore('?').substringBefore('#').trimEnd('/')
            val parts = path.split('/')
            if (parts.size < 3) return null

            val last = parts.last()
            val dot = last.lastIndexOf('.')
            val idText = if (dot > 0) last.substring(0, dot) else last
            val ext = if (dot > 0) last.substring(dot + 1).lowercase() else ""
            val id = idText.toLongOrNull() ?: return null

            return when {
                parts.size >= 4 && parts[parts.size - 4].lowercase() in setOf("live", "movie", "series") -> {
                    val kind = when (parts[parts.size - 4].lowercase()) {
                        "movie" -> EntryKind.MOVIE
                        "series" -> EntryKind.SERIES
                        else -> EntryKind.LIVE
                    }
                    val prefix = parts.subList(0, parts.size - 4).joinToString("") { "/$it" }
                    XtreamUrl(base + prefix, parts[parts.size - 3], parts[parts.size - 2], id, kind, ext)
                }
                // The short form is live only; anything with a film's extension is not a channel.
                parts.size == 3 && ext !in VOD_EXTENSIONS ->
                    XtreamUrl(base, parts[0], parts[1], id, EntryKind.LIVE, ext)
                else -> null
            }
        }

        /** What [url] plays: by the Xtream path when it has one, otherwise by its extension. */
        fun kindOf(url: String): EntryKind {
            parse(url)?.let { return it.kind }
            val path = url.substringBefore('?').lowercase()
            return if (path.substringAfterLast('.', "") in VOD_EXTENSIONS) EntryKind.MOVIE else EntryKind.LIVE
        }

        /**
         * [url] asking for the live stream as MPEG-TS (`ts`) or HLS (`m3u8`), when it is an Xtream
         * live URL; anything else is returned unchanged, because a URL the app does not understand
         * is safer played as given.
         */
        fun withFormat(url: String, format: String): String {
            val x = parse(url) ?: return url
            if (x.kind != EntryKind.LIVE) return url
            val wanted = if (format == "m3u8") "m3u8" else "ts"
            if (x.extension == wanted) return url
            // The short form without an extension already is MPEG-TS.
            if (x.extension.isEmpty() && wanted == "ts") return url
            if (x.extension.isNotEmpty() && x.extension != "ts" && x.extension != "m3u8") return url
            val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
            return "${x.base}/live/${x.username}/${x.password}/${x.streamId}.$wanted$query"
        }
    }
}
