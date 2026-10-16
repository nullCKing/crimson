package com.crimson.core.player

import com.crimson.core.player.StallWatchdog.Sample
import com.crimson.core.player.StallWatchdog.State
import com.crimson.core.player.StallWatchdog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {

    private val live = StallWatchdog.Limits.LIVE

    /** Feeds one sample a second from [fromS] to [toS] and returns the first stall, if any, with its second. */
    private fun run(
        dog: StallWatchdog,
        fromS: Int,
        toS: Int,
        sample: (s: Int) -> Sample,
    ): Pair<Int, Verdict.Stalled>? {
        for (s in fromS..toS) {
            val v = dog.check(sample(s))
            if (v is Verdict.Stalled) return s to v
        }
        return null
    }

    @Test
    fun `a stream that stops sending is caught after the no-data limit, not minutes later`() {
        val dog = StallWatchdog(live)
        // Playing normally for 30 s, then the server stops sending: buffering, buffered position stuck.
        val stall = run(dog, 0, 120) { s ->
            if (s < 30) Sample(s * 1000L, State.READY, true, s * 1000L, s * 1000L + 2000)
            else Sample(s * 1000L, State.BUFFERING, true, 30_000, 32_000)
        }!!
        assertEquals(30 + live.noDataMs.toInt() / 1000, stall.first)
        assertTrue(stall.second.reason.startsWith("no data"))
    }

    @Test
    fun `a slow trickle is given the longer buffering limit`() {
        val dog = StallWatchdog(live)
        val stall = run(dog, 0, 120) { s ->
            Sample(s * 1000L, State.BUFFERING, true, 0, s * 100L)
        }!!
        assertEquals(live.bufferingMs.toInt() / 1000, stall.first)
        assertTrue(stall.second.reason.startsWith("buffering"))
    }

    @Test
    fun `a frozen picture is caught while the player says it is playing`() {
        val dog = StallWatchdog(live)
        val stall = run(dog, 0, 60) { s ->
            val pos = if (s < 10) s * 1000L else 10_000L
            Sample(s * 1000L, State.READY, true, pos, pos + 3000)
        }!!
        assertEquals(10 + live.frozenMs.toInt() / 1000, stall.first)
    }

    @Test
    fun `pausing never counts as a stall`() {
        val dog = StallWatchdog(live)
        assertEquals(null, run(dog, 0, 300) { s -> Sample(s * 1000L, State.READY, false, 5_000, 8_000) })
        assertEquals(null, run(dog, 0, 300) { s -> Sample(s * 1000L, State.BUFFERING, false, 5_000, 5_000) })
    }

    @Test
    fun `short rebuffers that recover are left alone`() {
        val dog = StallWatchdog(live)
        // Every 10 s, 4 s of buffering with nothing arriving, then data and playback again.
        val stall = run(dog, 0, 300) { s ->
            val phase = s % 10
            val stuckAt = (s - phase) * 1000L
            if (phase < 4) Sample(s * 1000L, State.BUFFERING, true, stuckAt, stuckAt + 500)
            else Sample(s * 1000L, State.READY, true, s * 1000L, s * 1000L + 1500 + phase)
        }
        assertEquals(null, stall)
    }

    @Test
    fun `reset forgets a stall in progress`() {
        val dog = StallWatchdog(live)
        run(dog, 0, 6) { s -> Sample(s * 1000L, State.BUFFERING, true, 0, 0) }
        dog.reset()
        assertEquals(null, run(dog, 7, 13) { s -> Sample(s * 1000L, State.BUFFERING, true, 0, 0) })
    }

    @Test
    fun `reconnect waits settle at a few seconds and respect connection limits`() {
        val p = ReconnectPolicy(live = true)
        assertEquals(300L, p.delayMs(0, null))
        assertEquals(ReconnectPolicy.STEADY_MS, p.delayMs(20, null))
        assertEquals(ReconnectPolicy.CONNECTION_LIMIT_WAIT_MS, p.delayMs(0, 509))
        assertEquals(ReconnectPolicy.CONNECTION_LIMIT_WAIT_MS, p.delayMs(1, 403))
        assertEquals(300L, p.delayMs(0, 404))
        assertFalse(p.exhausted(119_000))
        assertTrue(p.exhausted(120_000))
    }
}
