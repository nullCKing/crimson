package com.crimson.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Something the player can play: a live channel, or a film or an episode.
 *
 * The name is RetroGuide's, from when a channel was the only thing there was to play. [isLive]
 * is what changes the player's behaviour: a live stream that ends has failed and is reconnected,
 * a film that ends has finished.
 */
data class PlayableChannel(
    val streamId: Long,
    val number: Int,
    val name: String,
    val url: String,
    val isLive: Boolean = true,
    /** Where to start a film or episode, for resume. Ignored for live. */
    val startPositionMs: Long = 0L,
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
    /** True when paused by the viewer, as opposed to waiting on the network. */
    val isPaused: Boolean = false,
    /** A film or episode reached its end. */
    val isEnded: Boolean = false,
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

    /** Pause and resume, for films and episodes. On live they pause the picture, as a DVR would not. */
    fun togglePause()

    fun pause()

    fun resume()

    /** Seek relative to the current position, clamped to the media. No-op on live. */
    fun seekBy(deltaMs: Long)

    fun seekTo(positionMs: Long)

    /** Current position and duration; the duration is 0 until known and for live streams. */
    fun positionMs(): Long

    fun durationMs(): Long
}
