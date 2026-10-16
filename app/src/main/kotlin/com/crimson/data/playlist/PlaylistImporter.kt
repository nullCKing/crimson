package com.crimson.data.playlist

import android.util.Log
import com.crimson.core.epg.ChannelMatcher
import com.crimson.core.playlist.EntryKind
import com.crimson.core.playlist.M3uEntry
import com.crimson.core.playlist.M3uParser
import com.crimson.core.playlist.XtreamUrl
import com.crimson.core.text.NameCleaner
import com.crimson.data.db.CategoryEntity
import com.crimson.data.db.ChannelEntity
import com.crimson.data.db.CrimsonDatabase
import com.crimson.data.xtream.ImportProgress
import com.crimson.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.zip.GZIPInputStream

/**
 * Replaces the provider's channel list with the channels in an M3U playlist.
 *
 * Built for curated playlists like EPGenius's: their channel names are cleaned up, their groups
 * make sense, and each channel's `tvg-id` is an id in the matching XMLTV guide, so the guide
 * attaches by id rather than by guessing from names. The streams in such a playlist are still the
 * provider's, at the provider's own URLs; the playlist chooses and labels them.
 *
 * The playlist is downloaded to a file before anything in the database changes, so a link that
 * fails, or answers with a web page instead of a playlist (which is what Google Drive does when it
 * wants a confirmation click), leaves the current list as it was. The file is then read one entry
 * at a time and written in batches inside one transaction: the list is replaced whole or not at
 * all.
 *
 * Only live entries are kept. Films and series still come from the Xtream login, where they have
 * posters, seasons and descriptions an M3U line does not carry.
 */
class PlaylistImporter(
    private val http: OkHttpClient,
    private val db: CrimsonDatabase,
    private val cacheDir: File,
) {

    /** True when the playlist was read more than [REFRESH_AFTER_MS] ago. */
    suspend fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - (db.categoryDao().lastPlaylistImport() ?: 0L) >= REFRESH_AFTER_MS

    fun import(playlistUrl: String): Flow<ImportProgress> = flow {
        emit(ImportProgress.ReadingCategories)
        val file = File(cacheDir, "playlist.m3u")
        download(playlistUrl.trim(), file)

        val startedAt = System.currentTimeMillis()
        val result = file.reader(Charsets.UTF_8).buffered(1 shl 16).use { reader ->
            db.runInTransaction(Callable { write(reader, startedAt) })
        }
        file.delete()
        Log.i(TAG, "playlist: ${result.kept} channels in ${result.groups} groups from ${result.seen} entries " +
            "(${result.notLive} films/series, ${result.duplicates} repeats and section headings skipped)")
        if (result.kept == 0) {
            throw PlaylistException(
                if (result.seen == 0) "The playlist had no channels in it."
                else "The playlist had ${result.seen} entries but no live channels."
            )
        }
        emit(ImportProgress.Done(result.seen, result.kept, 0, guideUrls = result.guideUrls))
    }.flowOn(Dispatchers.IO)

    private class Result(
        val seen: Int,
        val kept: Int,
        val groups: Int,
        val notLive: Int,
        val duplicates: Int,
        val guideUrls: List<String>,
    )

    private fun write(reader: java.io.Reader, now: Long): Result {
        val batch = ArrayList<ChannelEntity>(BATCH_SIZE)
        val seenIds = HashSet<Long>()
        val usedNumbers = HashSet<Int>()
        val groups = LinkedHashSet<String>()
        var guideUrls = emptyList<String>()
        var next = 1
        var kept = 0
        var notLive = 0
        var duplicates = 0
        var separators = 0

        fun flush() {
            if (batch.isEmpty()) return
            db.channelDao().upsertAllBlocking(batch)
            batch.clear()
        }

        val seen = M3uParser.parse(
            reader,
            onHeader = { guideUrls = it.epgUrls },
        ) { entry ->
            if (entry.kind != EntryKind.LIVE) {
                notLive++
                return@parse
            }
            if (entry.isSeparator) {
                separators++
                return@parse
            }
            val group = entry.group?.trim()?.ifEmpty { null } ?: UNGROUPED
            // A stream listed in several groups (EPGenius puts CNN under "TV Guide (USA)" and
            // "News Networks") stays in each: the first listing keeps the provider's stream id,
            // later ones get an id of their own. The same stream twice in one group is a repeat.
            var streamId = streamIdOf(entry)
            if (!seenIds.add(streamId)) {
                streamId = syntheticId(entry.url + "\u0000" + group)
                if (!seenIds.add(streamId)) {
                    duplicates++
                    return@parse
                }
            }
            // The playlist's own number when it asks for one that is free; otherwise the next
            // number in playlist order, which keeps the curator's order in the guide.
            val number = entry.channelNumber?.takeIf { it !in usedNumbers }
                ?: run {
                    while (next in usedNumbers) next++
                    next
                }
            usedNumbers.add(number)
            groups.add(group)
            val display = NameCleaner.clean(entry.name).ifBlank { entry.name.trim() }
            val tvgId = entry.tvgId?.trim()?.ifEmpty { null }
            batch.add(
                ChannelEntity(
                    streamId = streamId,
                    number = number,
                    originalName = entry.name,
                    displayName = display,
                    shortName = NameCleaner.shortName(display),
                    // Only for display and for keeping guide name-matches inside a country; a
                    // playlist channel is never filtered by country.
                    country = tvgId?.let(ChannelMatcher::countryOfId).orEmpty(),
                    market = null,
                    categoryId = categoryIdOf(group),
                    categoryName = group,
                    channelKey = ChannelEntity.key(tvgId, streamId),
                    epgChannelId = tvgId,
                    streamIcon = entry.logo,
                    tvArchive = false,
                    lastSeenAt = now,
                    streamUrl = entry.url,
                    userAgent = entry.userAgent,
                )
            )
            kept++
            if (batch.size >= BATCH_SIZE) flush()
        }
        flush()

        if (kept > 0) {
            // The old list goes only once the new one is in: the provider's channels on the
            // first import, and channels the playlist dropped on later ones.
            db.channelDao().deleteSeenBeforeBlocking(now)
            db.categoryDao().clearBlocking()
            db.categoryDao().upsertAllBlocking(
                groups.map { name ->
                    CategoryEntity(
                        categoryId = categoryIdOf(name),
                        name = name,
                        country = null,
                        market = null,
                        indicatesLocals = false,
                        foreignMarker = null,
                        imported = true,
                        lastImportedAt = now,
                    )
                }
            )
        }
        return Result(seen, kept, groups.size, notLive, duplicates + separators, guideUrls)
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", XtreamClient.USER_AGENT)
            // Identity, so a .m3u.gz arrives as the file it is and is unwrapped below.
            .header("Accept-Encoding", "identity")
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PlaylistException("Could not download the playlist: ${e.message ?: e.javaClass.simpleName}", e)
        }
        response.use {
            if (!it.isSuccessful) throw PlaylistException("The playlist link answered HTTP ${it.code}.")
            val body = it.body ?: throw PlaylistException("The playlist link returned nothing.")
            val raw = BufferedInputStream(body.byteStream(), 1 shl 16)
            val input: InputStream = if (looksGzipped(raw)) GZIPInputStream(raw, 1 shl 16) else raw
            val buffered = BufferedInputStream(input, 1 shl 16)
            checkLooksLikePlaylist(buffered)
            target.outputStream().use { out -> buffered.copyTo(out, 1 shl 16) }
        }
    }

    private fun looksGzipped(stream: BufferedInputStream): Boolean {
        stream.mark(2)
        val a = stream.read()
        val b = stream.read()
        stream.reset()
        return a == 0x1F && b == 0x8B
    }

    /**
     * Refuses a response that is plainly not a playlist, with a message that says what it was.
     * Google Drive answers a large file's download link with an HTML "can't scan this file for
     * viruses" page unless the link carries `confirm=t`.
     */
    private fun checkLooksLikePlaylist(stream: BufferedInputStream) {
        stream.mark(SNIFF_BYTES)
        val head = ByteArray(SNIFF_BYTES)
        var n = 0
        while (n < SNIFF_BYTES) {
            val r = stream.read(head, n, SNIFF_BYTES - n)
            if (r < 0) break
            n += r
        }
        stream.reset()
        val text = String(head, 0, n, Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        when {
            n == 0 -> throw PlaylistException("The playlist link returned an empty file.")
            text.startsWith("#EXTM3U", ignoreCase = true) || text.startsWith("#EXTINF", ignoreCase = true) -> Unit
            text.startsWith("<") -> throw PlaylistException(
                if ("drive.google" in text || "Google Drive" in text)
                    "Google Drive returned a web page instead of the playlist. Use the direct download link " +
                        "(drive.usercontent.google.com/download?id=…&confirm=t)."
                else "The playlist link returned a web page, not a playlist."
            )
            text.startsWith("{") -> throw PlaylistException("The playlist link returned an error: ${text.take(80)}")
            else -> throw PlaylistException("That link is not an M3U playlist (it does not start with #EXTM3U).")
        }
    }

    companion object {
        private const val TAG = "CrimsonPlaylist"
        private const val BATCH_SIZE = 250
        private const val SNIFF_BYTES = 1024
        const val UNGROUPED = "Channels"

        /**
         * Re-read every twelve hours, in the background. A curated playlist's event slots
         * ("ESPN+ 516 | Bills at Jets (2026-10-19 13:00)") are named for what is on them when the
         * file was made, so a list read once goes stale within a day.
         */
        const val REFRESH_AFTER_MS = 12 * 60 * 60 * 1000L

        fun categoryIdOf(group: String) = "m3u:$group"

        /**
         * The provider's own stream id when the URL is an Xtream one, so favourites, the last
         * channel and the short-EPG fallback keep working; otherwise a stable id from the URL,
         * negative so it can never collide with a provider's.
         */
        fun streamIdOf(entry: M3uEntry): Long =
            XtreamUrl.parse(entry.url)?.streamId ?: syntheticId(entry.url)

        /** A stable negative id from [text] (FNV-1a), for links that carry no stream id. */
        fun syntheticId(text: String): Long {
            var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
            for (c in text) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return -((h ushr 11) + 1)
        }
    }
}

class PlaylistException(message: String, cause: Throwable? = null) : Exception(message, cause)
