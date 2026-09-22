package com.crimson.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.crimson.core.epg.ProgramCategory
import com.crimson.core.model.Country
import com.crimson.core.model.KeptChannel
import com.crimson.core.model.Market

/**
 * A channel that passed the filter at import time.
 *
 * The country and market are stored rather than recomputed, which is what makes a filter change
 * instant: removing South Korea from the rules is a `WHERE country IN (...)` change against rows
 * that are already here, with no network and no re-parsing. Only *adding* a country needs a fetch,
 * and [CategoryEntity.imported] says which categories still need one.
 */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["number"], unique = true),
        Index(value = ["country"]),
        Index(value = ["market"]),
        Index(value = ["channelKey"]),
        Index(value = ["categoryId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey val streamId: Long,

    /** The stable cable-style number from `ChannelNumbering`. */
    val number: Int,

    /** Exactly what the provider called it. Kept so nothing is lost by the cleaner. */
    val originalName: String,
    /** Country prefixes and quality tags removed, for the info panel. */
    val displayName: String,
    /** Shortened to fit the guide's channel column. */
    val shortName: String,

    val country: String,
    val market: String?,

    val categoryId: String?,
    val categoryName: String?,

    /**
     * What programmes are keyed on: the provider's `epg_channel_id` when it has one, otherwise a
     * synthetic `sid:<stream id>` so channels served only by the short-EPG fallback still join.
     */
    val channelKey: String,
    val epgChannelId: String?,

    val streamIcon: String?,
    val tvArchive: Boolean,

    /** Wall-clock millis of the import that last saw this channel. */
    val lastSeenAt: Long,
) {
    val countryEnum: Country? get() = Country.fromCode(country)
    val marketEnum: Market? get() = market?.let { runCatching { Market.valueOf(it) }.getOrNull() }

    companion object {
        fun key(epgChannelId: String?, streamId: Long): String =
            if (!epgChannelId.isNullOrBlank()) epgChannelId else "sid:$streamId"

        fun from(kept: KeptChannel, number: Int, shortName: String, now: Long) = ChannelEntity(
            streamId = kept.streamId,
            number = number,
            originalName = kept.originalName,
            displayName = kept.displayName,
            shortName = shortName,
            country = kept.country.code,
            market = kept.market?.name,
            categoryId = kept.categoryId,
            categoryName = kept.categoryName,
            channelKey = key(kept.epgChannelId, kept.streamId),
            epgChannelId = kept.epgChannelId,
            streamIcon = kept.streamIcon,
            tvArchive = kept.tvArchive,
            lastSeenAt = now,
        )
    }
}

/**
 * One programme.
 *
 * Indexed on (channelKey, startMs) because that is the only query the guide ever makes: give me
 * everything on these channels between these two times. Without it, scrolling a 450,000-row table
 * on a Fire Stick is a full scan per frame.
 *
 * That index is **unique**, and that is what makes a refresh safe. The insert strategy has always
 * been IGNORE, but with an auto-generated primary key and no unique constraint there was nothing
 * to conflict with: every refresh appended the whole guide again. Four refreshes on the reference
 * account had turned 52,846 real programmes into 217,548 rows and a 5 MB database into 76 MB.
 * A channel cannot be showing two things at the same instant, so (channelKey, startMs) is the
 * natural identity of a programme, and the first source to claim a slot keeps it — which is also
 * how the provider's data is protected from being overwritten by a public feed's guess.
 */
@Entity(
    tableName = "programs",
    indices = [
        Index(value = ["channelKey", "startMs"], unique = true),
        Index(value = ["endMs"]),
        Index(value = ["source"]),
    ],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelKey: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String,
    val category: String,
    val rating: String?,
    /**
     * Which feed this came from: `provider`, `short-epg`, or an [com.crimson.core.epg.EpgSource]
     * id. Kept so the Settings screen can say what each feed contributed, and so turning the
     * public feeds off can remove exactly their rows.
     */
    val source: String = "provider",
) {
    val categoryEnum: ProgramCategory
        get() = runCatching { ProgramCategory.valueOf(category) }.getOrDefault(ProgramCategory.SERIES_OTHER)
}

/**
 * A category as the provider lists it, with the filter's verdict and whether its streams have
 * actually been downloaded.
 *
 * [imported] is what lets "add South Korea" fetch only Korean categories instead of re-importing
 * the whole provider.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val categoryId: String,
    val name: String,
    val country: String?,
    val market: String?,
    val indicatesLocals: Boolean,
    val foreignMarker: String?,
    /** True once this category's streams have been read and filtered. */
    val imported: Boolean,
    val lastImportedAt: Long?,
)

/**
 * The permanent channel-number ledger.
 *
 * Separate from [ChannelEntity] on purpose: a channel that disappears from the provider loses its
 * `channels` row, but its number stays here, so if it comes back next month the viewer's 1042 is
 * still 1042.
 */
@Entity(tableName = "channel_numbers")
data class ChannelNumberEntity(
    @PrimaryKey val streamId: Long,
    val number: Int,
)

/** User's favorited individual channels. */
@Entity(tableName = "favorite_channels")
data class FavoriteChannelEntity(
    @PrimaryKey val streamId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

/** User's favorited channel groups / categories. */
@Entity(tableName = "favorite_categories")
data class FavoriteCategoryEntity(
    @PrimaryKey val categoryId: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * A film in the provider's on-demand catalogue.
 *
 * Cached locally for two reasons. Search has to answer as the viewer types, and a round trip per
 * keystroke to a panel that takes a second to answer is not a search box. And the curated rows
 * have to know what the viewer owns before they can show it, which means holding the whole
 * catalogue, not one category at a time.
 *
 * Only what a poster row and a search result need is kept — the synopsis, cast and duration stay
 * behind `get_vod_info` and are fetched when a title is opened.
 */
@Entity(
    tableName = "vod",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["name"]),
        Index(value = ["titleKey"]),
        Index(value = ["titleKeyAlt"]),
        Index(value = ["imdbVotes"]),
        Index(value = ["added"]),
    ],
)
data class VodEntity(
    @PrimaryKey val streamId: Long,
    val name: String,
    val categoryId: String?,
    val categoryName: String?,
    val icon: String?,
    val rating: String?,
    val containerExtension: String?,
    /**
     * [com.crimson.core.catalog.TitleMatcher]'s key for [name], computed once at import.
     *
     * A curated list names 2,717 titles and this catalogue holds 215,000; matching them in memory
     * means loading the catalogue, which is tens of megabytes on a device that has a gigabyte for
     * everything. An indexed column turns the whole job into three `IN` queries instead.
     */
    val titleKey: String = "",
    /** The second reading of a name that ends in a number; see `TitleMatcher.variants`. */
    val titleKeyAlt: String? = null,
    /** The year the name carried, if it carried one; IMDb's year once the title is matched. */
    val titleYear: Int? = null,
    /** When the provider added it, epoch millis. */
    val added: Long? = null,
    /** IMDb genres as `,Crime,Drama,` — see [com.crimson.core.catalog.Genres]. Null until matched. */
    val genres: String? = null,
    val imdbRating: Float? = null,
    /** How many people rated it on IMDb: the popularity measure every "top" row sorts on. */
    val imdbVotes: Int? = null,
    /** A dub or a copy labelled for another language's audience; kept out of the browsing rows. */
    val isForeign: Boolean = false,
    val isAdult: Boolean = false,
)

/** A series in the provider's catalogue, cached for the same reasons as [VodEntity]. */
@Entity(
    tableName = "series",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["name"]),
        Index(value = ["titleKey"]),
        Index(value = ["titleKeyAlt"]),
        Index(value = ["imdbVotes"]),
        Index(value = ["added"]),
    ],
)
data class SeriesEntity(
    @PrimaryKey val seriesId: Long,
    val name: String,
    val categoryId: String?,
    val categoryName: String?,
    val cover: String?,
    val plot: String?,
    val rating: String?,
    val releaseDate: String?,
    /** See [VodEntity.titleKey]. */
    val titleKey: String = "",
    val titleKeyAlt: String? = null,
    val titleYear: Int? = null,
    val backdrop: String? = null,
    /** The provider's last-modified time, epoch millis, which moves when episodes are added. */
    val added: Long? = null,
    val genres: String? = null,
    val imdbRating: Float? = null,
    val imdbVotes: Int? = null,
    val isForeign: Boolean = false,
    val isAdult: Boolean = false,
)

/** A film or series the viewer added to My List. */
@Entity(tableName = "my_list", primaryKeys = ["kind", "itemId"])
data class MyListEntity(
    /** `MOVIE` or `SERIES`, a [com.crimson.core.catalog.TitleKind] name. */
    val kind: String,
    val itemId: Long,
    val name: String,
    val image: String?,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * How far into a film or an episode the viewer got.
 *
 * Keyed as `M:<stream id>` or `E:<episode id>`. An episode also records its series, so Continue
 * Watching can show one card per series — the episode the viewer is on — rather than one per
 * episode they ever started.
 */
@Entity(
    tableName = "watch_progress",
    indices = [Index(value = ["updatedAt"]), Index(value = ["seriesId"])],
)
data class WatchProgressEntity(
    @PrimaryKey val key: String,
    /** `MOVIE` or `EPISODE`. */
    val kind: String,
    val itemId: Long,
    val seriesId: Long? = null,
    val title: String,
    /** "S2:E5 · The One Where…" for an episode; null for a film. */
    val subtitle: String? = null,
    val image: String? = null,
    val backdrop: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val containerExtension: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Finished, for the purpose of "continue watching": the credits are rolling. */
    val isFinished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.94

    companion object {
        fun movieKey(streamId: Long) = "M:$streamId"
        fun episodeKey(episodeId: Long) = "E:$episodeId"
    }
}

