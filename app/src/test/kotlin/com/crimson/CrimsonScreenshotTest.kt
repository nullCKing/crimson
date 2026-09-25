package com.crimson

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.crimson.core.catalog.FeedPage
import com.crimson.core.catalog.TitleKind
import com.crimson.core.sports.Competitor
import com.crimson.core.sports.EventState
import com.crimson.core.sports.Leagues
import com.crimson.core.sports.SportsEvent
import com.crimson.data.profile.Profile
import com.crimson.player.PlaybackState
import com.crimson.ui.BannerState
import com.crimson.ui.NextEpisode
import com.crimson.ui.NowPlaying
import com.crimson.ui.SearchScope
import com.crimson.ui.browse.BrowseActions
import com.crimson.ui.browse.BrowseMemory
import com.crimson.ui.browse.BrowseScreen
import com.crimson.ui.components.ChannelTile
import com.crimson.ui.components.ContinueTile
import com.crimson.ui.components.FeedRow
import com.crimson.ui.components.GameTile
import com.crimson.ui.components.MainTab
import com.crimson.ui.components.RowKind
import com.crimson.ui.components.TitleTile
import com.crimson.ui.details.DetailsActions
import com.crimson.ui.details.DetailsScreen
import com.crimson.ui.details.DetailsState
import com.crimson.ui.details.EpisodeUi
import com.crimson.ui.details.PlayAction
import com.crimson.ui.feed.FeedState
import com.crimson.ui.feed.Spotlight
import com.crimson.ui.live.LiveActions
import com.crimson.ui.live.LiveCollection
import com.crimson.ui.live.LiveScreen
import com.crimson.ui.live.LiveState
import com.crimson.core.subtitles.SubtitleCue
import com.crimson.core.subtitles.SubtitleTrack
import com.crimson.core.subtitles.SyncFix
import com.crimson.data.settings.AppSettings
import com.crimson.data.settings.CaptionSize
import com.crimson.ui.UiState
import com.crimson.ui.player.CaptionLayer
import com.crimson.ui.player.CaptionState
import com.crimson.ui.player.PlayerActions
import com.crimson.ui.player.PlayerMenu
import com.crimson.ui.player.PlayerMenuActions
import com.crimson.ui.player.PlayerOverlay
import com.crimson.ui.settings.SettingsActions
import com.crimson.ui.settings.SettingsScreen
import com.crimson.ui.profiles.EditProfileScreen
import com.crimson.ui.profiles.ProfilesScreen
import com.crimson.ui.search.SearchActions
import com.crimson.ui.search.SearchScreen
import com.crimson.ui.search.SearchState
import com.crimson.ui.sports.SportsActions
import com.crimson.ui.sports.SportsScreen
import com.crimson.ui.sports.SportsState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of Crimson's pages at 1920 x 1080, from fixtures, with no network: posters fall back
 * to their typeset form, which is what these check — layout, hierarchy, colour — rather than
 * artwork. Record with `./gradlew recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CrimsonScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_790_100_000_000L + 23 * 60_000L

    private fun shoot(name: String, advanceMs: Long = 600, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { content() }
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val profiles = listOf(
        Profile("a", "Chris", 0, "http://x", "u", "p"),
        Profile("b", "Sam", 1, "http://x", "u", "p"),
        Profile("c", "Kids", 2, "http://x", "u", "p"),
    )

    private val movies = listOf(
        "The Dark Knight" to 2008, "Inception" to 2010, "Heat" to 1995, "Arrival" to 2016,
        "Parasite" to 2019, "Dune: Part Two" to 2024, "Oppenheimer" to 2023, "Se7en" to 1995,
        "Alien" to 1979, "Blade Runner 2049" to 2017,
    ).mapIndexed { i, (name, year) ->
        TitleTile(TitleKind.MOVIE, i.toLong() + 1, name, null, year = year, rating = 7.5f + (i % 5) * 0.3f, genres = listOf("Action", "Thriller"))
    }

    private val channels = listOf("ABC", "CBS", "NBC", "FOX", "CNN", "ESPN", "HBO", "AMC").mapIndexed { i, name ->
        ChannelTile(i.toLong() + 100, 1001 + i, name, null, nowTitle = listOf("World News Tonight", "NFL Football", "Sunday Night Football", "The Simpsons", "CNN Newsroom", "SportsCenter", "The Last of Us", "Breaking Bad")[i], nowStartMs = now - 20 * 60_000L, nowEndMs = now + 40 * 60_000L, nowDescription = "Live coverage and analysis.", category = "US| ENTERTAINMENT")
    }

    private fun game(id: String, state: EventState, home: String, away: String, net: String) = SportsEvent(
        id = id, league = Leagues.NFL, startMs = now + 3 * 3_600_000L, name = "$away at $home", shortName = "$away @ $home",
        state = state, statusText = if (state == EventState.LIVE) "5:21 - 3rd" else "8:20 PM",
        home = Competitor(home, home, home.take(3).uppercase(), null, "17", "e31837", "2-0", true, false),
        away = Competitor(away, away, away.take(3).uppercase(), null, "14", "311d00", "1-1", false, false),
        networks = listOf(net), venue = "Arrowhead Stadium",
    )

    private fun college(id: String, home: String, away: String, awayRank: Int?, homeRank: Int?, division: String, net: String) = SportsEvent(
        id = id, league = Leagues.NCAAF, startMs = now + 3 * 3_600_000L, name = "$away at $home", shortName = "$away @ $home",
        state = EventState.UPCOMING, statusText = "3:30 PM",
        home = Competitor(home, home, home.take(4).uppercase(), null, null, "4e3629", "1-2", true, false, rank = homeRank),
        away = Competitor(away, away, away.take(4).uppercase(), null, null, "a51c30", "3-0", false, false, rank = awayRank),
        networks = listOf(net), venue = null, division = division,
    )

    private val subtitle = SubtitleTrack(listOf(SubtitleCue(0, 60_000, "<i>We should go.</i>\n- Not yet. [thunder rumbles]")))

    private val feed = FeedState(
        page = FeedPage.HOME,
        loading = false,
        hero = movies[5],
        rows = listOf(
            FeedRow("continue", "Continue Watching", RowKind.CONTINUE, listOf(
                ContinueTile("M:1", TitleKind.MOVIE, 1, null, "Heat", null, null, null, 0.4f, 1L, null),
                ContinueTile("E:2", TitleKind.SERIES, 2, 3, "Severance", "S2:E4 · Woe's Hollow", null, null, 0.7f, 1L, null),
            )),
            FeedRow("sports", "Live Sports", RowKind.SPORTS, listOf(
                GameTile(game("1", EventState.LIVE, "Chiefs", "Browns", "NBC")),
                GameTile(game("2", EventState.UPCOMING, "Bills", "Lions", "Prime Video")),
            ), subtitle = "1 live now · select a game to find its channel"),
            FeedRow("top10_MOVIE", "Top 10 Movies", RowKind.TOP10, movies),
            FeedRow("live", "Live on American Cable", RowKind.LIVE, channels),
        ),
    )

    private val browseActions = BrowseActions({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})

    @Test
    fun `profile picker`() = shoot("profiles") {
        ProfilesScreen(profiles, "a", null, {}, {}, {})
    }

    @Test
    fun `first profile`() = shoot("profile_edit") {
        EditProfileScreen(null, isFirst = true, saving = false, error = null, encrypted = true, onSave = { _, _, _, _, _ -> }, onCancel = {}, onDelete = null)
    }

    @Test
    fun `home billboard`() = shoot("home") {
        BrowseScreen(
            tab = MainTab.HOME,
            state = feed,
            spotlight = Spotlight(
                key = movies[5].key, overline = "FEATURED", title = "Dune: Part Two",
                description = "Paul Atreides unites with Chani and the Fremen while on a warpath of revenge against the conspirators who destroyed his family.",
                rating = 8.5f, year = 2024, ageRating = "PG-13", runtime = "2h 46m", genres = listOf("Action", "Adventure", "Drama"),
                tile = movies[5],
            ),
            nowMs = now,
            avatar = 0,
            libraryStatus = null,
            memory = BrowseMemory(),
            actions = browseActions,
        )
    }

    @Test
    fun `series details`() = shoot("details") {
        DetailsScreen(
            DetailsState(
                kind = TitleKind.SERIES, id = 1, loading = false, name = "Severance", year = 2022, rating = 8.7f,
                ageRating = "TV-MA", genres = listOf("Drama", "Mystery", "Sci-Fi"),
                plot = "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
                cast = "Adam Scott, Britt Lower, Zach Cherry", director = "Dan Erickson",
                play = PlayAction("Resume S2:E4", EpisodeUi(4, 2, 4, "Woe's Hollow", null, "49m", null, 0.6f, 1L, null), 1L, 0.6f),
                seasons = listOf(1, 2), season = 2,
                episodes = (1..6).map { EpisodeUi(it.toLong(), 2, it, "Episode title $it", null, "52m", "Something happens at Lumon that changes everything.", if (it < 4) 1f else 0f, 0L, null) },
                moreLikeThis = movies,
            ),
            now,
            DetailsActions({}, {}, {}, {}, {}, {}),
        )
    }

    @Test
    fun `live tv`() = shoot("live") {
        LiveScreen(
            state = LiveState(
                collection = LiveCollection.AMERICAN_CABLE,
                loading = false,
                rows = listOf(
                    FeedRow("b", "Broadcast", RowKind.LIVE, channels.take(4), subtitle = "4 channels"),
                    FeedRow("n", "News", RowKind.LIVE, channels.drop(4), subtitle = "4 channels"),
                ),
                focused = channels[2],
            ),
            nowMs = now,
            avatar = 1,
            preview = { Box(it.background(Color(0xFF30394A))) },
            actions = LiveActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}),
        )
    }

    @Test
    fun `sports`() = shoot("sports") {
        SportsScreen(
            SportsState(
                loading = false,
                events = listOf(
                    game("1", EventState.LIVE, "Chiefs", "Browns", "NBC"),
                    game("2", EventState.LIVE, "Eagles", "Cowboys", "FOX"),
                    game("3", EventState.UPCOMING, "Bills", "Lions", "Prime Video"),
                    game("4", EventState.UPCOMING, "49ers", "Rams", "CBS"),
                    college("5", "Brown", "Harvard", 12, null, "FCS", "ESPN+"),
                    college("6", "Texas", "Tennessee", 3, 9, "FBS", "ABC"),
                ),
                updatedAt = now,
            ),
            avatar = 2,
            actions = SportsActions({}, {}, {}, {}, {}, {}),
        )
    }

    @Test
    fun `search from a game`() = shoot("search") {
        SearchScreen(
            SearchState(query = "NBC", scope = SearchScope.LIVE, channels = channels.take(5)),
            now,
            emptyList(),
            SearchActions({}, {}, {}, {}, { _, _ -> }),
        )
    }

    @Test
    fun `captions and sound menu`() = shoot("player_menu") {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            // Brightness at 40%, as the watch screen draws it: a black layer over the video only.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            val captions = CaptionState(
                status = CaptionState.Status.ONLINE, track = subtitle, choice = 0, choices = 7,
                source = "OpenSubtitles · Severance.S02E04.1080p.WEB.h264",
                sync = SyncFix(1_400, 1.0, 11.0), detail = "Synced to the dialogue (+1.4 s)",
            )
            CaptionLayer(captions, emptyList(), { 5_000L }, CaptionSize.MEDIUM, background = true, raised = false, level = 0.64f, besideMenu = true)
            PlayerMenu(
                captions = captions,
                settings = AppSettings(captions = true, dialogueBoost = true, videoBrightness = 40),
                isLive = false,
                actions = PlayerMenuActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                audioTracks = listOf(
                    com.crimson.player.AudioOption("0:0", "Japanese · Stereo", "ja", selected = false),
                    com.crimson.player.AudioOption("1:0", "English · 5.1", "en", selected = true),
                ),
                themeSkip = com.crimson.ui.player.ThemeSkipState(
                    show = "SEVERANCE",
                    choice = com.crimson.core.skip.ThemeSkipChoice(intro = true, ending = false),
                    learnedIntros = 1,
                ),
            )
        }
    }

    @Test
    fun `a theme song skipped`() = shoot("theme_skipped") {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            com.crimson.ui.player.ThemeSkipNote(com.crimson.ui.player.ThemeSkipState.Skipped(com.crimson.core.skip.Theme.Kind.INTRO, 1L))
        }
    }

    @Test
    fun `captions over the controls, outlined`() = shoot("captions_outline") {
        Box(Modifier.fillMaxSize().background(Color(0xFF6A7F8F))) {
            PlayerOverlay(
                playback = PlaybackState(isPlaying = false, isPaused = true),
                banner = BannerState(),
                nowPlaying = NowPlaying(NowPlaying.Kind.MOVIE, 5, "Heat", "1995 · 2h 50m"),
                vodControls = com.crimson.ui.player.VodControls(visible = true),
                nowMs = now,
                pendingDigits = "",
                actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, { 1_234_000L }, { 3_000_000L }),
                captionsOn = true,
            )
            CaptionLayer(
                CaptionState(status = CaptionState.Status.ONLINE, track = subtitle),
                emptyList(), { 5_000L }, CaptionSize.LARGE, background = false, raised = true, level = 1f,
            )
        }
    }

    @Test
    fun `captions and sound settings`() = shoot("settings_captions") {
        SettingsScreen(
            ui = UiState(settings = AppSettings(captions = true, captionSize = CaptionSize.LARGE, videoBrightness = 30)),
            actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
            initialSection = 4,
        )
    }

    @Test
    fun `movie controls`() = shoot("player_vod") {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            PlayerOverlay(
                playback = PlaybackState(isPlaying = false, isPaused = true),
                banner = BannerState(),
                nowPlaying = NowPlaying(
                    NowPlaying.Kind.EPISODE, 5, "Severance", "S2:E4 · Woe's Hollow", seriesId = 1, season = 2, episode = 4,
                    next = NextEpisode(6, 2, 5, "Trojan's Horse", null, null),
                ),
                vodControls = com.crimson.ui.player.VodControls(visible = true, button = com.crimson.ui.player.VodButton.AUDIO_SUBTITLES),
                nowMs = now,
                pendingDigits = "",
                actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, { 1_234_000L }, { 3_000_000L }),
            )
        }
    }

    @Test
    fun `moving through an episode`() = shoot("player_scrub") {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            PlayerOverlay(
                playback = PlaybackState(isPlaying = true),
                banner = BannerState(),
                nowPlaying = NowPlaying(NowPlaying.Kind.EPISODE, 5, "Hunter x Hunter", "S1:E12 · Nonstop x Hunter", seriesId = 1, season = 1, episode = 12),
                vodControls = com.crimson.ui.player.VodControls(visible = true, scrubMs = 812_000L),
                nowMs = now,
                pendingDigits = "",
                actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, { 402_000L }, { 1_420_000L }),
            )
        }
    }
}
