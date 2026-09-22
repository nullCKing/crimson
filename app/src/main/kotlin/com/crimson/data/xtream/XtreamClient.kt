package com.crimson.data.xtream

import com.crimson.core.json.LoginInfo
import com.crimson.core.json.ShortEpgEntry
import com.crimson.core.json.XtreamParser
import com.crimson.core.model.RawCategory
import com.crimson.core.model.RawChannel
import com.crimson.core.model.RawVodStream
import com.crimson.core.model.RawVodInfo
import com.crimson.core.model.RawSeries
import com.crimson.core.model.RawSeriesInfo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Where and how to reach the provider. */
data class XtreamAccount(
    val serverUrl: String,
    val username: String,
    val password: String,
) {
    /** Normalises whatever the user typed into a usable base URL. */
    fun baseUrl(): HttpUrl? {
        val text = serverUrl.trim().trimEnd('/')
        val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) {
            text
        } else {
            "http://$text"
        }
        return withScheme.toHttpUrlOrNull()
    }
}

/** A failure the UI can turn into a sentence a person can act on. */
sealed class XtreamError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BadCredentials(val serverMessage: String?) : XtreamError("credentials rejected")
    class Expired : XtreamError("account expired")
    class Unreachable(cause: Throwable?) : XtreamError("server unreachable", cause)
    class NotXtream(val detail: String) : XtreamError("not an Xtream panel: $detail")
}

/**
 * The HTTP side of the Xtream API.
 *
 * Every response that can be large is handed to the caller as a [Reader] and parsed as a stream.
 * Nothing here ever calls `response.body.string()` on a stream list or on XMLTV: those are tens
 * and hundreds of megabytes, and materialising either one is what kills the app on a 1 GB stick.
 */
class XtreamClient(
    private val account: XtreamAccount,
    private val client: OkHttpClient = defaultClient(),
) {

    private val base: HttpUrl = account.baseUrl()
        ?: throw XtreamError.NotXtream("the address could not be parsed as a URL")

    // ------------------------------------------------------------------ requests

    private fun apiUrl(action: String?, extra: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()

    private fun execute(url: HttpUrl): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw XtreamError.Unreachable(e)
        }
        if (!response.isSuccessful) {
            response.close()
            throw when (response.code) {
                401, 403 -> XtreamError.BadCredentials(null)
                else -> XtreamError.NotXtream("server answered HTTP ${response.code}")
            }
        }
        return response
    }

    /**
     * Opens a response body as a character stream, transparently gunzipping.
     *
     * OkHttp only decompresses responses whose `Accept-Encoding` it added itself. This asks for
     * gzip explicitly, because an uncompressed XMLTV document is often 150 MB or more over a
     * domestic connection, so the header has to be set here and the decompression handled here.
     */
    private fun readerFor(response: Response): Reader {
        val body = response.body ?: throw XtreamError.NotXtream("empty response")
        val encoding = response.header("Content-Encoding").orEmpty()
        val raw = body.byteStream().buffered(READ_BUFFER)
        // Some panels serve xmltv.php as a ready-made .gz file with no Content-Encoding header,
        // which fed gzip bytes to the XML parser and produced an empty guide. The magic number
        // is checked rather than the header, so both cases unwrap.
        val stream = if (encoding.contains("gzip", ignoreCase = true) || looksGzipped(raw)) {
            GZIPInputStream(raw).buffered(READ_BUFFER)
        } else {
            raw
        }
        return InputStreamReader(stream, Charsets.UTF_8)
    }

    private fun looksGzipped(stream: java.io.BufferedInputStream): Boolean {
        stream.mark(2)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        return b1 == 0x1F && b2 == 0x8B
    }

    private fun gzipRequest(url: HttpUrl): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip")
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw XtreamError.Unreachable(e)
        }
        if (!response.isSuccessful) {
            response.close()
            throw XtreamError.NotXtream("server answered HTTP ${response.code}")
        }
        return response
    }

    // ------------------------------------------------------------------ API

    /** Logs in. Throws [XtreamError.BadCredentials] or [XtreamError.Expired] rather than lying. */
    fun login(): LoginInfo {
        val info = execute(apiUrl(null)).use { response ->
            readerFor(response).use { XtreamParser.parseLogin(it) }
        }
        if (!info.authenticated) {
            if (info.status.equals("Expired", ignoreCase = true)) throw XtreamError.Expired()
            throw XtreamError.BadCredentials(info.message)
        }
        val expiry = info.expiryEpochSeconds
        if (expiry != null && expiry * 1000L < System.currentTimeMillis()) {
            throw XtreamError.Expired()
        }
        return info
    }

    fun categories(): List<RawCategory> =
        execute(apiUrl("get_live_categories")).use { response ->
            readerFor(response).use { XtreamParser.parseCategories(it) }
        }

    /**
     * Streams one category's channels through [onChannel], returning how many were seen.
     * Nothing is retained: the caller filters as they arrive.
     */
    fun streamCategory(categoryId: String, onChannel: (RawChannel) -> Unit): Int =
        gzipRequest(apiUrl("get_live_streams", mapOf("category_id" to categoryId)))
            .use { response ->
                readerFor(response).use { XtreamParser.streamChannels(it, onChannel) }
            }

    /**
     * The whole stream list, for providers that leave channels uncategorised or dump everything
     * into a single category. Same streaming treatment; this is the response that can be 50 MB.
     */
    fun streamAll(onChannel: (RawChannel) -> Unit): Int =
        gzipRequest(apiUrl("get_live_streams")).use { response ->
            readerFor(response).use { XtreamParser.streamChannels(it, onChannel) }
        }

    fun shortEpg(streamId: Long, limit: Int = 4): List<ShortEpgEntry> {
        val first = runCatching {
            execute(apiUrl("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString())))
                .use { response -> readerFor(response).use { XtreamParser.parseShortEpg(it) } }
        }.getOrNull().orEmpty()
        if (first.isNotEmpty()) return first

        return runCatching {
            execute(apiUrl("get_simple_data_table", mapOf("stream_id" to streamId.toString())))
                .use { response -> readerFor(response).use { XtreamParser.parseShortEpg(it) } }
        }.getOrNull().orEmpty()
    }

    /** The whole film catalogue, streamed. See [XtreamParser.streamVodStreams]. */
    fun streamAllVod(onVod: (RawVodStream) -> Unit): Int =
        gzipRequest(apiUrl("get_vod_streams")).use { response ->
            readerFor(response).use { XtreamParser.streamVodStreams(it, onVod) }
        }

    /** Every series, streamed. */
    fun streamAllSeries(onSeries: (RawSeries) -> Unit): Int =
        gzipRequest(apiUrl("get_series")).use { response ->
            readerFor(response).use { XtreamParser.streamSeries(it, onSeries) }
        }

    fun vodCategories(): List<RawCategory> =
        execute(apiUrl("get_vod_categories")).use { response ->
            readerFor(response).use { XtreamParser.parseCategories(it) }
        }

    fun vodStreams(categoryId: String? = null): List<RawVodStream> {
        val params = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return gzipRequest(apiUrl("get_vod_streams", params)).use { response ->
            readerFor(response).use { XtreamParser.parseVodStreams(it) }
        }
    }

    fun vodInfo(vodId: Long): RawVodInfo =
        execute(apiUrl("get_vod_info", mapOf("vod_id" to vodId.toString()))).use { response ->
            readerFor(response).use { XtreamParser.parseVodInfo(it, vodId) }
        }

    fun seriesCategories(): List<RawCategory> =
        execute(apiUrl("get_series_categories")).use { response ->
            readerFor(response).use { XtreamParser.parseCategories(it) }
        }

    fun series(categoryId: String? = null): List<RawSeries> {
        val params = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return gzipRequest(apiUrl("get_series", params)).use { response ->
            readerFor(response).use { XtreamParser.parseSeries(it) }
        }
    }

    fun seriesInfo(seriesId: Long): RawSeriesInfo =
        execute(apiUrl("get_series_info", mapOf("series_id" to seriesId.toString()))).use { response ->
            readerFor(response).use { XtreamParser.parseSeriesInfo(it, seriesId) }
        }

    fun vodStreamUrl(streamId: Long, containerExtension: String?): String {
        val ext = containerExtension?.trimStart('.')?.ifBlank { "mp4" } ?: "mp4"
        return base.newBuilder()
            .addPathSegment("movie")
            .addPathSegment(account.username)
            .addPathSegment(account.password)
            .addPathSegment("$streamId.$ext")
            .build()
            .toString()
    }

    fun seriesStreamUrl(streamId: Long, containerExtension: String?): String {
        val ext = containerExtension?.trimStart('.')?.ifBlank { "mp4" } ?: "mp4"
        return base.newBuilder()
            .addPathSegment("series")
            .addPathSegment(account.username)
            .addPathSegment(account.password)
            .addPathSegment("$streamId.$ext")
            .build()
            .toString()
    }

    /**
     * Opens `xmltv.php` as a character stream.
     *
     * The caller owns the returned reader and must close it, which also closes the socket. It is
     * handed out open rather than parsed here so the XMLTV importer can pull through it with an
     * `XmlPullParser` and never hold more than one `<programme>` at a time.
     */
    fun openXmltv(): Reader {
        val url = base.newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .build()
        return readerFor(gzipRequest(url))
    }

    /**
     * The playback URL for a channel.
     *
     * Xtream serves live streams at `/live/USER/PASS/ID.ext`, so the credentials are in the path.
     * That is the provider's design, not a choice made here; it is also why the app never logs a
     * stream URL and why `reports/discovery.md` contains none.
     */
    fun streamUrl(streamId: Long, format: String): String =
        base.newBuilder()
            .addPathSegment("live")
            .addPathSegment(account.username)
            .addPathSegment(account.password)
            .addPathSegment("$streamId.${if (format == "m3u8") "m3u8" else "ts"}")
            .build()
            .toString()

    companion object {
        private const val USER_AGENT = "IPTVSmartersPro/3.1.5 (AndroidTV)"
        private const val READ_BUFFER = 1 shl 16

        /**
         * Timeouts are generous on read because a provider generating a large XMLTV document can
         * take a while to send the first byte, but the connect timeout stays short so a wrong
         * address fails quickly instead of hanging the login screen.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
