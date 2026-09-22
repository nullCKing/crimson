package com.crimson.data.xtream

import android.util.Log
import com.crimson.core.catalog.AdultContent
import com.crimson.core.catalog.TitleIndex
import com.crimson.core.catalog.TitleKind
import com.crimson.core.catalog.TitleMatcher
import com.crimson.core.filter.ForeignCountries
import com.crimson.core.text.Tokenizer
import com.crimson.data.db.CrimsonDatabase
import com.crimson.data.db.SeriesEntity
import com.crimson.data.db.VodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What one library import found. */
data class LibraryReport(
    val movies: Int = 0,
    val series: Int = 0,
    val problem: String? = null,
) {
    fun summary(): String = when {
        problem != null -> problem
        movies == 0 && series == 0 -> "No on-demand catalogue on this account."
        else -> "$movies films and $series series."
    }
}

/**
 * Caches the provider's on-demand catalogue locally.
 *
 * Until now the app asked for one category of films at a time, which is all the Movies screen
 * needed. Search and the curated rows need the whole catalogue at once: you cannot answer "what
 * do I have from the IMDb top 250" by fetching 200 categories, and you cannot answer a search box
 * with a network round trip per keystroke.
 *
 * So the catalogue is pulled once and kept. It is streamed and written in batches for the same
 * reason the channel list is — `get_vod_streams` with no category is a single JSON array that
 * runs to tens of thousands of entries — and only the fields a poster and a search result need
 * are stored. Synopsis, cast and episode lists stay where they were, behind `get_vod_info` and
 * `get_series_info`, fetched when a title is actually opened.
 */
class LibraryImporter(
    private val client: XtreamClient,
    private val db: CrimsonDatabase,
    /** Opens `assets/title_index.tsv`; see [enrich]. */
    private val openTitleIndex: () -> java.io.Reader,
) {

    suspend fun import(): LibraryReport = withContext(Dispatchers.IO) {
        val dao = db.libraryDao()

        // Category names are wanted on the cards, and there are only a few hundred of them.
        val vodCategories = runCatching { client.vodCategories() }.getOrDefault(emptyList())
        val seriesCategories = runCatching { client.seriesCategories() }.getOrDefault(emptyList())
        val vodNames = vodCategories.associate { it.categoryId to it.categoryName }
        val seriesNames = seriesCategories.associate { it.categoryId to it.categoryName }
        val foreignCategories = (vodCategories + seriesCategories)
            .filter { isForeignLabel(it.categoryName) }
            .map { it.categoryId }
            .toSet()
        val adultCategories = (vodCategories + seriesCategories)
            .filter { AdultContent.isAdult(it.categoryName) }
            .map { it.categoryId }
            .toSet()

        var movies = 0
        var series = 0
        val problems = ArrayList<String>(2)

        // ---------------------------------------------------------------- films
        val vodBatch = ArrayList<VodEntity>(BATCH_SIZE)
        fun addVod(raw: com.crimson.core.model.RawVodStream) {
            val keys = TitleMatcher.variants(raw.name)
            vodBatch.add(
                VodEntity(
                    streamId = raw.streamId,
                    name = raw.name,
                    categoryId = raw.categoryId,
                    categoryName = raw.categoryId?.let { vodNames[it] },
                    icon = raw.streamIcon,
                    rating = raw.rating,
                    containerExtension = raw.containerExtension,
                    titleKey = keys.first().text,
                    titleKeyAlt = keys.getOrNull(1)?.text,
                    titleYear = keys.first().year,
                    added = raw.added?.takeIf { it > 0 }?.times(1000L),
                    isForeign = raw.categoryId in foreignCategories || isForeignLabel(raw.name),
                    isAdult = raw.categoryId in adultCategories || AdultContent.isAdult(raw.name),
                )
            )
            movies++
            if (vodBatch.size >= BATCH_SIZE) {
                dao.upsertVodBlocking(vodBatch)
                vodBatch.clear()
            }
        }

        val wholeCatalogue = runCatching { client.streamAllVod(::addVod) }
        if (vodBatch.isNotEmpty()) {
            dao.upsertVodBlocking(vodBatch)
            vodBatch.clear()
        }
        if (wholeCatalogue.isFailure || movies == 0) {
            // Not every panel will serve `get_vod_streams` with no category: this one closed the
            // connection part way through a catalogue of tens of thousands. Asking category by
            // category is slower and more requests, but each response is small enough that a
            // panel under load can actually finish it.
            wholeCatalogue.exceptionOrNull()?.let {
                Log.w(TAG, "whole-catalogue film fetch failed, falling back to categories", it)
            }
            movies = 0
            for (category in vodCategories) {
                currentCoroutineContext().ensureActive()
                runCatching { client.vodStreams(category.categoryId).forEach(::addVod) }
                    .onFailure { Log.w(TAG, "films in ${category.categoryName} failed", it) }
            }
            if (vodBatch.isNotEmpty()) {
                dao.upsertVodBlocking(vodBatch)
                vodBatch.clear()
            }
            if (movies == 0) problems.add("No films could be read")
        }

        currentCoroutineContext().ensureActive()

        // ---------------------------------------------------------------- series
        val seriesBatch = ArrayList<SeriesEntity>(BATCH_SIZE)
        fun addSeries(raw: com.crimson.core.model.RawSeries) {
            val keys = TitleMatcher.variants(raw.name)
            seriesBatch.add(
                SeriesEntity(
                    seriesId = raw.seriesId,
                    name = raw.name,
                    categoryId = raw.categoryId,
                    categoryName = raw.categoryId?.let { seriesNames[it] },
                    cover = raw.cover,
                    plot = raw.plot,
                    rating = raw.rating,
                    releaseDate = raw.releaseDate,
                    titleKey = keys.first().text,
                    titleKeyAlt = keys.getOrNull(1)?.text,
                    titleYear = keys.first().year
                        ?: raw.releaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 },
                    backdrop = raw.backdrop,
                    added = raw.lastModified?.takeIf { it > 0 }?.times(1000L),
                    isForeign = raw.categoryId in foreignCategories || isForeignLabel(raw.name),
                    isAdult = raw.categoryId in adultCategories || AdultContent.isAdult(raw.name),
                )
            )
            series++
            if (seriesBatch.size >= BATCH_SIZE) {
                dao.upsertSeriesBlocking(seriesBatch)
                seriesBatch.clear()
            }
        }

        val wholeSeries = runCatching { client.streamAllSeries(::addSeries) }
        if (seriesBatch.isNotEmpty()) {
            dao.upsertSeriesBlocking(seriesBatch)
            seriesBatch.clear()
        }
        if (wholeSeries.isFailure || series == 0) {
            wholeSeries.exceptionOrNull()?.let {
                Log.w(TAG, "whole-catalogue series fetch failed, falling back to categories", it)
            }
            series = 0
            for (category in seriesCategories) {
                currentCoroutineContext().ensureActive()
                runCatching { client.series(category.categoryId).forEach(::addSeries) }
                    .onFailure { Log.w(TAG, "series in ${category.categoryName} failed", it) }
            }
            if (seriesBatch.isNotEmpty()) {
                dao.upsertSeriesBlocking(seriesBatch)
                seriesBatch.clear()
            }
            if (series == 0) problems.add("No series could be read")
        }

        val report = LibraryReport(movies, series, problems.takeIf { it.isNotEmpty() }?.joinToString("; "))
        Log.i(TAG, "library import: ${report.summary()}")
        report
    }

    /**
     * Joins the cached catalogue to the IMDb title index, filling in genres, rating and
     * popularity — everything the recommendation rows sort and filter on.
     *
     * The index is streamed from the asset one title at a time and applied as indexed UPDATEs
     * inside transactions of [ENRICH_BATCH], so neither the index nor the catalogue is ever held
     * in memory. On the reference account's scale that is some 31,000 small updates.
     */
    suspend fun enrich(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val dao = db.libraryDao()
        val batch = ArrayList<com.crimson.core.catalog.IndexedTitle>(ENRICH_BATCH)
        fun flush() {
            if (batch.isEmpty()) return
            db.runInTransaction {
                for (t in batch) {
                    val key = t.key.text
                    if (key.isEmpty()) continue
                    if (t.kind == TitleKind.MOVIE) {
                        dao.enrichVodBlocking(key, t.year, t.genreColumn, t.rating, t.votes)
                        dao.enrichVodAltBlocking(key, t.year, t.genreColumn, t.rating, t.votes)
                    } else {
                        dao.enrichSeriesBlocking(key, t.year, t.genreColumn, t.rating, t.votes)
                    }
                }
            }
            batch.clear()
        }
        val started = System.currentTimeMillis()
        runCatching {
            openTitleIndex().use { reader ->
                TitleIndex.read(reader) { title ->
                    batch.add(title)
                    if (batch.size >= ENRICH_BATCH) flush()
                }
            }
            flush()
        }.onFailure { Log.w(TAG, "title index could not be applied", it) }
        val result = dao.vodIndexedCount() to dao.seriesIndexedCount()
        Log.i(
            TAG,
            "enriched ${result.first} films and ${result.second} series from the title index " +
                "in ${System.currentTimeMillis() - started} ms",
        )
        result
    }

    /**
     * A label written for another language's audience: `FR - Inception`, a `DE | FILME` category.
     * Uses the live filter's own foreign-marker list, so both agree on what "foreign" means.
     */
    private fun isForeignLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        return ForeignCountries.detectPrefix(Tokenizer.tokenize(label)) != null
    }

    companion object {
        private const val TAG = "CrimsonLibrary"

        /** Matches the channel importer's batch size, for the same memory reason. */
        const val BATCH_SIZE = 250

        /** Title-index updates per transaction. */
        const val ENRICH_BATCH = 500
    }
}
