package com.crimson.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.crimson.core.guide.GuideHit
import com.crimson.core.guide.ProgramSlot
import com.crimson.domain.GuideChannel
import com.crimson.ui.GuideState
import com.crimson.ui.components.ButtonStyle
import com.crimson.ui.components.CrimsonButton
import com.crimson.ui.components.LiveBadge
import com.crimson.ui.components.OutlineBadge
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.ProgressLine
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.live.timeRange
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import com.crimson.ui.theme.GuideTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the guide screen can ask the app to do. Every entry has a remote key and a mouse target. */
data class GuideActions(
    val onHover: (GuideHit) -> Unit = {},
    val onClick: (GuideHit) -> Unit = {},
    val onWheel: (rows: Int) -> Unit = {},
    val onSelect: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onPageBack: () -> Unit = {},
    val onPageForward: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onDismissDetails: () -> Unit = {},
    val onTuneFromDetails: () -> Unit = {},
)

/**
 * The TV guide: the highlighted programme and a live preview across the top, the drawn grid
 * below. The grid itself is RetroGuide's — one Canvas, driven by `GuideNavigator` — in Crimson's
 * colours; only the chrome around it is new.
 */
@Composable
fun GuideScreen(
    state: GuideState,
    theme: GuideTheme,
    previewContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    actions: GuideActions = GuideActions(),
) {
    val channel = state.selectedChannel
    PageBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 22.dp),
        ) {
            InfoPanel(state, theme, previewContent, actions, Modifier.fillMaxWidth().height(theme.infoPanelHeight))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.channels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No channels in this lineup.\nChange your channel filters in Settings.",
                            style = CrimsonType.Body,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    GuideGrid(
                        channels = state.channels,
                        programsByKey = state.programsByKey,
                        window = state.cursor.window,
                        firstVisibleRow = state.cursor.firstVisibleRow,
                        selectedRow = state.cursor.channelIndex,
                        selectedProgramId = state.selected?.id,
                        nowMs = state.nowMs,
                        theme = theme,
                        modifier = Modifier.fillMaxSize(),
                        onHover = actions.onHover,
                        onClick = actions.onClick,
                        onWheel = actions.onWheel,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HintKey(CrimsonIcons.Rewind, "−2 hrs", actions.onPageBack)
                HintKey(CrimsonIcons.Forward, "+2 hrs", actions.onPageForward)
                HintKey(
                    if (channel?.isFavorite == true) CrimsonIcons.Star else CrimsonIcons.StarOutline,
                    if (channel?.isFavorite == true) "Favorite" else "Add to favorites",
                    actions.onToggleFavorite,
                )
                HintKey(CrimsonIcons.Play, "Watch", actions.onSelect)
                Spacer(Modifier.weight(1f))
                Text("Play/Pause stars a channel  ·  Menu opens settings", style = CrimsonType.Caption.copy(fontSize = 10.sp))
            }
        }

        state.details?.let { slot ->
            ProgramDetailsDialog(slot, channel, actions.onDismissDetails, actions.onTuneFromDetails, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun InfoPanel(
    state: GuideState,
    theme: GuideTheme,
    previewContent: @Composable (Modifier) -> Unit,
    actions: GuideActions,
    modifier: Modifier = Modifier,
) {
    val slot = state.selected
    val channel = state.selectedChannel
    val isFuture = slot != null && slot.startMs > state.nowMs
    val isNow = slot != null && !isFuture && slot.endMs > state.nowMs

    Row(modifier) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TV GUIDE", style = CrimsonType.Overline)
                Spacer(Modifier.width(10.dp))
                Text(state.categoryName, style = CrimsonType.Label.copy(color = Crimson.TextSecondary), maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(
                    SimpleDateFormat("EEE MMM d  ·  h:mm a", Locale.getDefault()).format(Date(state.nowMs)),
                    style = CrimsonType.Label.copy(color = Crimson.TextSecondary),
                )
                Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!channel?.logoUrl.isNullOrBlank()) {
                    Box(
                        Modifier.size(54.dp, 34.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.crimson.ui.components.ChannelLogo(channel?.logoUrl, channel?.shortName.orEmpty(), Modifier.padding(4.dp).fillMaxSize(), textSize = 10.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        channel?.let { (if (it.number > 0) "${it.number}  " else "") + it.name }.orEmpty(),
                        style = CrimsonType.Caption.copy(color = Crimson.TextSecondary, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text(
                        slot?.title ?: "—",
                        style = CrimsonType.Headline.copy(fontSize = 24.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (channel != null) {
                    Spacer(Modifier.width(10.dp))
                    FavoriteBadge(channel.isFavorite, actions.onToggleFavorite)
                    Spacer(Modifier.width(18.dp))
                }
            }
            if (slot != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNow) {
                        LiveBadge(small = true)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(timeRange(slot.startMs, slot.endMs), style = CrimsonType.Label.copy(color = Crimson.TextSecondary))
                    slot.rating?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(10.dp))
                        OutlineBadge(it)
                    }
                    if (isNow) {
                        Spacer(Modifier.width(12.dp))
                        ProgressLine(
                            ((state.nowMs - slot.startMs).toFloat() / (slot.endMs - slot.startMs).coerceAtLeast(1)).coerceIn(0f, 1f),
                            Modifier.width(140.dp),
                        )
                    }
                }
                if (slot.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        slot.description,
                        style = CrimsonType.Body,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 18.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(width = theme.previewWidth, height = theme.previewHeight)
                .background(Crimson.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, Crimson.Stroke, RoundedCornerShape(10.dp))
                .tvInteractive(onSelect = actions.onSelect, focusTarget = false),
            contentAlignment = Alignment.Center,
        ) {
            if (isFuture) {
                Text(
                    "Starts at ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(slot!!.startMs))}\nSelect for details",
                    style = CrimsonType.Body,
                    textAlign = TextAlign.Center,
                )
            } else {
                previewContent(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FavoriteBadge(starred: Boolean, onToggle: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .background(if (hovered) Color.White else Crimson.ControlFill, RoundedCornerShape(50))
            .tvInteractive(onSelect = onToggle, onHover = { hovered = it }, focusTarget = false)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (starred) CrimsonIcons.Star else CrimsonIcons.StarOutline, null,
            tint = if (hovered) Color.Black else if (starred) Crimson.Gold else Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(if (starred) "Favorite" else "Favorite", style = CrimsonType.Caption.copy(color = if (hovered) Color.Black else Color.White, fontWeight = FontWeight.Bold))
    }
}

/** A key hint that is also a mouse target. */
@Composable
private fun HintKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .background(if (hovered) Color.White else Crimson.Surface, RoundedCornerShape(50))
            .tvInteractive(onSelect = onClick, onHover = { hovered = it }, focusTarget = false)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (hovered) Color.Black else Crimson.TextSecondary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = CrimsonType.Caption.copy(color = if (hovered) Color.Black else Crimson.TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 10.sp))
    }
}

/** Shown when Select lands on a programme that has not started yet. */
@Composable
private fun ProgramDetailsDialog(
    slot: ProgramSlot,
    channel: GuideChannel?,
    onDismiss: () -> Unit,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.9f))
            .tvInteractive(onSelect = onDismiss, focusTarget = false),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(540.dp)
                .background(Crimson.SurfaceRaised, RoundedCornerShape(12.dp))
                .tvInteractive(onSelect = {}, focusTarget = false)
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("COMING UP", style = CrimsonType.Overline)
            Text(slot.title, style = CrimsonType.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(channel?.name, timeRange(slot.startMs, slot.endMs), slot.rating).joinToString("  ·  "),
                style = CrimsonType.Label.copy(color = Crimson.TextSecondary),
            )
            if (slot.description.isNotBlank()) {
                Text(slot.description, style = CrimsonType.Body, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CrimsonButton("Watch channel now", onTune, icon = CrimsonIcons.Play, style = ButtonStyle.PRIMARY)
                CrimsonButton("Close", onDismiss)
                Spacer(Modifier.weight(1f))
                Text("OK watches · BACK closes", style = CrimsonType.Caption)
            }
        }
    }
}
