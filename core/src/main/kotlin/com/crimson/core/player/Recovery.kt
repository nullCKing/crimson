package com.crimson.core.player

/**
 * Notices a stream that has stopped without saying so.
 *
 * ExoPlayer reports a failure only when a read *fails*. An IPTV server that keeps the connection
 * open and simply stops sending — which is what an overloaded or restarting stream usually does —
 * never fails a read: the player sits in "buffering" until the socket's read timeout, and with
 * the 120 s timeout the Xtream client uses for its large downloads, plus the loader's own retries,
 * that was minutes. To the viewer the channel had died for good. The same goes for a picture that
 * freezes while the player still says it is playing (a decoder or audio clock that stopped after
 * a timestamp jump in the broadcast).
 *
 * This is fed a sample about once a second and answers whether the stream should be reopened.
 * It knows nothing about ExoPlayer, so it is tested on the JVM.
 */
class StallWatchdog(private val limits: Limits) {

    /** How long each kind of stall is allowed to last. */
    data class Limits(
        /** Buffering with no new data arriving at all. */
        val noDataMs: Long,
        /** Buffering for this long even with data trickling in (too slow to ever catch up). */
        val bufferingMs: Long,
        /** "Playing" while the position does not move. */
        val frozenMs: Long,
    ) {
        companion object {
            /**
             * Live: reopening a live stream costs a second or two and lands at the live edge, so
             * it is worth doing early; a long wait buys nothing because live data that arrives
             * late is still late.
             */
            val LIVE = Limits(noDataMs = 8_000, bufferingMs = 20_000, frozenMs = 6_000)

            /** Films and episodes: a slow file is still worth waiting for a while. */
            val VOD = Limits(noDataMs = 15_000, bufferingMs = 45_000, frozenMs = 10_000)
        }
    }

    enum class State { BUFFERING, READY, OTHER }

    data class Sample(
        val nowMs: Long,
        val state: State,
        /** False while paused, when nothing is expected to move. */
        val playWhenReady: Boolean,
        val positionMs: Long,
        val bufferedPositionMs: Long,
    )

    sealed interface Verdict {
        data object Fine : Verdict
        data class Stalled(val reason: String) : Verdict
    }

    private var bufferingSince = NONE
    private var lastDataAt = NONE
    private var lastBuffered = NONE
    private var lastPosition = NONE
    private var lastMoveAt = NONE

    /** Forget everything: called on every tune and reconnect. */
    fun reset() {
        bufferingSince = NONE
        lastDataAt = NONE
        lastBuffered = NONE
        lastPosition = NONE
        lastMoveAt = NONE
    }

    fun check(sample: Sample): Verdict {
        if (!sample.playWhenReady || sample.state == State.OTHER) {
            reset()
            return Verdict.Fine
        }
        val now = sample.nowMs

        // Data arriving: the buffered position moving forward.
        if (lastBuffered == NONE || sample.bufferedPositionMs > lastBuffered) lastDataAt = now
        lastBuffered = sample.bufferedPositionMs

        return when (sample.state) {
            State.BUFFERING -> {
                lastPosition = NONE
                lastMoveAt = NONE
                if (bufferingSince == NONE) bufferingSince = now
                when {
                    now - lastDataAt >= limits.noDataMs ->
                        Verdict.Stalled("no data for ${(now - lastDataAt) / 1000} s")
                    now - bufferingSince >= limits.bufferingMs ->
                        Verdict.Stalled("buffering for ${(now - bufferingSince) / 1000} s")
                    else -> Verdict.Fine
                }
            }
            State.READY -> {
                bufferingSince = NONE
                if (lastPosition == NONE || sample.positionMs != lastPosition) lastMoveAt = now
                lastPosition = sample.positionMs
                if (now - lastMoveAt >= limits.frozenMs) Verdict.Stalled("picture frozen for ${(now - lastMoveAt) / 1000} s")
                else Verdict.Fine
            }
            State.OTHER -> Verdict.Fine
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * How long to wait before reopening a failed stream, and when to stop.
 *
 * The first retries are quick, because most drops are a blip. After that they settle at a steady
 * few seconds rather than doubling into long silences: a live channel that comes back after 40 s
 * should be on screen at 41 s, not at 64.
 *
 * A server that says the account is already using its connections (Xtream panels answer 403, 429,
 * 458 or 509, depending on the panel) is waited on longer: it is still counting the connection that
 * just dropped, and asking again at once only burns the attempt.
 */
class ReconnectPolicy(private val live: Boolean) {

    /** True when the attempt should not be made: the budget is spent. */
    fun exhausted(elapsedMs: Long): Boolean = elapsedMs >= if (live) LIVE_BUDGET_MS else VOD_BUDGET_MS

    fun delayMs(attempt: Int, httpStatus: Int?): Long {
        val base = QUICK_STEPS_MS.getOrElse(attempt) { STEADY_MS }
        return if (httpStatus != null && httpStatus in CONNECTION_LIMIT_CODES) maxOf(base, CONNECTION_LIMIT_WAIT_MS) else base
    }

    companion object {
        val QUICK_STEPS_MS = longArrayOf(300, 1_000, 2_000, 3_000)
        const val STEADY_MS = 5_000L
        const val CONNECTION_LIMIT_WAIT_MS = 6_000L

        /** Two minutes for live: long enough to ride out a provider restarting a channel. */
        const val LIVE_BUDGET_MS = 120_000L
        const val VOD_BUDGET_MS = 60_000L

        val CONNECTION_LIMIT_CODES = setOf(403, 429, 458, 509)
    }
}
