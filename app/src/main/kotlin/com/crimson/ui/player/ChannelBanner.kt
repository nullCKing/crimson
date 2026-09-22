package com.crimson.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crimson.core.guide.ProgramSlot
import com.crimson.ui.guide.timeRange
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel

/**
 * The cable-style banner that appears at the bottom of the screen on every channel change.
 *
 * Channel number and name, what is on now with a progress bar, and what is on next — the same
 * information a cable box has shown for thirty years, styled from the guide's theme so the two
 * screens look like one product.
 */
@Composable
fun ChannelBanner(
    number: Int,
    name: String,
    now: ProgramSlot?,
    next: ProgramSlot?,
    nowMs: Long,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    isFavorite: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(theme.bannerHeight)
            .retroPanel(theme, theme.bannerBackground, corner = 5.dp, edge = theme.chromeBarEdge)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {

            // Channel number & logo
            // Wide enough for a five-digit number beside a logo: numbering overflows past 10000
            // on a large catalogue.
            Row(
                modifier = Modifier.width(170.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = name,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    text = number.toString(),
                    style = theme.headingStyle(theme.channelNumber).copy(fontSize = theme.titleSize * 1.15f),
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFavorite) {
                        Text(
                            text = "★ ",
                            color = theme.highlight,
                            fontSize = theme.detailSize,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = name,
                        style = theme.labelStyle(theme.infoTitle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = now?.title ?: "No Information",
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))

                ProgressBar(now = now, nowMs = nowMs, theme = theme)

                Spacer(Modifier.height(6.dp))
                Text(
                    text = next?.let { "Next: ${it.title}  •  ${timeRange(it)}" }
                        ?: "",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = now?.let { timeRange(it) } ?: "",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

/** How far through the current programme we are. Empty when there is no guide data. */
@Composable
private fun ProgressBar(now: ProgramSlot?, nowMs: Long, theme: GuideTheme) {
    val fraction = if (now == null || now.durationMs <= 0) {
        0f
    } else {
        ((nowMs - now.startMs).toFloat() / now.durationMs.toFloat()).coerceIn(0f, 1f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .retroPanel(theme, theme.bannerProgressTrack, corner = 2.dp, edge = null)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .retroPanel(theme, theme.bannerProgress, corner = 2.dp, edge = null, gloss = true)
            )
        }
    }
}
