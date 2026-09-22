package com.crimson.ui.details

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.catalog.TitleKind
import com.crimson.ui.components.Backdrop
import com.crimson.ui.components.ButtonStyle
import com.crimson.ui.components.Chip
import com.crimson.ui.components.CrimsonButton
import com.crimson.ui.components.EpisodeCard
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.FeedRowView
import com.crimson.ui.components.MetaLine
import com.crimson.ui.components.PivotScroll
import com.crimson.ui.components.ProgressLine
import com.crimson.ui.components.RowHeading
import com.crimson.ui.components.RowKind
import com.crimson.ui.components.Spinner
import com.crimson.ui.components.TitleTile
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos

class DetailsActions(
    val onPlay: () -> Unit,
    val onRestart: () -> Unit,
    val onToggleList: () -> Unit,
    val onSeason: (Int) -> Unit,
    val onEpisode: (EpisodeUi) -> Unit,
    val onOpenTitle: (TitleTile) -> Unit,
)

/** A title's page: the billboard, its facts and synopsis, episodes by season, and more like it. */
@Composable
fun DetailsScreen(state: DetailsState, nowMs: Long, actions: DetailsActions) {
    val play = remember { FocusRequester() }
    LaunchedEffect(state.id, state.loading) {
        withFrameNanos { }
        runCatching { play.requestFocus() }
    }
    val list = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Crimson.Background)) {
        Backdrop(url = state.backdrop, fallback = state.poster, widthFraction = 0.74f, heightFraction = 0.9f)
        // No pivot here: the Play button sits low in a tall header, and pinning it to the top
        // would scroll the title away. The lists reveal the minimum, as a document page does.
        run {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = 200.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") { Header(state, play, actions) }
                if (state.kind == TitleKind.SERIES && state.seasons.isNotEmpty()) {
                    item(key = "seasons") { Seasons(state, actions) }
                    item(key = "episodes") { Episodes(state, actions) }
                }
                if (state.moreLikeThis.isNotEmpty()) {
                    item(key = "more") {
                        FeedRowView(
                            row = FeedRow("more_like_this", "More Like This", RowKind.POSTER, state.moreLikeThis),
                            nowMs = nowMs,
                            onTileClick = { (it as? TitleTile)?.let(actions.onOpenTitle) },
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: DetailsState, play: FocusRequester, actions: DetailsActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(404.dp)
            .padding(start = Crimson.ScreenPadding, end = 330.dp, top = 52.dp),
    ) {
        Text(if (state.kind == TitleKind.SERIES) "SERIES" else "FILM", style = CrimsonType.Overline)
        Spacer(Modifier.height(8.dp))
        Text(
            state.name.ifBlank { " " },
            style = CrimsonType.Display.copy(fontSize = 42.sp, lineHeight = 44.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        MetaLine(
            rating = state.rating,
            year = state.year,
            ageRating = state.ageRating,
            runtime = state.runtime ?: state.seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 Season" else "$it Seasons" },
            extra = state.genres,
        )
        Spacer(Modifier.height(12.dp))
        if (state.loading && state.plot == null) {
            Spinner(size = 26.dp, stroke = 3.dp)
        } else {
            Text(
                state.plot ?: state.error ?: "",
                style = CrimsonType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFDCDCE2)),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Credit("Starring", state.cast)
        Credit(if (state.kind == TitleKind.SERIES) "Created by" else "Director", state.director)
        Spacer(Modifier.weight(1f))
        if (state.play.fraction > 0f) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                ProgressLine(state.play.fraction, Modifier.width(180.dp))
                Spacer(Modifier.width(10.dp))
                Text(remainingLabel(state), style = CrimsonType.Caption)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            CrimsonButton(state.play.label, actions.onPlay, icon = CrimsonIcons.Play, style = ButtonStyle.PRIMARY, focusRequester = play)
            if (state.play.fraction > 0f) CrimsonButton("Start Over", actions.onRestart, icon = CrimsonIcons.Replay)
            CrimsonButton(
                if (state.inMyList) "In My List" else "My List",
                actions.onToggleList,
                icon = if (state.inMyList) CrimsonIcons.Check else CrimsonIcons.Add,
            )
        }
    }
}

private fun remainingLabel(state: DetailsState): String {
    val ep = state.play.episode
    return when {
        ep != null -> "S${ep.season}:E${ep.number} · ${ep.title}"
        else -> "${(state.play.fraction * 100).toInt()}% watched"
    }
}

@Composable
private fun Credit(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Crimson.TextTertiary)) { append("$label: ") }
            withStyle(SpanStyle(color = Crimson.TextSecondary)) { append(value) }
        },
        style = CrimsonType.Caption.copy(fontSize = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Seasons(state: DetailsState, actions: DetailsActions) {
    Column(Modifier.padding(top = 6.dp)) {
        RowHeading("Episodes", Modifier.padding(start = Crimson.ScreenPadding, bottom = 10.dp), subtitle = state.name)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Crimson.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.seasons) { season ->
                Chip("Season $season", selected = season == state.season, onClick = { actions.onSeason(season) }, onFocus = { if (it) actions.onSeason(season) })
            }
        }
    }
}

@Composable
private fun Episodes(state: DetailsState, actions: DetailsActions) {
    if (state.episodes.isEmpty()) {
        Text(
            "No episodes listed for this season.",
            style = CrimsonType.Body,
            modifier = Modifier.padding(start = Crimson.ScreenPadding, top = 16.dp),
        )
        return
    }
    PivotScroll(offset = Crimson.ScreenPadding) {
        LazyRow(
            contentPadding = PaddingValues(start = Crimson.ScreenPadding, end = 300.dp, top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(state.episodes, key = { it.id }) { ep ->
                EpisodeCard(
                    number = ep.number,
                    title = ep.title,
                    image = ep.image,
                    fallbackImage = state.backdrop,
                    runtime = ep.runtime,
                    plot = ep.plot,
                    fraction = ep.fraction,
                    onClick = { actions.onEpisode(ep) },
                )
            }
        }
    }
}
