package com.crimson.data.sports

import android.util.Log
import com.crimson.BuildConfig
import com.crimson.core.sports.ExtraEvent
import com.crimson.core.sports.ExtraEventsFile
import com.crimson.core.sports.ExtraEventsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * The live events the Extra tab lists: the file `.github/workflows/live-events.yml` publishes
 * every six hours (Claude Haiku's reading of the guide, see `tools/events/find_events.py`).
 *
 * The file covers every channel in the guide; the caller keeps the ones on the viewer's
 * channels. It changes four times a day, so it is fetched at most every [CACHE_MS], and a failed
 * fetch keeps the last good copy while it still has events to come.
 */
class ExtraEventsRepository(private val http: OkHttpClient) {

    @Volatile private var cached: ExtraEventsFile? = null
    @Volatile private var fetchedAt = 0L

    suspend fun events(force: Boolean = false): List<ExtraEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { !force && now - fetchedAt < CACHE_MS }?.let { return@withContext it.events }
        val fresh = runCatching { fetch() }
            .onFailure { Log.w(TAG, "events file unavailable", it) }
            .getOrNull()
        if (fresh != null) {
            cached = fresh
            fetchedAt = now
        }
        (fresh ?: cached)?.events?.filter { it.endMs > now }.orEmpty()
    }

    private fun fetch(): ExtraEventsFile {
        val request = Request.Builder()
            .url(BuildConfig.EVENTS_URL)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "Crimson/1.4 (Android TV)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val raw = BufferedInputStream(response.body!!.byteStream(), 1 shl 16)
            raw.mark(2)
            val gz = raw.read() == 0x1F && raw.read() == 0x8B
            raw.reset()
            val stream = if (gz) GZIPInputStream(raw, 1 shl 16) else raw
            return stream.reader(Charsets.UTF_8).use(ExtraEventsParser::parse)
        }
    }

    companion object {
        private const val TAG = "CrimsonEvents"
        const val CACHE_MS = 20 * 60_000L
    }
}
