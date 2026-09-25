package com.crimson.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crimson.AppContainer
import com.crimson.core.catalog.ChannelPackages
import com.crimson.core.catalog.FeedPage
import com.crimson.core.catalog.TitleKind
import com.crimson.core.epg.EpgSources
import com.crimson.core.epg.EventChannelEpg
import com.crimson.core.guide.GuideCursor
import com.crimson.core.guide.GuideHit
import com.crimson.core.guide.GuideNavigator
import com.crimson.core.guide.GuideSource
import com.crimson.core.guide.ProgramSlot
import com.crimson.core.guide.TimeWindow
import com.crimson.core.model.Country
import com.crimson.core.model.FilterRules
import com.crimson.core.model.Market
import com.crimson.core.sports.BroadcastSearch
import com.crimson.data.db.CategoryEntity
import com.crimson.data.db.MyListEntity
import com.crimson.data.db.WatchProgressEntity
import com.crimson.data.epg.EpgProgress
import com.crimson.data.epg.ShortEpgFetcher
import com.crimson.data.epg.XmltvImporter
import com.crimson.data.profile.Profile
import com.crimson.data.settings.AppSettings
import com.crimson.data.xtream.ImportProgress
import com.crimson.data.xtream.XtreamClient
import com.crimson.data.xtream.XtreamError
import com.crimson.domain.GuideChannel
import com.crimson.player.PlayableChannel
import com.crimson.player.PlaybackState
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.ContinueTile
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.GameTile
import com.crimson.ui.components.MainTab
import com.crimson.ui.components.RowKind
import com.crimson.ui.components.Tile
import com.crimson.ui.components.TitleTile
import com.crimson.ui.details.DetailsController
import com.crimson.ui.details.EpisodeUi
import com.crimson.ui.feed.FeedController
import com.crimson.ui.live.LiveCollection
import com.crimson.ui.live.LiveController
import com.crimson.ui.search.SearchController
import com.crimson.ui.sports.SportsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's view model.
 *
 * It owns the session — which profile is signed in, the import, the guide cursor, the player and
 * the back stack — and hands each page's work to a controller: [feed] for Home, TV Shows and
 * Movies, [details], [search], [live] for Live TV and the country directory, and [sports].
 *
 * The genuinely tricky logic — which channels survive the filter, where a guide cell sits, which
 * rows a feed holds and what SQL they run — lives in `:core`, where it is unit-tested. What is here
 * is plumbing.
 */
class CrimsonViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)

    private val _ui = MutableStateFlow(UiState(hasSubtitleKey = com.crimson.BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _guide = MutableStateFlow(GuideState(categoryId = DEFAULT_CATEGORY_ID))
    val guide: StateFlow<GuideState> = _guide.asStateFlow()

    private val _banner = MutableStateFlow(BannerState())
    val banner: StateFlow<BannerState> = _banner.asStateFlow()

    private val _myList = MutableStateFlow<List<FeedRow>>(emptyList())
    val myList: StateFlow<List<FeedRow>> = _myList.asStateFlow()

    val playback: StateFlow<PlaybackState> get() = container.player.state

    /** The stream's own captions as the player decodes them. */
    val cues get() = container.player.cues

    private val info = InfoCache { container.client }

    val live = LiveController(
        container = container,
        allChannels = { _guide.value.allChannels },
        categories = { rawCategories },
        startPreview = ::startPreview,
    )
    val feed = FeedController(container, info) { liveNowTiles() }
    val details = DetailsController(container, info)
    val search = SearchController(container, { _guide.value.allChannels }) { live.nowPlaying(it) }
    val sports = SportsController(container)
    val captions = com.crimson.ui.player.CaptionsController(container.player, container.subtitles)
    val themeSkip = com.crimson.ui.player.ThemeSkipController(container.player, container.themeSkips)

    private val _vodControls = MutableStateFlow(com.crimson.ui.player.VodControls())
    /** The film-and-episode controls: shown or not, what has focus, where a scrub is heading. */
    val vodControls: StateFlow<com.crimson.ui.player.VodControls> = _vodControls.asStateFlow()
    private var controlsHideJob: Job? = null
    private var scrubSettleJob: Job? = null

    private val stack = ArrayList<Route>()

    private var sessionJob: Job? = null
    private lateinit var sessionScope: CoroutineScope

    private var channelsJob: Job? = null
    private var importJob: Job? = null
    private var programsJob: Job? = null
    private var libraryJob: Job? = null
    private var progressJob: Job? = null
    private var rawCategories: List<CategoryEntity> = emptyList()

    /** The channels Up and Down step through while watching live: whatever the viewer tuned from. */
    private var zapList: List<GuideChannel> = emptyList()

    /** True while the Live TV hero is playing a preview rather than the viewer watching. */
    private var previewActive = false

    private var rulesChangedAt = 0L

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
            container.profiles.profiles.collectLatest { list ->
                _ui.value = _ui.value.copy(profiles = list, profilesEncrypted = container.profiles.isEncrypted)
            }
        }
        val first = if (container.profiles.profiles.value.isEmpty()) Route.EditProfile(null) else Route.Profiles
        setRoot(first)
    }

    // ================================================================== navigation

    val route: Route get() = stack.lastOrNull() ?: Route.Starting

    private fun publish() {
        _ui.value = _ui.value.copy(route = route)
    }

    private fun setRoot(route: Route) {
        leaving(this.route, route)
        stack.clear()
        stack += route
        publish()
        entered(route)
    }

    fun navigate(route: Route) {
        if (this.route == route) return
        leaving(this.route, route)
        // A second visit to the same page moves it to the top instead of stacking a copy. There
        // is only ever one search page: a new search replaces an older one wherever it sits, so
        // Back can never surface a search from earlier (last night's game, say).
        stack.remove(route)
        if (route is Route.Search) stack.removeAll { it is Route.Search }
        stack += route
        publish()
        entered(route)
    }

    private fun replaceTop(route: Route) {
        leaving(this.route, route)
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.remove(route)
        stack += route
        publish()
        entered(route)
    }

    /** Back. Returns false when there is nowhere to go, so the system can leave the app. */
    fun back(): Boolean {
        val current = route
        when (current) {
            is Route.Guide -> if (_guide.value.details != null) { dismissDetails(); return true }
            is Route.Main -> if (current.tab != MainTab.HOME) { openTab(MainTab.HOME); return true }
            else -> Unit
        }
        if (stack.size <= 1) {
            return when (current) {
                is Route.EditProfile -> if (_ui.value.profiles.isNotEmpty()) { setRoot(Route.Profiles); true } else false
                is Route.Settings, is Route.Search, is Route.Details, is Route.Directory, Route.Guide, Route.Watching ->
                    { setRoot(Route.Main(MainTab.HOME)); true }
                else -> false
            }
        }
        val leavingRoute = stack.removeAt(stack.lastIndex)
        leaving(leavingRoute, route)
        publish()
        entered(route)
        return true
    }

    private fun leaving(from: Route, to: Route) {
        if (from == Route.Watching && to != Route.Watching) {
            saveProgress(final = true)
            progressJob?.cancel()
            captions.stop()
            themeSkip.stop()
            resetVodControls()
            if (_ui.value.playerMenuOpen) _ui.value = _ui.value.copy(playerMenuOpen = false)
            // Watching is over once the viewer leaves it, except into the guide, whose preview
            // window is the same stream.
            if (to != Route.Guide) {
                container.player.stop()
                _ui.value = _ui.value.copy(nowPlaying = null)
            }
        }
        if (from == Route.Guide && to != Route.Watching && to != Route.Guide) {
            if (playback.value.channel != null && _ui.value.nowPlaying?.isVod != true) container.player.stop()
        }
        if (from == Route.Main(MainTab.LIVE) && to != Route.Watching && to != Route.Guide) stopPreview()
        // Search keeps its query and results while the viewer looks at a result (a title, a
        // channel) and comes back; leaving the search page itself is what clears it.
        if (from is Route.Search && to !is Route.Details && to != Route.Watching && to != Route.Guide) {
            search.reset()
            searchOpenedFor = null
        }
    }

    /** The search page the controller's state belongs to; returning to it must not re-run it. */
    private var searchOpenedFor: Route.Search? = null

    private fun entered(route: Route) {
        if (sessionJob == null && route !is Route.Profiles && route !is Route.EditProfile && route != Route.Starting) return
        when (route) {
            is Route.Main -> when (route.tab) {
                MainTab.HOME -> feed.open(FeedPage.HOME)
                MainTab.MOVIES -> feed.open(FeedPage.MOVIES)
                MainTab.SHOWS -> feed.open(FeedPage.SHOWS)
                MainTab.LIVE -> if (live.state.value.rows.isEmpty()) live.rebuild()
                MainTab.SPORTS -> sports.refresh()
                MainTab.MY_LIST -> refreshMyList()
            }
            is Route.Details -> details.open(route.kind, route.id)
            is Route.Search -> if (searchOpenedFor != route) {
                searchOpenedFor = route
                search.open(route.query, route.scope, route.fallbacks)
            }
            is Route.Directory -> live.openDirectory()
            // Back from the guide to live TV: the channel's captions come back with it.
            Route.Watching -> _ui.value.nowPlaying?.takeIf { captions.target?.streamId != it.streamId && !it.isVod }?.let { np ->
                startCaptions(com.crimson.ui.player.CaptionsController.Target(np.streamId, isLive = true) { null })
            }
            else -> Unit
        }
    }

    fun openTab(tab: MainTab) {
        val target = Route.Main(tab)
        if (route is Route.Main) replaceTop(target) else navigate(target)
    }

    fun openDetails(kind: TitleKind, id: Long) = navigate(Route.Details(kind, id))

    fun openSearch(query: String = "", scope: SearchScope = SearchScope.ALL, fallbacks: List<String> = emptyList()) =
        navigate(Route.Search(query, scope, fallbacks))

    fun openDirectory() = navigate(Route.Directory)

    fun openSettings() = navigate(Route.Settings)

    fun openProfiles() {
        stopPreview()
        container.player.stop()
        setRoot(Route.Profiles)
    }

    fun editProfile(profileId: String?) {
        _ui.value = _ui.value.copy(profileError = null)
        navigate(Route.EditProfile(profileId))
    }

    /** The one handler for any card on any page. */
    fun openTile(tile: Tile, zap: List<Tile> = emptyList()) {
        when (tile) {
            is TitleTile -> openDetails(tile.kind, tile.id)
            is ContinueTile -> playContinue(tile)
            is ChannelTile -> {
                val channels = zap.filterIsInstance<ChannelTile>().mapNotNull { live.channel(it.streamId) }
                live.channel(tile.streamId)?.let { tuneAndWatch(it, channels) }
            }
            is GameTile -> {
                val terms = BroadcastSearch.termsFor(tile.event)
                openSearch(terms.first(), SearchScope.LIVE, terms.drop(1))
            }
        }
    }

    // ================================================================== profiles

    fun saveProfile(existingId: String?, name: String, avatar: Int, server: String, username: String, password: String) {
        if (_ui.value.isSavingProfile) return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _ui.value = _ui.value.copy(profileError = "Give this profile a name.")
            return
        }
        if (server.isBlank() || username.isBlank()) {
            _ui.value = _ui.value.copy(profileError = "Enter the Xtream server address and username.")
            return
        }
        _ui.value = _ui.value.copy(isSavingProfile = true, profileError = null)
        viewModelScope.launch {
            val existing = container.profiles.byId(existingId)
            val profile = Profile(
                id = existing?.id ?: Profile.newId(),
                name = trimmedName,
                avatar = avatar,
                serverUrl = server.trim(),
                username = username.trim(),
                password = password,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { XtreamClient(profile.account, container.httpClient).login() }
            }
            result.fold(
                onSuccess = { info ->
                    container.profiles.save(profile)
                    val credentialsChanged = existing != null &&
                        (existing.serverUrl != profile.serverUrl || existing.username != profile.username || existing.password != profile.password)
                    if (credentialsChanged && _ui.value.profile?.id == profile.id) endSession()
                    // The provider's preferred format, remembered for the first session.
                    pendingFormat = profile.id to info.preferredFormat
                    _ui.value = _ui.value.copy(
                        isSavingProfile = false,
                        profile = if (_ui.value.profile?.id == profile.id) profile else _ui.value.profile,
                    )
                    setRoot(Route.Profiles)
                },
                onFailure = { error ->
                    _ui.value = _ui.value.copy(isSavingProfile = false, profileError = describe(error))
                },
            )
        }
    }

    private var pendingFormat: Pair<String, String>? = null

    fun deleteProfile(profileId: String) {
        val profile = container.profiles.byId(profileId) ?: return
        if (_ui.value.profile?.id == profileId) endSession()
        container.deleteProfile(profile)
        setRoot(if (container.profiles.profiles.value.isEmpty()) Route.EditProfile(null) else Route.Profiles)
    }

    /** "Who's watching?" answered. */
    fun selectProfile(profile: Profile) {
        if (_ui.value.profile?.id == profile.id && sessionJob != null) {
            setRoot(Route.Main(MainTab.HOME))
            return
        }
        endSession()
        val session = container.activate(profile)
        _ui.value = _ui.value.copy(profile = profile)
        startSession()
        sessionScope.launch {
            pendingFormat?.takeIf { it.first == profile.id }?.let { session.settings.setStreamFormat(it.second) }
            pendingFormat = null
            val settings = session.settings.settings.first()
            _ui.value = _ui.value.copy(settings = settings)
            if (settings.hasCompletedImport && container.guideRepository.channelCount() > 0) {
                restartChannelObservation(settings.rules)
                setRoot(Route.Main(MainTab.HOME))
                // A catalogue missing, or joined against an older title index, is brought up to
                // date in the background; the rows refresh when it lands.
                importLibrary()
            } else {
                runImport(fullRefresh = true)
            }
        }
    }

    private fun startSession() {
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        sessionJob = job
        sessionScope = CoroutineScope(viewModelScope.coroutineContext + job)
        feed.scope = sessionScope
        details.scope = sessionScope
        search.scope = sessionScope
        live.scope = sessionScope
        sports.scope = sessionScope
        captions.scope = sessionScope
        themeSkip.scope = sessionScope
        observeSettings()
        observeCategories()
    }

    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
        channelsJob = null; importJob = null; programsJob = null; libraryJob = null; progressJob = null
        container.player.stop()
        if (::sessionScope.isInitialized) { captions.stop(); themeSkip.stop() }
        previewActive = false
        feed.reset(); details.reset(); search.reset(); live.reset(); sports.reset()
        searchOpenedFor = null
        info.clear()
        rawCategories = emptyList()
        zapList = emptyList()
        _guide.value = GuideState(categoryId = DEFAULT_CATEGORY_ID)
        _banner.value = BannerState()
        _myList.value = emptyList()
        _ui.value = UiState(
            route = route,
            hasSubtitleKey = _ui.value.hasSubtitleKey,
            profiles = _ui.value.profiles,
            profilesEncrypted = _ui.value.profilesEncrypted,
        )
        container.deactivate()
    }

    private fun observeSettings() {
        sessionScope.launch {
            container.settings.settings.collectLatest { settings ->
                val previous = _ui.value.settings
                _ui.value = _ui.value.copy(settings = settings)
                live.previewsEnabled = settings.livePreviews
                container.player.setDialogueBoost(settings.dialogueBoost)
                container.player.setPreferEnglishAudio(settings.preferEnglishAudio)
                captions.setSoundDescriptions(settings.captionSoundDescriptions)
                if (previous.captions != settings.captions && captions.target != null) captions.setEnabled(settings.captions)
                if (previous.rules != settings.rules && channelsJob != null) restartChannelObservation(settings.rules)
            }
        }
    }

    private fun observeCategories() {
        sessionScope.launch {
            container.database.categoryDao().observeAll().collectLatest { cats ->
                rawCategories = cats
                _ui.value = _ui.value.copy(liveCategories = cats)
            }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is XtreamError.BadCredentials ->
            error.serverMessage?.takeIf { it.isNotBlank() }
                ?: "That username or password was not accepted by the server."
        is XtreamError.Expired -> "This account has expired."
        is XtreamError.Unreachable -> "Could not reach the server. Check the address and your connection."
        is XtreamError.NotXtream -> "That address did not answer like an Xtream panel."
        else -> error.message ?: "Something went wrong signing in."
    }

    // ================================================================== import

    fun runImport(fullRefresh: Boolean) {
        val importer = container.channelImporter() ?: return
        importJob?.cancel()
        _ui.value = _ui.value.copy(importMessage = "Reading your channels…", importDetail = null)
        setRoot(Route.Loading)
        importJob = sessionScope.launch {
            val rules = container.settings.settings.first().rules
            runCatching {
                importer.import(rules, onlyNewCategories = !fullRefresh).collect { progress ->
                    when (progress) {
                        is ImportProgress.ReadingCategories ->
                            _ui.value = _ui.value.copy(importMessage = "Reading your channels…")
                        is ImportProgress.Scanning ->
                            _ui.value = _ui.value.copy(
                                importMessage = "Found ${progress.kept} channels",
                                importDetail = "${progress.seen} checked · ${progress.category}",
                            )
                        is ImportProgress.Done ->
                            _ui.value = _ui.value.copy(importMessage = "Found ${progress.kept} channels", importDetail = null)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "import failed", error)
                _ui.value = _ui.value.copy(profileError = describe(error))
                setRoot(Route.Profiles)
                return@launch
            }

            restartChannelObservation(rules)
            setRoot(Route.Main(MainTab.HOME))

            // Guide data next; nothing on screen waits for it.
            runCatching {
                val settings = container.settings.settings.first()
                container.xmltvImporter()?.import(
                    manualOffsetHours = settings.epgOffsetHours,
                    extraSources = extraSourcesFor(settings),
                )?.collect { progress ->
                    if (progress is EpgProgress.Done) container.settings.setEpgSummary(progress.report.summary())
                }
                container.settings.setEpgRefreshedAt(System.currentTimeMillis())
                container.settings.setImportCompleted(true)
            }.onFailure {
                Log.w(TAG, "guide data failed to load", it)
                container.settings.setEpgSummary("Guide download failed: ${it.message ?: it.javaClass.simpleName}")
                container.settings.setImportCompleted(true)
            }
            refreshWindow()
            live.rebuild()

            // The catalogue last: both it and the guide are streaming imports that write
            // continuously, and a 1 GB stick should not be asked to run two at once.
            importLibrary()
        }
    }

    private fun extraSourcesFor(settings: AppSettings) =
        if (!settings.useExtraEpgSources) emptyList()
        else EpgSources.forCountries(settings.rules.countries.map { it.code }.toSet())

    fun refreshEpgNow() {
        if (_ui.value.isRefreshingEpg) return
        _ui.value = _ui.value.copy(isRefreshingEpg = true)
        sessionScope.launch {
            runCatching {
                val settings = container.settings.settings.first()
                container.xmltvImporter()?.import(
                    manualOffsetHours = settings.epgOffsetHours,
                    extraSources = extraSourcesFor(settings),
                )?.collect { progress ->
                    when (progress) {
                        is EpgProgress.Source -> _ui.value = _ui.value.copy(epgRefreshDetail = progress.label)
                        is EpgProgress.Done -> container.settings.setEpgSummary(progress.report.summary())
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
            live.rebuild()
        }
    }

    /**
     * Caches the whole on-demand catalogue and joins it to the IMDb title index, which is what
     * every recommendation row queries. Skipped when both are current.
     */
    fun importLibrary(force: Boolean = false) {
        if (libraryJob?.isActive == true) return
        libraryJob = sessionScope.launch {
            val lib = container.database.libraryDao()
            val settings = container.settings.settings.first()
            val cached = withContext(Dispatchers.IO) { lib.vodCount() + lib.seriesCount() }
            val keysStale = settings.catalogKeyVersion != CATALOG_KEY_VERSION
            val indexStale = settings.titleIndexVersion != TITLE_INDEX_VERSION
            val importer = container.libraryImporter() ?: return@launch
            if (cached == 0 || force || keysStale) {
                _ui.value = _ui.value.copy(libraryStatus = "Reading the movie and series catalogue…")
                val report = importer.import()
                Log.i(TAG, "library: ${report.summary()}")
                if (report.movies > 0 || report.series > 0) container.settings.setCatalogKeyVersion(CATALOG_KEY_VERSION)
                feed.refreshAll()
            } else if (!indexStale) {
                return@launch
            }
            _ui.value = _ui.value.copy(libraryStatus = "Building your recommendations…")
            importer.enrich()
            container.settings.setTitleIndexVersion(TITLE_INDEX_VERSION)
            _ui.value = _ui.value.copy(libraryStatus = null)
            feed.refreshAll()
        }
    }

    // ================================================================== channels and guide

    private fun restartChannelObservation(rules: FilterRules) {
        channelsJob?.cancel()
        channelsJob = sessionScope.launch {
            container.guideRepository.observeChannels(rules).collectLatest { allChannels ->
                val now = System.currentTimeMillis()
                if (rulesChangedAt != 0L) {
                    Log.i(TAG, "filter change applied in ${now - rulesChangedAt} ms: ${allChannels.size} channels")
                    rulesChangedAt = 0L
                }
                val firstLoad = _guide.value.allChannels.isEmpty()
                _ui.value = _ui.value.copy(
                    totalChannelCount = allChannels.size,
                    favoriteChannelCount = allChannels.count { it.isFavorite },
                )
                _banner.value.channel?.let { shown ->
                    allChannels.firstOrNull { it.streamId == shown.streamId }?.let { fresh ->
                        if (fresh.isFavorite != shown.isFavorite) _banner.value = _banner.value.copy(channel = fresh)
                    }
                }
                val filtered = channelsFor(_guide.value.categoryId, allChannels)
                val previousStreamId = _guide.value.selectedChannel?.streamId
                val index = filtered.indexOfFirst { it.streamId == previousStreamId }.takeIf { it >= 0 } ?: 0
                _guide.value = _guide.value.copy(
                    allChannels = allChannels,
                    channels = filtered,
                    nowMs = now,
                    cursor = if (_guide.value.cursor.window.startMs == 0L) {
                        navigator.initial(now, index)
                    } else {
                        _guide.value.cursor.copy(channelIndex = index.coerceAtMost((filtered.size - 1).coerceAtLeast(0)))
                    },
                )
                if (firstLoad || live.state.value.collection == LiveCollection.FAVORITES) live.rebuild()
                if (route == Route.Main(MainTab.MY_LIST)) refreshMyList()
                refreshWindow()
            }
        }
    }

    /** Loads programmes for the visible rows plus a buffer, and nothing else. */
    private fun refreshWindow() {
        if (sessionJob == null) return
        programsJob?.cancel()
        programsJob = sessionScope.launch {
            val state = _guide.value
            if (state.channels.isEmpty()) return@launch
            val first = (state.cursor.firstVisibleRow - ROW_BUFFER).coerceAtLeast(0)
            val last = (state.cursor.firstVisibleRow + VISIBLE_ROWS + ROW_BUFFER).coerceAtMost(state.channels.size)
            val visible = state.channels.subList(first, last)
            val offsetHours = _ui.value.settings.epgOffsetHours
            val programs = container.guideRepository.programsFor(visible, state.cursor.window, offsetHours)
            // Replace rather than merge, so memory stays at a screenful however long the viewer
            // browses.
            _guide.value = _guide.value.copy(programsByKey = programs, nowMs = System.currentTimeMillis())
            _guide.value = _guide.value.copy(selected = navigator.selected(_guide.value.cursor))
            runCatching {
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

    fun tick() {
        _guide.value = _guide.value.copy(nowMs = System.currentTimeMillis())
    }

    /**
     * The channels a guide category stands for. A package is a category too, and every place
     * that asks has to agree about that — otherwise a filter change quietly drops the viewer back
     * to the full list.
     */
    private fun channelsFor(categoryId: String?, all: List<GuideChannel>): List<GuideChannel> = when {
        categoryId == null || categoryId == ALL_CHANNELS_ID -> all
        categoryId == FAVORITE_CHANNELS_ID -> all.filter { it.isFavorite }
        categoryId.startsWith(PACKAGE_PREFIX) -> {
            val pkgId = categoryId.removePrefix(PACKAGE_PREFIX)
            ChannelPackages.byId(pkgId)?.let { live.resolvePackage(it, all) }?.ifEmpty { null } ?: all
        }
        else -> all.filter { it.categoryId == categoryId }
    }

    private suspend fun liveNowTiles(): List<ChannelTile> {
        val all = _guide.value.allChannels
        if (all.isEmpty()) return emptyList()
        return live.nowPlaying(live.resolvePackage(ChannelPackages.AMERICAN_CABLE, all).take(40))
    }

    /** Opens the guide on a Live TV collection. */
    fun openGuideFor(collection: LiveCollection) {
        val (id, name) = when (collection) {
            LiveCollection.FAVORITES -> FAVORITE_CHANNELS_ID to "My Channels"
            LiveCollection.ALL -> ALL_CHANNELS_ID to "All Channels"
            else -> (PACKAGE_PREFIX + collection.packageId) to collection.label
        }
        selectGuideCategory(id, name)
    }

    fun selectGuideCategory(categoryId: String, categoryName: String) {
        stopPreview()
        val filtered = channelsFor(categoryId, _guide.value.allChannels)
        val now = System.currentTimeMillis()
        _guide.value = _guide.value.copy(
            categoryId = categoryId,
            categoryName = categoryName,
            channels = filtered,
            cursor = navigator.initial(now, 0),
            nowMs = now,
            details = null,
        )
        refreshWindow()
        navigate(Route.Guide)
    }

    /** From full-screen video, the guide opens on the channel being watched. */
    fun openGuideFromWatching() {
        val now = System.currentTimeMillis()
        val state = _guide.value
        val playingIndex = state.channels.indexOfFirst { it.streamId == playback.value.channel?.streamId }
            .takeIf { it >= 0 } ?: state.cursor.channelIndex
        _guide.value = state.copy(cursor = navigator.initial(now, playingIndex), nowMs = now, details = null)
        navigate(Route.Guide)
        refreshWindow()
    }

    fun toggleFavoriteChannel(streamId: Long) {
        sessionScope.launch { container.guideRepository.toggleFavoriteChannel(streamId) }
    }

    fun toggleFavoriteSelectedChannel() {
        val streamId = (if (route == Route.Guide) _guide.value.selectedChannel?.streamId else null)
            ?: _banner.value.channel?.streamId
            ?: return
        toggleFavoriteChannel(streamId)
    }

    fun guideUp() = moveCursor(navigator.moveUp(_guide.value.cursor))
    fun guideDown() = moveCursor(navigator.moveDown(_guide.value.cursor))
    fun guideLeft() = moveCursor(navigator.moveLeft(_guide.value.cursor, _guide.value.nowMs))
    fun guideRight() = moveCursor(navigator.moveRight(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageBack() = moveCursor(navigator.pageBack(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageForward() = moveCursor(navigator.pageForward(_guide.value.cursor))

    private fun moveCursor(next: GuideCursor) {
        if (_guide.value.details != null) return
        val previous = _guide.value.cursor
        if (next == previous) return
        _guide.value = _guide.value.copy(cursor = next, selected = navigator.selected(next))
        if (next.window != previous.window || next.firstVisibleRow != previous.firstVisibleRow) refreshWindow()
    }

    /** Select in the guide: tune when on now, details when in the future. */
    fun guideSelect() {
        val state = _guide.value
        val channel = state.selectedChannel ?: return
        val slot = state.selected
        if (state.details == null && slot != null && navigator.isFuture(slot, state.nowMs) && !slot.isFiller) {
            _guide.value = state.copy(details = slot)
            return
        }
        tuneAndWatch(channel, state.channels)
    }

    fun dismissDetails() {
        _guide.value = _guide.value.copy(details = null)
    }

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

    fun guideClick(hit: GuideHit) {
        if (_guide.value.details != null) return
        when (hit) {
            is GuideHit.Program -> { guideHover(hit); guideSelect() }
            is GuideHit.Channel -> { guideHover(hit); _guide.value.selectedChannel?.let { tuneAndWatch(it, _guide.value.channels) } }
            is GuideHit.Header, GuideHit.Nothing -> Unit
        }
    }

    fun guideWheel(rows: Int) {
        if (rows > 0) repeat(rows) { guideDown() } else repeat(-rows) { guideUp() }
    }

    fun detailsTuneNow() {
        val channel = _guide.value.selectedChannel ?: return
        dismissDetails()
        tuneAndWatch(channel, _guide.value.channels)
    }

    // ================================================================== playback: live

    private fun startPreview(channel: GuideChannel) {
        if (route != Route.Main(MainTab.LIVE)) return
        previewActive = true
        playLive(channel, remember = false)
    }

    fun stopPreview() {
        live.stopPreview()
        if (previewActive) {
            previewActive = false
            if (route != Route.Watching && route != Route.Guide) container.player.stop()
        }
    }

    /** Tunes and goes full screen; Up and Down then step through [zap]. */
    fun tuneAndWatch(channel: GuideChannel, zap: List<GuideChannel> = emptyList()) {
        previewActive = false
        if (zap.isNotEmpty()) zapList = zap
        playLive(channel)
        if (route != Route.Watching) navigate(Route.Watching)
    }

    private fun playLive(channel: GuideChannel, remember: Boolean = true) {
        sessionScope.launch {
            val settings = container.settings.settings.first()
            val client = container.client ?: return@launch
            themeSkip.stop()
            container.player.play(
                PlayableChannel(
                    streamId = channel.streamId,
                    number = channel.number,
                    name = channel.name,
                    url = client.streamUrl(channel.streamId, settings.streamFormat),
                )
            )
            if (!remember) return@launch
            startCaptions(com.crimson.ui.player.CaptionsController.Target(channel.streamId, isLive = true) { null })
            container.settings.setCurrentChannel(channel.streamId)
            _ui.value = _ui.value.copy(
                nowPlaying = NowPlaying(NowPlaying.Kind.LIVE, channel.streamId, channel.name, image = channel.logoUrl),
            )
            _banner.value = BannerState(channel = channel, showToken = System.currentTimeMillis())
            val (now, next) = container.guideRepository.nowAndNext(channel, System.currentTimeMillis(), settings.epgOffsetHours)
            if (_banner.value.channel?.streamId == channel.streamId) _banner.value = _banner.value.copy(now = now, next = next)
        }
    }

    fun channelUp() = stepChannel(+1)
    fun channelDown() = stepChannel(-1)

    private fun stepChannel(delta: Int) {
        if (_ui.value.nowPlaying?.isVod == true) return
        val channels = zapList.ifEmpty { _guide.value.channels }
        if (channels.isEmpty()) return
        val currentId = playback.value.channel?.streamId
        val index = channels.indexOfFirst { it.streamId == currentId }.takeIf { it >= 0 } ?: 0
        playLive(channels[Math.floorMod(index + delta, channels.size)])
    }

    fun lastChannel() {
        sessionScope.launch {
            val previous = container.settings.settings.first().previousChannelStreamId
            if (previous == 0L) return@launch
            (zapList + _guide.value.allChannels).firstOrNull { it.streamId == previous }?.let { playLive(it) }
        }
    }

    fun showBanner() {
        val current = _banner.value
        val channel = current.channel ?: return
        _banner.value = current.copy(showToken = System.currentTimeMillis())
        sessionScope.launch {
            val (now, next) = container.guideRepository.nowAndNext(channel, System.currentTimeMillis(), _ui.value.settings.epgOffsetHours)
            if (_banner.value.channel?.streamId == channel.streamId) _banner.value = _banner.value.copy(now = now, next = next)
        }
    }

    fun retryPlayback() = container.player.retry()

    // ================================================================== playback: films and episodes

    fun playMovie(id: Long, positionMs: Long = 0L) {
        val client = container.client ?: return
        previewActive = false
        sessionScope.launch {
            val entity = withContext(Dispatchers.IO) { container.database.libraryDao().vod(id) }
            val details = info.movie(id)
            val tile = entity?.let(Mappers::tile)
            val ext = details?.containerExtension ?: entity?.containerExtension ?: "mp4"
            val title = tile?.name ?: details?.name ?: "Movie"
            themeSkip.stop()
            // Before play: whether the audio must be decoded for caption sync is decided as the
            // stream is prepared.
            startCaptions(com.crimson.ui.player.CaptionsController.Target(id, isLive = false) {
                val year = entity?.titleYear ?: tile?.year ?: details?.year
                val keys = listOfNotNull(entity?.titleKey, entity?.titleKeyAlt)
                com.crimson.core.subtitles.SubtitleQuery(
                    title = title,
                    year = year,
                    imdbId = container.subtitles.imdbId(TitleKind.MOVIE, keys, year),
                    tmdbId = details?.tmdbId,
                )
            })
            container.player.play(
                PlayableChannel(id, 0, title, client.vodStreamUrl(id, ext), isLive = false, startPositionMs = positionMs)
            )
            _ui.value = _ui.value.copy(
                nowPlaying = NowPlaying(
                    kind = NowPlaying.Kind.MOVIE,
                    streamId = id,
                    title = title,
                    subtitle = listOfNotNull(tile?.year?.toString(), details?.runtime).joinToString(" · ").ifBlank { null },
                    image = tile?.poster ?: details?.poster,
                    backdrop = details?.backdrop,
                    containerExtension = ext,
                ),
            )
            showVodControls()
            startProgressSaver()
            if (route != Route.Watching) navigate(Route.Watching)
        }
    }

    fun playEpisode(seriesId: Long, episode: EpisodeUi, positionMs: Long = episode.positionMs.takeIf { episode.fraction < 0.94f } ?: 0L) {
        val client = container.client ?: return
        previewActive = false
        sessionScope.launch {
            val series = info.series(seriesId)
            val entity = withContext(Dispatchers.IO) { container.database.libraryDao().series(seriesId) }
            val seriesName = entity?.let(Mappers::tile)?.name ?: series?.name ?: "Series"
            val ext = episode.containerExtension ?: "mp4"
            startCaptions(com.crimson.ui.player.CaptionsController.Target(episode.id, isLive = false) {
                val year = entity?.titleYear ?: series?.year
                val keys = listOfNotNull(entity?.titleKey, entity?.titleKeyAlt)
                com.crimson.core.subtitles.SubtitleQuery(
                    title = seriesName,
                    year = year,
                    imdbId = container.subtitles.imdbId(TitleKind.SERIES, keys, year),
                    tmdbId = series?.tmdbId,
                    season = episode.season,
                    episode = episode.number,
                )
            })
            // Also before play: listening for the theme needs the audio decoded.
            val show = com.crimson.core.skip.ThemeSkipChoice.showKey(seriesName)
            themeSkip.start(
                com.crimson.ui.player.ThemeSkipController.Target(
                    episodeId = episode.id,
                    show = show,
                    season = episode.season,
                    episode = episode.number,
                    ids = {
                        val year = entity?.titleYear ?: series?.year
                        val keys = listOfNotNull(entity?.titleKey, entity?.titleKeyAlt)
                        container.subtitles.imdbId(TitleKind.SERIES, keys, year) to series?.tmdbId
                    },
                    onReachedEnd = ::themeSkippedToEnd,
                ),
                _ui.value.settings.themeSkipFor(show),
            )
            container.player.play(
                PlayableChannel(episode.id, 0, seriesName, client.seriesStreamUrl(episode.id, ext), isLive = false, startPositionMs = positionMs)
            )
            val next = details.episodeAfter(seriesId, episode.id)
            _ui.value = _ui.value.copy(
                nowPlaying = NowPlaying(
                    kind = NowPlaying.Kind.EPISODE,
                    streamId = episode.id,
                    title = seriesName,
                    subtitle = "S${episode.season}:E${episode.number} · ${episode.title}",
                    seriesId = seriesId,
                    season = episode.season,
                    episode = episode.number,
                    image = entity?.cover ?: series?.poster,
                    backdrop = episode.image ?: entity?.backdrop ?: series?.backdrop,
                    containerExtension = ext,
                    next = next?.let { NextEpisode(it.id, it.season, it.number, it.title, it.containerExtension, it.image) },
                ),
            )
            showVodControls()
            startProgressSaver()
            if (route != Route.Watching) navigate(Route.Watching)
        }
    }

    /** The big Play on a details page or a hero. */
    fun playTitle(kind: TitleKind, id: Long) {
        if (kind == TitleKind.MOVIE) {
            sessionScope.launch {
                val p = withContext(Dispatchers.IO) { container.database.userDao().progress(WatchProgressEntity.movieKey(id)) }
                playMovie(id, if (p != null && !p.isFinished) p.positionMs else 0L)
            }
            return
        }
        sessionScope.launch {
            val series = info.series(id)?.series ?: return@launch
            val all = series.episodes.toSortedMap().flatMap { (s, eps) -> eps.sortedBy { it.episodeNum }.map { s to it } }
            if (all.isEmpty()) return@launch
            val progress = withContext(Dispatchers.IO) { container.database.userDao().progressForSeries(id) }.firstOrNull()
            val index = all.indexOfFirst { it.second.id == progress?.itemId }
            val (season, ep) = when {
                index < 0 -> all.first()
                progress?.isFinished == true -> all.getOrNull(index + 1) ?: all[index]
                else -> all[index]
            }
            val resume = if (progress != null && progress.itemId == ep.id && !progress.isFinished) progress.positionMs else 0L
            playEpisode(
                id,
                EpisodeUi(ep.id, season, ep.episodeNum, com.crimson.ui.details.DetailsController.cleanEpisodeTitle(ep.title), ep.image, null, ep.info, 0f, resume, ep.containerExtension),
                resume,
            )
        }
    }

    fun playFromDetails() {
        val s = details.state.value
        if (s.kind == TitleKind.MOVIE) playMovie(s.id, s.play.positionMs)
        else s.play.episode?.let { playEpisode(s.id, it, s.play.positionMs) } ?: playTitle(TitleKind.SERIES, s.id)
    }

    fun restartFromDetails() {
        val s = details.state.value
        if (s.kind == TitleKind.MOVIE) playMovie(s.id, 0L)
        else s.play.episode?.let { playEpisode(s.id, it, 0L) }
    }

    private fun playContinue(tile: ContinueTile) {
        if (tile.kind == TitleKind.MOVIE) {
            playMovie(tile.titleId, tile.positionMs)
            return
        }
        val episodeId = tile.episodeId ?: return
        sessionScope.launch {
            val progress = withContext(Dispatchers.IO) { container.database.userDao().progress(WatchProgressEntity.episodeKey(episodeId)) }
            info.series(tile.titleId)
            playEpisode(
                tile.titleId,
                EpisodeUi(
                    id = episodeId,
                    season = progress?.season ?: 1,
                    number = progress?.episode ?: 1,
                    title = progress?.subtitle?.substringAfter(" · ", "") ?: "",
                    image = progress?.backdrop,
                    runtime = null,
                    plot = null,
                    fraction = tile.fraction,
                    positionMs = tile.positionMs,
                    containerExtension = tile.containerExtension,
                ),
                tile.positionMs,
            )
        }
    }

    fun playNextEpisode() {
        val np = _ui.value.nowPlaying ?: return
        val next = np.next ?: return
        saveProgress(final = true)
        playEpisode(
            np.seriesId ?: return,
            EpisodeUi(next.id, next.season, next.number, next.title, next.image, null, null, 0f, 0L, next.containerExtension),
            0L,
        )
    }

    private fun startProgressSaver() {
        progressJob?.cancel()
        progressJob = sessionScope.launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                saveProgress(final = false)
                if (playback.value.isEnded) {
                    saveProgress(final = true)
                    val np = _ui.value.nowPlaying
                    if (np?.next != null) {
                        // A few seconds for the "next episode" card, as streaming apps give.
                        delay(NEXT_EPISODE_DELAY_MS)
                        if (route == Route.Watching && playback.value.isEnded) playNextEpisode()
                    }
                    break
                }
            }
        }
    }

    private fun saveProgress(final: Boolean) {
        val np = _ui.value.nowPlaying ?: return
        if (!np.isVod || sessionJob == null) return
        val position = container.player.positionMs()
        val duration = container.player.durationMs()
        if (duration <= 0L || (position < 5_000L && !playback.value.isEnded)) return
        val entity = WatchProgressEntity(
            key = if (np.kind == NowPlaying.Kind.MOVIE) WatchProgressEntity.movieKey(np.streamId) else WatchProgressEntity.episodeKey(np.streamId),
            kind = if (np.kind == NowPlaying.Kind.MOVIE) "MOVIE" else "EPISODE",
            itemId = np.streamId,
            seriesId = np.seriesId,
            title = np.title,
            subtitle = np.subtitle,
            image = np.image,
            backdrop = np.backdrop,
            positionMs = if (playback.value.isEnded) duration else position,
            durationMs = duration,
            containerExtension = np.containerExtension,
            season = np.season,
            episode = np.episode,
        )
        sessionScope.launch {
            withContext(Dispatchers.IO) { container.database.userDao().saveProgress(entity) }
            if (final) feed.refreshPersonal()
        }
    }

    /** Keys while a film or episode is on screen. Returns true when handled. */
    fun vodKey(action: VodKey): Boolean {
        val player = container.player
        showVodControls()
        when (action) {
            VodKey.TOGGLE -> player.togglePause()
            VodKey.BACK_10 -> player.seekBy(-10_000L)
            VodKey.FORWARD_10 -> player.seekBy(10_000L)
            VodKey.BACK_30 -> player.seekBy(-30_000L)
            VodKey.FORWARD_30 -> player.seekBy(30_000L)
            VodKey.SHOW -> Unit
            VodKey.NEXT -> playNextEpisode()
        }
        return true
    }

    enum class VodKey { TOGGLE, BACK_10, FORWARD_10, BACK_30, FORWARD_30, SHOW, NEXT }

    fun seekTo(positionMs: Long) {
        container.player.seekTo(positionMs)
        showVodControls()
    }

    /** A remote key on a film or episode, through [com.crimson.ui.player.VodControls]; false lets Back leave. */
    fun vodControlKey(key: com.crimson.ui.player.VodControls.Key): Boolean {
        val np = _ui.value.nowPlaying ?: return false
        val player = container.player
        val ctx = com.crimson.ui.player.VodControls.Context(
            positionMs = player.positionMs(),
            durationMs = player.durationMs(),
            buttons = vodButtons(np),
            now = System.currentTimeMillis(),
        )
        val (next, effects) = _vodControls.value.onKey(key, ctx)
        _vodControls.value = next
        effects.forEach(::applyVodEffect)
        if (next.scrubMs != null) settleScrubLater() else scrubSettleJob?.cancel()
        hideVodControlsLater()
        return effects.none { it == com.crimson.ui.player.VodControls.Effect.Leave }
    }

    /** Brings the controls up (a new title, a media key, the pointer), focus on [button] or the time bar. */
    fun showVodControls(button: com.crimson.ui.player.VodButton? = null) {
        if (_ui.value.nowPlaying?.isVod != true) return
        _vodControls.value = _vodControls.value.copy(visible = true, button = button ?: _vodControls.value.button.takeIf { _vodControls.value.visible })
        hideVodControlsLater()
    }

    private fun vodButtons(np: NowPlaying) = listOfNotNull(
        com.crimson.ui.player.VodButton.PLAY_PAUSE,
        com.crimson.ui.player.VodButton.BACK_10,
        com.crimson.ui.player.VodButton.FORWARD_10,
        com.crimson.ui.player.VodButton.AUDIO_SUBTITLES,
        com.crimson.ui.player.VodButton.NEXT_EPISODE.takeIf { np.next != null },
    )

    private fun applyVodEffect(effect: com.crimson.ui.player.VodControls.Effect) {
        val player = container.player
        when (effect) {
            com.crimson.ui.player.VodControls.Effect.TogglePause -> player.togglePause()
            is com.crimson.ui.player.VodControls.Effect.SeekBy -> player.seekBy(effect.ms)
            is com.crimson.ui.player.VodControls.Effect.SeekTo -> player.seekTo(effect.ms)
            com.crimson.ui.player.VodControls.Effect.OpenMenu -> openPlayerMenu()
            com.crimson.ui.player.VodControls.Effect.Next -> playNextEpisode()
            com.crimson.ui.player.VodControls.Effect.Leave -> Unit
        }
    }

    /** The scrub jumps once the keys have stopped for a moment. */
    private fun settleScrubLater() {
        scrubSettleJob?.cancel()
        scrubSettleJob = sessionScope.launch {
            delay(com.crimson.ui.player.VodControls.SETTLE_MS)
            val (next, effects) = _vodControls.value.commit()
            _vodControls.value = next
            effects.forEach(::applyVodEffect)
            hideVodControlsLater()
        }
    }

    /** Hides the controls after a few idle seconds, but never while paused, scrubbing or in the menu. */
    private fun hideVodControlsLater() {
        controlsHideJob?.cancel()
        if (!_vodControls.value.visible) return
        controlsHideJob = sessionScope.launch {
            while (true) {
                delay(com.crimson.ui.player.VodControls.HIDE_MS)
                val c = _vodControls.value
                if (!c.visible) return@launch
                if (playback.value.isPaused || c.scrubMs != null || _ui.value.playerMenuOpen) continue
                _vodControls.value = c.hidden()
                return@launch
            }
        }
    }

    private fun resetVodControls() {
        controlsHideJob?.cancel()
        scrubSettleJob?.cancel()
        _vodControls.value = com.crimson.ui.player.VodControls()
    }

    fun positionMs(): Long = container.player.positionMs()
    fun durationMs(): Long = container.player.durationMs()

    /** Select while watching live: the guide, or a retry after playback gave up. */
    fun watchingSelect() {
        if (playback.value.error != null) retryPlayback() else openGuideFromWatching()
    }

    // ================================================================== my list

    fun toggleMyList(tile: TitleTile) {
        sessionScope.launch {
            val dao = container.database.userDao()
            withContext(Dispatchers.IO) {
                if (dao.isInList(tile.kind.name, tile.id)) dao.removeFromList(tile.kind.name, tile.id)
                else dao.addToList(MyListEntity(tile.kind.name, tile.id, tile.name, tile.poster))
            }
            feed.refreshPersonal()
            refreshMyList()
        }
    }

    fun toggleMyListFromDetails() {
        details.toggleMyList()
        sessionScope.launch {
            delay(150)
            feed.refreshPersonal()
        }
    }

    private fun refreshMyList() {
        if (sessionJob == null) return
        sessionScope.launch {
            val db = container.database
            val items = withContext(Dispatchers.IO) { db.userDao().list() }
            val lib = db.libraryDao()
            val movies = withContext(Dispatchers.IO) { lib.vodByIds(items.filter { it.kind == TitleKind.MOVIE.name }.map { it.itemId }) }
                .associateBy { it.streamId }
            val shows = withContext(Dispatchers.IO) { lib.seriesByIds(items.filter { it.kind == TitleKind.SERIES.name }.map { it.itemId }) }
                .associateBy { it.seriesId }
            val movieTiles = items.filter { it.kind == TitleKind.MOVIE.name }.map { item ->
                movies[item.itemId]?.let(Mappers::tile) ?: TitleTile(TitleKind.MOVIE, item.itemId, item.name, item.image)
            }
            val showTiles = items.filter { it.kind == TitleKind.SERIES.name }.map { item ->
                shows[item.itemId]?.let(Mappers::tile) ?: TitleTile(TitleKind.SERIES, item.itemId, item.name, item.image)
            }
            val favourites = live.nowPlaying(_guide.value.allChannels.filter { it.isFavorite })
            val recent = withContext(Dispatchers.IO) { db.userDao().recent(30) }
                .filter { !it.isFinished && it.positionMs > 30_000L }
                .distinctBy { it.seriesId ?: -it.itemId }
                .map { p ->
                    ContinueTile(
                        progressKey = p.key,
                        kind = if (p.kind == "MOVIE") TitleKind.MOVIE else TitleKind.SERIES,
                        titleId = if (p.kind == "MOVIE") p.itemId else (p.seriesId ?: p.itemId),
                        episodeId = if (p.kind == "EPISODE") p.itemId else null,
                        name = p.title, subtitle = p.subtitle, image = p.image, backdrop = p.backdrop,
                        fraction = p.fraction, positionMs = p.positionMs, containerExtension = p.containerExtension,
                    )
                }
            _myList.value = listOfNotNull(
                recent.takeIf { it.isNotEmpty() }?.let { FeedRow("ml_continue", "Continue Watching", RowKind.CONTINUE, it) },
                movieTiles.takeIf { it.isNotEmpty() }?.let { FeedRow("ml_movies", "Movies", RowKind.POSTER, it, subtitle = "${it.size} saved") },
                showTiles.takeIf { it.isNotEmpty() }?.let { FeedRow("ml_shows", "TV Shows", RowKind.POSTER, it, subtitle = "${it.size} saved") },
                favourites.takeIf { it.isNotEmpty() }?.let { FeedRow("ml_channels", "Favorite Channels", RowKind.LIVE, it, subtitle = "${it.size} channels") },
            )
        }
    }

    // ================================================================== direct channel entry

    private var digitJob: Job? = null

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
        if (route == Route.Guide) {
            if (inList >= 0) moveCursor(navigator.moveTo(state.cursor, inList, state.cursor.anchorMs, state.nowMs))
        } else {
            val channel = state.channels.getOrNull(inList) ?: state.allChannels.firstOrNull { it.number == number } ?: return
            tuneAndWatch(channel)
        }
    }

    // ================================================================== settings

    fun setCountries(countries: Set<Country>) {
        rulesChangedAt = System.currentTimeMillis()
        sessionScope.launch {
            val before = container.settings.settings.first().rules.countries
            container.settings.setCountries(countries)
            if ((countries - before).isNotEmpty()) fetchNewCategories()
        }
    }

    fun setMarkets(markets: Set<Market>) {
        rulesChangedAt = System.currentTimeMillis()
        sessionScope.launch {
            val before = container.settings.settings.first().rules.markets
            container.settings.setMarkets(markets)
            if ((markets - before).isNotEmpty()) fetchNewCategories()
        }
    }

    fun setExclusions(keywords: List<String>) {
        rulesChangedAt = System.currentTimeMillis()
        sessionScope.launch { container.settings.setExclusions(keywords) }
    }

    fun setEpgOffsetHours(hours: Int) {
        sessionScope.launch {
            container.settings.setEpgOffsetHours(hours)
            refreshEpgNow()
        }
    }

    fun setUseExtraEpgSources(use: Boolean) {
        sessionScope.launch {
            container.settings.setUseExtraEpgSources(use)
            if (!use) {
                withContext(Dispatchers.IO) {
                    container.database.programDao().deleteSourcesOtherThan(listOf(XmltvImporter.PROVIDER_ID, ShortEpgFetcher.SHORT_EPG_ID))
                }
                refreshWindow()
            } else {
                refreshEpgNow()
            }
        }
    }

    fun setStreamFormat(format: String) {
        sessionScope.launch { container.settings.setStreamFormat(format) }
    }

    fun setLivePreviews(on: Boolean) {
        sessionScope.launch { container.settings.setLivePreviews(on) }
    }

    // ================================================================== captions, sound, picture

    private fun startCaptions(target: com.crimson.ui.player.CaptionsController.Target) {
        val s = _ui.value.settings
        captions.start(target, enabled = s.captions, keepSoundDescriptions = s.captionSoundDescriptions)
    }

    /** An ending theme skipped that ran to the end: on to the next episode, as the credits would. */
    private fun themeSkippedToEnd() {
        if (_ui.value.nowPlaying?.next != null) playNextEpisode()
        else container.player.seekTo(container.player.durationMs())
    }

    /** Switches skipping the opening ([intro]) or the ending theme for the show playing, and remembers it. */
    fun toggleThemeSkip(intro: Boolean) {
        val s = themeSkip.state.value
        val show = s.show ?: return
        val choice = if (intro) s.choice.copy(intro = !s.choice.intro) else s.choice.copy(ending = !s.choice.ending)
        themeSkip.setChoice(choice)
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(themeSkip = _ui.value.settings.themeSkip + (show to choice)))
        sessionScope.launch { container.settings.setThemeSkip(show, choice) }
    }

    fun forgetThemes() = themeSkip.forget()

    /** Plays one of the title's audio tracks (a language), from the Audio & Subtitles menu. */
    fun selectAudio(id: String) = container.player.selectAudio(id)

    fun togglePreferEnglishAudio() {
        val on = !_ui.value.settings.preferEnglishAudio
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(preferEnglishAudio = on))
        container.player.setPreferEnglishAudio(on)
        sessionScope.launch { container.settings.setPreferEnglishAudio(on) }
    }

    fun openPlayerMenu() {
        if (route == Route.Watching) _ui.value = _ui.value.copy(playerMenuOpen = true)
    }

    fun closePlayerMenu() {
        _ui.value = _ui.value.copy(playerMenuOpen = false)
        // Back where the menu was opened from.
        showVodControls(com.crimson.ui.player.VodButton.AUDIO_SUBTITLES)
    }

    fun toggleCaptions() {
        val on = !_ui.value.settings.captions
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(captions = on))
        captions.setEnabled(on)
        sessionScope.launch { container.settings.setCaptions(on) }
    }

    fun setCaptions(on: Boolean) {
        if (_ui.value.settings.captions != on) toggleCaptions()
    }

    fun tryOtherCaptions() = captions.tryAnother()

    fun nudgeCaptions(deltaMs: Long) = captions.nudge(deltaMs)

    fun stepCaptionSize(delta: Int) {
        val current = _ui.value.settings.captionSize
        val next = if (delta > 0) current.next() else current.previous()
        sessionScope.launch { container.settings.setCaptionSize(next) }
    }

    fun toggleCaptionBackground() {
        val on = !_ui.value.settings.captionBackground
        sessionScope.launch { container.settings.setCaptionBackground(on) }
    }

    fun setCaptionSoundDescriptions(on: Boolean) {
        sessionScope.launch { container.settings.setCaptionSoundDescriptions(on) }
    }

    fun toggleDialogueBoost() {
        val on = !_ui.value.settings.dialogueBoost
        container.player.setDialogueBoost(on)
        sessionScope.launch { container.settings.setDialogueBoost(on) }
    }

    /** One step along [com.crimson.data.settings.SettingsStore.BRIGHTNESS_STEPS]. */
    fun stepVideoBrightness(delta: Int) {
        val steps = com.crimson.data.settings.SettingsStore.BRIGHTNESS_STEPS
        val current = _ui.value.settings.videoBrightness
        val index = steps.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: steps.lastIndex
        val next = steps[(index + delta).coerceIn(0, steps.lastIndex)]
        // Shown at once; the store catches up.
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(videoBrightness = next))
        sessionScope.launch { container.settings.setVideoBrightness(next) }
    }

    private fun fetchNewCategories() {
        val importer = container.channelImporter() ?: return
        _ui.value = _ui.value.copy(isFetchingMore = true)
        sessionScope.launch {
            val rules = container.settings.settings.first().rules
            runCatching { importer.import(rules, onlyNewCategories = true).collect { } }
                .onFailure { Log.w(TAG, "fetching new categories failed", it) }
            _ui.value = _ui.value.copy(isFetchingMore = false)
            refreshWindow()
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveProgress(final = false)
    }

    companion object {
        private const val TAG = "CrimsonVM"

        const val DIGIT_TIMEOUT_MS = 1_800L
        const val MAX_CHANNEL_DIGITS = 5
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val NEXT_EPISODE_DELAY_MS = 6_000L

        /** Bumped whenever TitleMatcher changes what a key means; the catalogue is rebuilt. */
        const val CATALOG_KEY_VERSION = 2

        /** Bumped whenever `title_index.tsv` is regenerated; the catalogue is re-joined. */
        const val TITLE_INDEX_VERSION = 1

        /** Channel rows the guide draws at once. Mirrors GuideTheme.rowsVisible. */
        const val VISIBLE_ROWS = 5
        const val ROW_BUFFER = 4
    }
}
