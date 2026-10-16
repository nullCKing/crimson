package com.crimson.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.crimson.core.player.ReconnectPolicy
import com.crimson.core.player.StallWatchdog
import com.crimson.data.xtream.XtreamClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single ExoPlayer instance, configured for live IPTV on low-end TV hardware.
 *
 * Three things matter here and everything else follows from them.
 *
 * **Channel changes have to feel instant.** The player instance is created once and reused; a
 * channel change is `setMediaItem` and `prepare` on the existing player, never a teardown. Buffers
 * are deliberately short — a live stream has nothing to gain from queueing fifty seconds of video,
 * and every millisecond of `bufferForPlaybackMs` is a millisecond the viewer waits after pressing
 * Up. [PlaybackState.timeToFirstFrameMs] records how long it actually took.
 *
 * **Only one stream is ever open.** Xtream accounts commonly allow a single connection, so a
 * second player for the guide's preview window would lock the user out of their own account. The
 * preview and the full-screen view are the same player, rendered into whichever surface is
 * attached.
 *
 * **A dropped stream should recover by itself.** IPTV streams stall; that is normal, not an error
 * worth a dialog. A failure retries ([ReconnectPolicy]) while the UI shows a small "Reconnecting…"
 * label, and only then gives up with a message and a retry action. A stream that stops sending
 * without failing — the common case, and the one that used to look like a channel dying for good —
 * is noticed by [StallWatchdog] and treated as a failure.
 */
@OptIn(UnstableApi::class)
class ExoPlayerController(
    context: Context,
    private val httpClient: OkHttpClient,
) : PlayerController {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override val audio = AudioEffects()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    override val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    private var tunedAtMs: Long = 0L
    private var reconnectAttempt = 0
    private var reconnectStartedAt = 0L
    private var pendingRetry: Runnable? = null
    private var bufferingSinceMs = 0L

    private var watchdog = StallWatchdog(StallWatchdog.Limits.LIVE)
    private val watchdogTick = object : Runnable {
        override fun run() {
            checkForStall()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Streams get their own HTTP client. The shared one has a 120 s read timeout because it also
     * downloads 150 MB guide files; on a stream, two minutes of silence is not a slow server, it is
     * a dead connection. Short timeouts here make a stall surface as an error the loader retries,
     * and [StallWatchdog] covers whatever still gets through.
     */
    private val streamHttp: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(STREAM_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Sends the same User-Agent as the Xtream API calls (or the one a playlist asks for). Some
     * panels limit or drop players they do not recognise; asking for the stream as a different
     * client from the one that signed in was asking for that.
     */
    private val httpFactory = OkHttpDataSource.Factory(streamHttp).setUserAgent(XtreamClient.USER_AGENT)

    /**
     * The player itself. Exposed so the Compose surface can bind to it; nothing else should touch
     * it, because every state change the UI cares about is mirrored into [state].
     */
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(
            CrimsonRenderersFactory(appContext, audio)
                // A Stick Lite decodes 1080p in hardware but a broken stream can put the decoder
                // in a bad state; falling back to software keeps a channel watchable rather than
                // showing a black screen.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(appContext, httpFactory)
            )
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                // Live streams are unbounded, so there is no "total size" to target and
                // prioritising time over size is what keeps the footprint predictable.
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()
        )
        .build()
        .apply {
            playWhenReady = true
            addListener(PlayerEvents())
            // Captions stay off until asked for; when they are on, English first, then a track
            // with no language (a broadcast's CEA-608 captions carry none).
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                // English audio when a file has a choice: files mark their original language
                // as the default, so a dual-audio anime would otherwise play in Japanese.
                .setPreferredAudioLanguage("en")
                .setPreferredTextLanguage("en")
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

    // ------------------------------------------------------------------ control

    override fun play(channel: PlayableChannel) {
        val current = _state.value
        if (current.channel?.streamId == channel.streamId && current.channel?.isLive == channel.isLive) {
            // Choosing the channel that is already on is how a viewer says "this is stuck": a
            // channel that is reconnecting, has given up, or has been buffering for a while is
            // started again. One that is playing, or only just tuning, is left alone.
            val stuck = current.error != null || current.isReconnecting ||
                (current.isBuffering && bufferingSinceMs > 0 &&
                    System.currentTimeMillis() - bufferingSinceMs > STUCK_ON_RESELECT_MS)
            if (stuck) {
                retry()
                return
            }
            if (current.isPlaying || current.isBuffering || current.isPaused) {
                if (current.isPaused) resume()
                return
            }
        }
        cancelPendingRetry()
        reconnectAttempt = 0
        reconnectStartedAt = 0L
        tunedAtMs = System.currentTimeMillis()
        _state.value = PlaybackState(
            channel = channel,
            isBuffering = true,
            isPlaying = false,
            isReconnecting = false,
            error = null,
            timeToFirstFrameMs = null,
        )
        startStream(channel)
    }

    private fun startStream(channel: PlayableChannel, positionMs: Long = channel.startPositionMs) {
        audio.generation++
        _cues.value = emptyList()
        httpFactory.setUserAgent(channel.userAgent ?: XtreamClient.USER_AGENT)
        httpFactory.setDefaultRequestProperties(
            channel.referrer?.let { mapOf("Referer" to it) } ?: emptyMap()
        )
        watchdog = StallWatchdog(if (channel.isLive) StallWatchdog.Limits.LIVE else StallWatchdog.Limits.VOD)
        bufferingSinceMs = System.currentTimeMillis()
        handler.removeCallbacks(watchdogTick)
        handler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
        // A track chosen by hand was for the last title.
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        if (!channel.isLive && positionMs > 0) {
            exoPlayer.setMediaItem(MediaItem.fromUri(channel.url), positionMs)
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(channel.url))
        }
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun togglePause() {
        if (_state.value.isPaused) resume() else pause()
    }

    override fun pause() {
        if (_state.value.channel == null) return
        exoPlayer.pause()
        _state.value = _state.value.copy(isPaused = true)
    }

    override fun resume() {
        if (_state.value.channel == null) return
        if (_state.value.isEnded) {
            exoPlayer.seekTo(0)
            _state.value = _state.value.copy(isEnded = false)
        }
        exoPlayer.play()
        _state.value = _state.value.copy(isPaused = false)
    }

    override fun seekBy(deltaMs: Long) {
        if (_state.value.channel?.isLive != false) return
        seekTo(exoPlayer.currentPosition + deltaMs)
    }

    override fun seekTo(positionMs: Long) {
        if (_state.value.channel?.isLive != false) return
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exoPlayer.seekTo(positionMs.coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L)))
        if (_state.value.isEnded) _state.value = _state.value.copy(isEnded = false)
    }

    override fun positionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)

    override fun setEmbeddedCaptions(enabled: Boolean) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        if (!enabled) _cues.value = emptyList()
    }

    override fun setDialogueBoost(enabled: Boolean) {
        if (audio.dialogueBoost == enabled) return
        audio.dialogueBoost = enabled
        // Encoded (passthrough) audio never reaches the processor. The sink now refuses it, but
        // the renderer only asks again when the stream is prepared, so prepare it again from
        // where it is: a brief rebuffer, once, and only for a Dolby track.
        val channel = _state.value.channel ?: return
        if (enabled && audio.passthrough) {
            Log.i(TAG, "reloading ${channel.name} to decode its audio for dialogue boost")
            startStream(channel, if (channel.isLive) 0L else exoPlayer.currentPosition)
        }
    }

    override fun selectAudio(id: String) {
        val (g, t) = id.split(':').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return
        val group = exoPlayer.currentTracks.groups.getOrNull(g)?.takeIf { it.type == C.TRACK_TYPE_AUDIO } ?: return
        if (t !in 0 until group.length) return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            .build()
    }

    override fun setPreferEnglishAudio(prefer: Boolean) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .apply { if (prefer) setPreferredAudioLanguage("en") else setPreferredAudioLanguages() }
            .build()
    }

    /** Every playable audio track, labelled by language and channels, in the file's order. */
    private fun audioOptions(tracks: Tracks): List<AudioOption> {
        val out = ArrayList<AudioOption>()
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t, true)) continue
                val format = group.getTrackFormat(t)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                val name = language?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf(String::isNotBlank) ?: it }
                    ?: format.label?.takeIf { it.isNotBlank() }
                    ?: "Track ${out.size + 1}"
                val channels = when (format.channelCount) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> null
                }
                val extra = format.label?.takeIf { it.isNotBlank() && language != null && !it.equals(name, ignoreCase = true) }
                out += AudioOption(
                    id = "$g:$t",
                    label = listOfNotNull(name, extra, channels).joinToString(" · "),
                    language = language,
                    selected = group.isTrackSelected(t),
                )
            }
        }
        return out
    }

    override fun setVolume(level: Float) {
        exoPlayer.volume = level.coerceIn(0f, 1f)
    }

    override fun videoFrameRate(): Double? =
        exoPlayer.videoFormat?.frameRate?.takeIf { it > 0f }?.toDouble()

    /** Whether the file has an English (or unlabelled) subtitle track, as opposed to broadcast captions. */
    private fun subtitleTrackIn(tracks: Tracks): Boolean = tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { i ->
            val format = group.getTrackFormat(i)
            val mime = format.sampleMimeType
            val broadcast = mime == MimeTypes.APPLICATION_CEA608 || mime == MimeTypes.APPLICATION_CEA708 ||
                mime == MimeTypes.APPLICATION_MP4CEA608
            val lang = format.language?.lowercase()
            !broadcast && group.isTrackSupported(i) && (lang == null || lang == "und" || lang.startsWith("en"))
        }
    }

    override fun durationMs(): Long =
        if (_state.value.channel?.isLive != false) 0L else exoPlayer.duration.takeIf { it > 0 } ?: 0L

    override fun retry() {
        val channel = _state.value.channel ?: return
        cancelPendingRetry()
        reconnectAttempt = 0
        reconnectStartedAt = 0L
        tunedAtMs = System.currentTimeMillis()
        _state.value = _state.value.copy(
            isBuffering = true,
            isReconnecting = false,
            error = null,
            timeToFirstFrameMs = null,
            isEnded = false,
        )
        // A film that failed part way resumes where it was, not from the start.
        startStream(channel, if (channel.isLive) 0L else exoPlayer.currentPosition)
    }

    override fun stop() {
        cancelPendingRetry()
        handler.removeCallbacks(watchdogTick)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlaybackState()
    }

    override fun release() {
        cancelPendingRetry()
        handler.removeCallbacks(watchdogTick)
        exoPlayer.release()
        _state.value = PlaybackState()
    }

    // ------------------------------------------------------------------ reconnect

    private fun cancelPendingRetry() {
        pendingRetry?.let(handler::removeCallbacks)
        pendingRetry = null
    }

    /**
     * Samples the player once a second and reopens a stream that has stalled without failing.
     * Nothing is checked while a reconnect is already pending or after giving up.
     */
    private fun checkForStall() {
        val st = _state.value
        if (st.channel == null || st.error != null || pendingRetry != null) return
        val state = when (exoPlayer.playbackState) {
            Player.STATE_BUFFERING -> StallWatchdog.State.BUFFERING
            Player.STATE_READY -> StallWatchdog.State.READY
            else -> StallWatchdog.State.OTHER
        }
        val verdict = watchdog.check(
            StallWatchdog.Sample(
                nowMs = System.currentTimeMillis(),
                state = state,
                playWhenReady = exoPlayer.playWhenReady && !st.isPaused,
                positionMs = exoPlayer.currentPosition,
                bufferedPositionMs = exoPlayer.bufferedPosition,
            )
        )
        if (verdict is StallWatchdog.Verdict.Stalled) {
            Log.w(TAG, "${st.channel.name} stalled (${verdict.reason}); reopening")
            scheduleReconnect("STALLED", httpStatus = null)
        }
    }

    /**
     * Schedules the next reconnect attempt, or gives up once [ReconnectPolicy] says the budget is
     * spent.
     *
     * The stream is stopped at once rather than when the retry fires: a stalled connection still
     * holds one of the account's connections, and the provider has to see it close before it will
     * hand out another.
     */
    private fun scheduleReconnect(reason: String, httpStatus: Int?) {
        val channel = _state.value.channel ?: return
        val now = System.currentTimeMillis()
        if (reconnectStartedAt == 0L) reconnectStartedAt = now
        val policy = ReconnectPolicy(channel.isLive)
        val resumeAt = if (channel.isLive) 0L else exoPlayer.currentPosition
        cancelPendingRetry()
        watchdog.reset()
        exoPlayer.stop()

        if (policy.exhausted(now - reconnectStartedAt)) {
            Log.w(TAG, "giving up on ${channel.name} after ${now - reconnectStartedAt} ms: $reason")
            handler.removeCallbacks(watchdogTick)
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = false,
                isReconnecting = false,
                error = reason,
            )
            return
        }

        val delay = policy.delayMs(reconnectAttempt, httpStatus)
        reconnectAttempt++
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = true,
            isReconnecting = true,
            error = null,
        )
        val runnable = Runnable {
            pendingRetry = null
            if (_state.value.channel?.streamId == channel.streamId) startStream(channel, resumeAt)
        }
        pendingRetry = runnable
        handler.postDelayed(runnable, delay)
    }

    // ------------------------------------------------------------------ events

    private inner class PlayerEvents : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!_state.value.isBuffering) bufferingSinceMs = System.currentTimeMillis()
                    _state.value = _state.value.copy(isBuffering = true, isPlaying = false)
                }

                Player.STATE_READY -> {
                    reconnectAttempt = 0
                    reconnectStartedAt = 0L
                    bufferingSinceMs = 0L
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        isPlaying = exoPlayer.playWhenReady,
                        isReconnecting = false,
                        error = null,
                    )
                }

                Player.STATE_ENDED ->
                    if (_state.value.channel?.isLive == false) {
                        // A film that ends has simply finished.
                        _state.value = _state.value.copy(isPlaying = false, isBuffering = false, isEnded = true)
                    } else {
                        // A live stream should never end. When one does, the source went away, so
                        // treat it exactly like an error rather than sitting on a frozen frame.
                        scheduleReconnect(REASON_ENDED, httpStatus = null)
                    }

                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(
                isPlaying = isPlaying,
                isPaused = if (isPlaying) false else _state.value.isPaused,
            )
        }

        override fun onRenderedFirstFrame() {
            // Fires again every time the output surface changes — the guide's preview window
            // replacing the full-screen view, for instance — so only the first frame after a
            // tune is the measurement; the rest would be logged against a stale tune time.
            if (_state.value.timeToFirstFrameMs != null) return
            val elapsed = System.currentTimeMillis() - tunedAtMs
            Log.i(TAG, "first frame for ${_state.value.channel?.name} in $elapsed ms")
            _state.value = _state.value.copy(
                timeToFirstFrameMs = elapsed,
                isBuffering = false,
                isReconnecting = false,
            )
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.groups.isEmpty()) return
            _state.value = _state.value.copy(
                tracksKnown = true,
                hasSubtitleTrack = subtitleTrackIn(tracks),
                audioTracks = audioOptions(tracks),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            val status = httpStatusOf(error)
            Log.w(TAG, "playback error: ${error.errorCodeName}" + (status?.let { " (HTTP $it)" } ?: ""), error)
            scheduleReconnect(error.errorCodeName ?: REASON_UNKNOWN, status)
        }
    }

    /** The HTTP status behind a load error, when there was one (a refused connection, a 509). */
    private fun httpStatusOf(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    companion object {
        private const val TAG = "CrimsonPlayer"

        /**
         * Buffer sizes for live IPTV.
         *
         * A live MPEG-TS stream arrives in real time after the server's opening burst, so the
         * buffer never grows much past what was there when playback started: the start threshold
         * *is* the cushion the stream has against network jitter for as long as it plays. At
         * 0.6 s, any hiccup longer than that was a rebuffer. 1.5 s costs up to a second more on a
         * channel change from a server that does not burst (most Xtream panels do), and each
         * rebuffer waits for 4 s, so a stream that hiccuped once has a bigger cushion afterwards
         * instead of rebuffering again on the next one. The maximum only bounds memory (about
         * 30 MB of 1080p at 30 s).
         */
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 30_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000

        const val STREAM_CONNECT_TIMEOUT_S = 10L
        const val STREAM_READ_TIMEOUT_S = 12L
        const val WATCHDOG_INTERVAL_MS = 1_000L

        /** Re-selecting a channel that has been buffering this long starts it again. */
        const val STUCK_ON_RESELECT_MS = 3_000L

        const val REASON_ENDED = "STREAM_ENDED"
        const val REASON_UNKNOWN = "UNKNOWN"
    }
}
