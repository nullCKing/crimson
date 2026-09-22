package com.crimson.data.xtream

import com.crimson.core.filter.ChannelFilter
import com.crimson.core.model.CategoryVerdict
import com.crimson.core.model.Country
import com.crimson.core.model.FilterRules
import com.crimson.core.model.KeptChannel
import com.crimson.core.numbering.ChannelNumbering
import com.crimson.core.text.NameCleaner
import com.crimson.data.db.CategoryEntity
import com.crimson.data.db.ChannelEntity
import com.crimson.data.db.ChannelNumberEntity
import com.crimson.data.db.CrimsonDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** What the import is doing, for the progress indicator. */
sealed interface ImportProgress {
    data object ReadingCategories : ImportProgress
    data class Scanning(val seen: Int, val kept: Int, val category: String?) : ImportProgress
    data class Done(val seen: Int, val kept: Int, val skippedCategories: Int) : ImportProgress
}

/**
 * Reads the provider's channel list and keeps only what the rules allow.
 *
 * Three things keep this inside a Fire Stick Lite's budget:
 *
 *  1. **Categories are classified first, and most are never fetched.** A provider with thirty
 *     countries has the overwhelming majority of its stream list skipped before a byte of it is
 *     downloaded — measured at 4,629 of 5,181 channels against the mock server.
 *  2. **Channels are filtered as they are parsed.** [XtreamClient.streamCategory] hands each
 *     channel over one at a time; the ones that fail go out of scope immediately and are never
 *     collected into a list.
 *  3. **Survivors are written in batches.** At most [BATCH_SIZE] channels are held at once, so
 *     peak usage is a function of the batch size, not of the provider's catalogue size.
 */
class ChannelImporter(
    private val client: XtreamClient,
    private val db: CrimsonDatabase,
) {

    /**
     * Runs an import.
     *
     * @param rules the current filter rules.
     * @param onlyNewCategories when true, categories already imported are left alone. This is the
     *   "adding a country fetches only the newly needed categories" path; a full refresh passes
     *   false.
     */
    fun import(rules: FilterRules, onlyNewCategories: Boolean): Flow<ImportProgress> = flow {
        emit(ImportProgress.ReadingCategories)

        val filter = ChannelFilter(rules)
        val startedAt = System.currentTimeMillis()

        val rawCategories = client.categories()
        val verdicts: Map<String, CategoryVerdict> =
            rawCategories.associate { it.categoryId to filter.classifyCategory(it) }

        db.categoryDao().upsertAll(
            verdicts.values.map { v ->
                CategoryEntity(
                    categoryId = v.category.categoryId,
                    name = v.category.categoryName,
                    country = v.country?.code,
                    market = v.market?.name,
                    indicatesLocals = v.indicatesLocals,
                    foreignMarker = v.foreignMarker,
                    imported = false,
                    lastImportedAt = null,
                )
            }
        )

        val alreadyImported = if (onlyNewCategories) {
            db.categoryDao().all().filter { it.imported }.map { it.categoryId }.toSet()
        } else {
            emptySet()
        }

        val wanted = verdicts.values.filter { it.mayContainKeptChannels(rules) }
        val toFetch = wanted.filterNot { it.category.categoryId in alreadyImported }
        val skipped = verdicts.size - wanted.size

        var seen = 0
        val sink = ChannelSink(db)

        for (verdict in toFetch) {
            currentCoroutineContext().ensureActive()
            val categoryId = verdict.category.categoryId
            seen += client.streamCategory(categoryId) { raw ->
                filter.evaluate(raw, verdict)?.let(sink::add)
            }
            sink.flush()
            db.categoryDao().markImported(categoryId, System.currentTimeMillis())
            emit(ImportProgress.Scanning(seen, sink.kept, verdict.category.categoryName))
        }

        // Providers that leave channels uncategorised, or put everything in one bucket, are
        // covered by a single pass over the whole list with the same streaming filter.
        val needsFullPass = verdicts.isEmpty() ||
            (!onlyNewCategories && sink.kept == 0 && wanted.isNotEmpty())
        if (needsFullPass) {
            emit(ImportProgress.Scanning(seen, sink.kept, null))
            seen += client.streamAll { raw ->
                filter.evaluate(raw, raw.categoryId?.let { verdicts[it] })?.let(sink::add)
            }
        }

        sink.flush()
        val kept = sink.kept
        db.channelDao().deleteStaleIn(toFetch.map { it.category.categoryId }, startedAt)

        emit(ImportProgress.Done(seen, kept, skipped))
    }.flowOn(Dispatchers.IO)

    /**
     * Which countries have never been imported, so the settings screen knows whether enabling one
     * needs a fetch or is purely a database query.
     */
    suspend fun countriesNeedingFetch(rules: FilterRules): List<Country> {
        val categories = db.categoryDao().all()
        if (categories.isEmpty()) return rules.countries.toList()
        return rules.countries.filter { country ->
            categories.none { it.country == country.code && it.imported }
        }
    }

    companion object {
        /**
         * Channels held in memory at once. 250 rows of a handful of short strings is a few tens of
         * kilobytes; the figure exists to make peak usage independent of catalogue size, not
         * because a larger batch would be slow.
         */
        const val BATCH_SIZE = 250
    }
}

/**
 * Accumulates surviving channels and writes them out in fixed-size batches.
 *
 * [add] is called from inside the streaming JSON parse, which is a plain callback and cannot
 * suspend, so the flush uses the blocking DAO methods. That is what lets a batch be written
 * *during* a category rather than only after it, which is the difference between peak memory
 * being bounded by [ChannelImporter.BATCH_SIZE] and being bounded by the largest category the
 * provider happens to have.
 */
private class ChannelSink(private val db: CrimsonDatabase) {

    private val batch = ArrayList<KeptChannel>(ChannelImporter.BATCH_SIZE)

    /**
     * The permanent numbering ledger, held whole because a new channel's number depends on every
     * number ever handed out. One Long and one Int per channel: a few hundred kilobytes even for
     * a provider with tens of thousands of kept channels, which no allowlist of four countries
     * will produce.
     */
    private var assignments = ChannelNumbering.Assignments(
        db.channelNumberDao().allBlocking().associate { it.streamId to it.number }
    )

    var kept = 0
        private set

    fun add(channel: KeptChannel) {
        batch.add(channel)
        kept++
        if (batch.size >= ChannelImporter.BATCH_SIZE) flush()
    }

    fun flush() {
        if (batch.isEmpty()) return
        assignments = ChannelNumbering.assign(assignments, batch)
        val now = System.currentTimeMillis()
        val rows = batch.mapNotNull { channel ->
            val number = assignments.numberOf(channel.streamId) ?: return@mapNotNull null
            ChannelEntity.from(
                kept = channel,
                number = number,
                shortName = NameCleaner.shortName(channel.displayName),
                now = now,
            )
        }
        db.channelNumberDao().insertAllBlocking(rows.map { ChannelNumberEntity(it.streamId, it.number) })
        db.channelDao().upsertAllBlocking(rows)
        batch.clear()
    }
}
