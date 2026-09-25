package com.crimson.data.skip

import android.content.Context
import android.util.Log
import com.crimson.BuildConfig
import com.crimson.core.skip.EpisodePrint
import com.crimson.core.skip.SkipTimes
import com.crimson.core.skip.SkipTimesParser
import com.crimson.core.skip.Theme
import com.crimson.core.skip.ThemeLearner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the app knows about each show's theme songs, on disk, and the two public intro databases.
 *
 * Per show, under `files/themes/<show key>/`: the themes learned so far (`themes.bin`, a few
 * kilobytes each) and the last few episodes' opening and closing minutes as heard
 * (`<episode>.print`, about 90 KB each), which the next episode is compared against. Shared by
 * every profile: a theme song is the same whoever is watching.
 */
class ThemeSkipRepository(context: Context, http: OkHttpClient) {

    private val appContext = context.applicationContext
    private val http = ExtraRoots.apply(http.newBuilder().callTimeout(8, TimeUnit.SECONDS), appContext).build()
    private val root = File(appContext.filesDir, "themes")
    private val times = HashMap<String, SkipTimes>()

    suspend fun themes(show: String): List<Theme> = withContext(Dispatchers.IO) {
        val file = File(dir(show), THEMES)
        if (!file.isFile) emptyList() else runCatching { file.inputStream().buffered().use(ThemeLearner::read) }.getOrDefault(emptyList())
    }

    suspend fun forget(show: String) = withContext(Dispatchers.IO) {
        dir(show).deleteRecursively()
        Unit
    }

    /**
     * Keeps [print] and compares it with the show's other recent episodes; returns the themes
     * that were new. Runs the comparison off the main thread; a tenth of a second or so each.
     */
    suspend fun learnFrom(show: String, print: EpisodePrint, now: Long): List<Theme> = withContext(Dispatchers.Default) {
        val dir = dir(show).apply { mkdirs() }
        val others = dir.listFiles { f -> f.name.endsWith(PRINT) && f.name != "${print.episodeId}$PRINT" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_PRINTS)
            ?.mapNotNull { f -> runCatching { f.inputStream().buffered().use { EpisodePrint.read(it) } }.getOrNull() }
            .orEmpty()
        runCatching { File(dir, "${print.episodeId}$PRINT").outputStream().buffered().use(print::write) }
            .onFailure { Log.w(TAG, "could not keep the episode's print", it) }
        // The oldest go; four is plenty to find a theme that changed.
        dir.listFiles { f -> f.name.endsWith(PRINT) }?.sortedByDescending { it.lastModified() }?.drop(MAX_PRINTS + 1)?.forEach { it.delete() }

        val known = themes(show)
        val learned = ThemeLearner.learn(print, others, known, now)
        if (learned.isNotEmpty()) {
            val all = ThemeLearner.merge(known, learned).sortedByDescending { it.learnedAt }.take(ThemeLearner.MAX_THEMES)
            runCatching { File(dir, THEMES).outputStream().buffered().use { ThemeLearner.write(all, it) } }
                .onFailure { Log.w(TAG, "could not save themes", it) }
        }
        learned
    }

    /**
     * Intro and ending times for an episode from IntroDB and TheIntroDB, where they agree (see
     * [SkipTimesParser.combine]). Remembered for the session; a failure is just no times.
     */
    suspend fun times(imdbId: String?, tmdbId: String?, season: Int, episode: Int, durationMs: Long): SkipTimes {
        if (imdbId == null && tmdbId == null) return SkipTimes.NONE
        val key = "$imdbId/$tmdbId/$season/$episode"
        synchronized(times) { times[key] }?.let { return it }
        val combined = coroutineScope {
            val a = async(Dispatchers.IO) {
                imdbId?.let { fetch("$INTRODB?imdb_id=$it&season=$season&episode=$episode")?.let(SkipTimesParser::parseIntroDb) }
            }
            val b = async(Dispatchers.IO) {
                val id = tmdbId?.takeIf { t -> t.isNotEmpty() && t.all(Char::isDigit) }?.let { "tmdb_id=$it" }
                    ?: imdbId?.let { "imdb_id=$it" }
                id?.let { fetch("$THEINTRODB?$it&season=$season&episode=$episode")?.let(SkipTimesParser::parseTheIntroDb) }
            }
            SkipTimesParser.combine(a.await() ?: SkipTimes.NONE, b.await() ?: SkipTimes.NONE, durationMs)
        }
        Log.i(TAG, "times for $key: $combined")
        synchronized(times) { times[key] = combined }
        return combined
    }

    private fun fetch(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            // "Not found" comes back as a 404 with a JSON body; either way, nothing to use.
            if (!response.isSuccessful) null else response.body?.string()?.takeIf { it.length < MAX_BODY }
        }
    }.onFailure { Log.w(TAG, "intro times from ${url.substringBefore('?')} failed: $it") }.getOrNull()

    private fun dir(show: String) = File(root, show.filter(Char::isLetterOrDigit).ifEmpty { "_" }.take(80))

    companion object {
        private const val TAG = "CrimsonThemes"
        private const val THEMES = "themes.bin"
        private const val PRINT = ".print"
        private const val MAX_PRINTS = 4
        private const val MAX_BODY = 64 * 1024
        private const val USER_AGENT = "Crimson/1.1 (Android TV)"
        private val INTRODB = BuildConfig.INTRODB_URL + "/segments"
        private val THEINTRODB = BuildConfig.THEINTRODB_URL + "/v3/media"
    }
}
