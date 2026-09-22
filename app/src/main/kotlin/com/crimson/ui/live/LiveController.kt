package com.crimson.ui.live

import android.util.Log
import com.crimson.AppContainer
import com.crimson.core.catalog.AdultContent
import com.crimson.core.catalog.CategoryTitles
import com.crimson.core.catalog.ChannelPackage
import com.crimson.core.catalog.ChannelPackages
import com.crimson.core.catalog.PackageResolver
import com.crimson.core.epg.EventChannelEpg
import com.crimson.core.guide.ProgramSlot
import com.crimson.core.live.Region
import com.crimson.core.live.WorldRegions
import com.crimson.core.model.Country
import com.crimson.core.text.NameCleaner
import com.crimson.data.db.CategoryEntity
import com.crimson.data.db.ChannelEntity
import com.crimson.domain.GuideChannel
import com.crimson.ui.Mappers
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.RowKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lineups the Live TV page can show. */
enum class LiveCollection(val label: String, val packageId: String?) {
    AMERICAN_CABLE("American Cable", "us_cable"),
    JAPAN("Japanese TV", "jp_tv"),
    KOREA("Korean TV", "kr_tv"),
    FAVORITES("My Channels", null),
    ALL("All Channels", null),
}

data class LiveState(
    val collection: LiveCollection = LiveCollection.AMERICAN_CABLE,
    val rows: List<FeedRow> = emptyList(),
    val loading: Boolean = true,
    /** The channel under the cursor, with what it is showing, for the hero. */
    val focused: ChannelTile? = null,
    val focusedNext: ProgramSlot? = null,
    /** True once the focused channel is playing in the hero's preview window. */
    val previewing: Boolean = false,
)

data class RegionEntry(val region: Region, val categories: List<CategoryEntity>)

data class DirectoryState(
    val regions: List<RegionEntry> = emptyList(),
    val query: String = "",
    val selectedRegion: String? = null,
    val selectedCategory: String? = null,
    val channels: List<ChannelTile> = emptyList(),
    val loadingChannels: Boolean = false,
    val error: String? = null,
) {
    val visibleRegions: List<RegionEntry>
        get() = regions.filter { WorldRegions.matches(it.region, query) }
    val categories: List<CategoryEntity>
        get() = regions.firstOrNull { it.region.code == selectedRegion }?.categories.orEmpty()
}

/**
 * The Live TV page and the country directory behind it.
 *
 * Live TV opens on a recommended lineup — American Cable by default, with Japanese and Korean
 * lineups a click away — laid out as rows by section, each card showing what is on now. The full
 * list of the provider's categories lives in the directory, grouped by country, including
 * countries the import never kept: those are read from the provider when opened and played
 * directly, without being added to the guide.
 */
class LiveController(
    private val container: AppContainer,
    private val allChannels: () -> List<GuideChannel>,
    private val categories: () -> List<CategoryEntity>,
    private val startPreview: (GuideChannel) -> Unit,
) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _directory = MutableStateFlow(DirectoryState())
    val directory: StateFlow<DirectoryState> = _directory.asStateFlow()

    private var buildJob: Job? = null
    private var focusJob: Job? = null
    private var channelJob: Job? = null
    private val fetchedCategories = LinkedHashMap<String, List<GuideChannel>>()

    /** Channels behind the tiles, for tuning and zapping. */
    private val byStreamId = HashMap<Long, GuideChannel>()

    var previewsEnabled = true

    fun reset() {
        buildJob?.cancel(); focusJob?.cancel(); channelJob?.cancel()
        _state.value = LiveState()
        _directory.value = DirectoryState()
        fetchedCategories.clear()
        byStreamId.clear()
    }

    fun channel(streamId: Long): GuideChannel? =
        byStreamId[streamId] ?: allChannels().firstOrNull { it.streamId == streamId }

    /** The channels a collection holds, in lineup order. */
    fun channelsFor(collection: LiveCollection): List<GuideChannel> {
        val all = allChannels()
        return when (collection) {
            LiveCollection.FAVORITES -> all.filter { it.isFavorite }
            LiveCollection.ALL -> all
            else -> ChannelPackages.byId(collection.packageId!!)?.let { resolvePackage(it, all) }.orEmpty()
        }
    }

    fun select(collection: LiveCollection) {
        if (collection == _state.value.collection && _state.value.rows.isNotEmpty()) return
        _state.value = _state.value.copy(collection = collection)
        rebuild()
    }

    /** Rebuilds the rows, keeping the current ones on screen until the new ones are ready. */
    fun rebuild() {
        buildJob?.cancel()
        val collection = _state.value.collection
        if (_state.value.rows.isEmpty()) _state.value = _state.value.copy(loading = true)
        buildJob = scope.launch {
            val all = allChannels()
            val sections: List<Pair<String, List<GuideChannel>>> = withContext(Dispatchers.Default) {
                when (collection) {
                    LiveCollection.FAVORITES -> listOf("Your Channels" to all.filter { it.isFavorite })
                    LiveCollection.ALL -> all.groupBy { it.categoryName ?: "Other" }
                        .filterKeys { !AdultContent.isAdult(it) }
                        .map { (name, list) -> CategoryTitles.clean(name) to list }
                    else -> packageSections(ChannelPackages.byId(collection.packageId!!)!!, all)
                }
            }.filter { it.second.isNotEmpty() }
            sections.forEach { (_, list) -> list.forEach { byStreamId[it.streamId] = it } }
            // Now-playing for the first rows at once; the rest fill in as they scroll into view.
            val rows = sections.mapIndexed { index, (title, list) ->
                val tiles = if (index < EAGER_ROWS) nowPlaying(list.take(ROW_LIMIT)) else list.take(ROW_LIMIT).map { Mappers.channelTile(it) }
                FeedRow("live_${collection.name}_$index", title, RowKind.LIVE, tiles, subtitle = "${list.size} channels")
            }
            _state.value = _state.value.copy(rows = rows, loading = false)
        }
    }

    /** Fills in what is on for a row that has scrolled into view. */
    fun ensureNowPlaying(rowId: String) {
        val row = _state.value.rows.firstOrNull { it.id == rowId } ?: return
        if (row.tiles.any { (it as? ChannelTile)?.nowTitle != null }) return
        scope.launch {
            val channels = row.tiles.mapNotNull { (it as? ChannelTile)?.streamId?.let(::channel) }
            val tiles = nowPlaying(channels)
            _state.value = _state.value.copy(
                rows = _state.value.rows.map { if (it.id == rowId) it.copy(tiles = tiles) else it }
            )
        }
    }

    private fun packageSections(pkg: ChannelPackage, all: List<GuideChannel>): List<Pair<String, List<GuideChannel>>> {
        val resolver = PackageResolver(all) { it.name }
        val used = HashSet<Long>()
        val out = ArrayList<Pair<String, List<GuideChannel>>>()
        for (section in pkg.sections) {
            val sub = ChannelPackage(pkg.id, pkg.name, pkg.description, listOf(section))
            val resolved = resolver.resolve(sub) { _, found -> found.filterNot { it.streamId in used }.minByOrNull { it.number } }
                .map { it.second }
            resolved.forEach { used += it.streamId }
            out += section.name to resolved
        }
        when (pkg.id) {
            "us_cable" -> out += "Local Channels" to all.filter { it.market != null && it.streamId !in used }.sortedBy { it.number }
            "jp_tv" -> out += "More from Japan" to all.filter {
                it.streamId !in used && (it.country == Country.JP || it.categoryName?.contains("JAPAN", ignoreCase = true) == true)
            }
            "kr_tv" -> out += "More from Korea" to all.filter {
                it.streamId !in used && (it.country == Country.KR || it.categoryName?.contains("KOREA", ignoreCase = true) == true)
            }
        }
        return out
    }

    /**
     * The viewer's own copy of each channel in a lineup, in the lineup's order.
     *
     * Where a provider carries the same channel several times — an SD feed, an HD feed, a backup —
     * the lowest number wins, because the import numbers channels alphabetically within a country
     * block and the main feed is almost always the first one it saw.
     */
    fun resolvePackage(pkg: ChannelPackage, all: List<GuideChannel>): List<GuideChannel> =
        packageSections(pkg, all).flatMap { it.second }

    /** Cards for [channels], with what each is showing now and next. */
    suspend fun nowPlaying(channels: List<GuideChannel>): List<ChannelTile> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val offset = 0
        val rows = runCatching {
            channels.map { it.channelKey }.distinct().chunked(800).flatMap {
                container.database.programDao().nowPlayingFor(it, now)
            }
        }.getOrDefault(emptyList()).associateBy { it.channelKey }
        channels.map { channel ->
            val row = rows[channel.channelKey]
            val slot = row?.let(Mappers::slot) ?: EventChannelEpg.programs(
                channel.rawName, now - 6 * HOUR, now + 6 * HOUR,
                { ms -> java.util.TimeZone.getDefault().getOffset(ms).toLong() + offset * HOUR }, channel.categoryName,
            ).firstOrNull { it.containsTime(now) }
            Mappers.channelTile(channel, slot)
        }
    }

    /** The cursor rested on a live card: show its details, and after a moment play it in the hero. */
    fun focus(tile: ChannelTile) {
        focusJob?.cancel()
        _state.value = _state.value.copy(focused = tile, focusedNext = null, previewing = false)
        focusJob = scope.launch {
            val channel = channel(tile.streamId) ?: return@launch
            val (_, next) = runCatching {
                container.guideRepository.nowAndNext(channel, System.currentTimeMillis())
            }.getOrDefault(null to null)
            if (_state.value.focused?.streamId == tile.streamId) _state.value = _state.value.copy(focusedNext = next)
            if (!previewsEnabled) return@launch
            delay(PREVIEW_DELAY_MS)
            if (_state.value.focused?.streamId == tile.streamId) {
                startPreview(channel)
                _state.value = _state.value.copy(previewing = true)
            }
        }
    }

    fun stopPreview() {
        focusJob?.cancel()
        _state.value = _state.value.copy(previewing = false)
    }

    // ------------------------------------------------------------------ directory

    fun openDirectory() {
        val cats = categories().filterNot { AdultContent.isAdult(it.name) }
        val grouped = cats.groupBy { WorldRegions.forCategory(it.country, it.foreignMarker, it.name) }
        val pinned = listOf("US", "UK", "CA", "JP", "KR")
        val regions = grouped.map { (region, list) -> RegionEntry(region, list.sortedBy { it.name.lowercase() }) }
            .sortedWith(
                compareBy<RegionEntry> { pinned.indexOf(it.region.code).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.region.isGroup }
                    .thenBy { if (it.region == WorldRegions.OTHER) 1 else 0 }
                    .thenBy { it.region.name }
            )
        val current = _directory.value
        _directory.value = current.copy(
            regions = regions,
            selectedRegion = current.selectedRegion ?: regions.firstOrNull()?.region?.code,
        )
        if (current.selectedCategory == null) {
            _directory.value.categories.firstOrNull()?.let { selectCategory(it.categoryId) }
        }
    }

    fun setRegionQuery(query: String) {
        _directory.value = _directory.value.copy(query = query.take(30))
    }

    fun selectRegion(code: String) {
        if (_directory.value.selectedRegion == code) return
        _directory.value = _directory.value.copy(selectedRegion = code, selectedCategory = null, channels = emptyList())
    }

    fun selectCategory(categoryId: String) {
        if (_directory.value.selectedCategory == categoryId && _directory.value.channels.isNotEmpty()) return
        channelJob?.cancel()
        _directory.value = _directory.value.copy(selectedCategory = categoryId, channels = emptyList(), loadingChannels = true, error = null)
        channelJob = scope.launch {
            val local = allChannels().filter { it.categoryId == categoryId }
            val channels = if (local.isNotEmpty()) local else fetchCategory(categoryId)
            channels.forEach { byStreamId[it.streamId] = it }
            val tiles = if (local.isNotEmpty()) nowPlaying(channels.take(300)) else channels.take(600).map { Mappers.channelTile(it).copy(isImported = false) }
            if (_directory.value.selectedCategory == categoryId) {
                _directory.value = _directory.value.copy(
                    channels = tiles,
                    loadingChannels = false,
                    error = if (tiles.isEmpty()) "No channels in this category." else null,
                )
            }
        }
    }

    /** The channels in a category this profile never imported, read from the provider now. */
    private suspend fun fetchCategory(categoryId: String): List<GuideChannel> {
        fetchedCategories[categoryId]?.let { return it }
        val client = container.client ?: return emptyList()
        val category = categories().firstOrNull { it.categoryId == categoryId }
        val list = withContext(Dispatchers.IO) {
            val out = ArrayList<GuideChannel>()
            runCatching {
                client.streamCategory(categoryId) { raw ->
                    if (out.size < MAX_FETCHED) {
                        val name = NameCleaner.clean(raw.name)
                        out += GuideChannel(
                            streamId = raw.streamId,
                            number = 0,
                            name = name,
                            shortName = name,
                            channelKey = ChannelEntity.key(raw.epgChannelId, raw.streamId),
                            logoUrl = raw.streamIcon,
                            country = null,
                            market = null,
                            categoryId = categoryId,
                            rawName = raw.name,
                            categoryName = category?.name,
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "category $categoryId could not be read", it) }
            out
        }
        fetchedCategories[categoryId] = list
        while (fetchedCategories.size > 6) fetchedCategories.remove(fetchedCategories.keys.first())
        return list
    }

    /** The channels of the directory's open category, for zapping through with Up and Down. */
    fun directoryChannels(): List<GuideChannel> =
        _directory.value.channels.mapNotNull { channel(it.streamId) }

    companion object {
        private const val TAG = "CrimsonLive"
        private const val HOUR = 3_600_000L
        const val ROW_LIMIT = 60
        const val EAGER_ROWS = 4
        const val PREVIEW_DELAY_MS = 1_400L
        const val MAX_FETCHED = 2_000
    }
}
