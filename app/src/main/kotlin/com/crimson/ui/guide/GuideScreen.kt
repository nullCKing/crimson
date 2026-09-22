package com.crimson.ui.guide

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crimson.core.guide.GuideHit
import com.crimson.core.guide.ProgramSlot
import com.crimson.domain.GuideChannel
import com.crimson.ui.GuideState
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.ClockBox
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroButtonBar
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel
import com.crimson.ui.theme.scanlines
import com.crimson.ui.vod.DialogButton

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
 * The whole guide screen: info panel and preview across the top, the drawn grid below, and the
 * button bar along the bottom.
 *
 * The 40/60 split, the info panel's layout and the preview window's placement follow the DirecTV
 * references. Everything inside [GuideTheme.safeMarginFraction] of the edges, so nothing is lost
 * to overscan on an older set.
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .scanlines(theme),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = (960.dp * theme.safeMarginFraction),
                    vertical = (540.dp * theme.safeMarginFraction),
                )
        ) {
            InfoPanel(
                state = state,
                theme = theme,
                previewContent = previewContent,
                actions = actions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(theme.infoPanelHeight),
            )

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.channels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No channels match the current filter.\nPress MENU to change it.",
                            color = theme.infoDetail,
                            fontSize = theme.detailSize,
                            fontFamily = theme.fontFamily,
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

            Spacer(Modifier.height(6.dp))

            RetroButtonBar(
                theme = theme,
                buttons = listOf(
                    BarButton("EXIT", actions.onBack, key = "◄", keyColor = theme.keyBlue),
                    BarButton("-2 HRS", actions.onPageBack, key = "◄◄", keyColor = theme.keyRed),
                    BarButton("+2 HRS", actions.onPageForward, key = "►►", keyColor = theme.keyRed),
                    BarButton(
                        label = if (channel?.isFavorite == true) "UNSTAR" else "STAR",
                        onClick = actions.onToggleFavorite,
                        key = "▶‖",
                        keyColor = theme.keyYellow,
                        enabled = channel != null,
                    ),
                    BarButton("WATCH", actions.onSelect, key = "OK", keyColor = theme.keyGreen, enabled = channel != null),
                    BarButton("SETTINGS", actions.onOpenSettings, key = "MENU", keyColor = theme.keyBlue),
                ),
                hint = "SCROLL WHEEL CHANGES ROW",
            )
        }

        state.details?.let { slot ->
            ProgramDetailsDialog(
                slot = slot,
                channel = channel,
                theme = theme,
                onDismiss = actions.onDismissDetails,
                onTune = actions.onTuneFromDetails,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The upper section: the highlighted programme's details on the left, the live preview on the
 * right, and the date and clock in the top corner.
 */
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

    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .retroPanel(theme, theme.chromeBar, corner = 4.dp, edge = theme.chromeBarEdge)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "PROGRAM GUIDE",
                    style = theme.captionStyle(theme.highlight).copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "  •  " + state.categoryName.uppercase(),
                    style = theme.captionStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!channel?.logoUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = channel?.logoUrl,
                        contentDescription = channel?.name,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .padding(end = 8.dp),
                    )
                }
                Text(
                    text = slot?.title ?: "—",
                    style = theme.headingStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (channel != null) {
                    Spacer(Modifier.width(10.dp))
                    FavoriteBadge(starred = channel.isFavorite, theme = theme, onToggle = actions.onToggleFavorite)
                }
            }
            Spacer(Modifier.height(4.dp))

            // Channel • rating • start-end, then the description, as in the reference.
            Text(
                text = buildDetailLine(slot, channel),
                color = theme.infoDetail,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(horizontalAlignment = Alignment.End) {
            ClockBox(nowMs = state.nowMs, theme = theme)
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .size(width = theme.previewWidth, height = theme.previewHeight)
                    .retroPanel(theme, theme.panel, corner = 3.dp, edge = theme.chromeBarEdge)
                    // Clicking the preview is "watch this", the same as Select.
                    .tvInteractive(onSelect = actions.onSelect, focusTarget = false)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isFuture) {
                    // The reference replaces the video with this message box when the highlight
                    // is on something that has not started yet.
                    Text(
                        text = "Future program.\nPress SELECT for options.",
                        color = theme.cellText,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    previewContent(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The star beside the title. A mouse target; the remote uses Play/Pause. */
@Composable
private fun FavoriteBadge(starred: Boolean, theme: GuideTheme, onToggle: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .retroPanel(
                theme = theme,
                base = if (hovered) theme.highlight else if (starred) theme.panelSelected else theme.background,
                corner = 3.dp,
                edge = if (hovered) theme.highlightText else theme.panelEdge,
                gloss = starred,
            )
            .tvInteractive(onSelect = onToggle, onHover = { hovered = it }, focusTarget = false)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (starred) "★ FAVORITE" else "☆ STAR",
            color = when {
                hovered -> theme.highlightText
                starred -> theme.highlight
                else -> theme.infoDetail
            },
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun buildDetailLine(slot: ProgramSlot?, channel: GuideChannel?): String {
    if (slot == null) return ""
    val parts = ArrayList<String>(4)
    channel?.let {
        val fav = if (it.isFavorite) " ★" else ""
        parts.add("${it.number} ${it.shortName}$fav")
    }
    slot.rating?.takeIf { it.isNotBlank() }?.let(parts::add)
    parts.add(timeRange(slot))
    val head = parts.joinToString("  •  ")
    val description = slot.description.takeIf { it.isNotBlank() }
    return if (description != null) "$head\n\"$description\"" else head
}

/** Shown when Select lands on a programme that has not started yet. */
@Composable
private fun ProgramDetailsDialog(
    slot: ProgramSlot,
    channel: GuideChannel?,
    theme: GuideTheme,
    onDismiss: () -> Unit,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color(0xAA000000))
            // A click on the scrim closes the dialog, as Back does.
            .tvInteractive(onSelect = onDismiss, focusTarget = false),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(540.dp)
                .retroPanel(theme, theme.panel, corner = 5.dp, edge = theme.highlight, edgeWidth = 2.dp)
                .tvInteractive(onSelect = {}, focusTarget = false)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = slot.title, style = theme.headingStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    channel?.let { "${it.number} ${it.shortName}" },
                    timeRange(slot),
                    slot.rating,
                ).joinToString("  •  "),
                style = theme.labelStyle(theme.highlight),
            )
            if (slot.description.isNotBlank()) {
                Text(
                    text = slot.description,
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButton(label = "▶ WATCH CHANNEL NOW", primary = true, theme = theme, onClick = onTune, focusTarget = false)
                DialogButton(label = "CLOSE", primary = false, theme = theme, onClick = onDismiss, focusTarget = false)
                Spacer(Modifier.weight(1f))
                Text(text = "SELECT watches  •  BACK closes", style = theme.captionStyle())
            }
        }
    }
}
