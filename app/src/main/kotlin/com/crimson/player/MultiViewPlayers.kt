package com.crimson.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
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
import com.crimson.data.xtream.XtreamClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The extra players for Multiview: windows 2 to 4. Window 1 is always [ExoPlayerController], so
 * captions, dialogue boost and the stall watchdog stay on the channel the viewer started from.
 *
 * This is the one deliberate exception to "one ExoPlayer, ever". Each window is a stream, and an
 * Xtream account allows only so many at once, so Multiview offers no more windows than the
 * account's `max_connections`, and every extra player is released the moment Multiview closes.
 * The windows are small, so they ask for at most 720p (which an HLS stream can honour; an
 * MPEG-TS stream has one size) and play muted until the viewer moves to them.
 */
@OptIn(UnstableApi::class)
class MultiViewPlayers(context: Context, httpClient: OkHttpClient) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val players = arrayOfNulls<ExoPlayer>(EXTRA_SLOTS)
    private val urls = arrayOfNulls<String>(EXTRA_SLOTS)
    private val retries = arrayOfNulls<Runnable>(EXTRA_SLOTS)

    private val streamHttp: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(ExoPlayerController.STREAM_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(ExoPlayerController.STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val factories = arrayOfNulls<OkHttpDataSource.Factory>(EXTRA_SLOTS)

    /** The player for window [slot] (1-3), created on first use. */
    fun player(slot: Int): ExoPlayer {
        val i = index(slot)
        players[i]?.let { return it }
        val http = OkHttpDataSource.Factory(streamHttp).setUserAgent(XtreamClient.USER_AGENT)
        factories[i] = http
        val player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http)))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        ExoPlayerController.MIN_BUFFER_MS,
                        ExoPlayerController.MAX_BUFFER_MS,
                        ExoPlayerController.BUFFER_FOR_PLAYBACK_MS,
                        ExoPlayerController.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(0, false)
                    .build()
            )
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1280, 720)
            .setPreferredAudioLanguage("en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.volume = 0f
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "window ${slot + 1}: ${error.errorCodeName}; reopening")
                retryLater(slot)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // A live stream that ends has dropped; open it again.
                if (playbackState == Player.STATE_ENDED) retryLater(slot)
            }
        })
        players[i] = player
        return player
    }

    fun play(slot: Int, url: String, userAgent: String?) {
        val i = index(slot)
        cancelRetry(i)
        val player = player(slot)
        factories[i]?.setUserAgent(userAgent ?: XtreamClient.USER_AGENT)
        urls[i] = url
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    /** Stops window [slot] and closes its connection. */
    fun stop(slot: Int) {
        val i = index(slot)
        cancelRetry(i)
        urls[i] = null
        players[i]?.let { it.stop(); it.clearMediaItems() }
    }

    /** Only the window the viewer is on is heard. */
    fun setAudible(slot: Int?) {
        for (i in players.indices) players[i]?.volume = if (slot != null && index(slot) == i) 1f else 0f
    }

    /** Multiview closed: every extra stream and decoder is let go. */
    fun releaseAll() {
        for (i in players.indices) {
            cancelRetry(i)
            players[i]?.release()
            players[i] = null
            factories[i] = null
            urls[i] = null
        }
    }

    private fun retryLater(slot: Int) {
        val i = index(slot)
        cancelRetry(i)
        val url = urls[i] ?: return
        val r = Runnable {
            retries[i] = null
            if (urls[i] == url) players[i]?.let { it.setMediaItem(MediaItem.fromUri(url)); it.prepare(); it.play() }
        }
        retries[i] = r
        handler.postDelayed(r, RETRY_MS)
    }

    private fun cancelRetry(i: Int) {
        retries[i]?.let(handler::removeCallbacks)
        retries[i] = null
    }

    private fun index(slot: Int): Int {
        require(slot in 1..EXTRA_SLOTS) { "window $slot is not an extra window" }
        return slot - 1
    }

    companion object {
        private const val TAG = "CrimsonMultiView"
        const val EXTRA_SLOTS = 3
        private const val RETRY_MS = 3_000L
    }
}
