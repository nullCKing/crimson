package com.crimson.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crimson.ui.theme.Crimson

/**
 * One horizontal row: a heading and its cards.
 *
 * The row keeps its focused card pinned at the left edge ([PivotScroll]), and hands every focus
 * change to [onTileFocus] so the page can put that title's artwork and synopsis in its spotlight.
 * [restoreKey] names the card that should take focus when the page is recomposed on return from
 * a title's details, so the viewer lands where they left.
 */
@Composable
fun FeedRowView(
    row: FeedRow,
    nowMs: Long,
    onTileClick: (Tile) -> Unit,
    modifier: Modifier = Modifier,
    onTileFocus: (Tile) -> Unit = {},
    restoreKey: String? = null,
    restoreRequester: FocusRequester? = null,
    startPadding: Dp = Crimson.ScreenPadding,
    showHeading: Boolean = true,
) {
    if (row.tiles.isEmpty()) return
    val state = rememberLazyListState()
    Column(modifier.fillMaxWidth()) {
        if (showHeading) {
            RowHeading(
                title = row.title,
                subtitle = row.subtitle,
                modifier = Modifier.padding(start = startPadding, bottom = 2.dp),
            )
        }
        // A Top 10 card's rank sits to the left of its poster, and the poster is what takes
        // focus, so those rows pin further right to keep the numeral on screen.
        PivotScroll(offset = if (row.kind == RowKind.TOP10) startPadding + 64.dp else startPadding) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = startPadding, end = 320.dp, top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (row.kind == RowKind.TOP10) 6.dp else 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(row.tiles, key = { _, t -> t.key }) { index, tile ->
                    TileCard(
                        tile = tile,
                        nowMs = nowMs,
                        onClick = { onTileClick(tile) },
                        rank = if (row.kind == RowKind.TOP10) index + 1 else null,
                        onFocus = { if (it) onTileFocus(tile) },
                        focusRequester = if (restoreKey != null && restoreKey == tile.key) restoreRequester else null,
                    )
                }
            }
        }
    }
}

/** Height a row of this kind occupies, heading included, for lists that need to know. */
fun rowHeight(kind: RowKind): Dp = when (kind) {
    RowKind.POSTER, RowKind.TOP10 -> CardSize.PosterHeight
    RowKind.CONTINUE -> CardSize.WideHeight
    RowKind.LIVE -> CardSize.LiveHeight
    RowKind.SPORTS -> CardSize.GameHeight
} + 20.dp + 22.dp

@Composable
fun VerticalGap(height: Dp) = Spacer(Modifier.height(height))
