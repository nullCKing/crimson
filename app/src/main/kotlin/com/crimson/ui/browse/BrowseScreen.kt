package com.crimson.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.ui.components.Backdrop
import com.crimson.ui.components.ButtonStyle
import com.crimson.ui.components.CrimsonButton
import com.crimson.ui.components.EmptyState
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.FeedRowView
import com.crimson.ui.components.LiveBadge
import com.crimson.ui.components.MainTab
import com.crimson.ui.components.MetaLine
import com.crimson.ui.components.PivotScroll
import com.crimson.ui.components.ProgressLine
import com.crimson.ui.components.Tile
import com.crimson.ui.components.TitleTile
import com.crimson.ui.components.TopNav
import com.crimson.ui.feed.FeedState
import com.crimson.ui.feed.Spotlight
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged

/** What a browse page can ask for. */
class BrowseActions(
    val onTab: (MainTab) -> Unit,
    val onSearch: () -> Unit,
    val onProfile: () -> Unit,
    val onTileClick: (Tile, FeedRow) -> Unit,
    val onTileFocus: (Tile) -> Unit,
    val onHeroFocus: (TitleTile) -> Unit,
    val onPlay: (TitleTile) -> Unit,
    val onMoreInfo: (TitleTile) -> Unit,
    val onLoadMore: () -> Unit,
)

/**
 * Remembered across visits to a page: where it was scrolled to and which card had focus.
 *
 * Marked stable because its fields are bookkeeping, not display state: nothing should recompose
 * when they change, and without the annotation every row's callbacks — which capture this —
 * would be rebuilt, and every visible row recomposed, each time the spotlight moved.
 */
@androidx.compose.runtime.Stable
class BrowseMemory {
    val list = LazyListState()
    var lastKey: String? = null
    var inRows: Boolean = false
}

/**
 * Home, TV Shows and Movies.
 *
 * Two modes, the way a streaming app's TV home works. At the top, the page is a billboard: the
 * featured title's artwork across the screen, its synopsis, Play and More Info, the navigation
 * bar above. Once the cursor goes down into the rows the navigation bar folds away, the
 * billboard shrinks into a spotlight that follows the focused card — its artwork, title, rating
 * and synopsis — and the focused row stays pinned just under it while the rows scroll past.
 */
@Composable
fun BrowseScreen(
    tab: MainTab,
    state: FeedState,
    spotlight: Spotlight,
    nowMs: Long,
    avatar: Int,
    libraryStatus: String?,
    memory: BrowseMemory,
    actions: BrowseActions,
) {
    var inRows by remember { mutableStateOf(memory.inRows) }
    var wantHero by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableIntStateOf(0) }
    val heroPlay = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val heroMode = !inRows || wantHero

    // Landing on the page: back on the card the viewer left from, or on the billboard's Play with
    // the rows scrolled back to the top.
    LaunchedEffect(state.rows.isNotEmpty(), state.hero != null) {
        withFrameNanos { }
        val restored = memory.inRows && memory.lastKey != null && runCatching { restore.requestFocus() }.isSuccess
        if (!restored) {
            memory.inRows = false
            runCatching { heroPlay.requestFocus() }
            if (memory.list.firstVisibleItemIndex > 0) memory.list.scrollToItem(0)
        }
        // Once more after the page transition, when the outgoing page lets go of focus.
        kotlinx.coroutines.delay(320)
        if (!restored && !inRows) runCatching { heroPlay.requestFocus() }
        else if (restored && !inRows) runCatching { restore.requestFocus() }
    }
    LaunchedEffect(wantHero) {
        if (!wantHero) return@LaunchedEffect
        withFrameNanos { }
        runCatching { heroPlay.requestFocus() }
        memory.list.scrollToItem(0)
        wantHero = false
    }
    LaunchedEffect(memory.list, state.rows.size) {
        snapshotFlow { memory.list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= state.rows.size - 3) actions.onLoadMore() }
    }

    val spotHeight by animateDpAsState(if (heroMode) 262.dp else 206.dp, tween(320), label = "spotHeight")

    Box(Modifier.fillMaxSize().background(Crimson.Background)) {
        val hero = state.hero
        val shown = if (heroMode && hero != null && spotlight.key != hero.key) Spotlight(key = hero.key, title = hero.name, poster = hero.poster, backdrop = hero.backdrop, rating = hero.rating, year = hero.year, genres = hero.genres) else spotlight
        val game = (shown.tile as? com.crimson.ui.components.GameTile)?.event
        if (game != null) {
            GameBackdrop(game)
        } else if (shown.isLive && shown.backdrop == null) {
            LiveBackdrop(shown.logo)
        } else {
            Backdrop(url = shown.backdrop, fallback = shown.poster, widthFraction = 0.7f, heightFraction = if (heroMode) 0.8f else 0.66f)
        }

        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = heroMode,
                enter = expandVertically(tween(260)) + fadeIn(tween(260)),
                exit = shrinkVertically(tween(260)) + fadeOut(tween(200)),
            ) {
                Box(Modifier.background(Crimson.ScrimTop)) {
                    TopNav(
                        selected = tab,
                        avatar = avatar,
                        onSelectTab = actions.onTab,
                        onSearch = actions.onSearch,
                        onProfile = actions.onProfile,
                    )
                }
            }
            SpotlightPanel(
                spotlight = shown,
                heroMode = heroMode,
                hero = hero,
                heroPlay = heroPlay,
                actions = actions,
                memory = memory,
                modifier = Modifier.fillMaxWidth().height(spotHeight),
            )

            when {
                state.rows.isEmpty() && state.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        EmptyState("Setting the table", "Picking out what to watch…", busy = true)
                    }
                state.rows.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        EmptyState(
                            if (libraryStatus != null) "Almost there" else "Nothing here yet",
                            libraryStatus ?: state.note ?: "This account's catalogue has no titles for this page.",
                            busy = libraryStatus != null,
                        )
                    }
                else -> PivotScroll(offset = 26.dp) {
                    LazyColumn(
                        state = memory.list,
                        contentPadding = PaddingValues(bottom = 320.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            // Where focus is is remembered from the cards themselves, not from here:
                            // leaving the page clears focus, which would read as "left the rows".
                            .onFocusChanged { inRows = it.hasFocus }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.DirectionUp && focusedRow == 0 -> { wantHero = true; true }
                                    // Back from the rows returns to the top of the page first,
                                    // as a streaming app's home does; Back again leaves.
                                    e.key == Key.Back || e.key == Key.Escape -> { wantHero = true; true }
                                    else -> false
                                }
                            },
                    ) {
                        itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                            FeedRowView(
                                row = row,
                                nowMs = nowMs,
                                onTileClick = { actions.onTileClick(it, row) },
                                onTileFocus = { tile ->
                                    focusedRow = index
                                    memory.inRows = true
                                    // The row as well as the title: the same film can be in
                                    // several rows, and the viewer left from one of them.
                                    memory.lastKey = row.id + "|" + tile.key
                                    actions.onTileFocus(tile)
                                },
                                restoreKey = memory.lastKey?.takeIf { it.startsWith(row.id + "|") }?.substringAfter("|"),
                                restoreRequester = restore,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        if (!state.exhausted) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                                    com.crimson.ui.components.Spinner(size = 26.dp, stroke = 3.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (libraryStatus != null && state.rows.isNotEmpty() && heroMode) {
            StatusPill(libraryStatus, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
}

@Composable
private fun SpotlightPanel(
    spotlight: Spotlight,
    heroMode: Boolean,
    hero: TitleTile?,
    heroPlay: FocusRequester,
    actions: BrowseActions,
    memory: BrowseMemory,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = Crimson.ScreenPadding, end = 360.dp, top = if (heroMode) 14.dp else 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spotlight.isLive) {
                LiveBadge()
                Spacer(Modifier.width(10.dp))
            }
            val overline = spotlight.overline ?: if (heroMode && hero != null) "FEATURED" else null
            if (overline != null) Text(overline, style = CrimsonType.Overline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            spotlight.title,
            style = CrimsonType.Display.copy(fontSize = if (heroMode) 38.sp else 30.sp, lineHeight = if (heroMode) 40.sp else 32.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        MetaLine(
            rating = spotlight.rating,
            year = spotlight.year,
            ageRating = spotlight.ageRating,
            runtime = spotlight.runtime,
            extra = spotlight.genres,
        )
        if (spotlight.isLive && spotlight.liveProgress > 0f) {
            Spacer(Modifier.height(8.dp))
            ProgressLine(spotlight.liveProgress, Modifier.width(260.dp))
        }
        if (!spotlight.description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                spotlight.description,
                style = CrimsonType.Body.copy(color = Color(0xFFD7D7DD)),
                maxLines = if (heroMode) 3 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (heroMode && hero != null) {
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                CrimsonButton(
                    "Play", { actions.onPlay(hero) },
                    icon = CrimsonIcons.Play, style = ButtonStyle.PRIMARY,
                    focusRequester = heroPlay,
                    onFocus = { if (it) { memory.inRows = false; actions.onHeroFocus(hero) } },
                )
                CrimsonButton(
                    "More Info", { actions.onMoreInfo(hero) },
                    icon = CrimsonIcons.Info,
                    onFocus = { if (it) actions.onHeroFocus(hero) },
                )
            }
        }
    }
}

/** For a live channel with no artwork: its logo, large and dim, on a red glow. */
@Composable
private fun LiveBackdrop(logo: String?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Crimson.RedDeep.copy(alpha = 0.55f), Crimson.Background),
                    center = androidx.compose.ui.geometry.Offset(1500f, 200f),
                    radius = 1300f,
                )
            )
    ) {
        if (!logo.isNullOrBlank()) {
            com.crimson.ui.components.LogoImage(
                logo, null,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 90.dp).size(220.dp, 150.dp),
                alpha = 0.35f,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Crimson.SurfaceRaised.copy(alpha = 0.92f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.crimson.ui.components.Spinner(size = 14.dp, stroke = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = CrimsonType.Caption.copy(color = Crimson.TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp))
    }
}

/** For a game: the two teams' colours meeting in the corner, their logos large and dim. */
@Composable
private fun GameBackdrop(event: com.crimson.core.sports.SportsEvent) {
    fun tint(hex: String?): Color = runCatching {
        Color(android.graphics.Color.parseColor("#" + hex!!.take(6)))
    }.getOrDefault(Crimson.RedDeep)
    val away = tint(event.away?.color)
    val home = tint(event.home?.color)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.7f)
                .fillMaxSize(0.8f)
                .background(Brush.linearGradient(listOf(away.copy(alpha = 0.55f), home.copy(alpha = 0.55f))))
        ) {
            Row(
                Modifier.align(Alignment.Center).padding(start = 120.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOfNotNull(event.away?.logoUrl, event.home?.logoUrl).forEachIndexed { i, logo ->
                    if (i > 0) Spacer(Modifier.width(40.dp))
                    com.crimson.ui.components.LogoImage(logo, null, Modifier.size(150.dp), alpha = 0.5f)
                }
            }
            Box(Modifier.fillMaxSize().background(Crimson.ScrimLeft))
            Box(Modifier.fillMaxSize().background(Crimson.ScrimBottom))
        }
    }
}
