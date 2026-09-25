package com.crimson.ui.player

import com.crimson.ui.player.VodControls.Effect
import com.crimson.ui.player.VodControls.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodControlsTest {

    private val buttons = listOf(VodButton.PLAY_PAUSE, VodButton.BACK_10, VodButton.FORWARD_10, VodButton.AUDIO_SUBTITLES, VodButton.NEXT_EPISODE)
    private fun ctx(now: Long = 10_000L, position: Long = 600_000L) = VodControls.Context(position, 1_400_000L, buttons, now)

    private fun VodControls.press(vararg keys: Key, now: Long = 10_000L): Pair<VodControls, List<Effect>> {
        var state = this
        val effects = ArrayList<Effect>()
        for (k in keys) {
            val (s, e) = state.onKey(k, ctx(now))
            state = s
            effects += e
        }
        return state to effects
    }

    @Test
    fun `down brings the controls up and does not open a menu`() {
        val (s, effects) = VodControls().press(Key.DOWN)
        assertTrue(s.visible)
        assertNull(s.button)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `from the time bar, down reaches the buttons and left and right move along them`() {
        val (s, _) = VodControls().press(Key.UP, Key.DOWN, Key.RIGHT, Key.RIGHT, Key.RIGHT)
        assertEquals(VodButton.AUDIO_SUBTITLES, s.button)
        val (opened, effects) = s.press(Key.OK)
        assertEquals(listOf(Effect.OpenMenu), effects)
        assertEquals(VodButton.AUDIO_SUBTITLES, opened.button)
        // The row ends where it ends.
        assertEquals(VodButton.NEXT_EPISODE, s.press(Key.RIGHT, Key.RIGHT, Key.RIGHT).first.button)
        assertEquals(VodButton.PLAY_PAUSE, s.press(Key.LEFT, Key.LEFT, Key.LEFT, Key.LEFT, Key.LEFT).first.button)
        // Up is back to the time bar.
        assertNull(s.press(Key.UP).first.button)
    }

    @Test
    fun `left and right on the time bar move a preview, which jumps when committed`() {
        val (s, effects) = VodControls().press(Key.UP, Key.RIGHT, Key.RIGHT)
        assertTrue(effects.isEmpty())
        assertEquals(620_000L, s.scrubMs)
        val (after, jump) = s.commit()
        assertEquals(listOf(Effect.SeekTo(620_000L)), jump)
        assertNull(after.scrubMs)
    }

    @Test
    fun `with the controls hidden, left starts moving back straight away`() {
        val (s, _) = VodControls().press(Key.LEFT)
        assertTrue(s.visible)
        assertEquals(590_000L, s.scrubMs)
    }

    @Test
    fun `holding a direction goes further with each step`() {
        var s = VodControls().shown()
        var now = 0L
        repeat(25) {
            now += 50
            s = s.onKey(Key.RIGHT, ctx(now)).first
        }
        // 3 × 10 s, then 7 × 28 s (a fiftieth of the video), then the rest at that cap.
        assertTrue("scrubbed to ${s.scrubMs}", s.scrubMs!! > 600_000L + 25 * 10_000L)
        // A pause between presses starts over at ten seconds.
        val before = s.scrubMs!!
        s = s.onKey(Key.RIGHT, ctx(now + 2_000)).first
        assertEquals(before + 10_000L, s.scrubMs)
    }

    @Test
    fun `ok pauses, and back hides the controls before it leaves`() {
        val (shown, pause) = VodControls().press(Key.OK)
        assertEquals(listOf(Effect.TogglePause), pause)
        assertTrue(shown.visible)
        val (hidden, none) = shown.press(Key.BACK)
        assertFalse(hidden.visible)
        assertTrue(none.isEmpty())
        assertEquals(listOf(Effect.Leave), hidden.press(Key.BACK).second)
    }

    @Test
    fun `back while scrubbing puts the bar back instead of hiding`() {
        val (s, _) = VodControls().press(Key.RIGHT, Key.RIGHT, Key.BACK)
        assertTrue(s.visible)
        assertNull(s.scrubMs)
    }

    @Test
    fun `ok while scrubbing jumps at once`() {
        val (_, effects) = VodControls().press(Key.RIGHT, Key.OK)
        assertEquals(listOf(Effect.SeekTo(610_000L)), effects)
    }

    @Test
    fun `the time bar stops short of the end and at zero`() {
        var s = VodControls().shown()
        repeat(200) { s = s.onKey(Key.RIGHT, ctx(it * 50L)).first }
        assertEquals(1_399_000L, s.scrubMs)
        s = VodControls().shown()
        repeat(200) { s = s.onKey(Key.LEFT, ctx(it * 50L)).first }
        assertEquals(0L, s.scrubMs)
    }
}
