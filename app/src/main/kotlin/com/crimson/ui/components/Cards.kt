package com.crimson.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.catalog.TitleKind
import com.crimson.core.sports.Competitor
import com.crimson.core.sports.EventState
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CardSize {
    val PosterWidth = 112.dp
    val PosterHeight = 168.dp
    val WideWidth = 224.dp
    val WideHeight = 126.dp
    val LiveWidth = 212.dp
    val LiveHeight = 119.dp
    val GameWidth = 252.dp
    val GameHeight = 128.dp
}

private val CardCorner = 6.dp
private val CardShape = RoundedCornerShape(CardCorner)

/** Shared interaction for every card: focus state, select, and the white-ring lift. */
@Composable
private fun Modifier.cardInteraction(
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit,
    focusRequester: FocusRequester?,
    focused: Boolean,
    setFocused: (Boolean) -> Unit,
    scale: Float = 1.08f,
): Modifier = this
    .cardFocus(focused, CardShape, scale)
    .tvInteractive(
        onSelect = onClick,
        onFocus = { setFocused(it); onFocus(it) },
        focusRequester = focusRequester,
    )

/** A 2:3 poster. With no art, the title is set in type on a dark gradient instead. */
@Composable
fun PosterCard(
    tile: TitleTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardSize.PosterWidth,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .cardInteraction(onClick, onFocus, focusRequester, focused, { focused = it })
            .background(Brush.verticalGradient(listOf(Crimson.SurfaceHigh, Crimson.Surface)), CardShape),
    ) {
        PosterFallback(tile.name, tile.kind)
        if (!tile.poster.isNullOrBlank()) {
            RoundedImage(tile.poster, tile.name, CardCorner, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PosterFallback(name: String, kind: TitleKind) {
    Column(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (kind == TitleKind.SERIES) "SERIES" else "FILM", style = CrimsonType.Overline.copy(fontSize = 8.sp))
        Text(
            name,
            style = CrimsonType.Label.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 15.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A Top 10 card: the rank set huge in outline behind the poster's left edge, the way the
 * charts on a streaming home page look.
 */
@Composable
fun Top10Card(
    rank: Int,
    tile: TitleTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val numberStyle = CrimsonType.Display.copy(
        fontSize = 132.sp,
        lineHeight = 132.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-10).sp,
    )
    Box(modifier = modifier.width(CardSize.PosterWidth + 70.dp).height(CardSize.PosterHeight)) {
        Box(Modifier.align(Alignment.BottomStart).offset(y = 22.dp)) {
            Text(rank.toString(), style = numberStyle.copy(color = Crimson.Background))
            Text(rank.toString(), style = numberStyle.copy(color = Color(0xFF9A9AA3), drawStyle = Stroke(width = 5f)))
        }
        PosterCard(
            tile = tile,
            onClick = onClick,
            onFocus = onFocus,
            focusRequester = focusRequester,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** A 16:9 card for something part-watched, with its progress. */
@Composable
fun ContinueCard(
    tile: ContinueTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(CardSize.WideWidth)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .cardInteraction(onClick, onFocus, focusRequester, focused, { focused = it }, scale = 1.06f)
                .background(Crimson.SurfaceHigh, CardShape),
        ) {
            val image = tile.backdrop ?: tile.image
            if (!image.isNullOrBlank()) {
                RoundedImage(image, tile.name, CardCorner, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)), CardShape))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .background(Color.Black.copy(alpha = if (focused) 0.2f else 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CrimsonIcons.Play, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(tile.name, style = CrimsonType.Label.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!tile.subtitle.isNullOrBlank()) {
                    Text(tile.subtitle, style = CrimsonType.Caption.copy(color = Crimson.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                ProgressLine(tile.fraction)
            }
        }
    }
}

/**
 * A live channel: its logo on a dark plate, and underneath, what it is showing now and how far
 * through it is.
 */
@Composable
fun LiveCard(
    tile: ChannelTile,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardSize.LiveWidth,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .cardInteraction(onClick, onFocus, focusRequester, focused, { focused = it }, scale = 1.07f)
            .background(Brush.linearGradient(
                    if (focused) listOf(Color(0xFF2E2E36), Color(0xFF17171B))
                    else listOf(Crimson.SurfaceHigh, Crimson.Surface)
                ), CardShape),
    ) {
        ChannelLogo(
            url = tile.logo,
            name = tile.name,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp, start = 10.dp, end = 10.dp)
                .fillMaxWidth(0.7f)
                .height(width * 0.22f),
        )
        if (tile.number > 0) {
            Text(
                tile.number.toString(),
                style = CrimsonType.Caption.copy(fontSize = 10.sp),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        if (tile.isFavorite) {
            Icon(
                CrimsonIcons.Star, null, tint = Crimson.Gold,
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(13.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (tile.nowTitle != null) {
                Text(
                    tile.nowTitle,
                    style = CrimsonType.Label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                ProgressLine(tile.progress(nowMs), height = 2.dp)
            } else {
                Text(
                    tile.category?.let { com.crimson.core.catalog.CategoryTitles.clean(it) } ?: "Live channel",
                    style = CrimsonType.Caption.copy(fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A game card: both teams, the score or the start time, and the network carrying it. */
@Composable
fun GameCard(
    tile: GameTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val event = tile.event
    Column(
        modifier = modifier
            .width(CardSize.GameWidth)
            .height(CardSize.GameHeight)
            .cardInteraction(onClick, onFocus, focusRequester, focused, { focused = it }, scale = 1.06f)
            .background(Brush.linearGradient(listOf(teamTint(event.away), Crimson.Surface, teamTint(event.home))), CardShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.league.label + (event.division?.let { " · $it" } ?: ""),
                style = CrimsonType.Overline.copy(color = Crimson.TextSecondary, fontSize = 9.sp),
            )
            Spacer(Modifier.weight(1f))
            when (event.state) {
                EventState.LIVE -> {
                    Text(event.statusText, style = CrimsonType.Caption.copy(color = Crimson.TextPrimary, fontSize = 10.sp), maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    LiveBadge(small = true)
                }
                EventState.UPCOMING -> Text(upcomingLabel(event.startMs), style = CrimsonType.Caption.copy(color = Crimson.TextPrimary, fontSize = 10.sp), maxLines = 1)
                EventState.FINAL -> Text(event.statusText.ifBlank { "Final" }, style = CrimsonType.Caption.copy(fontSize = 10.sp), maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        TeamLine(event.away, event.state)
        Spacer(Modifier.height(4.dp))
        TeamLine(event.home, event.state)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CrimsonIcons.Tv, null, tint = if (focused) Color.White else Crimson.TextTertiary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                event.networks.take(2).joinToString(" · ").ifBlank { "Find channel" },
                style = CrimsonType.Caption.copy(color = if (focused) Color.White else Crimson.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TeamLine(team: Competitor?, state: EventState) {
    if (team == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (!team.logoUrl.isNullOrBlank()) {
                LogoImage(team.logoUrl, team.name, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(8.dp))
        val dim = state == EventState.FINAL && !team.isWinner
        team.rank?.let { rank ->
            Text(
                "$rank ",
                style = CrimsonType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Crimson.Gold),
            )
        }
        Text(
            team.shortName,
            style = CrimsonType.Label.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (dim) Crimson.TextTertiary else Crimson.TextPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val record = team.record
        val score = team.score
        if (!record.isNullOrBlank() && state == EventState.UPCOMING) {
            Text(record, style = CrimsonType.Caption.copy(fontSize = 10.sp))
        }
        if (state != EventState.UPCOMING && !score.isNullOrBlank()) {
            Text(
                score,
                style = CrimsonType.Title.copy(fontSize = 17.sp, fontWeight = FontWeight.Black, color = if (dim) Crimson.TextTertiary else Crimson.TextPrimary),
            )
        }
    }
}

private fun teamTint(team: Competitor?): Color {
    val hex = team?.color?.takeIf { it.length == 6 } ?: return Crimson.SurfaceRaised
    return runCatching {
        val c = Color(android.graphics.Color.parseColor("#$hex"))
        androidx.compose.ui.graphics.lerp(Crimson.Surface, c, 0.28f)
    }.getOrDefault(Crimson.SurfaceRaised)
}

/** "Today 8:20 PM", "Tomorrow 1:00 PM", "Sat 3:30 PM". */
fun upcomingLabel(startMs: Long, now: Long = System.currentTimeMillis()): String {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(startMs))
    val dayMs = 86_400_000L
    val zone = java.util.TimeZone.getDefault()
    val today = Math.floorDiv(now + zone.getOffset(now), dayMs)
    val day = Math.floorDiv(startMs + zone.getOffset(startMs), dayMs)
    return when (day - today) {
        0L -> "Today $time"
        1L -> "Tomorrow $time"
        else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(startMs)) + " $time"
    }
}

/** An episode in a season: its still, number and title, runtime, synopsis and progress. */
@Composable
fun EpisodeCard(
    number: Int,
    title: String,
    image: String?,
    fallbackImage: String?,
    runtime: String?,
    plot: String?,
    fraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(CardSize.WideWidth)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .cardInteraction(onClick, onFocus, focusRequester, focused, { focused = it }, scale = 1.06f)
                .background(Crimson.SurfaceHigh, CardShape),
        ) {
            val art = image ?: fallbackImage
            if (!art.isNullOrBlank()) {
                RoundedImage(art, title, CardCorner, Modifier.fillMaxSize())
            }
            if (focused) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f), CardShape))
                Icon(CrimsonIcons.Play, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
            if (fraction > 0f) {
                ProgressLine(fraction, Modifier.align(Alignment.BottomStart).padding(8.dp), height = 3.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$number. $title",
                style = CrimsonType.Label.copy(fontWeight = FontWeight.Bold, color = if (focused) Color.White else Crimson.TextPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!runtime.isNullOrBlank()) Text(runtime, style = CrimsonType.Caption)
        }
        if (!plot.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(plot, style = CrimsonType.Caption.copy(color = Crimson.TextSecondary, lineHeight = 14.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Draws whatever card a tile calls for. */
@Composable
fun TileCard(
    tile: Tile,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
    /** Overrides the card's natural width, for grids that size their cells. */
    width: Dp? = null,
) {
    when (tile) {
        is TitleTile ->
            if (rank != null) Top10Card(rank, tile, onClick, modifier, onFocus, focusRequester)
            else PosterCard(tile, onClick, modifier, width = width ?: CardSize.PosterWidth, onFocus = onFocus, focusRequester = focusRequester)
        is ContinueTile -> ContinueCard(tile, onClick, modifier, onFocus, focusRequester)
        is ChannelTile -> LiveCard(tile, nowMs, onClick, modifier, width = width ?: CardSize.LiveWidth, onFocus = onFocus, focusRequester = focusRequester)
        is GameTile -> GameCard(tile, onClick, modifier, onFocus, focusRequester)
    }
}

/**
 * A channel's logo, or its name set in type when there is no logo or it fails to load — which,
 * on a provider's list, is often. A blank tile is the one thing a channel card must never be.
 */
@Composable
fun ChannelLogo(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    textSize: androidx.compose.ui.unit.TextUnit = 17.sp,
) {
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) {
            Text(
                name,
                style = CrimsonType.Title.copy(fontSize = textSize, fontWeight = FontWeight.Black, lineHeight = textSize * 1.1f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            LogoImage(url, name, Modifier.fillMaxSize(), onError = { failed = true })
        }
    }
}
