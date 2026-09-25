package com.crimson.ui.player

import android.util.Log
import androidx.media3.common.C
import com.crimson.core.skip.EpisodePrint
import com.crimson.core.skip.SkipTimes
import com.crimson.core.skip.Theme
import com.crimson.core.skip.ThemeLearner
import com.crimson.core.skip.ThemeMatcher
import com.crimson.core.skip.ThemePrints
import com.crimson.core.skip.ThemeSkipChoice
import com.crimson.data.skip.ThemeSkipRepository
import com.crimson.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

data class ThemeSkipState(
    /** The show playing, by [ThemeSkipChoice.showKey]; null when no episode is. */
    val show: String? = null,
    val choice: ThemeSkipChoice = ThemeSkipChoice.OFF,
    /** Theme songs learned by ear for this show. */
    val learnedIntros: Int = 0,
    val learnedEndings: Int = 0,
    /** The intro databases' times for this episode, where they could be trusted. */
    val times: SkipTimes = SkipTimes.NONE,
    /** The last skip, for the note on screen; a new [Skipped.at] each time. */
    val skipped: Skipped? = null,
) {
    data class Skipped(val kind: Theme.Kind, val at: Long)
}

/**
 * Skips theme songs, for the shows the viewer has chosen, without being asked each time.
 *
 * **By ear, once it knows the song.** The decoded audio's notes are recorded eight times a second
 * ([com.crimson.core.skip.ChromaMeter]) over each episode's first ten and last six minutes; when
 * an episode ends, its recording is compared with the last few of the same show, and music they
 * share is that show's theme ([ThemeLearner]). From then on the song is recognised as it plays
 * ([ThemeMatcher]): turned down within a second and a half of starting, skipped to its exact end
 * at three seconds. That works whatever copy the provider has and wherever the cold open ends.
 *
 * **By the intro databases, until then.** IntroDB and TheIntroDB have crowd-sourced times for
 * many episodes. They are used, when they agree, only for a kind of theme not yet learned: they
 * were timed on someone else's copy.
 *
 * Each theme is skipped once per episode: going back into it plays it.
 */
class ThemeSkipController(
    private val player: PlayerController,
    private val repository: ThemeSkipRepository,
) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(ThemeSkipState())
    val state: StateFlow<ThemeSkipState> = _state.asStateFlow()

    class Target(
        val episodeId: Long,
        val show: String,
        val season: Int,
        val episode: Int,
        /** IMDb and TMDB ids of the show, for the intro databases; either may be null. */
        val ids: suspend () -> Pair<String?, String?>,
        /** An ending skipped to the very end: the next episode, as the credits would lead to. */
        val onReachedEnd: () -> Unit,
    )

    private var target: Target? = null
    private var job: Job? = null
    private var themes: List<Theme> = emptyList()
    private var print: EpisodePrint? = null
    private val skipped = HashSet<Theme.Kind>()
    private val frames = ConcurrentLinkedQueue<Frame>()

    private class Frame(val generation: Int, val timeUs: Long, val chroma: ByteArray)

    /** An episode is about to play. Call before the player starts it, so its audio is decoded. */
    fun start(target: Target, choice: ThemeSkipChoice) {
        finish()
        this.target = target
        _state.value = ThemeSkipState(show = target.show, choice = choice)
        if (choice.any) listen()
    }

    /** The viewer switched skipping on or off for this show. */
    fun setChoice(choice: ThemeSkipChoice) {
        val target = target ?: return
        if (_state.value.choice == choice) return
        val wasListening = _state.value.choice.any
        _state.value = _state.value.copy(choice = choice)
        if (choice.any && !wasListening) listen() else if (!choice.any) quiet()
        // Turning on part way through: the audio may still be going out undecoded (Dolby), in
        // which case nothing can be heard until the next episode. The databases still work.
        if (choice.any && !wasListening) Log.i(TAG, "listening for ${target.show} from now")
    }

    /** Throws away what was learned about this show, if it learned something wrong. */
    fun forget() {
        val show = target?.show ?: return
        themes = emptyList()
        introMatcher = null
        outroMatcher = null
        _state.value = _state.value.copy(learnedIntros = 0, learnedEndings = 0)
        scope.launch { repository.forget(show) }
    }

    /** Playback of episodes is over. */
    fun stop() {
        finish()
        _state.value = ThemeSkipState()
    }

    // ------------------------------------------------------------------ listening

    private fun listen() {
        val target = target ?: return
        val audio = player.audio
        audio.decodeForThemes = true
        audio.themeListener = { time, chroma ->
            if (frames.size < MAX_QUEUED) frames.add(Frame(audio.generation, time, chroma))
        }
        job?.cancel()
        job = scope.launch { run(target) }
    }

    private fun quiet() {
        job?.cancel()
        job = null
        player.audio.themeListener = null
        player.audio.decodeForThemes = false
        player.setVolume(1f)
        frames.clear()
    }

    /** Learns from the episode that was playing, and lets go of the audio. */
    private fun finish() {
        val target = target
        val print = print
        quiet()
        this.target = null
        this.print = null
        themes = emptyList()
        skipped.clear()
        if (target == null || print == null) return
        if (print.introHeardMs < ThemeLearner.MIN_HEARD_MS && print.outroHeardMs < ThemeLearner.MIN_HEARD_MS) return
        scope.launch {
            val learned = runCatching { repository.learnFrom(target.show, print, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "learning failed", it) }.getOrDefault(emptyList())
            Log.i(TAG, "${target.show}: learned ${learned.map { "${it.kind} ${it.lengthMs / 1000}s" }} from episode ${target.episodeId}")
            // The next episode of the same show may already be playing; it gets them at once.
            if (learned.isNotEmpty() && this@ThemeSkipController.target?.show == target.show) loadThemes(target.show)
        }
    }

    private suspend fun loadThemes(show: String) {
        themes = repository.themes(show)
        introMatcher = matcher(Theme.Kind.INTRO)
        outroMatcher = matcher(Theme.Kind.OUTRO)
        _state.value = _state.value.copy(
            learnedIntros = themes.count { it.kind == Theme.Kind.INTRO },
            learnedEndings = themes.count { it.kind == Theme.Kind.OUTRO },
        )
    }

    private var introMatcher: ThemeMatcher? = null
    private var outroMatcher: ThemeMatcher? = null

    private fun matcher(kind: Theme.Kind): ThemeMatcher? =
        themes.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { list -> ThemeMatcher(list.map { it.frames }) }

    private suspend fun run(target: Target) {
        loadThemes(target.show)
        val audio = player.audio
        val offsets = ArrayList<Long>()
        var generation = audio.generation
        var lastFrameMs = Long.MIN_VALUE
        var duckedUntil = 0L
        var timesAsked = false
        var ducks = 0
        while (true) {
            delay(TICK_MS)
            val p = player.state.value
            if (p.channel?.streamId != target.episodeId) continue
            if (audio.generation != generation) {
                generation = audio.generation
                offsets.clear()
                frames.clear()
                introMatcher?.reset(); outroMatcher?.reset()
            }
            val duration = player.durationMs()
            val position = player.positionMs()
            if (print == null && duration > 0) print = EpisodePrint(target.episodeId, duration)
            if (!timesAsked && duration > 0) {
                timesAsked = true
                scope.launch {
                    val (imdb, tmdb) = runCatching { target.ids() }.getOrDefault(null to null)
                    val times = repository.times(imdb, tmdb, target.season, target.episode, duration)
                    if (this@ThemeSkipController.target === target) _state.value = _state.value.copy(times = times)
                }
            }

            // How the renderer's clock maps to the video's, sampled while it plays.
            val playout = audio.lastPlayoutUs
            if (playout != C.TIME_UNSET && p.isPlaying && !p.isBuffering) {
                offsets.add(playout - position * 1_000)
                if (offsets.size > 40) offsets.removeAt(0)
            }

            val choice = _state.value.choice
            if (offsets.size >= 3) {
                val offsetUs = offsets.sorted()[offsets.size / 2]
                val print = print
                val intro = introMatcher.takeIf { choice.intro && Theme.Kind.INTRO !in skipped }
                val outro = outroMatcher.takeIf { choice.ending && Theme.Kind.OUTRO !in skipped }
                val heard = withContext(Dispatchers.Default) {
                    var best: Pair<Theme.Kind, ThemeMatcher.Match>? = null
                    var bestAtMs = 0L
                    while (true) {
                        val frame = frames.poll() ?: break
                        if (frame.generation != generation) continue
                        val videoMs = (frame.timeUs - offsetUs) / 1_000
                        // A seek: what came before is not what comes next.
                        if (videoMs < lastFrameMs || videoMs > lastFrameMs + 1_000) { intro?.reset(); outro?.reset() }
                        lastFrameMs = videoMs
                        print?.record(videoMs, frame.chroma)
                        val inIntro = videoMs < EpisodePrint.INTRO_WINDOW_MS + 120_000
                        val inOutro = duration > 0 && videoMs > duration - EpisodePrint.OUTRO_WINDOW_MS - 120_000
                        val match = (if (inIntro) intro?.push(frame.chroma)?.let { Theme.Kind.INTRO to it } else null)
                            ?: (if (inOutro) outro?.push(frame.chroma)?.let { Theme.Kind.OUTRO to it } else null)
                        if (match != null && com.crimson.BuildConfig.DEBUG) Log.d(TAG, "match at ${videoMs}ms: ${match.second}")
                        // The first confirmed match wins; a likely one only stands in until then.
                        if (match != null && best?.second?.confirmed != true) { best = match; bestAtMs = videoMs }
                    }
                    best?.let { Triple(it.first, it.second, bestAtMs) }
                }
                if (heard != null) {
                    val (kind, match, atMs) = heard
                    if (match.confirmed) {
                        val endMs = atMs + match.framesLeft * ThemePrints.FRAME_MS + ThemePrints.FRAME_MS / 2
                        Log.i(TAG, "heard $kind theme at ${atMs / 1000.0}s (frame ${match.frame}), ends ${endMs / 1000.0}s")
                        skip(target, kind, endMs)
                    } else if (duckedUntil == 0L && ducks < MAX_DUCKS) {
                        ducks++
                        // Probably the theme starting: down now, rather than after three loud seconds.
                        player.setVolume(DUCKED)
                        duckedUntil = System.currentTimeMillis() + DUCK_MS
                    }
                }
            }
            // Not the theme after all: back up.
            if (duckedUntil != 0L && System.currentTimeMillis() > duckedUntil) {
                player.setVolume(1f)
                duckedUntil = 0L
            }

            // The databases, for a kind of theme not learned yet.
            val times = _state.value.times
            if (p.isPlaying) {
                val introStart = times.introStartMs
                val introEnd = times.introEndMs
                if (choice.intro && introMatcher == null && Theme.Kind.INTRO !in skipped && introStart != null && introEnd != null &&
                    position in introStart until introEnd - MIN_LEFT_MS
                ) {
                    Log.i(TAG, "database intro ${introStart / 1000}–${introEnd / 1000}s")
                    skip(target, Theme.Kind.INTRO, introEnd)
                }
                val outroStart = times.outroStartMs
                if (choice.ending && outroMatcher == null && Theme.Kind.OUTRO !in skipped && outroStart != null) {
                    val outroEnd = times.outroEndMs ?: duration
                    if (outroEnd > 0 && position in outroStart until outroEnd - MIN_LEFT_MS) {
                        Log.i(TAG, "database ending ${outroStart / 1000}–${outroEnd / 1000}s")
                        skip(target, Theme.Kind.OUTRO, outroEnd)
                    }
                }
            }
        }
    }

    private fun skip(target: Target, kind: Theme.Kind, toMs: Long) {
        if (!skipped.add(kind)) return
        player.setVolume(1f)
        val position = player.positionMs()
        if (toMs - position < MIN_LEFT_MS) return
        val duration = player.durationMs()
        if (kind == Theme.Kind.OUTRO && duration > 0 && toMs >= duration - END_MARGIN_MS) target.onReachedEnd()
        else player.seekTo(toMs)
        _state.value = _state.value.copy(skipped = ThemeSkipState.Skipped(kind, System.currentTimeMillis()))
    }

    companion object {
        private const val TAG = "CrimsonThemes"
        private const val TICK_MS = 125L
        private const val MAX_QUEUED = 4_000
        /** Less than this left of a theme is not worth a seek. */
        private const val MIN_LEFT_MS = 1_500L
        /** An ending that runs to within this of the end leads straight to the next episode. */
        private const val END_MARGIN_MS = 12_000L
        private const val DUCKED = 0.2f
        private const val DUCK_MS = 2_500L
        /** Music that keeps sounding like a theme's opening and isn't: stop turning it down. */
        private const val MAX_DUCKS = 4
    }
}
