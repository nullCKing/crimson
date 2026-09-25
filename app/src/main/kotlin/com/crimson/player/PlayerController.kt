package com.crimson.player

import androidx.media3.common.text.Cue
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
    /** The stream's tracks have been read, so [hasSubtitleTrack] means something. */
    val tracksKnown: Boolean = false,
    /** The file carries an English or unlabelled subtitle track of its own. */
    val hasSubtitleTrack: Boolean = false,
    /** The stream's audio tracks (languages), for the Audio & Subtitles menu. */
    val audioTracks: List<AudioOption> = emptyList(),
)

/** One of a stream's audio tracks: "English · 5.1", "Japanese · Stereo". */
data class AudioOption(val id: String, val label: String, val language: String?, val selected: Boolean)

/**
 * Playback, behind an interface.
 *
 * The UI talks to this rather than to ExoPlayer: the stream's own captions ([cues]) and dialogue
 * boost are a few methods here, not something every screen reaches into the player for.
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

    /** Captions carried in the stream (a subtitle track, or a broadcast's CEA-608), as decoded. */
    val cues: StateFlow<List<Cue>>

    /** Shows or hides the stream's own captions. */
    fun setEmbeddedCaptions(enabled: Boolean)

    /** Turns dialogue boost on or off; it fades in within a few milliseconds. */
    fun setDialogueBoost(enabled: Boolean)

    /** Plays audio track [id] (from [PlaybackState.audioTracks]) for the rest of this title. */
    fun selectAudio(id: String)

    /**
     * Whether an English track is chosen when a file has several (a dubbed anime's English dub
     * rather than the Japanese the file marks as its default).
     */
    fun setPreferEnglishAudio(prefer: Boolean)

    /** The player's own volume, 0–1, on top of the TV's; for turning a theme song down while it is being recognised. */
    fun setVolume(level: Float)

    /** The video's frame rate, once known; for matching downloaded subtitles to it. */
    fun videoFrameRate(): Double?

    /** The audio path's switches, and its tap for caption sync. */
    val audio: AudioEffects
}
