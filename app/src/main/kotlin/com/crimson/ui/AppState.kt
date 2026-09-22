package com.crimson.ui

import com.crimson.core.catalog.TitleKind
import com.crimson.core.guide.GuideCursor
import com.crimson.core.guide.ProgramSlot
import com.crimson.core.guide.TimeWindow
import com.crimson.data.profile.Profile
import com.crimson.data.settings.AppSettings
import com.crimson.data.db.CategoryEntity
import com.crimson.domain.GuideChannel
import com.crimson.ui.components.MainTab

/**
 * Where the viewer is.
 *
 * The app keeps a back stack of these rather than a single "current screen": a title opened from
 * search, then a similar title opened from its "More Like This", then Back, has to land on the
 * first title and then on search, in that order.
 */
sealed interface Route {
    data object Starting : Route
    data object Profiles : Route
    data class EditProfile(val profileId: String?) : Route
    /** The first import after choosing a profile. */
    data object Loading : Route
    data class Main(val tab: MainTab) : Route
    data class Details(val kind: TitleKind, val id: Long) : Route
    data class Search(val query: String = "", val scope: SearchScope = SearchScope.ALL) : Route
    /** Every live category, grouped by country. */
    data object Directory : Route
    data object Guide : Route
    data object Watching : Route
    data object Settings : Route
}

enum class SearchScope(val label: String) { ALL("All"), LIVE("Live TV"), MOVIES("Movies"), SHOWS("TV Shows") }

/** Session-wide state that is not a page's own. */
data class UiState(
    val route: Route = Route.Starting,
    val profiles: List<Profile> = emptyList(),
    val profile: Profile? = null,
    val profilesEncrypted: Boolean = true,
    val settings: AppSettings = AppSettings(),
    /** The profile editor's validation state. */
    val profileError: String? = null,
    val isSavingProfile: Boolean = false,
    val importMessage: String? = null,
    val importDetail: String? = null,
    val allowedFormats: List<String> = listOf("ts"),
    val accountExpiry: Long? = null,
    val maxConnections: Int = 1,
    val isFetchingMore: Boolean = false,
    val pendingChannelNumber: String = "",
    val isRefreshingEpg: Boolean = false,
    val epgRefreshDetail: String? = null,
    /** Set while the catalogue is being fetched or joined to the title index. */
    val libraryStatus: String? = null,
    val liveCategories: List<CategoryEntity> = emptyList(),
    val favoriteChannelCount: Int = 0,
    val totalChannelCount: Int = 0,
    /** What the full-screen player is showing, beyond what the player itself knows. */
    val nowPlaying: NowPlaying? = null,
    /** Bumped when the player's controls should show (a key was pressed while watching). */
    val controlsToken: Long = 0L,
) {
    val canGoBackFromProfiles: Boolean get() = profile != null
}

/** The film or episode on screen, for the player's controls and for saving progress. */
data class NowPlaying(
    val kind: Kind,
    val streamId: Long,
    val title: String,
    val subtitle: String? = null,
    val seriesId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val image: String? = null,
    val backdrop: String? = null,
    val containerExtension: String? = null,
    val next: NextEpisode? = null,
) {
    enum class Kind { LIVE, MOVIE, EPISODE }

    val isVod: Boolean get() = kind != Kind.LIVE
}

data class NextEpisode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val containerExtension: String?,
    val image: String?,
)

/**
 * The channel banner's contents.
 *
 * [showToken] changes on every tune, including a re-tune to the same channel, which is what lets
 * the overlay restart its timer rather than keeping a stale one running.
 */
data class BannerState(
    val channel: GuideChannel? = null,
    val now: ProgramSlot? = null,
    val next: ProgramSlot? = null,
    val showToken: Long = 0L,
)

const val PACKAGE_PREFIX = "__pkg:"
const val ALL_CHANNELS_ID = "__all__"
const val FAVORITE_CHANNELS_ID = "__favorites__"
const val DEFAULT_CATEGORY_ID = PACKAGE_PREFIX + "us_cable"
const val DEFAULT_CATEGORY_NAME = "American Cable"

data class GuideState(
    val channels: List<GuideChannel> = emptyList(),
    val allChannels: List<GuideChannel> = emptyList(),
    val programsByKey: Map<String, List<ProgramSlot>> = emptyMap(),
    val cursor: GuideCursor = GuideCursor(0, 0L, TimeWindow(0L), 0),
    val nowMs: Long = 0L,
    /** The programme under the highlight. */
    val selected: ProgramSlot? = null,
    /** Non-null while the future-programme details dialog is open. */
    val details: ProgramSlot? = null,
    val categoryId: String? = null,
    val categoryName: String = DEFAULT_CATEGORY_NAME,
) {
    val selectedChannel: GuideChannel?
        get() = channels.getOrNull(cursor.channelIndex)
}
