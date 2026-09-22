package com.crimson.data.epg

import com.crimson.core.epg.EpgSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Downloads a public XMLTV feed and hands back an open reader.
 *
 * Deliberately separate from [com.crimson.data.xtream.XtreamClient]: these feeds have nothing
 * to do with the account, carry no credentials, and must never have any attached to them. The
 * only thing the two share is the gzip handling, which is repeated here rather than exported,
 * because the Xtream client's copy is about a provider's quirks and this one is about ordinary
 * `.xml.gz` files on a web server.
 *
 * The caller owns the reader and must close it; the importer streams through it one `<programme>`
 * at a time, so a 20 MB feed never becomes 20 MB of heap.
 */
class EpgFeedClient(private val http: OkHttpClient = defaultClient()) {

    /** Opens [source] for reading, unwrapping gzip whether or not the server admits to it. */
    fun open(source: EpgSource): Reader {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", USER_AGENT)
            // Asking for identity keeps OkHttp's transparent gzip out of the way, so the magic
            // number below sees the file as it is on disk rather than as the transport left it.
            .header("Accept-Encoding", "identity")
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw EpgFeedException("${source.label}: HTTP ${response.code}")
        }
        val body = response.body ?: run {
            response.close()
            throw EpgFeedException("${source.label}: empty response")
        }

        val raw = BufferedInputStream(body.byteStream(), READ_BUFFER)
        val stream = if (looksGzipped(raw)) GZIPInputStream(raw).buffered(READ_BUFFER) else raw
        return InputStreamReader(stream, Charsets.UTF_8)
    }

    private fun looksGzipped(stream: BufferedInputStream): Boolean {
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        return first == 0x1F && second == 0x8B
    }

    companion object {
        private const val USER_AGENT = "Crimson/1.0 (Android TV)"
        private const val READ_BUFFER = 1 shl 16

        /**
         * A separate client from the Xtream one, with a long read timeout: these are large static
         * files from hosts that are sometimes slow to start sending, and there is no user waiting
         * on them — the guide already works without them.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}

class EpgFeedException(message: String, cause: Throwable? = null) : Exception(message, cause)
