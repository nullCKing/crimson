package com.crimson.ui.input

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * One modifier that makes a row, tile or button behave the same from a remote and from a mouse.
 *
 * The app was built for a D-pad, where "the thing with focus" and "the thing that is highlighted"
 * are the same thing. A mouse fits into that model without a second highlight system: hovering
 * an item gives it focus, so the yellow highlight follows the pointer exactly as it follows the
 * D-pad, and clicking does what Select does. Touch (the emulator's mouse, a phone mirroring
 * screen) has no hover, so a press both focuses and selects.
 *
 * Every screen used to hand-roll `focusable` + `onPreviewKeyEvent` + `clickable` with small
 * differences between them; this is the one copy.
 *
 * @param onSelect     Select on the remote, or a click.
 * @param onFocus      Called with true and false as focus arrives and leaves; drives the highlight.
 * @param onHover      Called as the pointer enters and leaves, for elements that are not focus
 *                     targets (the button bar) but still want a hover highlight.
 * @param focusTarget  False for elements that a D-pad should skip. The bottom button bar and the
 *                     star toggles are mouse-only conveniences; if they took focus the remote
 *                     would have to step over them to reach anything.
 * @param onLeft/onRight  Left and Right on the remote, for steppers. Return true to consume.
 * @param onPlayPause  The Play/Pause key, which the app uses for "favourite" on lists.
 */
@Composable
fun Modifier.tvInteractive(
    onSelect: () -> Unit,
    onFocus: ((Boolean) -> Unit)? = null,
    onHover: ((Boolean) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusTarget: Boolean = true,
    onLeft: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    onPlayPause: (() -> Unit)? = null,
): Modifier {
    val requester = focusRequester ?: remember { FocusRequester() }

    // The callbacks are read through state so a recomposition that hands in a fresh lambda (which
    // is every recomposition, for an inline lambda) does not restart the pointer coroutine and
    // lose a press that is half way to becoming a click.
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnHover by rememberUpdatedState(onHover)

    // Built on a fresh Modifier and appended below. Built on the receiver, it carried every
    // modifier before this one along with it, so the chain was applied twice: a second focus
    // ring inside every focused card, and backgrounds, clips and scales doubled.
    val pointer = Modifier.pointerInput(focusTarget) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            // A click is a press and a release on this element without a drag in between. The
            // drag check is what stops a finger scrolling a list from "clicking" whatever row it
            // happens to lift off.
            var pressedAt: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull()
                when (event.type) {
                    PointerEventType.Enter -> {
                        currentOnHover?.invoke(true)
                        if (focusTarget) runCatching { requester.requestFocus() }
                    }
                    PointerEventType.Exit -> {
                        currentOnHover?.invoke(false)
                        pressedAt = null
                    }
                    PointerEventType.Press -> {
                        // A right-click is left alone so the platform can keep treating it as Back.
                        pressedAt = if (event.buttons.isSecondaryPressed) null else change?.position
                    }
                    PointerEventType.Move -> {
                        val origin = pressedAt
                        if (origin != null && change != null && (change.position - origin).getDistance() > slop) {
                            pressedAt = null
                        }
                    }
                    PointerEventType.Release -> {
                        val origin = pressedAt
                        pressedAt = null
                        if (origin != null && change != null && !change.isConsumed) {
                            change.consume()
                            if (focusTarget) runCatching { requester.requestFocus() }
                            currentOnSelect()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    if (!focusTarget) return this.then(pointer)

    return this
        .focusRequester(requester)
        .onFocusChanged { onFocus?.invoke(it.isFocused) }
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSelect(); true }
                Key.DirectionLeft -> onLeft?.invoke() ?: false
                Key.DirectionRight -> onRight?.invoke() ?: false
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause ->
                    if (onPlayPause != null) { onPlayPause(); true } else false
                else -> false
            }
        }
        .then(pointer)
}

/**
 * Mouse-wheel scrolling, as discrete steps.
 *
 * A wheel notch on a television is a channel change, not a scroll, so the callback fires once
 * per notch rather than by pixel. Trackpads that scroll smoothly accumulate until a notch's
 * worth has gone by.
 */
fun Modifier.mouseWheel(onUp: () -> Unit, onDown: () -> Unit): Modifier =
    pointerInput(Unit) {
        var accumulated = 0f
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                accumulated += change.scrollDelta.y
                change.consume()
                while (accumulated >= 1f) { onDown(); accumulated -= 1f }
                while (accumulated <= -1f) { onUp(); accumulated += 1f }
            }
        }
    }

/**
 * Reports that a pointer moved or was pressed anywhere inside the element, without consuming
 * anything. The full-screen player uses it to show its mouse toolbar for a few seconds.
 */
fun Modifier.onPointerActivity(onActivity: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Move, PointerEventType.Press, PointerEventType.Enter -> onActivity()
                    else -> Unit
                }
            }
        }
    }
