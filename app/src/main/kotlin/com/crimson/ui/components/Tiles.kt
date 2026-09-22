package com.crimson.ui.components

import androidx.compose.runtime.Immutable
import com.crimson.core.catalog.TitleKind
import com.crimson.core.sports.SportsEvent

/**
 * Everything a row can hold.
 *
 * Rows mix kinds — search results hold channels and films, Home holds games and series — so a row
 * is a list of [Tile] and each kind draws its own card.
 */
@Immutable
sealed interface Tile {
    /** Stable across reloads, for list keys and focus restoration. */
    val key: String
}

/** A film or a series. */
@Immutable
data class TitleTile(
    val kind: TitleKind,
    val id: Long,
    val name: String,
    val poster: String?,
    val backdrop: String? = null,
    val year: Int? = null,
    val rating: Float? = null,
    val genres: List<String> = emptyList(),
    /** Series have a plot in the catalogue already; films need `get_vod_info`. */
    val plot: String? = null,
    val containerExtension: String? = null,
) : Tile {
    override val key: String get() = "${kind.name}:$id"
}

/** Something the viewer started: a film, or the episode of a series they are on. */
@Immutable
data class ContinueTile(
    val progressKey: String,
    val kind: TitleKind,
    /** The film's stream id, or the series id. */
    val titleId: Long,
    val episodeId: Long? = null,
    val name: String,
    val subtitle: String?,
    val image: String?,
    val backdrop: String?,
    val fraction: Float,
    val positionMs: Long,
    val containerExtension: String?,
) : Tile {
    override val key: String get() = "C:$progressKey"
}

/** A live channel, with what it is showing. */
@Immutable
data class ChannelTile(
    val streamId: Long,
    val number: Int,
    val name: String,
    val logo: String?,
    val nowTitle: String? = null,
    val nowStartMs: Long = 0L,
    val nowEndMs: Long = 0L,
    val nowDescription: String? = null,
    val nextTitle: String? = null,
    val isFavorite: Boolean = false,
    val category: String? = null,
    /** False for a channel read live from a category the import never kept. */
    val isImported: Boolean = true,
) : Tile {
    override val key: String get() = "L:$streamId"

    fun progress(nowMs: Long): Float =
        if (nowEndMs > nowStartMs && nowMs in nowStartMs..nowEndMs)
            (nowMs - nowStartMs).toFloat() / (nowEndMs - nowStartMs)
        else 0f
}

/** A game from the scoreboard. */
@Immutable
data class GameTile(val event: SportsEvent) : Tile {
    override val key: String get() = "G:${event.league.id}:${event.id}"
}

/** How a row lays its tiles out. */
enum class RowKind { POSTER, TOP10, CONTINUE, LIVE, SPORTS }

@Immutable
data class FeedRow(
    val id: String,
    val title: String,
    val kind: RowKind,
    val tiles: List<Tile>,
    val subtitle: String? = null,
)
