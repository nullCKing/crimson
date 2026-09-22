package com.crimson

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.crimson.ui.CrimsonViewModel
import com.crimson.ui.Screen
import com.crimson.ui.categories.LiveCategoriesScreen
import com.crimson.ui.guide.GuideActions
import com.crimson.ui.guide.GuideScreen
import com.crimson.ui.home.HomeScreen
import com.crimson.ui.login.ImportScreen
import com.crimson.ui.login.LoginScreen
import com.crimson.ui.player.WatchingActions
import com.crimson.ui.player.WatchingOverlay
import com.crimson.ui.settings.SettingsScreen
import com.crimson.ui.browse.BrowseScreen
import com.crimson.ui.vod.OnDemandScreen
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.CrimsonGuideTheme
import kotlinx.coroutines.delay

/**
 * The only activity.
 *
 * Everything is a Compose destination inside it rather than a separate activity, for one concrete
 * reason: the ExoPlayer instance lives in [AppContainer] and its surface lives in this composition.
 * Splitting the guide into its own activity would tear the surface down and rebuild it on every
 * trip between watching and browsing, which is the single most common thing a viewer does.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the screen on while watching. A television with no input for ten minutes should
        // not blank out in the middle of a film.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CrimsonRoot() }
    }
}

@Composable
private fun CrimsonRoot() {
    val viewModel: CrimsonViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()
    val guide by viewModel.guide.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val browse by viewModel.browse.collectAsState()

    val theme = GuideTheme.Default
    val focusRequester = remember { FocusRequester() }

    // The clock in the guide and the banner's progress bar are only honest if something moves
    // them. Once a minute is enough and costs nothing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            viewModel.tick()
        }
    }

    // Watching and the guide have no focusable content of their own, so the root takes focus
    // there to give key events somewhere to land. Login and settings put focus on their own
    // fields and rows: a D-pad move never descends from a focused ancestor into its children,
    // so if the root held focus on those screens nothing in them could be reached.
    LaunchedEffect(ui.screen) {
        if (ui.screen == Screen.Watching || ui.screen == Screen.Guide) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    CrimsonGuideTheme(theme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event -> handleKey(event, ui.screen, viewModel) }
        ) {
            when (ui.screen) {
                Screen.Starting -> Unit

                Screen.Login -> LoginScreen(
                    theme = theme,
                    error = ui.loginError,
                    isSigningIn = ui.isSigningIn,
                    credentialsEncrypted = ui.credentialsEncrypted,
                    onSubmit = viewModel::signIn,
                )

                Screen.Importing -> ImportScreen(
                    theme = theme,
                    message = ui.importMessage.orEmpty(),
                    detail = ui.importDetail,
                )

                Screen.Home -> HomeScreen(
                    channelCount = ui.totalChannelCount,
                    favoriteChannelCount = ui.favoriteChannelCount,
                    nowMs = guide.nowMs,
                    theme = theme,
                    onNavigateLiveTv = viewModel::openLiveTv,
                    onNavigateOnDemand = viewModel::openOnDemand,
                    onNavigateBrowse = viewModel::openBrowse,
                    onNavigateGuide = viewModel::openGuideDirect,
                    onNavigateFavorites = viewModel::openFavoritesDirect,
                    onNavigateSettings = viewModel::openSettings,
                )

                Screen.LiveCategories -> LiveCategoriesScreen(
                    categories = ui.liveCategories,
                    favoriteCategoryIds = ui.favoriteCategoryIds,
                    totalChannelCount = ui.totalChannelCount,
                    favoriteChannelCount = ui.favoriteChannelCount,
                    categoryChannelCounts = ui.categoryChannelCounts,
                    nowMs = guide.nowMs,
                    theme = theme,
                    onSelectCategory = viewModel::selectLiveCategory,
                    onToggleFavoriteCategory = viewModel::toggleFavoriteCategory,
                    onBack = viewModel::liveCategoriesBack,
                    onOpenSettings = viewModel::openSettings,
                )

                Screen.Watching -> {
                    VideoSurface(Modifier.fillMaxSize())
                    WatchingOverlay(
                        playback = playback,
                        banner = banner,
                        nowMs = guide.nowMs,
                        theme = theme,
                        modifier = Modifier.fillMaxSize(),
                        pendingChannelNumber = ui.pendingChannelNumber,
                        actions = remember(viewModel) {
                            WatchingActions(
                                onSelect = viewModel::watchingSelect,
                                onChannelUp = viewModel::channelUp,
                                onChannelDown = viewModel::channelDown,
                                onGuide = viewModel::openGuide,
                                onToggleFavorite = viewModel::toggleFavoriteSelectedChannel,
                                onLastChannel = viewModel::lastChannel,
                                onInfo = viewModel::showBanner,
                                onHome = viewModel::watchingBack,
                                onOpenSettings = viewModel::openSettings,
                            )
                        },
                    )
                }

                Screen.Guide -> GuideScreen(
                    state = guide,
                    theme = theme,
                    previewContent = { modifier -> VideoSurface(modifier) },
                    modifier = Modifier.fillMaxSize(),
                    actions = remember(viewModel) {
                        GuideActions(
                            onHover = viewModel::guideHover,
                            onClick = viewModel::guideClick,
                            onWheel = viewModel::guideWheel,
                            onSelect = viewModel::guideSelect,
                            onBack = viewModel::guideBack,
                            onPageBack = viewModel::guidePageBack,
                            onPageForward = viewModel::guidePageForward,
                            onToggleFavorite = viewModel::toggleFavoriteSelectedChannel,
                            onOpenSettings = viewModel::openSettings,
                            onDismissDetails = viewModel::dismissDetails,
                            onTuneFromDetails = viewModel::detailsTuneNow,
                        )
                    },
                )

                Screen.OnDemand -> OnDemandScreen(
                    ui = ui,
                    browse = browse,
                    nowMs = guide.nowMs,
                    theme = theme,
                    onSetMode = viewModel::setOnDemandMode,
                    onSelectCategory = viewModel::selectOnDemandCategory,
                    onOpenItem = viewModel::openPoster,
                    onPlayMovie = viewModel::playVod,
                    onPlayEpisode = viewModel::playEpisode,
                    onDismissDialog = viewModel::dismissOnDemandDialog,
                    onBack = viewModel::onDemandBack,
                    onOpenSettings = viewModel::openSettings,
                    onOpenBrowse = viewModel::openBrowse,
                )

                Screen.Browse -> BrowseScreen(
                    state = browse,
                    nowMs = guide.nowMs,
                    theme = theme,
                    onQueryChange = viewModel::setSearchQuery,
                    onOpenItem = viewModel::openPoster,
                    onOpenPackage = viewModel::openPackage,
                    onBack = viewModel::browseBack,
                    onOpenSettings = viewModel::openSettings,
                    onOpenOnDemand = viewModel::openOnDemand,
                )

                Screen.Settings -> SettingsScreen(
                    ui = ui,
                    theme = theme,
                    onCountries = viewModel::setCountries,
                    onMarkets = viewModel::setMarkets,
                    onExclusions = viewModel::setExclusions,
                    onOffset = viewModel::setEpgOffsetHours,
                    onFormat = viewModel::setStreamFormat,
                    onRefreshEpg = viewModel::refreshEpgNow,
                    onExtraEpgSources = viewModel::setUseExtraEpgSources,
                    onRebuildCatalogue = { viewModel.importLibrary(force = true) },
                    onSignOut = viewModel::signOut,
                    onClose = viewModel::closeSettings,
                    nowMs = guide.nowMs,
                )
            }

            if (ui.isFetchingMore) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    com.crimson.ui.player.ReconnectingLabel(theme)
                }
            }
        }
    }
}

/**
 * The video surface.
 *
 * Bound to the one shared ExoPlayer. Used both full-screen and as the guide's preview window: the
 * same player, never a second stream, because an Xtream account's connection limit is commonly one
 * and opening two would lock the user out of their own service.
 *
 * PlayerView's buffering, resize and keep-content setters are all @UnstableApi, hence the opt-in;
 * it is scoped to this one composable so the rest of the UI stays off Media3's unstable surface.
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
                player = container.player.exoPlayer
            }
        },
        update = { view -> view.player = container.player.exoPlayer },
    )

    DisposableEffect(Unit) {
        onDispose { /* the player outlives the surface; nothing to release here */ }
    }
}

/**
 * Remote-control routing, in one place.
 *
 * The rules are section 5.3 of the spec. The cursor logic they drive lives in
 * `core`'s GuideNavigator, where it is unit-tested; this only decides which method a key calls.
 */
private fun handleKey(
    event: KeyEvent,
    screen: Screen,
    viewModel: CrimsonViewModel,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    // Number keys, from a keyboard or a remote that has them, are direct channel entry on the
    // screens where a channel number means something.
    if (screen == Screen.Watching || screen == Screen.Guide || screen == Screen.Home) {
        digitOf(event.key)?.let { viewModel.enterDigit(it); return true }
    }

    return when (screen) {
        Screen.Home -> when (event.key) {
            Key.Menu -> { viewModel.openSettings(); true }
            else -> false
        }

        Screen.LiveCategories -> when (event.key) {
            Key.Back, Key.Escape -> { viewModel.liveCategoriesBack(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            else -> false
        }

        Screen.Watching -> when (event.key) {
            Key.DirectionUp -> { viewModel.channelUp(); true }
            Key.DirectionDown -> { viewModel.channelDown(); true }
            Key.DirectionCenter, Key.Enter -> { viewModel.watchingSelect(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            Key.Info, Key.I -> { viewModel.showBanner(); true }
            Key.Guide, Key.G -> { viewModel.openGuide(); true }
            Key.F -> { viewModel.toggleFavoriteSelectedChannel(); true }
            // The Fire remote has no number pad, so Play/Pause carries "last channel" instead.
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { viewModel.lastChannel(); true }
            Key.Back, Key.Escape -> { viewModel.watchingBack(); true }
            else -> false
        }

        Screen.Guide -> when (event.key) {
            Key.DirectionUp -> { viewModel.guideUp(); true }
            Key.DirectionDown -> { viewModel.guideDown(); true }
            Key.DirectionLeft -> { viewModel.guideLeft(); true }
            Key.DirectionRight -> { viewModel.guideRight(); true }
            Key.DirectionCenter, Key.Enter -> { viewModel.guideSelect(); true }
            Key.MediaRewind -> { viewModel.guidePageBack(); true }
            Key.MediaFastForward -> { viewModel.guidePageForward(); true }
            Key.MediaPlayPause, Key.MediaPlay, Key.F -> { viewModel.toggleFavoriteSelectedChannel(); true }
            Key.PageUp -> { viewModel.guidePageBack(); true }
            Key.PageDown -> { viewModel.guidePageForward(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            // Back closes the details dialog first when one is open, and only then the guide.
            Key.Back, Key.Escape -> { viewModel.guideBack(); true }
            else -> false
        }

        Screen.OnDemand -> when (event.key) {
            Key.Back, Key.Escape -> { viewModel.onDemandBack(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            else -> false
        }

        Screen.Browse -> when (event.key) {
            Key.Back, Key.Escape -> { viewModel.browseBack(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            else -> false
        }

        Screen.Settings -> when (event.key) {
            Key.Back, Key.Escape -> { viewModel.closeSettings(); true }
            else -> false
        }

        else -> false
    }
}

/** The digit a key carries, from the number row or the numeric keypad, or null. */
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
