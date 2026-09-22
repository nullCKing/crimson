package com.crimson.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crimson.AppContainer
import com.crimson.core.guide.GuideCursor
import com.crimson.core.guide.GuideHit
import com.crimson.core.guide.GuideNavigator
import com.crimson.core.guide.GuideSource
import com.crimson.core.guide.ProgramSlot
import com.crimson.core.guide.TimeWindow
import com.crimson.core.model.Country
import com.crimson.core.model.FilterRules
import com.crimson.core.model.Market
import com.crimson.data.epg.EpgProgress
import com.crimson.data.epg.ShortEpgFetcher
import com.crimson.data.epg.XmltvImporter
import com.crimson.data.settings.AppSettings
import com.crimson.data.xtream.ImportProgress
import com.crimson.data.xtream.XtreamAccount
import com.crimson.data.xtream.XtreamError
import com.crimson.domain.GuideChannel
import com.crimson.player.PlayableChannel
import com.crimson.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.crimson.core.model.RawCategory
import com.crimson.core.model.RawVodInfo
import com.crimson.core.model.RawSeriesInfo
import com.crimson.core.catalog.ChannelPackage
import com.crimson.core.catalog.ChannelPackages
import com.crimson.core.catalog.CuratedLists
import com.crimson.core.catalog.LibraryIndex
import com.crimson.core.catalog.PackageResolver
import com.crimson.core.catalog.TitleKind
import com.crimson.core.catalog.TitleMatcher
import com.crimson.core.epg.EpgSources
import com.crimson.core.epg.EventChannelEpg
import com.crimson.core.filter.CountryDetector
import com.crimson.core.filter.ForeignCountries
import com.crimson.core.filter.StreamingServiceDetector
import com.crimson.core.text.Tokenizer
import com.crimson.data.db.CategoryEntity
import com.crimson.data.db.SeriesEntity
import com.crimson.data.db.VodEntity
import com.crimson.ui.components.PosterItem
import com.crimson.ui.categories.ALL_CHANNELS_ID
import com.crimson.ui.categories.FAVORITE_CHANNELS_ID

/** Which screen is in front. */
enum class Screen {
    Starting,
    Login,
    Importing,
    Home,
    LiveCategories,
    Watching,
    Guide,
    /** Films and series together, the way a set-top box's On Demand menu had them. */
    OnDemand,
    /** Search, favourites, channel packages and the curated rows. */
    Browse,
    Settings,
}

/** Which half of the on-demand catalogue is showing. */
enum class OnDemandMode { MOVIES, SERIES }

/** A row of covers built from a curated list, holding only what the viewer actually has. */
data class CuratedRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val items: List<PosterItem>,
)

/** One channel package, and how much of it this account can fill. */
data class PackageSummary(
    val id: String,
    val name: String,
    val description: String,
    val found: Int,
    val total: Int,
)

/** Everything the Browse screen shows. */
data class BrowseState(
    val query: String = "",
    val searching: Boolean = false,
    val channelResults: List<PosterItem> = emptyList(),
    val movieResults: List<PosterItem> = emptyList(),
    val seriesResults: List<PosterItem> = emptyList(),
    val favorites: List<PosterItem> = emptyList(),
    val packages: List<PackageSummary> = emptyList(),
    val movieRows: List<CuratedRow> = emptyList(),
    val seriesRows: List<CuratedRow> = emptyList(),
    /** Set while the catalogue is still being fetched or indexed. */
    val note: String? = null,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val resultCount: Int get() = channelResults.size + movieResults.size + seriesResults.size
}

data class UiState(
    val screen: Screen = Screen.Starting,
    val settings: AppSettings = AppSettings(),
    val loginError: String? = null,
    val isSigningIn: Boolean = false,
    val importMessage: String? = null,
    val importDetail: String? = null,
    val allowedFormats: List<String> = listOf("ts"),
    val accountExpiry: Long? = null,
    val maxConnections: Int = 1,
    val credentialsEncrypted: Boolean = true,
    /** Set while adding a country needs new categories fetched. */
    val isFetchingMore: Boolean = false,
    /** Digits typed on a keyboard or a numeric remote, waiting to become a channel change. */
    val pendingChannelNumber: String = "",
    /** Set while a manual guide refresh is running, so Settings can say so. */
    val isRefreshingEpg: Boolean = false,
    /** Which feed the running refresh is on. */
    val epgRefreshDetail: String? = null,
    val savedAccount: XtreamAccount? = null,
    val liveCategories: List<CategoryEntity> = emptyList(),
    val categoryChannelCounts: Map<String, Int> = emptyMap(),
    val favoriteCategoryIds: Set<String> = emptySet(),
    val favoriteChannelCount: Int = 0,
    val totalChannelCount: Int = 0,
    val selectedVodInfo: RawVodInfo? = null,
    val isLoadingVodInfo: Boolean = false,
    val onDemandMode: OnDemandMode = OnDemandMode.MOVIES,
    /** Null means the curated rows rather than a category's grid. */
    val onDemandCategoryId: String? = null,
    val onDemandItems: List<PosterItem> = emptyList(),
    val onDemandCategories: List<RawCategory> = emptyList(),
    val selectedSeriesInfo: RawSeriesInfo? = null,
    val isLoadingSeriesInfo: Boolean = false,
)

/**
 * The channel banner's contents.
 *
 * [showToken] changes on every tune, including a re-tune to the same channel, which is what lets
 * the overlay restart its four-second timer rather than keeping a stale one running.
 */
data class BannerState(
    val channel: GuideChannel? = null,
    val now: ProgramSlot? = null,
    val next: ProgramSlot? = null,
    val showToken: Long = 0L,
)

const val PACKAGE_PREFIX = "__pkg:"
const val DEFAULT_CATEGORY_ID = PACKAGE_PREFIX + "us_cable"
const val DEFAULT_CATEGORY_NAME = "AMERICAN CABLE"

data class GuideState(
    val channels: List<GuideChannel> = emptyList(),
    val allChannels: List<GuideChannel> = emptyList(),
    val programsByKey: Map<String, List<ProgramSlot>> = emptyMap(),
    val cursor: GuideCursor = GuideCursor(0, 0L, TimeWindow(0L), 0),
    val nowMs: Long = 0L,
    /** The programme under the highlight. */
    val selected: ProgramSlot? = null,
    /** Non-null while the future-programme details dialog is open. */
    val details: ProgramSlot? = null,
    val categoryId: String? = null,
    val categoryName: String = "ALL CHANNELS",
) {
    val selectedChannel: GuideChannel?
        get() = channels.getOrNull(cursor.channelIndex)
}

/**
 * The app's single view model.
 *
 * It coordinates login, import, the guide cursor and the player. The genuinely tricky logic —
 * which channels survive the filter, where a cell sits on the grid, what Up and Left do to the
 * cursor — is not here: it lives in `:core` where it is unit-tested without an emulator. What is
 * here is plumbing, deliberately kept thin for that reason.
 */
class CrimsonViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _guide = MutableStateFlow(
        GuideState(
            categoryId = DEFAULT_CATEGORY_ID,
            categoryName = DEFAULT_CATEGORY_NAME,
        )
    )
    val guide: StateFlow<GuideState> = _guide.asStateFlow()

    private val _banner = MutableStateFlow(BannerState())
    val banner: StateFlow<BannerState> = _banner.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    val playback: StateFlow<PlaybackState> get() = container.player.state

    private var channelsJob: Job? = null
    private var importJob: Job? = null
    private var programsJob: Job? = null
    private var searchJob: Job? = null
    private var browseJob: Job? = null
    private var libraryJob: Job? = null

    /**
     * When the user last changed a filter rule, so the time until the guide has the new channel
     * list can be logged against the spec's 100 ms target. Zero when nothing is being timed.
     */
    private var rulesChangedAt = 0L

    /**
     * Reads the loaded window. The navigator asks for a row's programmes while moving the cursor,
     * so this must be a cheap in-memory lookup, never a query.
     */
    private val guideSource = object : GuideSource {
        override val channelCount: Int get() = _guide.value.channels.size

        override fun programsFor(channelIndex: Int, window: TimeWindow): List<ProgramSlot> {
            val state = _guide.value
            val channel = state.channels.getOrNull(channelIndex) ?: return emptyList()
            return state.programsByKey[channel.channelKey].orEmpty()
        }
    }

    private val navigator = GuideNavigator(guideSource, visibleRows = VISIBLE_ROWS)

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                credentialsEncrypted = container.credentials.isEncrypted,
            )
            observeSettings()
            restoreSession()
        }
    }

    // ------------------------------------------------------------------ startup

    private fun observeSettings() {
        viewModelScope.launch {
            container.settings.settings.collectLatest { settings ->
                val previous = _ui.value.settings
                _ui.value = _ui.value.copy(settings = settings)
                if (previous.rules != settings.rules) restartChannelObservation(settings.rules)
            }
        }
    }

    private var rawCategories: List<CategoryEntity> = emptyList()

    private fun updateLiveCategories() {
        val channels = _guide.value.allChannels
        val channelCounts = channels.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
        val allowedCountries = setOf("US", "UK", "JP", "KR")

        val filtered = rawCategories.filter { cat ->
            // 1. Exclude streaming services (Netflix, Paramount+, HBO Max, Disney+, etc.)
            if (StreamingServiceDetector.isStreamingService(cat.name)) return@filter false

            // 2. Exclude foreign countries
            if (cat.foreignMarker != null) return@filter false
            val tokens = Tokenizer.tokenize(cat.name)
            if (ForeignCountries.detect(tokens) != null) return@filter false

            // 3. Must have at least 1 kept channel
            val count = channelCounts[cat.categoryId] ?: 0
            if (count == 0) return@filter false

            // 4. If category has a prefix before '|', validate it strictly
            val pipeIdx = cat.name.indexOf('|')
            if (pipeIdx > 0) {
                val prefix = cat.name.substring(0, pipeIdx).trim()
                val prefixTokens = Tokenizer.tokenize(prefix)
                val prefixCountry = CountryDetector.detect(prefixTokens, prefixOnly = true)?.country
                if (prefixCountry != null && prefixCountry.code in allowedCountries) {
                    return@filter true
                }
                if (prefix.equals("NA", ignoreCase = true) || prefix.startsWith("24/7", ignoreCase = true)) {
                    return@filter true
                }
                // Any other prefix before '|' is rejected
                return@filter false
            }

            // 5. Must belong to US, UK, Japan, or Korea
            val hasExplicitAllowedCountry = cat.country in allowedCountries ||
                CountryDetector.detect(tokens, prefixOnly = true)?.country != null ||
                CountryDetector.detect(tokens, prefixOnly = false)?.country != null

            if (hasExplicitAllowedCountry) return@filter true

            // If no explicit country marker in category name (e.g. 24/7 channels), ensure all channels inside belong to allowed countries
            val categoryChannels = channels.filter { it.categoryId == cat.categoryId }
            categoryChannels.isNotEmpty() && categoryChannels.all { it.country?.code in allowedCountries }
        }

        _ui.value = _ui.value.copy(
            liveCategories = filtered,
            categoryChannelCounts = channelCounts,
        )
    }

    private fun observeLiveCategories() {
        viewModelScope.launch {
            container.database.categoryDao().observeAll().collectLatest { cats ->
                rawCategories = cats
                updateLiveCategories()
            }
        }
        viewModelScope.launch {
            container.guideRepository.observeFavoriteCategoryIds().collectLatest { favIds ->
                _ui.value = _ui.value.copy(favoriteCategoryIds = favIds)
            }
        }
    }

    private suspend fun restoreSession() {
        val account = container.credentials.load()
        if (account == null) {
            _ui.value = _ui.value.copy(screen = Screen.Login)
            return
        }
        _ui.value = _ui.value.copy(savedAccount = account)
        val settings = container.settings.settings.first()
        container.connect(account)
        observeLiveCategories()
        if (settings.hasCompletedImport && container.guideRepository.channelCount() > 0) {
            restartChannelObservation(settings.rules)
            _ui.value = _ui.value.copy(screen = Screen.Home)
        } else {
            runImport(fullRefresh = true)
        }
    }

    // ------------------------------------------------------------------ login

    fun signIn(serverUrl: String, username: String, password: String) {
        if (_ui.value.isSigningIn) return
        _ui.value = _ui.value.copy(isSigningIn = true, loginError = null)
        viewModelScope.launch {
            val account = XtreamAccount(serverUrl.trim(), username.trim(), password)
            val result = withContext(Dispatchers.IO) {
                runCatching { container.connect(account).login() }
            }
            result.fold(
                onSuccess = { info ->
                    container.credentials.save(account)
                    container.settings.setStreamFormat(info.preferredFormat)
                    observeLiveCategories()
                    _ui.value = _ui.value.copy(
                        isSigningIn = false,
                        loginError = null,
                        savedAccount = account,
                        allowedFormats = info.allowedOutputFormats,
                        accountExpiry = info.expiryEpochSeconds?.times(1000L),
                        maxConnections = info.maxConnections,
                    )
                    runImport(fullRefresh = true)
                },
                onFailure = { error ->
                    container.disconnect()
                    _ui.value = _ui.value.copy(
                        isSigningIn = false,
                        screen = Screen.Login,
                        savedAccount = account,
                        loginError = describe(error),
                    )
                },
            )
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is XtreamError.BadCredentials ->
            error.serverMessage?.takeIf { it.isNotBlank() }
                ?: "That username or password was not accepted by the server."
        is XtreamError.Expired -> "This account has expired."
        is XtreamError.Unreachable ->
            "Could not reach the server. Check the address and your connection."
        is XtreamError.NotXtream -> "That address did not answer like an Xtream panel."
        else -> error.message ?: "Something went wrong signing in."
    }

    fun signOut() {
        container.player.stop()
        container.credentials.clear()
        container.disconnect()
        channelsJob?.cancel()
        importJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.database.clearAllTables()
            }
            container.settings.clear()
            _guide.value = GuideState()
            _ui.value = UiState(screen = Screen.Login)
        }
    }

    // ------------------------------------------------------------------ import

    fun runImport(fullRefresh: Boolean) {
        val importer = container.channelImporter() ?: return
        importJob?.cancel()
        _ui.value = _ui.value.copy(screen = Screen.Importing, importMessage = "Reading categories…")
        importJob = viewModelScope.launch {
            val rules = container.settings.settings.first().rules
            runCatching {
                importer.import(rules, onlyNewCategories = !fullRefresh).collect { progress ->
                    when (progress) {
                        is ImportProgress.ReadingCategories ->
                            _ui.value = _ui.value.copy(importMessage = "Reading categories…")

                        is ImportProgress.Scanning ->
                            _ui.value = _ui.value.copy(
                                importMessage = "Read ${progress.seen} channels, kept ${progress.kept}",
                                importDetail = progress.category,
                            )

                        is ImportProgress.Done ->
                            _ui.value = _ui.value.copy(
                                importMessage = "Kept ${progress.kept} of ${progress.seen} channels",
                                importDetail = null,
                            )
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "import failed", error)
                _ui.value = _ui.value.copy(
                    screen = Screen.Login,
                    loginError = describe(error),
                )
                return@launch
            }

            // Guide data next. The channel list is already usable, so a slow EPG does not block
            // the user from watching television.
            _ui.value = _ui.value.copy(importMessage = "Loading the guide…", importDetail = null)
            restartChannelObservation(rules)
            _ui.value = _ui.value.copy(screen = Screen.Home)

            runCatching {
                val settings = container.settings.settings.first()
                container.xmltvImporter()?.import(
                    manualOffsetHours = settings.epgOffsetHours,
                    extraSources = extraSourcesFor(settings),
                )?.collect { progress ->
                    when (progress) {
                        is EpgProgress.Source ->
                            _ui.value = _ui.value.copy(
                                importMessage = "Loading the guide…",
                                importDetail = "${progress.label} (${progress.index} of ${progress.total})",
                            )
                        is EpgProgress.Done -> {
                            Log.i(TAG, "guide: kept ${progress.kept} of ${progress.seen} programmes")
                            container.settings.setEpgSummary(progress.report.summary())
                        }
                        else -> Unit
                    }
                }
                container.settings.setEpgRefreshedAt(System.currentTimeMillis())
                container.settings.setImportCompleted(true)
            }.onFailure {
                Log.w(TAG, "guide data failed to load", it)
                container.settings.setEpgSummary("Guide download failed: ${it.message ?: it.javaClass.simpleName}")
            }

            refreshWindow()

            // The on-demand catalogue last, and only after the guide: both are streaming imports
            // that write continuously, and a 1 GB stick should not be asked to run two at once.
            // Nothing on screen waits for it, and Browse starts it itself if the viewer gets
            // there first.
            importLibrary()
        }
    }

    /**
     * The public feeds worth fetching for the current filter: a viewer who has turned Japan off
     * never downloads the Japanese guide. Empty when the viewer has turned the feeds off.
     */
    private fun extraSourcesFor(settings: AppSettings) =
        if (!settings.useExtraEpgSources) emptyList()
        else EpgSources.forCountries(settings.rules.countries.map { it.code }.toSet())

    fun refreshEpgNow() {
        if (_ui.value.isRefreshingEpg) return
        _ui.value = _ui.value.copy(isRefreshingEpg = true)
        viewModelScope.launch {
            runCatching {
                val settings = container.settings.settings.first()
                container.xmltvImporter()?.import(
                    manualOffsetHours = settings.epgOffsetHours,
                    extraSources = extraSourcesFor(settings),
                )?.collect { progress ->
                    when (progress) {
                        is EpgProgress.Source ->
                            _ui.value = _ui.value.copy(epgRefreshDetail = progress.label)
                        is EpgProgress.Done ->
                            container.settings.setEpgSummary(progress.report.summary())
                        else -> Unit
                    }
                }
                container.settings.setEpgRefreshedAt(System.currentTimeMillis())
            }.onFailure {
                Log.w(TAG, "manual guide refresh failed", it)
                container.settings.setEpgSummary("Guide download failed: ${it.message ?: it.javaClass.simpleName}")
            }
            _ui.value = _ui.value.copy(isRefreshingEpg = false, epgRefreshDetail = null)
            refreshWindow()
        }
    }

    // ------------------------------------------------------------------ channels

    private fun restartChannelObservation(rules: FilterRules) {
        channelsJob?.cancel()
        channelsJob = viewModelScope.launch {
            container.guideRepository.observeChannels(rules).collectLatest { allChannels ->
                val now = System.currentTimeMillis()
                if (rulesChangedAt != 0L) {
                    Log.i(TAG, "filter change applied in ${now - rulesChangedAt} ms: ${allChannels.size} channels")
                    rulesChangedAt = 0L
                }
                _ui.value = _ui.value.copy(
                    totalChannelCount = allChannels.size,
                    favoriteChannelCount = allChannels.count { it.isFavorite },
                )
                // The banner holds a snapshot of its channel; starring it should show at once.
                _banner.value.channel?.let { shown ->
                    allChannels.firstOrNull { it.streamId == shown.streamId }?.let { fresh ->
                        if (fresh.isFavorite != shown.isFavorite) {
                            _banner.value = _banner.value.copy(channel = fresh)
                        }
                    }
                }
                val filtered = channelsFor(_guide.value.categoryId, allChannels)
                val previousStreamId = _guide.value.selectedChannel?.streamId
                val index = filtered.indexOfFirst { it.streamId == previousStreamId }
                    .takeIf { it >= 0 } ?: 0
                _guide.value = _guide.value.copy(
                    allChannels = allChannels,
                    channels = filtered,
                    nowMs = now,
                    cursor = if (_guide.value.cursor.window.startMs == 0L) {
                        navigator.initial(now, index)
                    } else {
                        _guide.value.cursor.copy(
                            channelIndex = index.coerceAtMost((filtered.size - 1).coerceAtLeast(0))
                        )
                    },
                )
                updateLiveCategories()
                refreshBrowseChannels()
                refreshWindow()
            }
        }
    }

    /** Loads programmes for the visible rows plus a buffer, and nothing else. */
    private fun refreshWindow() {
        programsJob?.cancel()
        programsJob = viewModelScope.launch {
            val state = _guide.value
            if (state.channels.isEmpty()) return@launch
            val first = (state.cursor.firstVisibleRow - ROW_BUFFER).coerceAtLeast(0)
            val last = (state.cursor.firstVisibleRow + VISIBLE_ROWS + ROW_BUFFER)
                .coerceAtMost(state.channels.size)
            val visible = state.channels.subList(first, last)

            val offsetHours = _ui.value.settings.epgOffsetHours
            val programs = container.guideRepository.programsFor(visible, state.cursor.window, offsetHours)

            // Replace rather than merge. Merging would let the map grow by a row's worth of
            // programmes every time the user scrolls past a channel, so an evening of browsing a
            // 400-channel guide would accumulate the whole catalogue in memory — exactly what the
            // windowed query exists to avoid. Only the visible rows plus the buffer are kept.
            _guide.value = _guide.value.copy(
                programsByKey = programs,
                nowMs = System.currentTimeMillis(),
            )
            _guide.value = _guide.value.copy(selected = navigator.selected(_guide.value.cursor))

            // Channels XMLTV had nothing for get their guide data on demand, once, for the rows
            // actually on screen.
            runCatching {
                // Event slots and loops are excluded: no provider has short EPG for those, and
                // asking for it on every scroll is a request per row for nothing.
                val worthAsking = visible
                    .filterNot { EventChannelEpg.isSynthetic(it.rawName, it.categoryName) }
                    .map { it.streamId }
                val filled = container.shortEpgFetcher()?.fillGaps(worthAsking) ?: 0
                if (filled > 0) {
                    val again = container.guideRepository.programsFor(visible, _guide.value.cursor.window, offsetHours)
                    _guide.value = _guide.value.copy(programsByKey = again)
                }
            }.onFailure { Log.d(TAG, "short EPG fallback skipped", it) }
        }
    }

    /** Called once a minute so the clock and the "now" line stay honest. */
    fun tick() {
        _guide.value = _guide.value.copy(nowMs = System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ navigation

    fun openGuide() {
        val now = System.currentTimeMillis()
        val state = _guide.value
        val playingIndex = state.channels
            .indexOfFirst { it.streamId == playback.value.channel?.streamId }
            .takeIf { it >= 0 } ?: state.cursor.channelIndex
        _guide.value = state.copy(
            cursor = navigator.initial(now, playingIndex),
            nowMs = now,
            details = null,
        )
        _ui.value = _ui.value.copy(screen = Screen.Guide)
        refreshWindow()
    }

    fun closeGuide() {
        _guide.value = _guide.value.copy(details = null)
        _ui.value = _ui.value.copy(screen = Screen.Watching)
    }

    private var screenBeforeSettings: Screen = Screen.Home

    fun openHome() {
        _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    fun openLiveCategories() {
        _ui.value = _ui.value.copy(screen = Screen.LiveCategories)
    }

    fun openLiveTv() {
        val pkg = ChannelPackages.AMERICAN_CABLE
        selectLiveCategory(PACKAGE_PREFIX + pkg.id, pkg.name)
    }

    /**
     * The channels a guide category stands for.
     *
     * One function rather than a `when` in each place that needs it, because a package is a
     * category too and every one of those places has to agree about that — otherwise a filter
     * change or a favourite toggle quietly drops the viewer back to the full list.
     */
    private fun channelsFor(categoryId: String?, all: List<GuideChannel>): List<GuideChannel> = when {
        categoryId == null || categoryId == ALL_CHANNELS_ID -> all
        categoryId == FAVORITE_CHANNELS_ID -> all.filter { it.isFavorite }
        categoryId.startsWith(PACKAGE_PREFIX) -> {
            val pkgId = categoryId.removePrefix(PACKAGE_PREFIX)
            val resolved = ChannelPackages.byId(pkgId)?.let { pkg -> resolvePackage(pkg, all) }
            if (resolved.isNullOrEmpty() && pkgId == ChannelPackages.AMERICAN_CABLE.id) all
            else resolved ?: all
        }
        else -> all.filter { it.categoryId == categoryId }
    }

    /**
     * The viewer's own copy of each channel in a lineup, in the lineup's order.
     *
     * Where a provider carries the same channel several times — an SD feed, an HD feed, a backup —
     * the lowest number wins, because the import numbers channels alphabetically within a country
     * block and the main feed is almost always the first one it saw.
     */
    private fun resolvePackage(pkg: ChannelPackage, all: List<GuideChannel>): List<GuideChannel> {
        val resolved = PackageResolver(all) { it.name }
            .resolve(pkg) { _, found -> found.minByOrNull { it.number } }
            .map { it.second }
        if (pkg.id == "jp_tv") {
            val usedIds = resolved.map { it.streamId }.toSet()
            val extraJp = all.filter {
                it.streamId !in usedIds && (it.country == Country.JP || it.categoryName?.contains("JAPAN", ignoreCase = true) == true)
            }
            return resolved + extraJp
        }
        if (pkg.id == "kr_tv") {
            val usedIds = resolved.map { it.streamId }.toSet()
            val extraKr = all.filter {
                it.streamId !in usedIds && (it.country == Country.KR || it.categoryName?.contains("KOREA", ignoreCase = true) == true)
            }
            return resolved + extraKr
        }
        return resolved
    }

    fun selectLiveCategory(categoryId: String?, categoryName: String) {
        val catId = categoryId ?: ALL_CHANNELS_ID
        val filtered = channelsFor(catId, _guide.value.allChannels)
        val now = System.currentTimeMillis()
        _guide.value = _guide.value.copy(
            categoryId = catId,
            categoryName = categoryName.uppercase(),
            channels = filtered,
            cursor = navigator.initial(now, 0),
            nowMs = now,
            details = null,
        )
        refreshWindow()
        _ui.value = _ui.value.copy(screen = Screen.Guide)
    }

    fun openGuideDirect() {
        val currentCat = _guide.value.categoryId
        if (currentCat == null) {
            openLiveTv()
        } else {
            selectLiveCategory(currentCat, _guide.value.categoryName)
        }
    }

    fun openFavoritesDirect() {
        selectLiveCategory(FAVORITE_CHANNELS_ID, "FAVORITE CHANNELS")
    }

    fun toggleFavoriteCategory(categoryId: String) {
        viewModelScope.launch {
            container.guideRepository.toggleFavoriteCategory(categoryId)
        }
    }

    fun toggleFavoriteChannel(streamId: Long) {
        viewModelScope.launch {
            container.guideRepository.toggleFavoriteChannel(streamId)
        }
    }

    fun toggleFavoriteSelectedChannel() {
        val streamId = _guide.value.selectedChannel?.streamId
            ?: _banner.value.channel?.streamId
            ?: return
        toggleFavoriteChannel(streamId)
    }

    fun watchingBack() {
        _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    fun liveCategoriesBack() {
        _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    fun openSettings() {
        screenBeforeSettings = _ui.value.screen
        _ui.value = _ui.value.copy(screen = Screen.Settings)
    }

    fun closeSettings() {
        _ui.value = _ui.value.copy(screen = screenBeforeSettings)
    }

    fun guideUp() = moveCursor(navigator.moveUp(_guide.value.cursor))
    fun guideDown() = moveCursor(navigator.moveDown(_guide.value.cursor))
    fun guideLeft() = moveCursor(navigator.moveLeft(_guide.value.cursor, _guide.value.nowMs))
    fun guideRight() = moveCursor(navigator.moveRight(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageBack() = moveCursor(navigator.pageBack(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageForward() = moveCursor(navigator.pageForward(_guide.value.cursor))

    private fun moveCursor(next: GuideCursor) {
        // The details dialog is modal: the cursor stays where it is until Back closes it.
        if (_guide.value.details != null) return
        val previous = _guide.value.cursor
        if (next == previous) return
        _guide.value = _guide.value.copy(cursor = next, selected = navigator.selected(next))
        if (next.window != previous.window || next.firstVisibleRow != previous.firstVisibleRow) {
            refreshWindow()
        }
    }

    /**
     * Select on the highlighted cell: tune when it is on now, open the details dialog when it is
     * in the future. A "No Information" filler is not a programme, so selecting one still tunes
     * the channel — which is what a viewer expects when the guide simply has no data.
     */
    fun guideSelect() {
        val state = _guide.value
        val channel = state.selectedChannel ?: return
        val slot = state.selected
        // With the details dialog already open, Select is "watch this channel now".
        if (state.details == null && slot != null && navigator.isFuture(slot, state.nowMs) && !slot.isFiller) {
            _guide.value = state.copy(details = slot)
            return
        }
        tuneTo(channel)
        closeGuide()
    }

    fun dismissDetails() {
        _guide.value = _guide.value.copy(details = null)
    }

    // ------------------------------------------------------------------ guide: mouse and touch

    /**
     * A pointer hovering over the grid moves the highlight, exactly as the D-pad would, so a
     * mouse user gets the same info panel and preview a remote user does. Hovering never scrolls
     * the window or tunes anything.
     */
    fun guideHover(hit: GuideHit) {
        if (_guide.value.details != null) return
        val state = _guide.value
        val cursor = state.cursor
        val next = when (hit) {
            is GuideHit.Program -> navigator.moveTo(cursor, cursor.firstVisibleRow + hit.row, hit.timeMs, state.nowMs)
            is GuideHit.Channel -> navigator.moveTo(cursor, cursor.firstVisibleRow + hit.row, cursor.anchorMs, state.nowMs)
            else -> return
        }
        moveCursor(next)
    }

    /**
     * A click on the grid puts the highlight there and then does what Select does: a channel
     * cell or a current programme tunes, a future programme opens its details. Touch has no
     * hover, so the move and the select happen together.
     */
    fun guideClick(hit: GuideHit) {
        if (_guide.value.details != null) return
        when (hit) {
            is GuideHit.Program -> {
                guideHover(hit)
                guideSelect()
            }
            is GuideHit.Channel -> {
                guideHover(hit)
                _guide.value.selectedChannel?.let { tuneTo(it); closeGuide() }
            }
            is GuideHit.Header, GuideHit.Nothing -> Unit
        }
    }

    /** Mouse wheel over the grid: one notch, one row. */
    fun guideWheel(rows: Int) {
        if (rows > 0) repeat(rows) { guideDown() } else repeat(-rows) { guideUp() }
    }

    /** "Watch channel now" from the future-programme dialog. */
    fun detailsTuneNow() {
        val channel = _guide.value.selectedChannel ?: return
        tuneTo(channel)
        closeGuide()
    }

    /** Back in the guide: close the details dialog if one is open, otherwise leave the guide. */
    fun guideBack() {
        if (_guide.value.details != null) {
            dismissDetails()
        } else if (playback.value.channel != null) {
            closeGuide()
        } else {
            _ui.value = _ui.value.copy(screen = Screen.Home)
        }
    }

    // ------------------------------------------------------------------ browse

    fun openBrowse() {
        _ui.value = _ui.value.copy(screen = Screen.Browse)
        // importLibrary falls through to buildBrowse when the catalogue is already cached, so
        // this covers both the first visit and a visit after an import that failed.
        importLibrary()
    }

    fun browseBack() {
        if (_browse.value.hasQuery) setSearchQuery("") else _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    /**
     * Search, as the viewer types.
     *
     * Debounced: a television keyboard produces a keystroke every few hundred milliseconds and
     * each one would otherwise scan three tables. Two characters is the floor — one letter
     * matches most of a twenty-thousand-title catalogue and tells nobody anything.
     */
    fun setSearchQuery(query: String) {
        _browse.value = _browse.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _browse.value = _browse.value.copy(
                searching = false,
                channelResults = emptyList(),
                movieResults = emptyList(),
                seriesResults = emptyList(),
            )
            return
        }
        if (query.trim().length < 2) return
        _browse.value = _browse.value.copy(searching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val term = query.trim()
            val (channels, movies, series) = withContext(Dispatchers.IO) {
                val dao = container.database
                Triple(
                    dao.channelDao().search(term, SEARCH_LIMIT),
                    dao.libraryDao().searchVod(term, SEARCH_LIMIT),
                    dao.libraryDao().searchSeries(term, SEARCH_LIMIT),
                )
            }
            val favourites = _guide.value.allChannels.filter { it.isFavorite }.map { it.streamId }.toSet()
            _browse.value = _browse.value.copy(
                searching = false,
                channelResults = channels.map {
                    PosterItem(
                        id = it.streamId,
                        name = it.displayName,
                        imageUrl = it.streamIcon,
                        kind = PosterItem.Kind.CHANNEL,
                        caption = it.number.toString(),
                        isFavorite = it.streamId in favourites,
                    )
                },
                movieResults = movies.map(::posterFor),
                seriesResults = series.map(::posterFor),
            )
        }
    }

    // The provider's name is a file label; a card shows the title inside it.
    private fun posterFor(vod: VodEntity) = PosterItem(
        id = vod.streamId,
        name = TitleMatcher.displayTitle(vod.name),
        imageUrl = vod.icon,
        kind = PosterItem.Kind.MOVIE,
        caption = vod.titleYear?.toString(),
    )

    private fun posterFor(series: SeriesEntity) = PosterItem(
        id = series.seriesId,
        name = TitleMatcher.displayTitle(series.name),
        imageUrl = series.cover,
        kind = PosterItem.Kind.SERIES,
        caption = series.releaseDate?.take(4) ?: series.titleYear?.toString(),
    )

    /** Opens whatever a poster stands for: a film, a series, or a channel to tune. */
    fun openPoster(item: PosterItem) {
        when (item.kind) {
            PosterItem.Kind.MOVIE -> openMovie(item.id)
            PosterItem.Kind.SERIES -> openSeries(item.id)
            PosterItem.Kind.CHANNEL -> {
                val channel = _guide.value.allChannels.firstOrNull { it.streamId == item.id } ?: return
                tuneTo(channel)
                _ui.value = _ui.value.copy(screen = Screen.Watching)
            }
        }
    }

    /** A package becomes a guide category, so everything the guide already does works on it. */
    fun openPackage(packageId: String) {
        when (packageId) {
            ALL_CHANNELS_ID -> selectLiveCategory(ALL_CHANNELS_ID, "ALL CHANNELS")
            "__categories__" -> _ui.value = _ui.value.copy(screen = Screen.LiveCategories)
            else -> {
                val pkg = ChannelPackages.byId(packageId) ?: return
                selectLiveCategory(PACKAGE_PREFIX + packageId, pkg.name)
            }
        }
    }

    /**
     * Builds everything the Browse screen shows that is not a search result.
     *
     * The curated lists come from IMDb's published data and name thousands of titles; the viewer
     * has some small fraction of them. So the library is indexed once by title and each list is
     * resolved against that index, which is 2,700 hash lookups rather than 46 scans of a
     * twenty-thousand-title catalogue. The index is dropped as soon as the rows are built — it
     * holds the whole catalogue and the rows hold only what is on screen.
     */
    fun buildBrowse() {
        if (browseJob?.isActive == true) return
        browseJob = viewModelScope.launch {
            _browse.value = _browse.value.copy(note = "Reading the catalogue\u2026")
            val result = withContext(Dispatchers.IO) {
                val dao = container.database.libraryDao()
                val lists = runCatching {
                    getApplication<Application>().assets.open(CURATED_ASSET).reader().use {
                        CuratedLists.parse(it)
                    }
                }.onFailure { Log.w(TAG, "curated lists unreadable", it) }.getOrDefault(emptyList())

                // Every curated title's key, asked for in one go. The catalogue is 215,000 rows
                // on the account this was built against; it is never loaded, only queried.
                val movieLists = lists.filter { it.kind == TitleKind.MOVIE }
                val seriesLists = lists.filter { it.kind == TitleKind.SERIES }
                val movieRows = resolveRows(movieLists) { keys -> dao.vodByTitleKeys(keys) }
                val seriesRows = resolveRows(seriesLists) { keys -> dao.seriesByTitleKeys(keys) }
                Triple(movieRows, seriesRows, dao.vodCount() + dao.seriesCount())
            }
            val (movieRows, seriesRows, cached) = result
            _browse.value = _browse.value.copy(
                movieRows = movieRows,
                seriesRows = seriesRows,
                note = when {
                    cached == 0 -> "The on-demand catalogue has not been read yet."
                    movieRows.isEmpty() && seriesRows.isEmpty() ->
                        "None of the curated titles are in this account's catalogue."
                    else -> null
                },
            )
            refreshBrowseChannels()
        }
    }

    /**
     * Turns curated lists into rows of what the viewer has.
     *
     * One query per [SQLITE_KEYS] keys rather than one per title, and the rows are assembled from
     * the result in memory — a few thousand records, not the catalogue.
     */
    private suspend fun <T : Any> resolveRows(
        lists: List<com.crimson.core.catalog.CuratedList>,
        lookup: suspend (List<String>) -> List<T>,
    ): List<CuratedRow> {
        if (lists.isEmpty()) return emptyList()
        val keys = lists.flatMap { list -> list.titles.map { it.key.text } }
            .filter { it.isNotEmpty() }
            .distinct()
        if (keys.isEmpty()) return emptyList()

        val found = keys.chunked(SQLITE_KEYS).flatMap { lookup(it) }
        val index = LibraryIndex(found, ::nameOfCatalogItem, ::languagePreference)
        return lists.mapNotNull { list ->
            // The list knows what the film is called and when it came out; the provider's copy
            // only knows what the file was named. Show the former, open the latter.
            val items = list.titles.mapNotNull { title ->
                val item = index.find(title) ?: return@mapNotNull null
                posterForCatalogItem(item).copy(
                    name = title.name,
                    caption = title.year.toString(),
                )
            }.take(ROW_LIMIT)
            if (items.size < MIN_ROW) null
            else CuratedRow(list.id, list.title, list.subtitle, items)
        }
    }

    /**
     * How much this app would rather have one copy of a film than another.
     *
     * The catalogue carries the same title once per language, all under the same key. An English
     * label wins; anything else is a dub or a subtitle track for somebody else.
     */
    private fun languagePreference(item: Any): Int {
        val name = nameOfCatalogItem(item)
        val prefix = name.takeWhile { it.isLetterOrDigit() || it == '+' }.uppercase()
        return when {
            prefix in ENGLISH_PREFIXES -> 0
            prefix.length !in 2..4 -> 1     // no language label at all: probably the main entry
            else -> 2
        }
    }

    private fun nameOfCatalogItem(item: Any): String = when (item) {
        is VodEntity -> item.name
        is SeriesEntity -> item.name
        else -> ""
    }

    private fun posterForCatalogItem(item: Any): PosterItem = when (item) {
        is VodEntity -> posterFor(item)
        is SeriesEntity -> posterFor(item)
        else -> PosterItem(0, "", null, PosterItem.Kind.MOVIE)
    }

    /** The favourites row and the package counts, which change whenever the channel list does. */
    private fun refreshBrowseChannels() {
        val all = _guide.value.allChannels
        if (all.isEmpty()) return
        val pkgs = ArrayList<PackageSummary>()
        // 1. American Cable
        val amCable = ChannelPackages.AMERICAN_CABLE
        pkgs.add(
            PackageSummary(
                id = amCable.id,
                name = amCable.name,
                description = amCable.description,
                found = resolvePackage(amCable, all).size,
                total = amCable.size,
            )
        )
        // 2. The longer playlist: All Channels
        pkgs.add(
            PackageSummary(
                id = ALL_CHANNELS_ID,
                name = "ALL CHANNELS",
                description = "The complete channel lineup from your provider",
                found = all.size,
                total = all.size,
            )
        )
        // 3. Japanese TV
        ChannelPackages.byId("jp_tv")?.let { jpPkg ->
            val resolvedJp = resolvePackage(jpPkg, all)
            pkgs.add(
                PackageSummary(
                    id = jpPkg.id,
                    name = jpPkg.name,
                    description = jpPkg.description,
                    found = resolvedJp.size,
                    total = resolvedJp.size,
                )
            )
        }
        // 4. Korean TV
        ChannelPackages.byId("kr_tv")?.let { krPkg ->
            val resolvedKr = resolvePackage(krPkg, all)
            pkgs.add(
                PackageSummary(
                    id = krPkg.id,
                    name = krPkg.name,
                    description = krPkg.description,
                    found = resolvedKr.size,
                    total = resolvedKr.size,
                )
            )
        }
        // 5. Category browser
        if (rawCategories.isNotEmpty()) {
            pkgs.add(
                PackageSummary(
                    id = "__categories__",
                    name = "BROWSE CATEGORIES",
                    description = "View channels organized by provider category",
                    found = _ui.value.liveCategories.size,
                    total = _ui.value.liveCategories.size,
                )
            )
        }

        _browse.value = _browse.value.copy(
            favorites = all.filter { it.isFavorite }.map {
                PosterItem(
                    id = it.streamId,
                    name = it.name,
                    imageUrl = it.logoUrl,
                    kind = PosterItem.Kind.CHANNEL,
                    caption = it.number.toString(),
                    isFavorite = true,
                )
            },
            packages = pkgs,
        )
    }

    // ------------------------------------------------------------------ on demand

    fun openOnDemand() {
        _ui.value = _ui.value.copy(screen = Screen.OnDemand)
        importLibrary()
        if (_ui.value.onDemandCategories.isEmpty()) loadOnDemandCategories()
    }

    fun onDemandBack() {
        when {
            _ui.value.selectedVodInfo != null || _ui.value.isLoadingVodInfo -> dismissVodDialog()
            _ui.value.selectedSeriesInfo != null || _ui.value.isLoadingSeriesInfo -> dismissSeriesDialog()
            _ui.value.onDemandCategoryId != null -> selectOnDemandCategory(null)
            else -> _ui.value = _ui.value.copy(screen = Screen.Home)
        }
    }

    fun setOnDemandMode(mode: OnDemandMode) {
        if (_ui.value.onDemandMode == mode) return
        _ui.value = _ui.value.copy(onDemandMode = mode, onDemandCategoryId = null, onDemandItems = emptyList())
        loadOnDemandCategories()
    }

    private fun loadOnDemandCategories() {
        viewModelScope.launch {
            val mode = _ui.value.onDemandMode
            val categories = withContext(Dispatchers.IO) {
                val dao = container.database.libraryDao()
                // Built from what was cached, so switching mode costs nothing and works offline.
                val refs = if (mode == OnDemandMode.MOVIES) dao.vodCategories() else dao.seriesCategories()
                refs.map { RawCategory(it.categoryId, it.categoryName ?: it.categoryId) }
            }
            if (_ui.value.onDemandMode == mode) {
                _ui.value = _ui.value.copy(onDemandCategories = categories)
            }
        }
    }

    /** Null selects the curated rows rather than a category grid. */
    fun selectOnDemandCategory(categoryId: String?) {
        _ui.value = _ui.value.copy(onDemandCategoryId = categoryId, onDemandItems = emptyList())
        if (categoryId == null) return
        viewModelScope.launch {
            val mode = _ui.value.onDemandMode
            val items = withContext(Dispatchers.IO) {
                val dao = container.database.libraryDao()
                if (mode == OnDemandMode.MOVIES) dao.vodInCategory(categoryId).map(::posterFor)
                else dao.seriesInCategory(categoryId).map(::posterFor)
            }
            if (_ui.value.onDemandCategoryId == categoryId) {
                _ui.value = _ui.value.copy(onDemandItems = items)
            }
        }
    }

    /** Fetches a film's details by id and opens the dialog the Movies screen already had. */
    fun openMovie(streamId: Long) {
        val client = container.client ?: return
        _ui.value = _ui.value.copy(isLoadingVodInfo = true, selectedVodInfo = null)
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { client.vodInfo(streamId) }.getOrNull() }
            _ui.value = _ui.value.copy(selectedVodInfo = info, isLoadingVodInfo = false)
        }
    }

    fun openSeries(seriesId: Long) {
        val client = container.client ?: return
        _ui.value = _ui.value.copy(isLoadingSeriesInfo = true, selectedSeriesInfo = null)
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { client.seriesInfo(seriesId) }.getOrNull() }
            _ui.value = _ui.value.copy(selectedSeriesInfo = info, isLoadingSeriesInfo = false)
        }
    }

    /** Pulls the whole on-demand catalogue into the database, for search and the curated rows. */
    fun importLibrary(force: Boolean = false) {
        if (libraryJob?.isActive == true) return
        libraryJob = viewModelScope.launch {
            val already = withContext(Dispatchers.IO) {
                container.database.libraryDao().vodCount() + container.database.libraryDao().seriesCount()
            }
            // A cached catalogue whose keys were built by older matching rules matches less than
            // it should, and nothing on screen would say so; rebuild it rather than quietly
            // showing half a row.
            val stale = container.settings.settings.first().catalogKeyVersion != CATALOG_KEY_VERSION
            if (already > 0 && !force && !stale) {
                buildBrowse()
                return@launch
            }
            if (stale && already > 0) Log.i(TAG, "catalogue keys are stale; rebuilding")
            val importer = container.libraryImporter() ?: return@launch
            _browse.value = _browse.value.copy(note = "Reading the on-demand catalogue\u2026")
            val report = importer.import()
            Log.i(TAG, "library: ${report.summary()}")
            if (report.movies > 0 || report.series > 0) {
                container.settings.setCatalogKeyVersion(CATALOG_KEY_VERSION)
            }
            _browse.value = _browse.value.copy(note = report.problem)
            loadOnDemandCategories()
            buildBrowse()
        }
    }

    // ------------------------------------------------------------------ VOD & Series

    fun playVod(streamId: Long, extension: String?) {
        val client = container.client ?: return
        val ext = extension ?: "mp4"
        val url = client.vodStreamUrl(streamId, ext)
        val info = _ui.value.selectedVodInfo
        val title = info?.name?.takeIf { it.isNotBlank() } ?: "Film"
        val coverUrl = info?.coverUrl
        val plot = info?.description.orEmpty()

        container.player.play(
            PlayableChannel(
                streamId = streamId,
                number = 0,
                name = title,
                url = url,
            )
        )
        val now = System.currentTimeMillis()
        _banner.value = BannerState(
            channel = GuideChannel(
                streamId = streamId,
                number = 0,
                name = title,
                shortName = title,
                channelKey = "vod_$streamId",
                logoUrl = coverUrl,
                country = null,
                market = null,
                categoryId = null,
                isFavorite = false,
            ),
            now = ProgramSlot(
                id = streamId,
                startMs = now,
                endMs = now + 7_200_000L,
                title = title,
                description = plot,
            ),
            showToken = now,
        )
        _ui.value = _ui.value.copy(
            selectedVodInfo = null,
            screen = Screen.Watching,
        )
    }

    fun dismissVodDialog() {
        _ui.value = _ui.value.copy(selectedVodInfo = null)
    }

    /** Back or a click on the scrim closes whichever details dialog is open. */
    fun dismissOnDemandDialog() {
        _ui.value = _ui.value.copy(selectedVodInfo = null, selectedSeriesInfo = null)
    }

    fun playEpisode(episodeId: Long, extension: String?) {
        val client = container.client ?: return
        val ext = extension ?: "mkv"
        val url = client.seriesStreamUrl(episodeId, ext)

        var episodeTitle = "Episode"
        var episodePlot = ""
        val info = _ui.value.selectedSeriesInfo
        if (info != null) {
            for ((_, episodes) in info.episodes) {
                val ep = episodes.firstOrNull { it.id == episodeId }
                if (ep != null) {
                    episodeTitle = ep.title.ifBlank { "Episode ${ep.episodeNum}" }
                    episodePlot = ep.info.orEmpty()
                    break
                }
            }
        }
        val seriesTitle = info?.name ?: "TV Series"
        val fullTitle = "$seriesTitle: $episodeTitle"
        val coverUrl = info?.cover

        container.player.play(
            PlayableChannel(
                streamId = episodeId,
                number = 0,
                name = fullTitle,
                url = url,
            )
        )
        val now = System.currentTimeMillis()
        _banner.value = BannerState(
            channel = GuideChannel(
                streamId = episodeId,
                number = 0,
                name = fullTitle,
                shortName = fullTitle,
                channelKey = "series_$episodeId",
                logoUrl = coverUrl,
                country = null,
                market = null,
                categoryId = null,
                isFavorite = false,
            ),
            now = ProgramSlot(
                id = episodeId,
                startMs = now,
                endMs = now + 3_600_000L,
                title = fullTitle,
                description = episodePlot,
            ),
            showToken = now,
        )
        _ui.value = _ui.value.copy(
            selectedSeriesInfo = null,
            screen = Screen.Watching,
        )
    }

    fun dismissSeriesDialog() {
        _ui.value = _ui.value.copy(selectedSeriesInfo = null)
    }

    // ------------------------------------------------------------------ playback

    fun channelUp() = stepChannel(+1)
    fun channelDown() = stepChannel(-1)

    private fun stepChannel(delta: Int) {
        val channels = _guide.value.channels
        if (channels.isEmpty()) return
        val currentId = playback.value.channel?.streamId
        val index = channels.indexOfFirst { it.streamId == currentId }.takeIf { it >= 0 } ?: 0
        val next = Math.floorMod(index + delta, channels.size)
        tuneTo(channels[next])
    }

    /** Play/Pause on the Fire remote jumps back to the previous channel. */
    fun lastChannel() {
        viewModelScope.launch {
            val previous = container.settings.settings.first().previousChannelStreamId
            if (previous == 0L) return@launch
            _guide.value.channels.firstOrNull { it.streamId == previous }?.let(::tuneTo)
        }
    }

    fun tuneTo(channel: GuideChannel) {
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            val client = container.client ?: return@launch
            container.player.play(
                PlayableChannel(
                    streamId = channel.streamId,
                    number = channel.number,
                    name = channel.name,
                    url = client.streamUrl(channel.streamId, settings.streamFormat),
                )
            )
            container.settings.setCurrentChannel(channel.streamId)

            // The banner goes up immediately with what is known, then fills in now-and-next when
            // the query returns. Waiting for the database first would make every channel change
            // feel slower than it is.
            _banner.value = BannerState(
                channel = channel,
                showToken = System.currentTimeMillis(),
            )
            val (now, next) = container.guideRepository.nowAndNext(
                channel,
                System.currentTimeMillis(),
                settings.epgOffsetHours,
            )
            if (_banner.value.channel?.streamId == channel.streamId) {
                _banner.value = _banner.value.copy(now = now, next = next)
            }
        }
    }

    private suspend fun resumeLastChannel() {
        val settings = container.settings.settings.first()
        val channels = _guide.value.channels.ifEmpty {
            container.guideRepository.channels(settings.rules).also { loaded ->
                _guide.value = _guide.value.copy(channels = loaded)
            }
        }
        if (channels.isEmpty()) return
        val channel = channels.firstOrNull { it.streamId == settings.lastChannelStreamId }
            ?: channels.first()
        tuneTo(channel)
    }

    fun retryPlayback() = container.player.retry()

    /**
     * Select while watching. It opens the guide, except when playback has given up, where the
     * obvious thing for the button under the viewer's thumb to do is try again.
     */
    fun watchingSelect() {
        if (playback.value.error != null) retryPlayback() else openGuide()
    }

    /** The Info key, or the toolbar's INFO: put the banner back up for the current channel. */
    fun showBanner() {
        val current = _banner.value
        val channel = current.channel ?: return
        _banner.value = current.copy(showToken = System.currentTimeMillis())
        viewModelScope.launch {
            val (now, next) = container.guideRepository.nowAndNext(
                channel,
                System.currentTimeMillis(),
                _ui.value.settings.epgOffsetHours,
            )
            if (_banner.value.channel?.streamId == channel.streamId) {
                _banner.value = _banner.value.copy(now = now, next = next)
            }
        }
    }

    // ------------------------------------------------------------------ direct channel entry

    private var digitJob: Job? = null

    /**
     * A digit from a keyboard or a numeric remote. Digits accumulate for a moment and then tune
     * to that channel number, as a cable box does; [MAX_CHANNEL_DIGITS] digits tune at once
     * because no number is longer. While watching that is a channel change; in the guide it
     * moves the highlight.
     */
    fun enterDigit(digit: Int) {
        val digits = (_ui.value.pendingChannelNumber + digit).takeLast(MAX_CHANNEL_DIGITS)
        _ui.value = _ui.value.copy(pendingChannelNumber = digits)
        digitJob?.cancel()
        digitJob = viewModelScope.launch {
            if (digits.length < MAX_CHANNEL_DIGITS) delay(DIGIT_TIMEOUT_MS)
            commitChannelNumber()
        }
    }

    private fun commitChannelNumber() {
        val digits = _ui.value.pendingChannelNumber
        _ui.value = _ui.value.copy(pendingChannelNumber = "")
        val number = digits.toIntOrNull() ?: return
        val state = _guide.value
        val inList = state.channels.indexOfFirst { it.number == number }
        when (_ui.value.screen) {
            Screen.Guide -> if (inList >= 0) {
                moveCursor(navigator.moveTo(state.cursor, inList, state.cursor.anchorMs, state.nowMs))
            }
            else -> {
                val channel = state.channels.getOrNull(inList)
                    ?: state.allChannels.firstOrNull { it.number == number }
                    ?: return
                tuneTo(channel)
                if (_ui.value.screen != Screen.Watching) _ui.value = _ui.value.copy(screen = Screen.Watching)
            }
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * Applies a new country set.
     *
     * Removing one is instant: the rules change, the database query re-runs, the guide redraws,
     * and no network call is made. Adding one needs the categories for that country, which the
     * importer fetches on its own — every other category is already marked imported and is skipped.
     */
    fun setCountries(countries: Set<Country>) {
        rulesChangedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val before = container.settings.settings.first().rules.countries
            container.settings.setCountries(countries)
            val added = countries - before
            if (added.isNotEmpty()) fetchNewCategories()
        }
    }

    fun setMarkets(markets: Set<Market>) {
        rulesChangedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val before = container.settings.settings.first().rules.markets
            container.settings.setMarkets(markets)
            val added = markets - before
            if (added.isNotEmpty()) fetchNewCategories()
        }
    }

    fun setExclusions(keywords: List<String>) {
        rulesChangedAt = System.currentTimeMillis()
        viewModelScope.launch { container.settings.setExclusions(keywords) }
    }

    fun setEpgOffsetHours(hours: Int) {
        viewModelScope.launch {
            container.settings.setEpgOffsetHours(hours)
            refreshEpgNow()
        }
    }

    /**
     * Turning the public feeds off removes their rows as well as stopping the downloads: leaving
     * a half-filled grid behind would make the setting look broken. Turning them back on refreshes.
     */
    fun setUseExtraEpgSources(use: Boolean) {
        viewModelScope.launch {
            container.settings.setUseExtraEpgSources(use)
            if (!use) {
                withContext(Dispatchers.IO) {
                    container.database.programDao().deleteSourcesOtherThan(
                        listOf(XmltvImporter.PROVIDER_ID, ShortEpgFetcher.SHORT_EPG_ID)
                    )
                }
                refreshWindow()
            } else {
                refreshEpgNow()
            }
        }
    }

    fun setStreamFormat(format: String) {
        viewModelScope.launch { container.settings.setStreamFormat(format) }
    }

    private fun fetchNewCategories() {
        val importer = container.channelImporter() ?: return
        _ui.value = _ui.value.copy(isFetchingMore = true)
        viewModelScope.launch {
            val rules = container.settings.settings.first().rules
            runCatching {
                importer.import(rules, onlyNewCategories = true).collect { }
            }.onFailure { Log.w(TAG, "fetching new categories failed", it) }
            _ui.value = _ui.value.copy(isFetchingMore = false)
            refreshWindow()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The player belongs to the container, not to this view model: a configuration change
        // must not tear down the stream.
    }

    companion object {
        private const val TAG = "CrimsonVM"

        /** How long after the last digit a partial channel number tunes. */
        const val DIGIT_TIMEOUT_MS = 1_800L

        /** Numbering overflows past 10000 on a large catalogue, so five digits are possible. */
        const val MAX_CHANNEL_DIGITS = 5


        /** Waiting this long after a keystroke turns a typed word into one query, not six. */
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SEARCH_LIMIT = 60

        /** Curated rows are capped: nobody scrolls past forty covers. */
        const val ROW_LIMIT = 40

        /** A row with fewer than this is not worth the space it takes. */
        const val MIN_ROW = 4

        const val CURATED_ASSET = "curated_lists.txt"

        /**
         * Bumped whenever [com.crimson.core.catalog.TitleMatcher] changes what a key means.
         *
         * 1: the first version. 2: language labels separated by a dash — `EN - Pulp Fiction` —
         * are stripped, which on the reference account is most of the catalogue.
         */
        const val CATALOG_KEY_VERSION = 2

        /** Labels that mean the copy is in English. */
        val ENGLISH_PREFIXES = setOf("EN", "US", "UK", "GB", "ENG", "USA")

        /**
         * SQLite binds one variable per element of an `IN` list and stops at 999. The query uses
         * the list twice, so the chunk is half of what the channel queries can afford.
         */
        const val SQLITE_KEYS = 450

        /** Channel rows drawn at once. Mirrors GuideTheme.rowsVisible. */
        const val VISIBLE_ROWS = 5

        /** Extra rows loaded either side, so scrolling never waits on a query. */
        const val ROW_BUFFER = 4
    }
}
