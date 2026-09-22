package com.crimson.player

import kotlinx.coroutines.flow.StateFlow

/** A channel the player can tune to. */
data class PlayableChannel(
    val streamId: Long,
    val number: Int,
    val name: String,
    val url: String,
)

/** What the UI needs to know about playback. */
data class PlaybackState(
    val channel: PlayableChannel? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** True while the backoff retry loop is running; the UI shows "Reconnecting…". */
    val isReconnecting: Boolean = false,
    /** Set once retries have been given up on. */
    val error: String? = null,
    /** Milliseconds from tune to first rendered frame, for the performance measurements. */
    val timeToFirstFrameMs: Long? = null,
)

/**
 * Playback, behind an interface.
 *
 * Captions, subtitles and audio-track selection are explicitly out of scope for this build, but
 * they are the first thing anyone will want next. Keeping the UI talking to this interface rather
 * than to ExoPlayer directly means adding a track selector later is a change to the implementation
 * and one new method here, not a rewrite of the player screen and the guide's preview window.
 *
 * There is exactly one implementation alive at a time, and it owns exactly one ExoPlayer. That is
 * not an implementation detail: Xtream accounts are sold with a connection limit, commonly one, so
 * opening a second stream for the guide's preview window would get the user's account locked out.
 * The preview and the full-screen view share this instance.
 */
interface PlayerController {

    val state: StateFlow<PlaybackState>

    /** Tunes to a channel. Tuning to the channel already playing does nothing. */
    fun play(channel: PlayableChannel)

    /** Retries the current channel after a failure the user was told about. */
    fun retry()

    fun stop()

    fun release()
}
