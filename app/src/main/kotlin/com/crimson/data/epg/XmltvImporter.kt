package com.crimson.data.epg

import android.util.Log
import android.util.Xml
import com.crimson.core.epg.ChannelMatcher
import com.crimson.core.epg.EpgSource
import com.crimson.core.epg.EventChannelEpg
import com.crimson.core.epg.ProgramCategory
import com.crimson.core.epg.ShortEpg
import com.crimson.core.epg.XmltvTime
import com.crimson.data.db.ProgramEntity
import com.crimson.data.db.ChannelEntity
import com.crimson.data.db.CrimsonDatabase
import com.crimson.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser
import java.io.Reader

sealed interface EpgProgress {
    data object Downloading : EpgProgress
    data class Parsing(val kept: Int, val seen: Int) : EpgProgress
    /** Which feed is being read, and where it comes in the run. */
    data class Source(val label: String, val index: Int, val total: Int) : EpgProgress
    data class Done(val kept: Int, val seen: Int, val report: EpgReport = EpgReport()) : EpgProgress
}

/** What one public feed contributed. */
data class SourceResult(
    val id: String,
    val label: String,
    val channelsFilled: Int,
    val programmesKept: Int,
    val problem: String? = null,
)

/**
 * What an import found, in numbers. Logged, and kept in settings so the Settings screen can say
 * why the guide is empty instead of leaving the viewer to guess. Every count that can explain a
 * "No Information" grid is here: how many `<channel>` elements the document had, how many of
 * them attached to a channel in the list and by what route, and where the programmes went.
 */
data class EpgReport(
    /** `<channel>` elements in the document. Zero means the provider sent an empty guide. */
    val xmltvChannels: Int = 0,
    /** Channels in the list that an XMLTV channel attached to, by `epg_channel_id`. */
    val matchedById: Int = 0,
    /** Channels in the list that attached by display name instead. */
    val matchedByName: Int = 0,
    /** Channels in the list that nothing attached to. */
    val unmatchedChannels: Int = 0,
    val programmesSeen: Int = 0,
    val programmesKept: Int = 0,
    /** Programmes on XMLTV channels that attached to nothing. */
    val droppedUnknownChannel: Int = 0,
    /** Programmes outside the kept time window. */
    val droppedOutsideWindow: Int = 0,
    /** Programmes whose start or stop could not be read. */
    val droppedBadTime: Int = 0,
    /** The first few XMLTV ids that attached to nothing, for the log. */
    val sampleUnmatchedIds: List<String> = emptyList(),
    /** Set when the response was not an XMLTV document at all. */
    val problem: String? = null,
    /** What each public feed added, after the provider's own data. */
    val sources: List<SourceResult> = emptyList(),
) {
    /** Channels the public feeds filled that the provider had nothing for. */
    val extraChannelsFilled: Int get() = sources.sumOf { it.channelsFilled }

    /** One line for the Settings screen. */
    fun summary(): String = when {
        problem != null -> problem
        xmltvChannels == 0 && programmesSeen == 0 -> "The provider's guide was empty."
        programmesKept == 0 && droppedUnknownChannel > 0 ->
            "$programmesSeen programmes, none for channels in the list ($xmltvChannels guide channels, $unmatchedChannels channels unmatched)."
        programmesKept == 0 && droppedOutsideWindow > 0 ->
            "$programmesSeen programmes, all outside the next three days: check the time offset."
        else -> buildString {
            append("$programmesKept programmes for ${matchedById + matchedByName} channels ")
            append("($matchedById by id, $matchedByName by name)")
            if (sources.isNotEmpty()) {
                val worked = sources.filter { it.channelsFilled > 0 }
                append(if (worked.isEmpty()) ". No extra channels from the public guides."
                       else ". Public guides added $extraChannelsFilled channels: " +
                           worked.joinToString(", ") { "${it.label} ${it.channelsFilled}" } + ".")
            }
        }
    }

    /** Everything, for logcat. */
    fun detail(): String =
        "xmltvChannels=$xmltvChannels byId=$matchedById byName=$matchedByName unmatched=$unmatchedChannels " +
            "seen=$programmesSeen kept=$programmesKept unknownChannel=$droppedUnknownChannel " +
            "outsideWindow=$droppedOutsideWindow badTime=$droppedBadTime" +
            (if (sampleUnmatchedIds.isNotEmpty()) " unmatchedIds=$sampleUnmatchedIds" else "") +
            (problem?.let { " problem=$it" } ?: "") +
            sources.joinToString("") {
                " [${it.id} filled=${it.channelsFilled} kept=${it.programmesKept}" +
                    (it.problem?.let { p -> " problem=$p" } ?: "") + "]"
            }
}

/**
 * Imports guide data: the provider's `xmltv.php` first, then the built-in public feeds for
 * whatever the provider had nothing for.
 *
 * A provider's XMLTV for a few thousand channels over several days is well over a hundred
 * megabytes — the mock server's is 187 MB with 542,046 programmes, deliberately, because that is
 * the case worth being able to survive. So this pulls through the document with an
 * `XmlPullParser`, holds one `<programme>` at a time, and writes survivors in batches.
 *
 * Three filters run while parsing, before anything is allocated for long:
 *
 *  - **Channel.** Only programmes whose `channel` attribute matches a channel that survived the
 *    import are kept. With four countries out of thirty that discards most of the document.
 *  - **Time.** Only a window from [PAST_WINDOW_MS] behind to [FUTURE_WINDOW_MS] ahead is kept.
 *    A provider publishing two weeks of guide data is not worth storing on a Fire Stick.
 *  - **Already covered.** A public feed is only ever shown the channels that still have no
 *    listings, so the twelfth feed in the list parses against a handful of names rather than
 *    eleven thousand, and a feed is skipped entirely once nothing is left to fill.
 *
 * The order of the sources *is* the priority: whoever writes a slot first keeps it, because the
 * programmes table has a unique index on (channelKey, startMs). The provider is always first, so
 * its data — which knows what this account actually carries — is never overwritten by a public
 * feed's guess.
 */
class XmltvImporter(
    private val client: XtreamClient,
    private val db: CrimsonDatabase,
    private val feeds: EpgFeedClient = EpgFeedClient(),
) {

    /**
     * Runs the whole import. [extraSources] are the public feeds to try after the provider's own
     * data; pass an empty list to use the provider alone.
     */
    fun import(
        manualOffsetHours: Int = 0,
        now: Long = System.currentTimeMillis(),
        extraSources: List<EpgSource> = emptyList(),
    ): Flow<EpgProgress> =
        flow {
            emit(EpgProgress.Downloading)

            val allChannels = db.channelDao().all()
            if (allChannels.isEmpty()) {
                emit(EpgProgress.Done(0, 0))
                return@flow
            }

            val from = now - PAST_WINDOW_MS
            val to = now + FUTURE_WINDOW_MS
            val offsetMinutes = manualOffsetHours * 60

            // ---------------------------------------------------------- the provider
            val stats = Stats()
            val matchedById = HashSet<String>()
            val matchedByName = HashSet<String>()
            val filledByProvider = HashSet<String>()

            readOneSource(
                sourceId = PROVIDER_ID,
                open = { sniff(client.openXmltv(), stats) },
                index = ChannelIndex.of(allChannels, includeProviderIds = true),
                from = from,
                to = to,
                offsetMinutes = offsetMinutes,
                stats = stats,
                onChannelMatched = { keys, byName ->
                    if (byName) matchedByName.addAll(keys)
                    else matchedById.addAll(keys.filter { it !in matchedByName })
                },
                onFilled = filledByProvider::add,
            )

            // ---------------------------------------------------------- the public feeds
            val sourceResults = ArrayList<SourceResult>(extraSources.size)

            // Event slots and 24/7 loops are skipped: their name is their listing, no feed
            // carries them, and leaving them in would make every feed parse against thousands of
            // names that can never match.
            var remaining = allChannels.filter {
                it.channelKey !in filledByProvider &&
                    !EventChannelEpg.isSynthetic(it.originalName, it.categoryName)
            }

            extraSources.forEachIndexed { i, source ->
                if (remaining.isEmpty()) return@forEachIndexed
                emit(EpgProgress.Source(source.label, i + 1, extraSources.size))

                val sourceStats = Stats()
                val filledHere = HashSet<String>()
                val failure = runCatching {
                    readOneSource(
                        sourceId = source.id,
                        open = { sniff(feeds.open(source), sourceStats, source.label) },
                        index = ChannelIndex.of(remaining, includeProviderIds = false),
                        from = from,
                        to = to,
                        offsetMinutes = offsetMinutes,
                        stats = sourceStats,
                        onChannelMatched = { _, _ -> },
                        onFilled = filledHere::add,
                    )
                }.exceptionOrNull()

                sourceResults.add(
                    SourceResult(
                        id = source.id,
                        label = source.label,
                        channelsFilled = filledHere.size,
                        programmesKept = sourceStats.kept,
                        problem = failure?.let { it.message ?: it.javaClass.simpleName }
                            ?: sourceStats.problem,
                    )
                )
                if (filledHere.isNotEmpty()) {
                    remaining = remaining.filter { it.channelKey !in filledHere }
                }
            }

            // Old programmes are dropped on every refresh so the table does not grow without
            // bound over months of use.
            db.programDao().prune(from)

            val report = EpgReport(
                xmltvChannels = stats.xmltvChannels,
                matchedById = matchedById.size,
                matchedByName = matchedByName.size,
                unmatchedChannels = (allChannels.size - matchedById.size - matchedByName.size -
                    sourceResults.sumOf { it.channelsFilled }).coerceAtLeast(0),
                programmesSeen = stats.seen,
                programmesKept = stats.kept,
                droppedUnknownChannel = stats.unknownChannel,
                droppedOutsideWindow = stats.outsideWindow,
                droppedBadTime = stats.badTime,
                sampleUnmatchedIds = stats.sampleUnmatched.toList(),
                problem = stats.problem,
                sources = sourceResults,
            )
            Log.i(TAG, "xmltv import: ${report.detail()}")
            emit(EpgProgress.Done(stats.kept + sourceResults.sumOf { it.programmesKept }, stats.seen, report))
        }.flowOn(Dispatchers.IO)

    /** Reads one document end to end, writing what survives in batches. */
    /** Running counts for one import. */
    private class Stats {
        var xmltvChannels = 0
        var seen = 0
        var kept = 0
        var unknownChannel = 0
        var outsideWindow = 0
        var badTime = 0
        val sampleUnmatched = LinkedHashSet<String>()
        var problem: String? = null
    }

    /**
     * Looks at the first bytes of the response before handing it to the XML parser.
     *
     * A provider that answers `xmltv.php` with an HTML error page, or a JSON `{"auth":0}`,
     * used to look exactly like "the guide is empty". It is reported here instead of parsed.
     * (A gzip file served without a `Content-Encoding` header, the other common failure, is
     * unwrapped in `XtreamClient.readerFor` before the bytes get this far.)
     */
    private fun sniff(raw: Reader, stats: Stats, label: String = "xmltv.php"): Reader {
        val buffered = java.io.BufferedReader(raw, 1 shl 16)
        buffered.mark(SNIFF_CHARS)
        val head = CharArray(SNIFF_CHARS)
        var n = 0
        while (n < SNIFF_CHARS) {
            val read = buffered.read(head, n, SNIFF_CHARS - n)
            if (read < 0) break
            n += read
        }
        buffered.reset()
        val text = String(head, 0, n)

        // A byte-order mark has to be *consumed*, not just ignored: XmlPullParser reports it as
        // content before the prolog and gives up on the whole document. Several of the public
        // feeds ship one, and before this the highest-yield feed of the lot failed on it with
        // "Unexpected token" while looking for all the world like a network problem.
        if (n > 0 && head[0] == '\uFEFF') buffered.read()

        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        when {
            n == 0 -> stats.problem = "$label returned an empty response."
            trimmed.startsWith("<") -> Unit
            trimmed.startsWith("{") || trimmed.startsWith("[") ->
                stats.problem = "$label answered with JSON, not a guide: " + trimmed.take(80)
            else -> stats.problem = "$label did not return XML: " + trimmed.take(80).replace('\n', ' ')
        }
        if (stats.problem != null) Log.w(TAG, "sniff: ${stats.problem}")
        return buffered
    }

    private suspend fun readOneSource(
        sourceId: String,
        open: () -> Reader,
        index: ChannelIndex,
        from: Long,
        to: Long,
        offsetMinutes: Int,
        stats: Stats,
        onChannelMatched: (keys: List<String>, byName: Boolean) -> Unit,
        onFilled: (channelKey: String) -> Unit,
    ) {
        val batch = ArrayList<ProgramEntity>(BATCH_SIZE)
        val reader = open()
        if (stats.problem != null) {
            reader.close()
            return
        }
        reader.use {
            parse(
                reader = it,
                index = index,
                keysByXmltvId = index.byId,
                from = from,
                to = to,
                fallbackOffsetMinutes = offsetMinutes,
                sourceId = sourceId,
                stats = stats,
                onChannelMatched = { _, keys, byName -> onChannelMatched(keys, byName) },
                onProgram = { program ->
                    onFilled(program.channelKey)
                    batch.add(program)
                    if (batch.size >= BATCH_SIZE) {
                        db.programDao().insertAllBlocking(batch)
                        batch.clear()
                    }
                },
            )
        }
        if (batch.isNotEmpty()) db.programDao().insertAllBlocking(batch)
    }

    /**
     * The two ways a guide channel attaches to channels in the list, built once per source.
     *
     * By id: the XMLTV `channel` attribute equals a channel's `epg_channel_id` (or, for the
     * provider only, its stream id or exact name). By name: a `<display-name>` in the document
     * reduces to the same [ChannelMatcher] key as the channel's name. Most channels on a real
     * account have no `epg_channel_id` at all, so the second route is the one that fills the grid.
     */
    private class ChannelIndex(
        val byId: HashMap<String, MutableList<String>>,
        val byName: HashMap<String, MutableList<String>>,
        /** A channel's own `epg_channel_id`, so a name match can stand aside for it. */
        val epgIdOf: Map<String, String>,
        /** A channel's country, so a name match cannot cross borders. */
        val countryOf: Map<String, String>,
    ) {
        companion object {
            fun of(channels: List<ChannelEntity>, includeProviderIds: Boolean): ChannelIndex {
                val byId = HashMap<String, MutableList<String>>()
                val byName = HashMap<String, MutableList<String>>()
                val epgIdOf = HashMap<String, String>()
                val countryOf = HashMap<String, String>()

                fun put(map: HashMap<String, MutableList<String>>, k: String, channelKey: String) {
                    if (k.isEmpty()) return
                    val list = map.getOrPut(k) { ArrayList(1) }
                    if (channelKey !in list) list.add(channelKey)
                }

                for (ch in channels) {
                    ch.epgChannelId?.takeIf(String::isNotBlank)?.let {
                        put(byId, it, ch.channelKey)
                        epgIdOf[ch.channelKey] = it
                    }
                    if (ch.country.isNotBlank()) countryOf[ch.channelKey] = ch.country
                    if (includeProviderIds) {
                        // Only the provider's own document can mean these: a public feed that
                        // happened to number a channel "12345" must not claim stream 12345.
                        put(byId, ch.channelKey, ch.channelKey)
                        put(byId, ch.streamId.toString(), ch.channelKey)
                        put(byId, ch.originalName, ch.channelKey)
                        put(byId, ch.displayName, ch.channelKey)
                    }
                    put(byName, ChannelMatcher.key(ch.displayName), ch.channelKey)
                    put(byName, ChannelMatcher.key(ch.originalName), ch.channelKey)
                }
                return ChannelIndex(byId, byName, epgIdOf, countryOf)
            }
        }
    }

    /**
     * Pulls through the document. `<channel>` elements, which XMLTV puts before the programmes,
     * are used to attach guide channels to list channels by display name; every `<programme>`
     * is then routed to whichever list channels its XMLTV id attached to, and counted either way.
     *
     * Name matches are held back until the first `<programme>`, by which point every `<channel>`
     * in the document has been seen. That is what lets a name match stand aside for a channel
     * that has its own entry further down the file — an id match is what the provider meant, and
     * a name match is only ever a guess at what they meant.
     */
    private suspend fun parse(
        reader: Reader,
        index: ChannelIndex,
        keysByXmltvId: MutableMap<String, MutableList<String>>,
        from: Long,
        to: Long,
        fallbackOffsetMinutes: Int,
        sourceId: String,
        stats: Stats,
        onChannelMatched: (xmltvId: String, keys: List<String>, byName: Boolean) -> Unit,
        onProgram: (ProgramEntity) -> Unit,
    ) {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        val documentIds = HashSet<String>()
        val pendingByName = ArrayList<Pair<String, List<String>>>()
        var committed = false

        /** Applies the held-back name matches, now that the whole channel list is known. */
        fun commitNameMatches() {
            for ((xmltvId, candidates) in pendingByName) {
                val idCountry = ChannelMatcher.countryOfId(xmltvId)
                val allowed = candidates.filter { channelKey ->
                    // The channel has its own entry in this document; let that one have it.
                    if (index.epgIdOf[channelKey]?.let { it in documentIds } == true) return@filter false
                    val mine = index.countryOf[channelKey]
                    idCountry == null || mine == null || idCountry == mine
                }
                if (allowed.isEmpty()) continue
                val existing = keysByXmltvId.getOrPut(xmltvId) { ArrayList(allowed.size) }
                val added = allowed.filter { it !in existing }
                if (added.isEmpty()) continue
                existing.addAll(added)
                onChannelMatched(xmltvId, added, true)
            }
            pendingByName.clear()
        }

        var event = parser.eventType
        var checks = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        stats.xmltvChannels++
                        readChannel(parser, index, documentIds, pendingByName)
                    }
                    "programme" -> {
                        if (!committed) {
                            commitNameMatches()
                            committed = true
                        }
                        // Cancellation is checked every so often rather than every tag: half a
                        // million iterations of a context lookup is measurable on a Stick.
                        if (++checks % 512 == 0) currentCoroutineContext().ensureActive()
                        stats.seen++
                        val xmltvId = parser.getAttributeValue(null, "channel").orEmpty()
                        val keys = keysByXmltvId[xmltvId]
                        if (keys == null) {
                            stats.unknownChannel++
                            if (stats.sampleUnmatched.size < SAMPLE_IDS) stats.sampleUnmatched.add(xmltvId)
                            skipTag(parser)
                        } else {
                            val program =
                                readProgramme(parser, keys.first(), from, to, fallbackOffsetMinutes, sourceId, stats)
                            if (program != null) {
                                stats.kept++
                                onProgram(program)
                                for (i in 1 until keys.size) onProgram(program.copy(channelKey = keys[i]))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Reads one `<channel>`, noting its id and what it might match by name.
     *
     * The match is only a candidate at this point: whether it is allowed depends on the rest of
     * the document, which has not been read yet. See `commitNameMatches`.
     */
    private fun readChannel(
        parser: XmlPullParser,
        index: ChannelIndex,
        documentIds: MutableSet<String>,
        pendingByName: MutableList<Pair<String, List<String>>>,
    ) {
        val id = parser.getAttributeValue(null, "id").orEmpty()
        val names = ArrayList<String>(2)
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "display-name") names.add(parser.nextText().trim()) else skipTag(parser)
        }
        if (id.isEmpty()) return
        documentIds.add(id)

        // Even an id that will match by epg_channel_id is tried by name: the same guide channel
        // usually serves several list channels ("US| A&E HD" with the id, "TV| A&E RAW" without),
        // and only the name links the second to it.
        val candidates = ArrayList<String>(names.size + 1)
        for (name in names) ChannelMatcher.key(name).takeIf { it.isNotEmpty() }?.let(candidates::add)
        ChannelMatcher.keyOfId(id).takeIf { it.isNotEmpty() }?.let(candidates::add)
        for (key in candidates) {
            val keys = index.byName[key] ?: continue
            pendingByName.add(id to ArrayList(keys))
            return
        }
    }

    /**
     * Reads one `<programme>`, returning null when it should be discarded.
     *
     * The channel and time checks happen from the attributes alone, before any child element is
     * read, so a programme on an unwanted channel costs nothing but a `skipTag`.
     */
    private fun readProgramme(
        parser: XmlPullParser,
        targetKey: String,
        from: Long,
        to: Long,
        fallbackOffsetMinutes: Int,
        sourceId: String,
        stats: Stats,
    ): ProgramEntity? {
        val start = XmltvTime.parse(parser.getAttributeValue(null, "start"), fallbackOffsetMinutes)
        val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"), fallbackOffsetMinutes)

        if (start == null || stop == null || stop <= start) {
            stats.badTime++
            skipTag(parser)
            return null
        }
        if (stop <= from || start >= to) {
            stats.outsideWindow++
            skipTag(parser)
            return null
        }

        var title = ""
        var description = ""
        var rating: String? = null
        val categories = ArrayList<String>(2)

        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                "desc" -> if (description.isEmpty()) description = parser.nextText().trim()
                "category" -> categories.add(parser.nextText().trim())
                "rating" -> rating = readRating(parser) ?: rating
                else -> skipTag(parser)
            }
        }

        return ProgramEntity(
            channelKey = targetKey,
            startMs = start,
            endMs = stop,
            title = title.ifEmpty { UNTITLED },
            description = description,
            category = ProgramCategory.fromXmltv(categories).name,
            rating = rating,
            source = sourceId,
        )
    }

    /** `<rating><value>TV-14</value></rating>`. */
    private fun readRating(parser: XmlPullParser): String? {
        var value: String? = null
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "value") value = parser.nextText().trim() else skipTag(parser)
        }
        return value?.takeIf { it.isNotEmpty() }
    }

    /** Consumes the current element and everything inside it. */
    private fun skipTag(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        const val TAG = "CrimsonEpg"
        /** What the provider's own rows are tagged with in the programmes table. */
        const val PROVIDER_ID = "provider"
        private const val SNIFF_CHARS = 512
        private const val SAMPLE_IDS = 8
        /** Two hours back, as the spec asks: enough that the guide can show what is on now. */
        const val PAST_WINDOW_MS = 2 * 60 * 60 * 1000L
        /** Seventy-two hours ahead. Beyond that the data is not worth a Fire Stick's storage. */
        const val FUTURE_WINDOW_MS = 72 * 60 * 60 * 1000L
        const val BATCH_SIZE = 500
        const val UNTITLED = "Untitled"
    }
}

/**
 * The per-channel fallback for channels XMLTV had nothing for.
 *
 * Fetched lazily and only for rows the guide is actually showing, because a provider with no
 * XMLTV at all would otherwise mean one HTTP request per channel at import time.
 */
class ShortEpgFetcher(
    private val client: XtreamClient,
    private val db: CrimsonDatabase,
) {

    /** Fetches short EPG for any of [streamIds] that has no stored guide data. Returns how many. */
    suspend fun fillGaps(streamIds: List<Long>, limit: Int = 8): Int {
        if (streamIds.isEmpty()) return 0
        val channels = streamIds.mapNotNull { db.channelDao().byStreamId(it) }
        val haveData = db.programDao().keysWithData(channels.map { it.channelKey }).toSet()
        val missing = channels.filterNot { it.channelKey in haveData }
        var filled = 0

        for (channel in missing) {
            currentCoroutineContext().ensureActive()
            val entries = runCatching { client.shortEpg(channel.streamId, limit) }.getOrNull()
                ?: continue
            if (entries.isEmpty()) continue
            db.programDao().insertAll(
                entries.map { entry ->
                    ProgramEntity(
                        channelKey = channel.channelKey,
                        startMs = entry.startEpochSeconds * 1000L,
                        endMs = entry.stopEpochSeconds * 1000L,
                        // Titles and descriptions in this endpoint are base64; some providers
                        // send them in plain text anyway, which decodeField handles.
                        title = ShortEpg.decodeField(entry.titleEncoded)
                            .ifBlank { XmltvImporter.UNTITLED },
                        description = ShortEpg.decodeField(entry.descriptionEncoded),
                        category = ProgramCategory.SERIES_OTHER.name,
                        rating = null,
                        source = SHORT_EPG_ID,
                    )
                }
            )
            filled++
        }
        return filled
    }

    companion object {
        /** What the per-channel fallback's rows are tagged with. */
        const val SHORT_EPG_ID = "short-epg"
    }
}
