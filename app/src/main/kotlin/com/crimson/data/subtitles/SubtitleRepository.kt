package com.crimson.data.subtitles

import android.content.Context
import android.util.Log
import com.crimson.BuildConfig
import com.crimson.core.catalog.TitleIndex
import com.crimson.core.catalog.TitleKind
import com.crimson.core.subtitles.SubtitleCandidate
import com.crimson.core.subtitles.SubtitleParser
import com.crimson.core.subtitles.SubtitleQuery
import com.crimson.core.subtitles.SubtitleResults
import com.crimson.core.subtitles.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * English subtitles from OpenSubtitles, for films and episodes that carry none of their own.
 *
 * Two ways in to the same database. With an API key built in (`secrets/opensubtitles.properties`,
 * see README), the official REST API: full metadata to rank on — download counts, frame rates,
 * hearing-impaired and machine-translation flags — and, with an account, 20 downloads a day. With
 * no key, the public Stremio addon in front of the same database: no key, no quota, less to rank
 * on. Either way a downloaded file is kept on disk, so watching the same thing again costs nothing.
 *
 * Titles are looked up by IMDb id, which the bundled title index carries for every title popular
 * enough to be in it; the rest are searched by name and year (official API only).
 */
class SubtitleRepository(context: Context, http: OkHttpClient) {

    private val appContext = context.applicationContext
    private val http = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val cacheDir = File(appContext.cacheDir, "subtitles").apply { mkdirs() }

    private val apiKey = BuildConfig.OPENSUBTITLES_API_KEY
    private val username = BuildConfig.OPENSUBTITLES_USERNAME
    private val password = BuildConfig.OPENSUBTITLES_PASSWORD

    private var token: String? = null
    private var tokenAt = 0L

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /** The IMDb id for a catalogue title, from the bundled index; null when it is not in it. */
    suspend fun imdbId(kind: TitleKind, titleKeys: List<String>, year: Int?): String? = withContext(Dispatchers.IO) {
        runCatching {
            appContext.assets.open(TITLE_INDEX_ASSET).reader().use { TitleIndex.findImdbId(it, kind, titleKeys, year) }
        }.onFailure { Log.w(TAG, "title index lookup failed", it) }.getOrNull()
    }

    /** Every English file the services offer for [query], unranked. */
    suspend fun search(query: SubtitleQuery): List<SubtitleCandidate> = withContext(Dispatchers.IO) {
        val official = if (hasApiKey) runCatching { searchOfficial(query) }
            .onFailure { Log.w(TAG, "OpenSubtitles search failed", it) }.getOrNull().orEmpty() else emptyList()
        if (official.isNotEmpty()) return@withContext official
        runCatching { searchAddon(query) }.onFailure { Log.w(TAG, "addon search failed", it) }.getOrNull().orEmpty()
    }

    /**
     * The file, parsed; from the disk cache when it has been fetched before. Throws
     * [QuotaExceeded] when OpenSubtitles' daily download allowance is used up.
     */
    suspend fun load(candidate: SubtitleCandidate): SubtitleTrack? = withContext(Dispatchers.IO) {
        val cached = File(cacheDir, "${candidate.source.name.lowercase()}_${candidate.id.filter(Char::isLetterOrDigit)}.srt")
        val bytes = if (cached.isFile && cached.length() > 0) cached.readBytes() else {
            val link = when (candidate.source) {
                SubtitleCandidate.Source.OPENSUBTITLES -> downloadLink(candidate.id)
                SubtitleCandidate.Source.ADDON -> candidate.url
            } ?: return@withContext null
            val fetched = get(link) ?: return@withContext null
            runCatching { cached.writeBytes(fetched) }
            trimCache()
            fetched
        }
        SubtitleParser.parse(bytes).takeIf { !it.isEmpty }
    }

    // ------------------------------------------------------------------ OpenSubtitles.com

    private fun searchOfficial(query: SubtitleQuery): List<SubtitleCandidate> {
        val params = sortedMapOf("languages" to "en")
        val imdb = query.imdbNumber
        val tmdb = query.tmdbId
        when {
            query.isEpisode && imdb != null -> {
                params["parent_imdb_id"] = imdb
                params["season_number"] = query.season.toString()
                params["episode_number"] = query.episode.toString()
            }
            query.isEpisode && tmdb != null -> {
                params["parent_tmdb_id"] = tmdb
                params["season_number"] = query.season.toString()
                params["episode_number"] = query.episode.toString()
            }
            query.isEpisode -> {
                params["query"] = query.title.lowercase()
                params["season_number"] = query.season.toString()
                params["episode_number"] = query.episode.toString()
            }
            imdb != null -> params["imdb_id"] = imdb
            tmdb != null -> params["tmdb_id"] = tmdb
            else -> {
                params["query"] = query.title.lowercase()
                query.year?.let { params["year"] = it.toString() }
            }
        }
        // The API redirects unless parameters are sorted and lower-case; sortedMapOf sorts them.
        val url = "$API/subtitles?" + params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val request = officialRequest(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.charStream()?.use(SubtitleResults::parseOpenSubtitles).orEmpty()
        }
    }

    private fun downloadLink(fileId: String): String? {
        val body = JSONObject().put("file_id", fileId.toLongOrNull() ?: return null).put("sub_format", "srt").toString()
        val request = officialRequest("$API/download")
            .apply { loginToken()?.let { header("Authorization", "Bearer $it") } }
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 406) throw QuotaExceeded()
            if (!response.isSuccessful) error("download HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            Log.i(TAG, "OpenSubtitles download, ${json.optInt("remaining", -1)} left today")
            return json.optString("link").takeIf { it.isNotBlank() }
        }
    }

    /** A token for the account in secrets, renewed daily; downloads work without one, at a lower allowance. */
    private fun loginToken(): String? {
        if (username.isBlank() || password.isBlank()) return null
        if (token != null && System.currentTimeMillis() - tokenAt < TOKEN_MS) return token
        val body = JSONObject().put("username", username).put("password", password).toString()
        return runCatching {
            http.newCall(officialRequest("$API/login").post(body.toRequestBody(JSON)).build()).execute().use { response ->
                if (!response.isSuccessful) error("login HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty()).optString("token").takeIf { it.isNotBlank() }
            }
        }.onFailure { Log.w(TAG, "OpenSubtitles login failed", it) }.getOrNull()?.also {
            token = it
            tokenAt = System.currentTimeMillis()
        }
    }

    private fun officialRequest(url: String) = Request.Builder()
        .url(url)
        .header("Api-Key", apiKey)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")

    // ------------------------------------------------------------------ the addon

    private fun searchAddon(query: SubtitleQuery): List<SubtitleCandidate> {
        val imdb = query.imdbId ?: return emptyList()
        val path = if (query.isEpisode) "series/$imdb:${query.season}:${query.episode}" else "movie/$imdb"
        val request = Request.Builder().url("$ADDON/subtitles/$path.json").header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.charStream()?.use(SubtitleResults::parseAddon).orEmpty()
        }
    }

    // ------------------------------------------------------------------ files

    private fun get(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) { Log.w(TAG, "subtitle file HTTP ${response.code}"); return null }
            response.body?.bytes()?.takeIf { it.size in 1..MAX_FILE_BYTES }
        }
    }

    /** Keeps the most recent files, a few hundred subtitles' worth. */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > CACHE_BYTES) file.delete()
        }
    }

    class QuotaExceeded : Exception("OpenSubtitles' daily download limit is used up")

    companion object {
        private const val TAG = "CrimsonSubtitles"
        private const val API = "https://api.opensubtitles.com/api/v1"
        private val ADDON = BuildConfig.SUBTITLE_ADDON_URL.trimEnd('/')
        private const val USER_AGENT = "Crimson v1.0"
        private const val TITLE_INDEX_ASSET = "title_index.tsv"
        private const val TOKEN_MS = 20 * 3_600_000L
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val CACHE_BYTES = 40L * 1024 * 1024
        private val JSON = "application/json".toMediaType()
    }
}
