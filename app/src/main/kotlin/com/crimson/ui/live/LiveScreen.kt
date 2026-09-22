package com.crimson.ui.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import coil.compose.AsyncImage
import com.crimson.ui.components.Chip
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.EmptyState
import com.crimson.ui.components.FeedRowView
import com.crimson.ui.components.LiveBadge
import com.crimson.ui.components.MainTab
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.PivotScroll
import com.crimson.ui.components.ProgressLine
import com.crimson.ui.components.Tile
import com.crimson.ui.components.TopNav
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveActions(
    val onTab: (MainTab) -> Unit,
    val onSearch: () -> Unit,
    val onProfile: () -> Unit,
    val onCollection: (LiveCollection) -> Unit,
    val onGuide: () -> Unit,
    val onDirectory: () -> Unit,
    val onFocusChannel: (ChannelTile) -> Unit,
    val onOpen: (Tile, List<Tile>) -> Unit,
    val onRowVisible: (String) -> Unit,
)

/**
 * Live TV: a lineup to browse, in the manner of a live streaming service rather than a cable
 * box. The channel under the cursor fills the top of the page — what it is showing, how far in,
 * what is next — and after a moment starts playing in the preview window beside it, on the same
 * single player the full-screen view uses, so Select continues the stream without a re-tune.
 */
@Composable
fun LiveScreen(
    state: LiveState,
    nowMs: Long,
    avatar: Int,
    preview: @Composable (Modifier) -> Unit,
    actions: LiveActions,
) {
    var inRows by remember { mutableStateOf(false) }
    val chipFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var focusedRow by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { chipFocus.requestFocus() }
    }
    LaunchedEffect(list, state.rows.size) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key } }
            .distinctUntilChanged()
            .collect { keys -> keys.forEach { (it as? String)?.let(actions.onRowVisible) } }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = !inRows, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
                TopNav(MainTab.LIVE, avatar, actions.onTab, actions.onSearch, actions.onProfile)
            }
            LiveHero(state, nowMs, preview, Modifier.fillMaxWidth().height(if (inRows) 214.dp else 196.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = Crimson.ScreenPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(LiveCollection.entries) { index, c ->
                    Chip(
                        text = c.label,
                        selected = state.collection == c,
                        onClick = { actions.onCollection(c) },
                        focusRequester = if (index == 0) chipFocus else null,
                    )
                }
                item {
                    Chip("TV Guide", selected = false, onClick = actions.onGuide, icon = CrimsonIcons.Guide)
                }
                item {
                    Chip("Browse by Country", selected = false, onClick = actions.onDirectory, icon = CrimsonIcons.Globe)
                }
            }
            if (state.rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        if (state.loading) "Tuning in" else "No channels here",
                        if (state.loading) "Finding what's on…"
                        else if (state.collection == LiveCollection.FAVORITES) "Star a channel in the guide, or with F while watching, and it appears here."
                        else "This account carries none of this lineup's channels.",
                        busy = state.loading,
                    )
                }
            } else {
                PivotScroll(offset = 24.dp) {
                    LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(top = 4.dp, bottom = 280.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { inRows = it.hasFocus }
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && focusedRow == 0) {
                                    runCatching { chipFocus.requestFocus() }
                                    true
                                } else false
                            },
                    ) {
                        itemsIndexed(state.rows, key = { _, r -> r.id }) { index, row ->
                            FeedRowView(
                                row = row,
                                nowMs = nowMs,
                                onTileClick = { actions.onOpen(it, row.tiles) },
                                onTileFocus = {
                                    focusedRow = index
                                    (it as? ChannelTile)?.let(actions.onFocusChannel)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHero(state: LiveState, nowMs: Long, preview: @Composable (Modifier) -> Unit, modifier: Modifier) {
    val ch = state.focused
    Row(modifier.padding(start = Crimson.ScreenPadding, end = Crimson.ScreenPadding, top = 8.dp, bottom = 8.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            if (ch == null) {
                Text("LIVE TV", style = CrimsonType.Overline)
                Spacer(Modifier.height(6.dp))
                Text(state.collection.label, style = CrimsonType.Display.copy(fontSize = 34.sp))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Hundreds of channels, live. Pick a lineup, move onto a channel to preview it, and press Select to watch.",
                    style = CrimsonType.Body,
                    maxLines = 3,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (ch.number > 0) "${ch.number}  " else "") + ch.name.uppercase(),
                    style = CrimsonType.Overline.copy(color = Crimson.TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                ch.nowTitle ?: ch.name,
                style = CrimsonType.Display.copy(fontSize = 30.sp, lineHeight = 32.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (ch.nowEndMs > 0L) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeRange(ch.nowStartMs, ch.nowEndMs), style = CrimsonType.Label.copy(color = Crimson.TextSecondary))
                    Spacer(Modifier.width(12.dp))
                    ProgressLine(ch.progress(nowMs), Modifier.width(160.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${((ch.nowEndMs - nowMs) / 60_000L).coerceAtLeast(0)} min left", style = CrimsonType.Caption)
                }
            }
            if (!ch.nowDescription.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(ch.nowDescription, style = CrimsonType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            state.focusedNext?.let { next ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Up next  ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(next.startMs))}  ${next.title}",
                    style = CrimsonType.Caption.copy(color = Crimson.TextTertiary, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Crimson.SurfaceHigh, Crimson.Surface)))
                .border(1.dp, if (state.previewing) Crimson.Red.copy(alpha = 0.7f) else Crimson.Stroke, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (ch != null && !ch.logo.isNullOrBlank()) {
                AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(120.dp, 70.dp), alpha = 0.8f)
            }
            if (state.previewing) preview(Modifier.fillMaxSize())
            if (ch != null && !state.previewing) {
                Text(
                    "Preview starts in a moment",
                    style = CrimsonType.Caption,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                )
            }
            if (state.previewing) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(10.dp)
                ) {
                    Text("Press Select to watch full screen", style = CrimsonType.Caption.copy(color = Color.White))
                }
            }
        }
    }
}

fun timeRange(start: Long, end: Long): String {
    val f = SimpleDateFormat("h:mm", Locale.getDefault())
    val a = SimpleDateFormat("h:mm a", Locale.getDefault())
    return "${f.format(Date(start))} – ${a.format(Date(end))}"
}
