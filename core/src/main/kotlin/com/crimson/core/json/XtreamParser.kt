package com.crimson.core.json

import com.crimson.core.model.RawCategory
import com.crimson.core.model.RawChannel
import java.io.Reader

/** What `player_api.php` returns for a bare login. */
data class LoginInfo(
    val authenticated: Boolean,
    val status: String?,
    val message: String?,
    val expiryEpochSeconds: Long?,
    val maxConnections: Int,
    val allowedOutputFormats: List<String>,
    val serverUrl: String?,
    val serverPort: String?,
    val serverHttpsPort: String?,
    val serverProtocol: String?,
    val timezone: String?,
) {
    /** The format to default to: whatever the provider lists first, per the spec. */
    val preferredFormat: String
        get() = allowedOutputFormats.firstOrNull { it == "ts" || it == "m3u8" } ?: "ts"
}

/** One entry from `get_short_epg`, still base64 encoded. */
data class ShortEpgEntry(
    val titleEncoded: String,
    val descriptionEncoded: String,
    val startEpochSeconds: Long,
    val stopEpochSeconds: Long,
)

/**
 * Parsers for the Xtream API, written against [JsonReader] so a stream list is never materialised.
 *
 * The channel parser takes a callback rather than returning a list: the import filters each
 * channel as it is read and discards the overwhelming majority immediately, so nothing
 * accumulates. That is the difference between a few hundred kilobytes of peak usage and a hundred
 * megabytes.
 */
object XtreamParser {

    fun parseLogin(reader: Reader): LoginInfo {
        var auth = false
        var status: String? = null
        var message: String? = null
        var expiry: Long? = null
        var maxConnections = 1
        val formats = ArrayList<String>(2)
        var url: String? = null
        var port: String? = null
        var httpsPort: String? = null
        var protocol: String? = null
        var timezone: String? = null

        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) {
                return LoginInfo(false, null, "unexpected response", null, 1, emptyList(),
                    null, null, null, null, null)
            }
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "user_info" -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "auth" -> auth = json.nextBoolean()
                                "status" -> status = json.nextString()
                                "message" -> message = json.nextString()
                                "exp_date" -> expiry = json.nextString()?.toLongOrNull()
                                "max_connections" ->
                                    maxConnections = json.nextString()?.toIntOrNull() ?: 1
                                "allowed_output_formats" -> {
                                    if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                        json.beginArray()
                                        while (json.hasNext()) {
                                            json.nextString()?.let { formats.add(it) }
                                        }
                                        json.endArray()
                                    } else {
                                        json.skipValue()
                                    }
                                }
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    "server_info" -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "url" -> url = json.nextString()
                                "port" -> port = json.nextString()
                                "https_port" -> httpsPort = json.nextString()
                                "server_protocol" -> protocol = json.nextString()
                                "timezone" -> timezone = json.nextString()
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }

        // A provider that reports `status: Active` without an explicit auth flag is authenticated.
        val effectiveAuth = auth || status.equals("Active", ignoreCase = true)
        return LoginInfo(
            authenticated = effectiveAuth,
            status = status,
            message = message,
            expiryEpochSeconds = expiry,
            maxConnections = maxConnections.coerceAtLeast(1),
            allowedOutputFormats = if (formats.isEmpty()) listOf("ts") else formats,
            serverUrl = url,
            serverPort = port,
            serverHttpsPort = httpsPort,
            serverProtocol = protocol,
            timezone = timezone,
        )
    }

    /** Categories are small — a few hundred entries — so a list is fine here. */
    fun parseCategories(reader: Reader): List<RawCategory> {
        val out = ArrayList<RawCategory>(64)
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return emptyList()
            json.beginArray()
            while (json.hasNext()) {
                json.beginObject()
                var id: String? = null
                var name: String? = null
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "category_id" -> id = json.nextString()
                        "category_name" -> name = json.nextString()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (id != null && name != null) out.add(RawCategory(id, name))
            }
            json.endArray()
        }
        return out
    }

    /**
     * Streams the live stream list, invoking [onChannel] once per entry.
     *
     * Returns the number of channels seen, which is what the discovery report's "total on the
     * server" figure comes from. Nothing is retained between callbacks.
     */
    fun streamChannels(reader: Reader, onChannel: (RawChannel) -> Unit): Int {
        var seen = 0
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return 0
            json.beginArray()
            while (json.hasNext()) {
                json.beginObject()
                var streamId = 0L
                var name: String? = null
                var categoryId: String? = null
                var epgId: String? = null
                var icon: String? = null
                var archive = 0
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "stream_id" -> streamId = json.nextLong()
                        "name" -> name = json.nextString()
                        "category_id" -> categoryId = json.nextString()
                        "epg_channel_id" -> epgId = json.nextString()
                        "stream_icon" -> icon = json.nextString()
                        "tv_archive" -> archive = json.nextInt()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                seen++
                if (streamId != 0L && !name.isNullOrBlank()) {
                    onChannel(RawChannel(streamId, name, categoryId, epgId, icon, archive))
                }
            }
            json.endArray()
        }
        return seen
    }

    fun parseShortEpg(reader: Reader): List<ShortEpgEntry> {
        val out = ArrayList<ShortEpgEntry>(8)
        JsonReader(reader).use { json ->
            val token = json.peek()
            if (token == JsonReader.Token.BEGIN_OBJECT) {
                json.beginObject()
                while (json.hasNext()) {
                    val name = json.nextName()
                    if (name == "epg_listings") {
                        if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            parseEpgArray(json, out)
                        } else {
                            json.skipValue()
                        }
                    } else {
                        json.skipValue()
                    }
                }
                json.endObject()
            } else if (token == JsonReader.Token.BEGIN_ARRAY) {
                parseEpgArray(json, out)
            }
        }
        return out
    }

    private fun parseEpgArray(json: JsonReader, out: MutableList<ShortEpgEntry>) {
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            var title = ""
            var desc = ""
            var start = 0L
            var stop = 0L
            var startStr: String? = null
            var endStr: String? = null
            while (json.hasNext()) {
                when (json.nextName()) {
                    "title" -> title = json.nextString().orEmpty()
                    "description" -> desc = json.nextString().orEmpty()
                    "start_timestamp" -> start = json.nextLong()
                    "stop_timestamp" -> stop = json.nextLong()
                    "start" -> startStr = json.nextString()
                    "end" -> endStr = json.nextString()
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (start == 0L && startStr != null) start = parseTimestamp(startStr)
            if (stop == 0L && endStr != null) stop = parseTimestamp(endStr)
            if (stop > start) out.add(ShortEpgEntry(title, desc, start, stop))
        }
        json.endArray()
    }

    private fun parseTimestamp(text: String): Long {
        text.toLongOrNull()?.let { return it }
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            sdf.parse(text)?.time?.div(1000L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** Parses VOD stream items from `get_vod_streams`. */
    fun parseVodStreams(reader: Reader): List<com.crimson.core.model.RawVodStream> {
        val out = ArrayList<com.crimson.core.model.RawVodStream>(64)
        streamVodStreams(reader) { out.add(it) }
        return out
    }

    /**
     * Streams `get_vod_streams` one film at a time.
     *
     * The whole catalogue is one JSON array that runs to tens of thousands of entries on a large
     * account — the same shape, and the same memory problem, as the live stream list. The caller
     * gets each item as it is read and writes in batches, so the array is never held at once.
     */
    fun streamVodStreams(reader: Reader, onVod: (com.crimson.core.model.RawVodStream) -> Unit): Int {
        var seen = 0
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return 0
            json.beginArray()
            while (json.hasNext()) {
                json.beginObject()
                var streamId = 0L
                var name = ""
                var categoryId: String? = null
                var icon: String? = null
                var rating: String? = null
                var extension: String? = null
                var added: Long? = null
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "added" -> added = json.nextString()?.toLongOrNull()
                        "stream_id", "vod_id" -> streamId = json.nextLong()
                        "name" -> name = json.nextString().orEmpty()
                        "category_id" -> categoryId = json.nextString()
                        "stream_icon" -> icon = json.nextString()
                        "rating", "rating_5based" -> rating = json.nextString()
                        "container_extension" -> extension = json.nextString()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                seen++
                if (streamId != 0L && name.isNotBlank()) {
                    onVod(com.crimson.core.model.RawVodStream(streamId, name, categoryId, icon, rating, extension, added))
                }
            }
            json.endArray()
        }
        return seen
    }

    /** Parses detailed movie info from `get_vod_info`. */
    fun parseVodInfo(reader: Reader, defaultVodId: Long): com.crimson.core.model.RawVodInfo {
        var streamId = defaultVodId
        var name = ""
        var desc: String? = null
        var duration: String? = null
        var releaseDate: String? = null
        var rating: String? = null
        var cast: String? = null
        var director: String? = null
        var cover: String? = null
        var backdrop: String? = null
        var extension: String? = null
        var genre: String? = null
        var durationSecs: Int? = null
        var mpaa: String? = null
        var trailer: String? = null

        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) {
                return com.crimson.core.model.RawVodInfo(defaultVodId, "")
            }
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "info" -> {
                        if (json.peek() == JsonReader.Token.BEGIN_OBJECT) {
                            json.beginObject()
                            while (json.hasNext()) {
                                when (json.nextName()) {
                                    "name" -> name = json.nextString().orEmpty()
                                    "description", "plot" -> desc = json.nextString()
                                    "duration" -> duration = json.nextString()
                                    "duration_secs" -> durationSecs = json.nextString()?.toIntOrNull()
                                    "genre" -> genre = json.nextString()
                                    "mpaa_rating", "mpaa", "age" -> json.nextString()?.takeIf { it.isNotBlank() }?.let { mpaa = it }
                                    "youtube_trailer" -> trailer = json.nextString()
                                    "releasedate", "release_date" -> releaseDate = json.nextString()
                                    "rating" -> rating = json.nextString()
                                    "cast", "actors" -> cast = json.nextString()
                                    "director" -> director = json.nextString()
                                    "cover_big", "movie_image" -> cover = json.nextString()
                                    "backdrop_path" -> {
                                        if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                            json.beginArray()
                                            if (json.hasNext()) backdrop = json.nextString()
                                            while (json.hasNext()) json.skipValue()
                                            json.endArray()
                                        } else {
                                            backdrop = json.nextString()
                                        }
                                    }
                                    else -> json.skipValue()
                                }
                            }
                            json.endObject()
                        } else json.skipValue()
                    }
                    "movie_data" -> {
                        if (json.peek() == JsonReader.Token.BEGIN_OBJECT) {
                            json.beginObject()
                            while (json.hasNext()) {
                                when (json.nextName()) {
                                    "stream_id" -> streamId = json.nextLong()
                                    "name" -> if (name.isBlank()) name = json.nextString().orEmpty() else json.skipValue()
                                    "container_extension" -> extension = json.nextString()
                                    else -> json.skipValue()
                                }
                            }
                            json.endObject()
                        } else json.skipValue()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        return com.crimson.core.model.RawVodInfo(
            streamId = streamId,
            name = name,
            description = desc,
            duration = duration,
            releaseDate = releaseDate,
            rating = rating,
            cast = cast,
            director = director,
            coverUrl = cover,
            backdropUrl = backdrop,
            containerExtension = extension,
            genre = genre,
            durationSecs = durationSecs,
            mpaa = mpaa,
            trailer = trailer,
        )
    }

    /** Parses series list from `get_series`. */
    fun parseSeries(reader: Reader): List<com.crimson.core.model.RawSeries> {
        val out = ArrayList<com.crimson.core.model.RawSeries>(64)
        streamSeries(reader) { out.add(it) }
        return out
    }

    /** Streams `get_series` one show at a time, for the same reason as [streamVodStreams]. */
    fun streamSeries(reader: Reader, onSeries: (com.crimson.core.model.RawSeries) -> Unit): Int {
        var seen = 0
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return 0
            json.beginArray()
            while (json.hasNext()) {
                json.beginObject()
                var seriesId = 0L
                var name = ""
                var categoryId: String? = null
                var cover: String? = null
                var plot: String? = null
                var rating: String? = null
                var releaseDate: String? = null
                var backdrop: String? = null
                var lastModified: Long? = null
                var genre: String? = null
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "backdrop_path" -> backdrop = firstString(json)
                        "last_modified" -> lastModified = json.nextString()?.toLongOrNull()
                        "genre" -> genre = json.nextString()
                        "series_id" -> seriesId = json.nextLong()
                        "name" -> name = json.nextString().orEmpty()
                        "category_id" -> categoryId = json.nextString()
                        "cover" -> cover = json.nextString()
                        "plot" -> plot = json.nextString()
                        "rating", "rating_5based" -> rating = json.nextString()
                        "releaseDate", "release_date" -> releaseDate = json.nextString()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                seen++
                if (seriesId != 0L && name.isNotBlank()) {
                    onSeries(com.crimson.core.model.RawSeries(seriesId, name, categoryId, cover, plot, rating, releaseDate, backdrop, lastModified, genre))
                }
            }
            json.endArray()
        }
        return seen
    }

    /** Parses series info, seasons and episodes from `get_series_info`. */
    fun parseSeriesInfo(reader: Reader, defaultSeriesId: Long): com.crimson.core.model.RawSeriesInfo {
        var name = ""
        var cover: String? = null
        var plot: String? = null
        var backdrop: String? = null
        var genre: String? = null
        var releaseDate: String? = null
        var cast: String? = null
        var director: String? = null
        var rating: String? = null
        val seasons = ArrayList<Int>()
        val episodes = LinkedHashMap<Int, ArrayList<com.crimson.core.model.RawEpisode>>()

        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) {
                return com.crimson.core.model.RawSeriesInfo(defaultSeriesId, "")
            }
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "info" -> {
                        if (json.peek() == JsonReader.Token.BEGIN_OBJECT) {
                            json.beginObject()
                            while (json.hasNext()) {
                                when (json.nextName()) {
                                    "name" -> name = json.nextString().orEmpty()
                                    "cover" -> cover = json.nextString()
                                    "plot" -> plot = json.nextString()
                                    "backdrop_path" -> backdrop = firstString(json)
                                    "genre" -> genre = json.nextString()
                                    "releaseDate", "release_date" -> releaseDate = json.nextString()
                                    "cast" -> cast = json.nextString()
                                    "director" -> director = json.nextString()
                                    "rating" -> rating = json.nextString()
                                    else -> json.skipValue()
                                }
                            }
                            json.endObject()
                        } else json.skipValue()
                    }
                    "seasons" -> {
                        if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            json.beginArray()
                            while (json.hasNext()) {
                                json.beginObject()
                                while (json.hasNext()) {
                                    if (json.nextName() == "season_number") {
                                        val sn = json.nextInt()
                                        if (sn !in seasons) seasons.add(sn)
                                    } else {
                                        json.skipValue()
                                    }
                                }
                                json.endObject()
                            }
                            json.endArray()
                        } else json.skipValue()
                    }
                    "episodes" -> {
                        if (json.peek() == JsonReader.Token.BEGIN_OBJECT) {
                            json.beginObject()
                            while (json.hasNext()) {
                                val seasonKey = json.nextName()
                                val seasonNum = seasonKey.toIntOrNull() ?: 1
                                if (seasonNum !in seasons) seasons.add(seasonNum)
                                val list = episodes.getOrPut(seasonNum) { ArrayList() }

                                if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                    json.beginArray()
                                    while (json.hasNext()) {
                                        json.beginObject()
                                        var epId = 0L
                                        var epNum = 0
                                        var epTitle = ""
                                        var ext: String? = null
                                        val epInfo = EpisodeInfo()
                                        while (json.hasNext()) {
                                            when (json.nextName()) {
                                                "id" -> epId = json.nextLong()
                                                "episode_num" -> epNum = json.nextInt()
                                                "title" -> epTitle = json.nextString().orEmpty()
                                                "container_extension" -> ext = json.nextString()
                                                "info" -> readEpisodeInfo(json, epInfo)
                                                else -> json.skipValue()
                                            }
                                        }
                                        json.endObject()
                                        if (epId != 0L) {
                                            list.add(
                                                com.crimson.core.model.RawEpisode(
                                                    id = epId,
                                                    season = seasonNum,
                                                    episodeNum = epNum,
                                                    title = epTitle,
                                                    containerExtension = ext,
                                                    info = epInfo.plot,
                                                    image = epInfo.image,
                                                    durationSecs = epInfo.durationSecs,
                                                    releaseDate = epInfo.releaseDate,
                                                )
                                            )
                                        }
                                    }
                                    json.endArray()
                                } else {
                                    json.skipValue()
                                }
                            }
                            json.endObject()
                        } else json.skipValue()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        seasons.sort()
        return com.crimson.core.model.RawSeriesInfo(
            seriesId = defaultSeriesId,
            name = name,
            cover = cover,
            plot = plot,
            seasons = seasons,
            episodes = episodes,
            backdrop = backdrop,
            genre = genre,
            releaseDate = releaseDate,
            cast = cast,
            director = director,
            rating = rating,
        )
    }

    private class EpisodeInfo(
        var plot: String? = null,
        var image: String? = null,
        var durationSecs: Int? = null,
        var releaseDate: String? = null,
    )

    /** An episode's `info` object; some panels send an empty array instead of an object. */
    private fun readEpisodeInfo(json: JsonReader, into: EpisodeInfo) {
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); return }
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "plot", "description" -> into.plot = json.nextString()?.takeIf { it.isNotBlank() }
                "movie_image", "cover_big" -> into.image = json.nextString()?.takeIf { it.isNotBlank() }
                "duration_secs" -> into.durationSecs = json.nextString()?.toIntOrNull()
                "releasedate", "release_date", "air_date" -> into.releaseDate = json.nextString()
                else -> json.skipValue()
            }
        }
        json.endObject()
    }

    /** A value that panels send either as a string or as an array of strings; the first wins. */
    private fun firstString(json: JsonReader): String? {
        if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return json.nextString()?.takeIf { it.isNotBlank() }
        var first: String? = null
        json.beginArray()
        while (json.hasNext()) {
            if (first == null && json.peek() != JsonReader.Token.BEGIN_ARRAY && json.peek() != JsonReader.Token.BEGIN_OBJECT) {
                first = json.nextString()?.takeIf { it.isNotBlank() }
            } else {
                json.skipValue()
            }
        }
        json.endArray()
        return first
    }
}
