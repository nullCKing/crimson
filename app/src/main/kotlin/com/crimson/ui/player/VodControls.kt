package com.crimson.ui.player

/** The buttons under the time bar, left to right. */
enum class VodButton { PLAY_PAUSE, BACK_10, FORWARD_10, AUDIO_SUBTITLES, NEXT_EPISODE }

/**
 * The film-and-episode controls, as the big streaming apps have them: hidden while the video
 * plays; any key brings them up. The time bar has focus first, and Left/Right there move a
 * preview of where playback will jump to, further with each quick press or while held, and it
 * jumps when the keys stop. Down goes to the row of buttons, which Left/Right move along and OK
 * presses. Back hides the controls; Back again leaves.
 *
 * This is the state and what each key does to it, with no timers or player in it: the view model
 * runs the two timers (hide when idle, jump when scrubbing stops) and carries out [Effect]s.
 */
data class VodControls(
    val visible: Boolean = false,
    /** Null: the time bar. Otherwise the focused button. */
    val button: VodButton? = null,
    /** Where playback will jump to, while the time bar is being moved. */
    val scrubMs: Long? = null,
    /** Quick presses in a row, which make each step longer. */
    val streak: Int = 0,
    val lastScrubAt: Long = 0L,
) {
    enum class Key { LEFT, RIGHT, UP, DOWN, OK, BACK }

    sealed interface Effect {
        data object TogglePause : Effect
        data class SeekBy(val ms: Long) : Effect
        data class SeekTo(val ms: Long) : Effect
        data object OpenMenu : Effect
        data object Next : Effect
        /** Back with nothing showing: leave the player. */
        data object Leave : Effect
    }

    /** What the view model knows when a key comes in. */
    class Context(val positionMs: Long, val durationMs: Long, val buttons: List<VodButton>, val now: Long)

    fun onKey(key: Key, ctx: Context): Pair<VodControls, List<Effect>> {
        if (!visible) {
            return when (key) {
                Key.BACK -> this to listOf(Effect.Leave)
                // Netflix's way: OK pauses, and the controls come up to show it.
                Key.OK -> shown() to listOf(Effect.TogglePause)
                // Left and Right start moving through the video straight away.
                Key.LEFT, Key.RIGHT -> shown().scrub(if (key == Key.RIGHT) 1 else -1, ctx) to emptyList()
                Key.UP, Key.DOWN -> shown() to emptyList()
            }
        }
        val focused = button
        if (focused == null) {
            return when (key) {
                Key.LEFT, Key.RIGHT -> scrub(if (key == Key.RIGHT) 1 else -1, ctx) to emptyList()
                Key.OK -> if (scrubMs != null) commit() else this to listOf(Effect.TogglePause)
                Key.DOWN -> copy(button = ctx.buttons.firstOrNull()) to emptyList()
                Key.UP -> this to emptyList()
                // Back while moving the time bar puts it back where playback is.
                Key.BACK -> (if (scrubMs != null) copy(scrubMs = null, streak = 0) else hidden()) to emptyList()
            }
        }
        val buttons = ctx.buttons
        val index = buttons.indexOf(focused).coerceAtLeast(0)
        return when (key) {
            Key.LEFT -> copy(button = buttons.getOrNull(index - 1) ?: focused) to emptyList()
            Key.RIGHT -> copy(button = buttons.getOrNull(index + 1) ?: focused) to emptyList()
            Key.UP -> copy(button = null) to emptyList()
            Key.DOWN -> this to emptyList()
            Key.BACK -> hidden() to emptyList()
            Key.OK -> this to listOf(
                when (focused) {
                    VodButton.PLAY_PAUSE -> Effect.TogglePause
                    VodButton.BACK_10 -> Effect.SeekBy(-10_000)
                    VodButton.FORWARD_10 -> Effect.SeekBy(10_000)
                    VodButton.AUDIO_SUBTITLES -> Effect.OpenMenu
                    VodButton.NEXT_EPISODE -> Effect.Next
                },
            )
        }
    }

    /** The scrub has settled: jump there. */
    fun commit(): Pair<VodControls, List<Effect>> {
        val target = scrubMs ?: return this to emptyList()
        return copy(scrubMs = null, streak = 0) to listOf(Effect.SeekTo(target))
    }

    fun shown(): VodControls = copy(visible = true, button = null)

    fun hidden(): VodControls = VodControls()

    private fun scrub(direction: Int, ctx: Context): VodControls {
        val quick = ctx.now - lastScrubAt < QUICK_MS
        val streak = if (quick && scrubMs != null) streak + 1 else 0
        val base = scrubMs ?: ctx.positionMs
        val step = stepFor(streak, ctx.durationMs)
        val end = if (ctx.durationMs > 0) ctx.durationMs - 1_000 else Long.MAX_VALUE
        val target = (base + direction * step).coerceIn(0L, end.coerceAtLeast(0L))
        return copy(visible = true, button = null, scrubMs = target, streak = streak, lastScrubAt = ctx.now)
    }

    companion object {
        /** Presses closer together than this (or a held key's repeats) count as one run. */
        const val QUICK_MS = 600L
        /** The time bar jumps this long after the last press. */
        const val SETTLE_MS = 800L
        /** Controls hide after this long without a key, unless paused. */
        const val HIDE_MS = 5_000L

        /** Ten seconds at a time, then thirty, a minute and two as the presses keep coming. */
        fun stepFor(streak: Int, durationMs: Long): Long {
            val step = when {
                streak < 3 -> 10_000L
                streak < 10 -> 30_000L
                streak < 20 -> 60_000L
                else -> 120_000L
            }
            // Never more than a fiftieth of the video: a short episode stays controllable.
            return if (durationMs > 0) minOf(step, maxOf(10_000L, durationMs / 50)) else step
        }
    }
}
