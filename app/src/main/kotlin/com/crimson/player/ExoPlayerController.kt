package com.crimson.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

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
 * worth a dialog. A failure retries with a widening backoff for [RECONNECT_BUDGET_MS] while the UI
 * shows a small "Reconnecting…" label, and only then gives up with a message and a retry action.
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

    private var tunedAtMs: Long = 0L
    private var reconnectAttempt = 0
    private var reconnectStartedAt = 0L
    private var pendingRetry: Runnable? = null

    /**
     * The player itself. Exposed so the Compose surface can bind to it; nothing else should touch
     * it, because every state change the UI cares about is mirrored into [state].
     */
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(
            DefaultRenderersFactory(appContext)
                // A Stick Lite decodes 1080p in hardware but a broken stream can put the decoder
                // in a bad state; falling back to software keeps a channel watchable rather than
                // showing a black screen.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    appContext,
                    OkHttpDataSource.Factory(httpClient).setUserAgent(USER_AGENT),
                )
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
        }

    // ------------------------------------------------------------------ control

    override fun play(channel: PlayableChannel) {
        if (_state.value.channel?.streamId == channel.streamId &&
            (_state.value.isPlaying || _state.value.isBuffering)
        ) {
            return
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

    private fun startStream(channel: PlayableChannel) {
        exoPlayer.setMediaItem(MediaItem.fromUri(channel.url))
        exoPlayer.prepare()
        exoPlayer.play()
    }

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
        )
        startStream(channel)
    }

    override fun stop() {
        cancelPendingRetry()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlaybackState()
    }

    override fun release() {
        cancelPendingRetry()
        exoPlayer.release()
        _state.value = PlaybackState()
    }

    // ------------------------------------------------------------------ reconnect

    private fun cancelPendingRetry() {
        pendingRetry?.let(handler::removeCallbacks)
        pendingRetry = null
    }

    /**
     * Schedules the next reconnect attempt, or gives up.
     *
     * The delay doubles from [RECONNECT_BASE_DELAY_MS], which means about six attempts inside the
     * thirty-second budget: frequent enough that a brief blip recovers without the viewer noticing,
     * spaced enough that a genuinely dead stream is not hammered.
     */
    private fun scheduleReconnect(reason: String) {
        val channel = _state.value.channel ?: return
        val now = System.currentTimeMillis()
        if (reconnectStartedAt == 0L) reconnectStartedAt = now

        if (now - reconnectStartedAt >= RECONNECT_BUDGET_MS) {
            Log.w(TAG, "giving up on ${channel.name} after ${now - reconnectStartedAt} ms: $reason")
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = false,
                isReconnecting = false,
                error = reason,
            )
            return
        }

        val delay = (RECONNECT_BASE_DELAY_MS shl reconnectAttempt.coerceAtMost(5))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempt++
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = true,
            isReconnecting = true,
            error = null,
        )
        val runnable = Runnable {
            pendingRetry = null
            if (_state.value.channel?.streamId == channel.streamId) startStream(channel)
        }
        pendingRetry = runnable
        handler.postDelayed(runnable, delay)
    }

    // ------------------------------------------------------------------ events

    private inner class PlayerEvents : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING ->
                    _state.value = _state.value.copy(isBuffering = true, isPlaying = false)

                Player.STATE_READY -> {
                    reconnectAttempt = 0
                    reconnectStartedAt = 0L
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        isPlaying = exoPlayer.playWhenReady,
                        isReconnecting = false,
                        error = null,
                    )
                }

                Player.STATE_ENDED ->
                    // A live stream should never end. When one does, the source went away, so
                    // treat it exactly like an error rather than sitting on a frozen frame.
                    scheduleReconnect(REASON_ENDED)

                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
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

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error: ${error.errorCodeName}", error)
            scheduleReconnect(error.errorCodeName ?: REASON_UNKNOWN)
        }
    }

    companion object {
        private const val TAG = "CrimsonPlayer"
        private const val USER_AGENT = "Crimson/1.0 (AndroidTV)"

        /**
         * Live buffer sizes. Short on purpose: the only thing a large buffer buys on a live
         * stream is a longer wait after every channel change.
         */
        const val MIN_BUFFER_MS = 1_500
        const val MAX_BUFFER_MS = 6_000
        const val BUFFER_FOR_PLAYBACK_MS = 600
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_200

        /** How long to keep retrying a stalled stream before telling the user. */
        const val RECONNECT_BUDGET_MS = 30_000L
        const val RECONNECT_BASE_DELAY_MS = 500L
        const val RECONNECT_MAX_DELAY_MS = 8_000L

        const val REASON_ENDED = "STREAM_ENDED"
        const val REASON_UNKNOWN = "UNKNOWN"
    }
}
