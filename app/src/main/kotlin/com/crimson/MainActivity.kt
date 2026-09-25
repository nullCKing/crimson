package com.crimson

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.crimson.core.catalog.FeedPage
import com.crimson.ui.CrimsonViewModel
import com.crimson.ui.CrimsonViewModel.VodKey
import com.crimson.ui.Route
import com.crimson.ui.browse.BrowseActions
import com.crimson.ui.browse.BrowseMemory
import com.crimson.ui.browse.BrowseScreen
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.MainTab
import com.crimson.ui.details.DetailsActions
import com.crimson.ui.details.DetailsScreen
import com.crimson.ui.guide.GuideActions
import com.crimson.ui.guide.GuideScreen
import com.crimson.ui.live.DirectoryActions
import com.crimson.ui.live.DirectoryScreen
import com.crimson.ui.live.LiveActions
import com.crimson.ui.live.LiveScreen
import com.crimson.ui.mylist.MyListScreen
import com.crimson.ui.player.CaptionLayer
import com.crimson.ui.player.ThemeSkipNote
import com.crimson.ui.player.VodControls
import com.crimson.ui.player.PlayerActions
import com.crimson.ui.player.PlayerMenu
import com.crimson.ui.player.PlayerMenuActions
import com.crimson.ui.player.PlayerOverlay
import com.crimson.ui.profiles.EditProfileScreen
import com.crimson.ui.profiles.LoadingScreen
import com.crimson.ui.profiles.ProfilesScreen
import com.crimson.ui.search.SearchActions
import com.crimson.ui.search.SearchScreen
import com.crimson.ui.settings.SettingsActions
import com.crimson.ui.settings.SettingsScreen
import com.crimson.ui.sports.SportsActions
import com.crimson.ui.sports.SportsScreen
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonType
import com.crimson.ui.theme.GuideTheme
import kotlinx.coroutines.delay

/**
 * The only activity.
 *
 * Everything is a Compose destination inside it rather than a separate activity, for one concrete
 * reason: the ExoPlayer instance lives in [AppContainer] and its surface lives in this composition.
 * Moving between the Live TV preview, the guide and full-screen video is then a matter of which
 * composable holds the surface, never a teardown of the stream.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CrimsonRoot(onExit = { finish() }) }
    }
}

@Composable
private fun CrimsonRoot(onExit: () -> Unit) {
    val vm: CrimsonViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val guide by vm.guide.collectAsState()
    val playback by vm.playback.collectAsState()
    val banner by vm.banner.collectAsState()
    val spotlight by vm.feed.spotlight.collectAsState()
    val captionState by vm.captions.state.collectAsState()
    val themeSkipState by vm.themeSkip.state.collectAsState()
    val vodControls by vm.vodControls.collectAsState()
    val cues by vm.cues.collectAsState()

    val guideTheme = GuideTheme.Default
    val rootFocus = remember { FocusRequester() }
    val memories = remember { HashMap<MainTab, BrowseMemory>() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            vm.tick()
        }
    }

    // Full-screen video and the guide have no focusable content of their own, so the root takes
    // focus there to give remote keys somewhere to land. Every other page focuses its own
    // controls: a D-pad move never descends from a focused ancestor into its children.
    LaunchedEffect(ui.route, ui.playerMenuOpen) {
        if ((ui.route == Route.Watching && !ui.playerMenuOpen) || ui.route == Route.Guide) runCatching { rootFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Crimson.Background)
            .focusRequester(rootFocus)
            // Focusable only where it handles the keys itself; elsewhere a page's own controls
            // hold focus, and a focusable root would catch it whenever a page lets go.
            .focusable(enabled = ui.route == Route.Watching || ui.route == Route.Guide)
            .onPreviewKeyEvent { handleKey(it, ui.route, vm) }
            .onKeyEvent { event ->
                // Back bubbles: a page that wants it first (Home returning to its top) consumes it
                // before it reaches here.
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    if (!vm.back()) onExit()
                    true
                } else if (event.type == KeyEventType.KeyDown && event.key == Key.Menu && ui.route is Route.Main) {
                    vm.openSettings(); true
                } else false
            },
    ) {
        val avatar = ui.profile?.avatar ?: 0
        AnimatedContent(
            targetState = ui.route,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(90)) },
            contentKey = { route -> if (route is Route.Main) "main:${route.tab}" else route.toString() },
            label = "route",
        ) { route ->
            when (route) {
                Route.Starting -> Unit

                Route.Profiles -> ProfilesScreen(
                    profiles = ui.profiles,
                    lastProfileId = ui.profile?.id,
                    error = ui.profileError,
                    onSelect = vm::selectProfile,
                    onEdit = { vm.editProfile(it.id) },
                    onAdd = { vm.editProfile(null) },
                )

                is Route.EditProfile -> {
                    val existing = ui.profiles.firstOrNull { it.id == route.profileId }
                    EditProfileScreen(
                        existing = existing,
                        isFirst = ui.profiles.isEmpty(),
                        saving = ui.isSavingProfile,
                        error = ui.profileError,
                        encrypted = ui.profilesEncrypted,
                        onSave = { name, av, server, user, pass -> vm.saveProfile(existing?.id, name, av, server, user, pass) },
                        onCancel = { vm.back() },
                        onDelete = existing?.let { { vm.deleteProfile(it.id) } },
                    )
                }

                Route.Loading -> LoadingScreen(ui.profile, ui.importMessage.orEmpty(), ui.importDetail)

                is Route.Main -> when (route.tab) {
                    MainTab.HOME, MainTab.MOVIES, MainTab.SHOWS -> {
                        val page = when (route.tab) {
                            MainTab.MOVIES -> FeedPage.MOVIES
                            MainTab.SHOWS -> FeedPage.SHOWS
                            else -> FeedPage.HOME
                        }
                        val state by vm.feed.state(page).collectAsState()
                        BrowseScreen(
                            tab = route.tab,
                            state = state,
                            spotlight = spotlight,
                            nowMs = guide.nowMs,
                            avatar = avatar,
                            libraryStatus = ui.libraryStatus,
                            memory = memories.getOrPut(route.tab) { BrowseMemory() },
                            actions = remember(vm, page) {
                                BrowseActions(
                                    onTab = vm::openTab,
                                    onSearch = { vm.openSearch() },
                                    onProfile = vm::openSettings,
                                    onTileClick = { tile, row -> vm.openTile(tile, row.tiles) },
                                    onTileFocus = { vm.feed.focus(it) },
                                    onHeroFocus = { vm.feed.focus(it, overline = "FEATURED") },
                                    onPlay = { vm.playTitle(it.kind, it.id) },
                                    onMoreInfo = { vm.openDetails(it.kind, it.id) },
                                    onLoadMore = { vm.feed.loadMore(page) },
                                )
                            },
                        )
                    }
                    MainTab.LIVE -> {
                        val state by vm.live.state.collectAsState()
                        LiveScreen(
                            state = state,
                            nowMs = guide.nowMs,
                            avatar = avatar,
                            preview = { modifier -> VideoSurface(modifier) },
                            actions = remember(vm) {
                                LiveActions(
                                    onTab = vm::openTab,
                                    onSearch = { vm.openSearch(scope = com.crimson.ui.SearchScope.LIVE) },
                                    onProfile = vm::openSettings,
                                    onCollection = vm.live::select,
                                    onGuide = { vm.openGuideFor(vm.live.state.value.collection) },
                                    onDirectory = vm::openDirectory,
                                    onFocusChannel = vm.live::focus,
                                    onOpen = { tile, row -> vm.search.opened(tile.key); vm.openTile(tile, row) },
                                    onRowVisible = vm.live::ensureNowPlaying,
                                )
                            },
                        )
                    }
                    MainTab.SPORTS -> {
                        val state by vm.sports.state.collectAsState()
                        SportsScreen(
                            state = state,
                            avatar = avatar,
                            actions = remember(vm) {
                                SportsActions(
                                    onTab = vm::openTab,
                                    onSearch = { vm.openSearch() },
                                    onProfile = vm::openSettings,
                                    onLeague = vm.sports::selectLeague,
                                    onGame = { vm.openTile(it) },
                                    onRefresh = { vm.sports.refresh(force = true) },
                                )
                            },
                        )
                    }
                    MainTab.MY_LIST -> {
                        val rows by vm.myList.collectAsState()
                        MyListScreen(
                            rows = rows,
                            nowMs = guide.nowMs,
                            avatar = avatar,
                            profileName = ui.profile?.name.orEmpty(),
                            onTab = vm::openTab,
                            onSearch = { vm.openSearch() },
                            onProfile = vm::openSettings,
                            onOpen = { tile, row -> vm.openTile(tile, row) },
                        )
                    }
                }

                is Route.Details -> {
                    val state by vm.details.state.collectAsState()
                    DetailsScreen(
                        state = state,
                        nowMs = guide.nowMs,
                        actions = remember(vm) {
                            DetailsActions(
                                onPlay = vm::playFromDetails,
                                onRestart = vm::restartFromDetails,
                                onToggleList = vm::toggleMyListFromDetails,
                                onSeason = vm.details::selectSeason,
                                onEpisode = { ep -> vm.playEpisode(vm.details.state.value.id, ep) },
                                onOpenTitle = { vm.openDetails(it.kind, it.id) },
                            )
                        },
                    )
                }

                is Route.Search -> {
                    val state by vm.search.state.collectAsState()
                    val suggestions by vm.feed.state(FeedPage.HOME).collectAsState()
                    SearchScreen(
                        state = state,
                        nowMs = guide.nowMs,
                        suggestions = suggestions.rows.filter { it.id.startsWith("top10") || it.id == "live" }.take(3),
                        actions = remember(vm) {
                            SearchActions(
                                onChar = vm.search::type,
                                onBackspace = vm.search::backspace,
                                onClear = vm.search::clear,
                                onScope = vm.search::setScope,
                                onOpen = { tile, row -> vm.search.opened(tile.key); vm.openTile(tile, row) },
                            )
                        },
                    )
                }

                Route.Directory -> {
                    val state by vm.live.directory.collectAsState()
                    DirectoryScreen(
                        state = state,
                        nowMs = guide.nowMs,
                        actions = remember(vm) {
                            DirectoryActions(
                                onQuery = vm.live::setRegionQuery,
                                onRegion = vm.live::selectRegion,
                                onCategory = vm.live::selectCategory,
                                onChannel = { tile: ChannelTile, all -> vm.openTile(tile, all) },
                            )
                        },
                    )
                }

                Route.Guide -> GuideScreen(
                    state = guide,
                    theme = guideTheme,
                    previewContent = { modifier -> VideoSurface(modifier) },
                    modifier = Modifier.fillMaxSize(),
                    actions = remember(vm) {
                        GuideActions(
                            onHover = vm::guideHover,
                            onClick = vm::guideClick,
                            onWheel = vm::guideWheel,
                            onSelect = vm::guideSelect,
                            onBack = { vm.back() },
                            onPageBack = vm::guidePageBack,
                            onPageForward = vm::guidePageForward,
                            onToggleFavorite = vm::toggleFavoriteSelectedChannel,
                            onOpenSettings = vm::openSettings,
                            onDismissDetails = vm::dismissDetails,
                            onTuneFromDetails = vm::detailsTuneNow,
                        )
                    },
                )

                Route.Watching -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    VideoSurface(Modifier.fillMaxSize())
                    val brightness = ui.settings.videoBrightness / 100f
                    // Video brightness: a black layer over the picture only. The captions, the
                    // controls and every menu are drawn above it and keep their own brightness.
                    if (brightness < 1f) {
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f - brightness)))
                    }
                    var bannerUp by remember { mutableStateOf(false) }
                    LaunchedEffect(banner.showToken) {
                        bannerUp = true
                        delay(com.crimson.ui.player.BANNER_MS)
                        bannerUp = false
                    }
                    // Captions move up out of the way of whatever is along the bottom.
                    val raised = if (ui.nowPlaying?.isVod == true) vodControls.visible || playback.isPaused else bannerUp
                    CaptionLayer(
                        captions = captionState,
                        cues = cues,
                        position = vm::positionMs,
                        size = ui.settings.captionSize,
                        background = ui.settings.captionBackground,
                        raised = raised,
                        // Dimmed video would leave full-white captions glaring; they dim less.
                        level = 0.4f + 0.6f * brightness,
                        besideMenu = ui.playerMenuOpen,
                    )
                    PlayerOverlay(
                        playback = playback,
                        banner = banner,
                        nowPlaying = ui.nowPlaying,
                        vodControls = vodControls,
                        nowMs = guide.nowMs,
                        pendingDigits = ui.pendingChannelNumber,
                        actions = remember(vm) {
                            PlayerActions(
                                onSelect = vm::watchingSelect,
                                onChannelUp = vm::channelUp,
                                onChannelDown = vm::channelDown,
                                onTogglePause = { vm.vodKey(VodKey.TOGGLE) },
                                onSeekBy = { if (it < 0) vm.vodKey(VodKey.BACK_10) else vm.vodKey(VodKey.FORWARD_10) },
                                onNext = { vm.vodKey(VodKey.NEXT) },
                                onBack = { vm.back() },
                                onRetry = vm::retryPlayback,
                                position = vm::positionMs,
                                duration = vm::durationMs,
                                onMenu = vm::openPlayerMenu,
                                onPointer = { vm.showVodControls() },
                            )
                        },
                        captionsOn = captionState.isOn,
                    )
                    ThemeSkipNote(themeSkipState.skipped)
                    if (ui.playerMenuOpen) {
                        PlayerMenu(
                            captions = captionState,
                            settings = ui.settings,
                            isLive = ui.nowPlaying?.isVod != true,
                            actions = remember(vm) {
                                PlayerMenuActions(
                                    onCaptions = vm::toggleCaptions,
                                    onTryAnother = vm::tryOtherCaptions,
                                    onNudge = vm::nudgeCaptions,
                                    onCaptionSize = vm::stepCaptionSize,
                                    onCaptionBackground = vm::toggleCaptionBackground,
                                    onDialogueBoost = vm::toggleDialogueBoost,
                                    onBrightness = vm::stepVideoBrightness,
                                    onSettings = { vm.closePlayerMenu(); vm.openSettings() },
                                    onClose = vm::closePlayerMenu,
                                    onSkipIntro = { vm.toggleThemeSkip(intro = true) },
                                    onSkipEnding = { vm.toggleThemeSkip(intro = false) },
                                    onForgetThemes = vm::forgetThemes,
                                    onSelectAudio = vm::selectAudio,
                                    onPreferEnglishAudio = vm::togglePreferEnglishAudio,
                                )
                            },
                            audioTracks = playback.audioTracks,
                            themeSkip = themeSkipState.takeIf { ui.nowPlaying?.kind == com.crimson.ui.NowPlaying.Kind.EPISODE },
                        )
                    }
                }

                Route.Settings -> SettingsScreen(
                    ui = ui,
                    actions = remember(vm) {
                        SettingsActions(
                            onCountries = vm::setCountries,
                            onMarkets = vm::setMarkets,
                            onExclusions = vm::setExclusions,
                            onOffset = vm::setEpgOffsetHours,
                            onFormat = vm::setStreamFormat,
                            onRefreshEpg = vm::refreshEpgNow,
                            onExtraEpgSources = vm::setUseExtraEpgSources,
                            onLivePreviews = vm::setLivePreviews,
                            onCaptions = vm::setCaptions,
                            onCaptionSize = vm::stepCaptionSize,
                            onCaptionBackground = vm::toggleCaptionBackground,
                            onSoundDescriptions = vm::setCaptionSoundDescriptions,
                            onDialogueBoost = vm::toggleDialogueBoost,
                            onBrightness = vm::stepVideoBrightness,
                            onRebuildCatalogue = { vm.importLibrary(force = true) },
                            onSwitchProfile = vm::openProfiles,
                            onEditProfile = { vm.editProfile(vm.ui.value.profile?.id) },
                        )
                    },
                )
            }
        }

        val context = LocalContext.current
        var crash by remember { mutableStateOf(CrashLog.read(context)) }
        crash?.let { report ->
            CrashReport(report, onDismiss = { CrashLog.clear(context); crash = null })
        }
    }
}

/**
 * The last crash's trace, shown once on the next launch. On a Fire TV this is the only way a
 * viewer can see (and photograph) what went wrong without a computer.
 */
@Composable
private fun CrashReport(report: String, onDismiss: () -> Unit) {
    val button = remember { FocusRequester() }
    com.crimson.ui.components.InitialFocus(button)
    Box(
        Modifier
            .fillMaxSize()
            .background(Crimson.Background.copy(alpha = 0.96f))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    onDismiss(); true
                } else false
            }
            .padding(horizontal = 64.dp, vertical = 40.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("Crimson closed unexpectedly last time", style = CrimsonType.Headline)
            Spacer(Modifier.height(6.dp))
            Text(
                "This is what went wrong. A photo of this screen is enough to find the cause.",
                style = CrimsonType.Body,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                CrashLog.summary(report),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                style = CrimsonType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp),
                color = Crimson.TextSecondary,
                overflow = TextOverflow.Clip,
            )
            Spacer(Modifier.height(16.dp))
            com.crimson.ui.components.CrimsonButton(
                "Continue",
                onClick = onDismiss,
                style = com.crimson.ui.components.ButtonStyle.PRIMARY,
                focusRequester = button,
            )
        }
    }
}

/**
 * The video surface, bound to the one shared ExoPlayer. Used full screen, as the guide's preview
 * and as the Live TV hero's preview: the same player every time, never a second stream.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Captions are drawn by CaptionLayer. PlayerView's own subtitle view would draw
                // the stream's captions a second time underneath (they were seen twice), and its
                // edge styles include a blurred shadow that Fire OS 5 cannot render.
                subtitleView?.visibility = android.view.View.GONE
                player = container.player.exoPlayer
            }
        },
        update = { view -> view.player = container.player.exoPlayer },
        onRelease = { view -> view.player = null },
    )
}

/** Remote-control routing for the two screens that hold focus themselves. */
private fun handleKey(event: KeyEvent, route: Route, vm: CrimsonViewModel): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // The captions-and-sound panel takes the keys while it is open.
    if (route == Route.Watching && vm.ui.value.playerMenuOpen) return false
    val vod = vm.ui.value.nowPlaying?.isVod == true

    if ((route == Route.Watching && !vod) || route == Route.Guide) {
        digitOf(event.key)?.let { vm.enterDigit(it); return true }
    }

    return when (route) {
        Route.Watching -> if (vod) {
            val playback = vm.playback.value
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                    playback.error != null -> { vm.retryPlayback(); true }
                    // The next-episode card has the only thing worth pressing.
                    playback.isEnded && vm.ui.value.nowPlaying?.next != null && !vm.vodControls.value.visible -> vm.vodKey(VodKey.NEXT)
                    else -> vm.vodControlKey(VodControls.Key.OK)
                }
                // The remote's arrows move around the controls, as in any streaming app.
                Key.DirectionLeft -> vm.vodControlKey(VodControls.Key.LEFT)
                Key.DirectionRight -> vm.vodControlKey(VodControls.Key.RIGHT)
                Key.DirectionUp -> vm.vodControlKey(VodControls.Key.UP)
                Key.DirectionDown -> vm.vodControlKey(VodControls.Key.DOWN)
                // Back hides the controls first; with none showing it falls through and leaves.
                Key.Back, Key.Escape -> vm.vodControlKey(VodControls.Key.BACK)
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> vm.vodKey(VodKey.TOGGLE)
                Key.MediaRewind -> vm.vodKey(VodKey.BACK_30)
                Key.MediaFastForward -> vm.vodKey(VodKey.FORWARD_30)
                Key.MediaNext -> vm.vodKey(VodKey.NEXT)
                Key.Info, Key.I -> vm.vodKey(VodKey.SHOW)
                // The remote's menu button is a shortcut to Audio & Subtitles.
                Key.Menu, Key.C -> { vm.openPlayerMenu(); true }
                Key.Captions -> { vm.toggleCaptions(); true }
                else -> false
            }
        } else when (event.key) {
            Key.DirectionUp, Key.ChannelUp -> { vm.channelUp(); true }
            Key.DirectionDown, Key.ChannelDown -> { vm.channelDown(); true }
            Key.DirectionCenter, Key.Enter -> { vm.watchingSelect(); true }
            Key.Menu, Key.C -> { vm.openPlayerMenu(); true }
            Key.Captions -> { vm.toggleCaptions(); true }
            Key.Info, Key.I, Key.DirectionLeft, Key.DirectionRight -> { vm.showBanner(); true }
            Key.Guide, Key.G -> { vm.openGuideFromWatching(); true }
            Key.F -> { vm.toggleFavoriteSelectedChannel(); true }
            // The Fire remote has no number pad, so Play/Pause carries "last channel" instead.
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { vm.lastChannel(); true }
            else -> false
        }

        Route.Guide -> when (event.key) {
            Key.DirectionUp -> { vm.guideUp(); true }
            Key.DirectionDown -> { vm.guideDown(); true }
            Key.DirectionLeft -> { vm.guideLeft(); true }
            Key.DirectionRight -> { vm.guideRight(); true }
            Key.DirectionCenter, Key.Enter -> { vm.guideSelect(); true }
            Key.MediaRewind, Key.PageUp -> { vm.guidePageBack(); true }
            Key.MediaFastForward, Key.PageDown -> { vm.guidePageForward(); true }
            Key.MediaPlayPause, Key.MediaPlay, Key.F -> { vm.toggleFavoriteSelectedChannel(); true }
            Key.Menu -> { vm.openSettings(); true }
            else -> false
        }

        else -> false
    }
}

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}
