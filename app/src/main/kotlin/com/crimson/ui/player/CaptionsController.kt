package com.crimson.ui.player

import android.util.Log
import androidx.media3.common.C
import com.crimson.core.subtitles.FrameRates
import com.crimson.core.subtitles.SoundDescriptions
import com.crimson.core.subtitles.SpeechTimeline
import com.crimson.core.subtitles.SubtitleCandidate
import com.crimson.core.subtitles.SubtitleQuery
import com.crimson.core.subtitles.SubtitleRanking
import com.crimson.core.subtitles.SubtitleSync
import com.crimson.core.subtitles.SubtitleTrack
import com.crimson.core.subtitles.SyncFix
import com.crimson.data.subtitles.SubtitleRepository
import com.crimson.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CaptionState(
    val status: Status = Status.OFF,
    /** The downloaded file on screen, retimed by sync and by the viewer's nudge. Null otherwise. */
    val track: SubtitleTrack? = null,
    /** Which of the found files is showing, and how many there are to try. */
    val choice: Int = 0,
    val choices: Int = 0,
    /** "OpenSubtitles · The.Matrix.1999.1080p.BluRay.x264". */
    val source: String? = null,
    /** What automatic sync decided, once it has. */
    val sync: SyncFix? = null,
    /** The viewer's own adjustment on top, in milliseconds; positive shows captions later. */
    val nudgeMs: Long = 0L,
    /** One line for the player's menu: what is showing, or why nothing is. */
    val detail: String? = null,
) {
    enum class Status { OFF, EMBEDDED, BROADCAST, SEARCHING, ONLINE, NOT_FOUND, FAILED }

    val isOn: Boolean get() = status != Status.OFF
}

/**
 * Captions for whatever is playing, and which captions they are.
 *
 * In order of quality: a subtitle track in the file itself (timed to that exact file, so always
 * right); for live TV, the broadcast's own CEA-608 captions; otherwise English subtitles from
 * OpenSubtitles, best-ranked first ([SubtitleRanking]), with the frame rate corrected when the
 * file says it was made for a PAL copy.
 *
 * A downloaded file can still sit a second or two off, or drift, because it was made for a
 * different release. So while it plays, the speech band of the decoded audio is measured
 * ([com.crimson.core.audio.SpeechMeter]) and, once a minute and a half of dialogue has been heard,
 * lined up against where the captions say people speak ([SubtitleSync]). A confident answer is
 * applied without asking; the menu says what was done, and Left/Right there still nudge by hand.
 */
class CaptionsController(
    private val player: PlayerController,
    private val subtitles: SubtitleRepository,
) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(CaptionState())
    val state: StateFlow<CaptionState> = _state.asStateFlow()

    /** What is playing: enough to search for it, and to tell one playback from the next. */
    class Target(
        val streamId: Long,
        val isLive: Boolean,
        val query: suspend () -> SubtitleQuery?,
    )

    /** What captions are being chosen for; null when nothing is playing full screen. */
    var target: Target? = null
        private set
    private var enabled = false
    private var keepSoundDescriptions = true

    private var job: Job? = null
    private var syncJob: Job? = null
    private var candidates: List<SubtitleCandidate> = emptyList()
    /** The chosen file as downloaded; [base] is it with sound descriptions removed or kept. */
    private var raw: SubtitleTrack? = null
    private var base: SubtitleTrack? = null
    private var capture: SpeechCapture? = null

    /** A new title or channel started. */
    fun start(target: Target, enabled: Boolean, keepSoundDescriptions: Boolean) {
        this.target = target
        this.enabled = enabled
        this.keepSoundDescriptions = keepSoundDescriptions
        refresh()
    }

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        refresh()
    }

    fun setSoundDescriptions(keep: Boolean) {
        if (keepSoundDescriptions == keep) return
        keepSoundDescriptions = keep
        raw?.let { track ->
            base = if (keep) track else SoundDescriptions.strip(track)
            publishTrack()
        }
    }

    /** Playback ended; nothing is shown and nothing listens. */
    fun stop() {
        target = null
        cancelWork()
        player.setEmbeddedCaptions(false)
        _state.value = CaptionState()
    }

    /** The next-best file, for when the one showing is wrong. Wraps around. */
    fun tryAnother() {
        val s = _state.value
        if (s.status != CaptionState.Status.ONLINE || candidates.size < 2) return
        // The search stands; only the file changes, and sync starts over for it.
        job?.cancel()
        syncJob?.cancel()
        player.audio.speechListener = null
        job = scope.launch { load((s.choice + 1) % candidates.size, tries = candidates.size) }
    }

    /** Moves the captions later (positive) or earlier, by hand, on top of automatic sync. */
    fun nudge(deltaMs: Long) {
        val s = _state.value
        if (s.status != CaptionState.Status.ONLINE) return
        _state.value = s.copy(nudgeMs = (s.nudgeMs + deltaMs).coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS))
        publishTrack()
    }

    // ------------------------------------------------------------------ choosing

    private fun refresh() {
        cancelWork()
        val target = target ?: return
        if (!enabled) {
            player.setEmbeddedCaptions(false)
            _state.value = CaptionState()
            return
        }
        if (target.isLive) {
            player.setEmbeddedCaptions(true)
            _state.value = CaptionState(CaptionState.Status.BROADCAST, detail = "Shown when the channel broadcasts them")
            return
        }
        // Decoded audio from the start, so sync can listen without reloading the stream later.
        player.audio.decodeForSync = true
        _state.value = CaptionState(CaptionState.Status.SEARCHING, detail = "Looking for captions…")
        job = scope.launch {
            val tracks = withTimeoutOrNull(TRACKS_TIMEOUT_MS) {
                player.state.first { it.channel?.streamId == target.streamId && it.tracksKnown }
            }
            if (tracks?.hasSubtitleTrack == true) {
                player.setEmbeddedCaptions(true)
                player.audio.decodeForSync = false
                _state.value = CaptionState(CaptionState.Status.EMBEDDED, source = "From the video file", detail = "From the video file")
                return@launch
            }
            player.setEmbeddedCaptions(false)
            val query = runCatching { target.query() }.getOrNull()
            if (query == null) {
                notFound("This title couldn't be identified to search for captions")
                return@launch
            }
            val found = subtitles.search(query)
            candidates = SubtitleRanking.rank(found, player.videoFrameRate(), preferSdh = keepSoundDescriptions)
            Log.i(TAG, "${found.size} English subtitles for ${query.title} (imdb ${query.imdbId}, tmdb ${query.tmdbId})")
            if (candidates.isEmpty()) {
                notFound("No English captions were found for this title")
                return@launch
            }
            load(0, tries = MAX_AUTOMATIC_TRIES)
        }
    }

    /** Loads candidate [index], moving on to the next when one cannot be used, [tries] times at most. */
    private suspend fun load(index: Int, tries: Int) {
        var i = index
        repeat(tries.coerceAtMost(candidates.size)) {
            val candidate = candidates[i]
            _state.value = _state.value.copy(status = CaptionState.Status.SEARCHING, detail = "Loading captions…")
            val track = try {
                subtitles.load(candidate)
            } catch (e: SubtitleRepository.QuotaExceeded) {
                _state.value = CaptionState(CaptionState.Status.FAILED, detail = "OpenSubtitles' daily download limit is used up")
                return
            } catch (e: Exception) {
                Log.w(TAG, "subtitle ${candidate.id} failed", e)
                null
            }
            if (track != null && fitsThisCut(track)) {
                show(candidate, i, track)
                return
            }
            i = (i + 1) % candidates.size
        }
        notFound("The captions found don't match this video")
    }

    /** A file that runs well past the end of the video was made for a different cut of it. */
    private fun fitsThisCut(track: SubtitleTrack): Boolean {
        val duration = player.durationMs()
        return duration <= 0 || track.lastEndMs <= duration * 1.1 + 120_000
    }

    private fun show(candidate: SubtitleCandidate, index: Int, track: SubtitleTrack) {
        raw = track
        base = if (keepSoundDescriptions) track else SoundDescriptions.strip(track)
        // A file made for a copy at another speed is stretched to fit before anything else.
        val rate = player.videoFrameRate()
        val correction = if (candidate.fps != null && rate != null) FrameRates.correction(candidate.fps!!, rate) else null
        _state.value = CaptionState(
            status = CaptionState.Status.ONLINE,
            choice = index,
            choices = candidates.size,
            source = candidate.source.label + (candidate.release.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            sync = correction?.let { SyncFix(0, it, 0.0) },
        )
        publishTrack()
        listenForSync()
    }

    private fun notFound(why: String) {
        player.audio.decodeForSync = false
        _state.value = CaptionState(CaptionState.Status.NOT_FOUND, detail = why)
    }

    private fun publishTrack() {
        val s = _state.value
        val base = base ?: return
        val fix = s.sync
        val offset = (fix?.offsetMs ?: 0L) + s.nudgeMs
        val track = base.retimed(offset, fix?.scale ?: 1.0)
        _state.value = s.copy(track = track, detail = describe(s))
    }

    private fun describe(s: CaptionState): String {
        val sync = s.sync
        val synced = when {
            sync == null -> "Syncing to the dialogue once it has heard enough"
            sync.confidence == 0.0 -> "Frame rate corrected · syncing to the dialogue"
            else -> "Synced to the dialogue (${SubtitleSync.describe(sync)})"
        }
        val nudge = if (s.nudgeMs != 0L) " · adjusted ${if (s.nudgeMs > 0) "+" else "−"}${kotlin.math.abs(s.nudgeMs) / 1000.0} s" else ""
        return synced + nudge
    }

    // ------------------------------------------------------------------ automatic sync

    /**
     * Records the dialogue's loudness as it plays and, at a minute and a half, three, six and ten
     * minutes heard, lines the captions up with it. Stops listening once it is sure.
     */
    private fun listenForSync() {
        syncJob?.cancel()
        val audio = player.audio
        val capture = SpeechCapture(audio.generation).also { this.capture = it }
        audio.speechListener = { time, level -> capture.add(audio.generation, time, level) }
        syncJob = scope.launch {
            val offsets = ArrayList<Long>()
            var nextAtMs = SubtitleSync.MIN_HEARD_MS
            while (isActive) {
                delay(1_000)
                if (capture.generation != audio.generation) {
                    capture.reset(audio.generation)
                    offsets.clear()
                    continue
                }
                // How the renderer's clock maps to the video's: sampled while it plays.
                val playout = audio.lastPlayoutUs
                val p = player.state.value
                if (playout != C.TIME_UNSET && p.isPlaying && !p.isBuffering) {
                    offsets.add(playout - player.positionMs() * 1_000)
                    if (offsets.size > 60) offsets.removeAt(0)
                }
                val heardMs = capture.size * SpeechTimeline.BIN_MS
                if (heardMs < nextAtMs || offsets.size < 5) continue
                nextAtMs = when {
                    nextAtMs < 180_000 -> 180_000
                    nextAtMs < 360_000 -> 360_000
                    else -> 600_000
                }
                val base = base ?: continue
                val offsetUs = offsets.sorted()[offsets.size / 2]
                val duration = player.durationMs().takeIf { it > 0 } ?: continue
                val fix = withContext(Dispatchers.Default) {
                    val speech = capture.timeline(duration, offsetUs)
                    SubtitleSync.estimate(base, speech)
                }
                Log.i(TAG, "sync after ${heardMs / 1000} s heard: $fix")
                val current = _state.value
                if (fix != null && current.status == CaptionState.Status.ONLINE &&
                    (current.sync == null || fix.confidence > current.sync.confidence)
                ) {
                    _state.value = current.copy(sync = fix)
                    publishTrack()
                }
                if ((fix != null && fix.confidence >= CERTAIN_Z) || heardMs >= 600_000) break
            }
            audio.speechListener = null
        }
    }

    private fun cancelWork() {
        job?.cancel()
        syncJob?.cancel()
        player.audio.speechListener = null
        player.audio.decodeForSync = false
        capture = null
        raw = null
        base = null
        candidates = emptyList()
    }

    /**
     * The meter's readings, in the renderer's clock, kept in flat arrays: four hours of them is a
     * megabyte and a half, not a hundred and forty thousand objects.
     */
    private class SpeechCapture(@Volatile var generation: Int) {
        private var times = LongArray(4_096)
        private var levels = FloatArray(4_096)
        @Volatile var size = 0
            private set

        @Synchronized
        fun add(generation: Int, timeUs: Long, levelDb: Float) {
            if (generation != this.generation || size >= MAX) return
            if (size == times.size) {
                times = times.copyOf(size * 2)
                levels = levels.copyOf(size * 2)
            }
            times[size] = timeUs
            levels[size] = levelDb
            size++
        }

        @Synchronized
        fun reset(generation: Int) {
            this.generation = generation
            size = 0
        }

        @Synchronized
        fun timeline(durationMs: Long, rendererOffsetUs: Long): SpeechTimeline {
            val timeline = SpeechTimeline(durationMs)
            for (i in 0 until size) timeline.record((times[i] - rendererOffsetUs) / 1_000, levels[i])
            return timeline
        }

        companion object {
            const val MAX = 144_000
        }
    }

    companion object {
        private const val TAG = "CrimsonCaptions"
        private const val TRACKS_TIMEOUT_MS = 15_000L
        private const val MAX_AUTOMATIC_TRIES = 3
        private const val MAX_NUDGE_MS = 30_000L
        /** A peak this far clear of the rest is not going to get any surer. */
        private const val CERTAIN_Z = 9.0
    }
}
