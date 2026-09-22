package com.crimson

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.crimson.core.epg.ProgramCategory
import com.crimson.core.guide.GuideCursor
import com.crimson.core.guide.GuideGeometry
import com.crimson.core.guide.HALF_HOUR_MS
import com.crimson.core.guide.ProgramSlot
import com.crimson.core.guide.TimeWindow
import com.crimson.core.model.Country
import com.crimson.domain.GuideChannel
import com.crimson.ui.BannerState
import com.crimson.ui.GuideState
import com.crimson.ui.guide.GuideScreen
import com.crimson.ui.player.ChannelBanner
import com.crimson.ui.theme.GuideTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests that run on the JVM.
 *
 * Roborazzi renders real Compose output through Robolectric's native graphics, so the guide can be
 * checked at 1920 x 1080 without an emulator — which matters here because the development
 * environment cannot run one at all. These are not a substitute for looking at the app on a
 * television, but they catch the failures that actually happen in layout work: a cell drawn off
 * screen, text that no longer fits, a theme change that turns the whole grid one colour.
 *
 * Record a new baseline with:   ./gradlew recordRoborazziDebug
 * Check against the baseline:   ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class GuideScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val theme = GuideTheme.Default

    /** A fixed instant so the clock, the "now" line and the time tabs are identical every run. */
    private val now = 1_790_100_000_000L + 23 * 60_000L
    private val windowStart = TimeWindow.snapToHalfHour(now)

    // ------------------------------------------------------------------ fixtures

    private fun channels(): List<GuideChannel> = listOf(
        GuideChannel(1, 1001, "HBO", "HBO", "k1", null, Country.US, null),
        GuideChannel(2, 1002, "ESPN", "ESPN", "k2", null, Country.US, null),
        GuideChannel(3, 1003, "Cartoon Network", "Cartoon", "k3", null, Country.US, null),
        GuideChannel(4, 1004, "CNN", "CNN", "k4", null, Country.US, null),
        GuideChannel(5, 1005, "KTLA 5 Los Angeles", "KTLA 5", "k5", null, Country.US, null),
        GuideChannel(6, 5001, "BBC One", "BBC One", "k6", null, Country.UK, null),
    )

    private fun slot(
        id: Long,
        startOffsetMin: Int,
        durationMin: Int,
        title: String,
        category: ProgramCategory = ProgramCategory.SERIES_OTHER,
        rating: String? = "TV-14",
        description: String = "",
    ) = ProgramSlot(
        id = id,
        startMs = windowStart + startOffsetMin * 60_000L,
        endMs = windowStart + (startOffsetMin + durationMin) * 60_000L,
        title = title,
        category = category,
        description = description,
        rating = rating,
    )

    private fun programs(): Map<String, List<ProgramSlot>> {
        val window = TimeWindow(windowStart - 2 * HALF_HOUR_MS, 6 * HALF_HOUR_MS)
        return mapOf(
            // Starts before the window: draws a left notch.
            "k1" to GuideGeometry.withFillers(
                listOf(
                    slot(10, -45, 130, "The Long Afternoon", ProgramCategory.MOVIE,
                        description = "A quiet character study that takes its time."),
                    slot(11, 85, 120, "Harbour Lights", ProgramCategory.MOVIE),
                ),
                window,
            ),
            // Runs past the window: draws a right notch.
            "k2" to GuideGeometry.withFillers(
                listOf(
                    slot(20, 0, 30, "SportsCenter", ProgramCategory.SPORTS),
                    slot(21, 30, 180, "Live: Championship Football", ProgramCategory.SPORTS),
                ),
                window,
            ),
            "k3" to GuideGeometry.withFillers(
                listOf(
                    slot(30, 0, 30, "Pebble and Bean", ProgramCategory.KIDS),
                    slot(31, 30, 30, "The Sock Drawer", ProgramCategory.KIDS),
                    slot(32, 60, 60, "Captain Compass", ProgramCategory.KIDS),
                ),
                window,
            ),
            // No data at all: the row fills with "No Information" blocks.
            "k4" to GuideGeometry.withFillers(emptyList(), window),
            "k5" to GuideGeometry.withFillers(
                listOf(
                    slot(50, 0, 60, "Evening News", ProgramCategory.NEWS),
                    slot(51, 60, 30, "Weather Watch", ProgramCategory.NEWS),
                    slot(52, 90, 30, "Nightly News", ProgramCategory.NEWS),
                ),
                window,
            ),
            "k6" to GuideGeometry.withFillers(
                listOf(slot(60, 0, 150, "Northgate: S3E5", ProgramCategory.SERIES_OTHER)),
                window,
            ),
        )
    }

    private fun state(
        selectedRow: Int = 0,
        selectedId: Long? = 10,
        windowOffsetHours: Int = 0,
        details: ProgramSlot? = null,
    ): GuideState {
        val window = TimeWindow(windowStart + windowOffsetHours * 3600_000L, theme.windowMillis)
        val programs = programs()
        val channels = channels()
        val selected = programs[channels[selectedRow].channelKey]?.firstOrNull { it.id == selectedId }
        return GuideState(
            channels = channels,
            programsByKey = programs,
            cursor = GuideCursor(
                channelIndex = selectedRow,
                anchorMs = selected?.startMs ?: window.startMs,
                window = window,
                firstVisibleRow = 0,
            ),
            nowMs = now,
            selected = selected,
            details = details,
            categoryName = "ALL CHANNELS",
        )
    }

    @Composable
    private fun previewStub(modifier: Modifier) {
        // Stands in for the video surface, which has nothing to render under Robolectric.
        Box(modifier.background(androidx.compose.ui.graphics.Color(0xFF203050)))
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `guide with a movie highlighted`() {
        compose.setContent {
            GuideScreen(
                state = state(selectedRow = 0, selectedId = 10),
                theme = theme,
                previewContent = { previewStub(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_movie_highlighted.png")
    }

    @Test
    fun `guide with a channel that has no information`() {
        compose.setContent {
            GuideScreen(
                state = state(selectedRow = 3, selectedId = null),
                theme = theme,
                previewContent = { previewStub(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_no_information.png")
    }

    @Test
    fun `guide showing a future programme message box`() {
        val future = programs()["k2"]!!.first { it.startMs > now }
        compose.setContent {
            GuideScreen(
                state = state(selectedRow = 1, selectedId = future.id),
                theme = theme,
                previewContent = { previewStub(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_future_program.png")
    }

    @Test
    fun `guide with the details dialog open`() {
        val future = programs()["k2"]!!.first { it.startMs > now }
        compose.setContent {
            GuideScreen(
                state = state(selectedRow = 1, selectedId = future.id, details = future),
                theme = theme,
                previewContent = { previewStub(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_details_dialog.png")
    }

    @Test
    fun `guide with an empty channel list`() {
        compose.setContent {
            GuideScreen(
                state = GuideState(nowMs = now),
                theme = theme,
                previewContent = { previewStub(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_empty.png")
    }

    @Test
    fun `guide honours a theme change to six rows`() {
        // The whole point of the theme object: one edit changes the look everywhere.
        val sixRows = theme.copy(rowsVisible = 6, rowHeight = androidx.compose.ui.unit.Dp(42f))
        compose.setContent {
            GuideScreen(
                state = state(),
                theme = sixRows,
                previewContent = { previewStub(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_six_rows.png")
    }

    @Test
    fun `channel banner`() {
        val slots = programs()["k1"]!!
        val current = slots.first { it.startMs <= now && it.endMs > now }
        val next = slots.first { it.startMs > now }
        compose.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFF101010))
            ) {
                ChannelBanner(
                    number = 1001,
                    name = "HBO",
                    now = current,
                    next = next,
                    nowMs = now,
                    theme = theme,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/channel_banner.png")
    }
}
