package com.crimson.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.crimson.domain.GuideChannel
import com.crimson.player.MultiViewPlayers
import com.crimson.ui.components.InitialFocus
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Up to four live channels at once, in a grid. Window 0 is the main player. */
data class MultiViewState(
    /** One entry per window the account's connections allow; null is an empty window. */
    val slots: List<GuideChannel?> = emptyList(),
    val focused: Int = 0,
    /** The window a channel is being chosen for, while the picker is open. */
    val pickingFor: Int? = null,
) {
    val capacity: Int get() = slots.size
}

/**
 * Multiview: what is in each window, which one is heard, and the channel picker.
 *
 * Arrows move between windows and the sound follows. OK on an empty window opens the picker;
 * OK on a filled one, or BACK, goes full screen on that channel and closes the rest. MENU
 * replaces the channel in the window under the cursor.
 */
class MultiViewController(
    private val players: MultiViewPlayers,
    private val setMainVolume: (Float) -> Unit,
    private val tuneMain: (GuideChannel) -> Unit,
    private val urlFor: suspend (GuideChannel) -> Pair<String, String?>?,
) {
    lateinit var scope: CoroutineScope

    private val _state = MutableStateFlow(MultiViewState())
    val state: StateFlow<MultiViewState> = _state.asStateFlow()

    val isOpen: Boolean get() = _state.value.slots.isNotEmpty()

    /** Opens with [current] (already on the main player) in the first window. */
    fun open(current: GuideChannel, windows: Int) {
        val n = windows.coerceIn(2, MAX_WINDOWS)
        _state.value = MultiViewState(slots = listOf<GuideChannel?>(current) + List(n - 1) { null }, focused = 1, pickingFor = 1)
        applyAudio()
    }

    fun move(dx: Int, dy: Int) {
        val s = _state.value
        if (s.pickingFor != null || s.capacity == 0) return
        val cols = 2
        val row = s.focused / cols
        val col = s.focused % cols
        val target = (row + dy).coerceAtLeast(0) * cols + (col + dx).coerceIn(0, cols - 1)
        if (target in 0 until s.capacity && target != s.focused) {
            _state.value = s.copy(focused = target)
            applyAudio()
        }
    }

    /** OK: an empty window gets a channel; a filled one is the one to keep watching. */
    fun select(): GuideChannel? {
        val s = _state.value
        val channel = s.slots.getOrNull(s.focused)
        if (channel == null) {
            _state.value = s.copy(pickingFor = s.focused)
            return null
        }
        return channel
    }

    fun replaceFocused() {
        val s = _state.value
        if (s.capacity > 0) _state.value = s.copy(pickingFor = s.focused)
    }

    fun closePicker() {
        _state.value = _state.value.copy(pickingFor = null)
    }

    fun pick(channel: GuideChannel) {
        val s = _state.value
        val slot = s.pickingFor ?: return
        _state.value = s.copy(
            slots = s.slots.toMutableList().also { it[slot] = channel },
            pickingFor = null,
            focused = slot,
        )
        if (slot == 0) {
            tuneMain(channel)
        } else {
            scope.launch {
                val (url, agent) = urlFor(channel) ?: return@launch
                if (_state.value.slots.getOrNull(slot)?.streamId == channel.streamId) players.play(slot, url, agent)
            }
        }
        applyAudio()
    }

    /**
     * Closes Multiview and returns the channel to keep watching full screen: the one under the
     * cursor. Every extra stream is stopped and its player released.
     */
    fun close(): GuideChannel? {
        val s = _state.value
        val keep = s.slots.getOrNull(s.focused) ?: s.slots.firstOrNull()
        players.releaseAll()
        setMainVolume(1f)
        _state.value = MultiViewState()
        return keep
    }

    private fun applyAudio() {
        val f = _state.value.focused
        setMainVolume(if (f == 0) 1f else 0f)
        players.setAudible(f.takeIf { it > 0 })
    }

    companion object {
        const val MAX_WINDOWS = 4
    }
}

class MultiViewActions(
    val onPick: (GuideChannel) -> Unit,
    val onClosePicker: () -> Unit,
)

/** The grid of windows, and the channel picker over it. */
@OptIn(UnstableApi::class)
@Composable
fun MultiViewScreen(
    state: MultiViewState,
    mainPlayer: Player,
    extraPlayer: (Int) -> Player,
    candidates: List<GuideChannel>,
    actions: MultiViewActions,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val rows = if (state.capacity <= 2) 1 else 2
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 2) {
                        val i = r * 2 + c
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (i < state.capacity) {
                                Window(
                                    channel = state.slots[i],
                                    player = if (state.slots[i] == null) null else if (i == 0) mainPlayer else extraPlayer(i),
                                    focused = i == state.focused,
                                    number = i + 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "◀ ▶ ▲ ▼ move · sound follows the white frame · OK full screen · MENU change channel · BACK full screen",
            style = CrimsonType.Caption.copy(color = Color.White.copy(alpha = 0.75f)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
        )
        val picking = state.pickingFor
        if (picking != null) ChannelPicker(window = picking + 1, candidates = candidates, actions = actions)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Window(channel: GuideChannel?, player: Player?, focused: Boolean, number: Int) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10), shape)
            .border(if (focused) 4.dp else 1.dp, if (focused) Color.White else Crimson.Stroke, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (channel != null && player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        subtitleView?.visibility = android.view.View.GONE
                        this.player = player
                    }
                },
                update = { view -> if (view.player !== player) view.player = player },
                onRelease = { view -> view.player = null },
            )
            Text(
                "$number · ${channel.name}",
                style = CrimsonType.Label.copy(fontWeight = FontWeight.Bold, color = Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(CrimsonIcons.Add, null, tint = if (focused) Color.White else Crimson.TextTertiary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text("Add a channel", style = CrimsonType.Title.copy(color = if (focused) Color.White else Crimson.TextTertiary))
            }
        }
    }
}

@Composable
private fun ChannelPicker(window: Int, candidates: List<GuideChannel>, actions: MultiViewActions) {
    val first = remember { FocusRequester() }
    InitialFocus(first)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    actions.onClosePicker(); true
                } else false
            },
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(420.dp)
                .fillMaxHeight()
                .background(Crimson.Surface.copy(alpha = 0.97f))
                .padding(horizontal = 18.dp, vertical = 22.dp),
        ) {
            Text("Channel for window $window", style = CrimsonType.Headline.copy(fontSize = 22.sp))
            Text("From the lineup you were watching · BACK closes", style = CrimsonType.Caption)
            Spacer(Modifier.height(10.dp))
            if (candidates.isEmpty()) {
                Text("No channels to choose from.", style = CrimsonType.Body)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(candidates, key = { _, ch -> ch.streamId }) { index, ch ->
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
                            .tvInteractive(
                                onSelect = { actions.onPick(ch) },
                                onFocus = { focused = it },
                                focusRequester = if (index == 0) first else null,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ch.number.toString(),
                            style = CrimsonType.Label.copy(color = if (focused) Color.Black else Crimson.TextTertiary),
                            modifier = Modifier.width(54.dp),
                        )
                        Text(
                            ch.name,
                            style = CrimsonType.BodyStrong.copy(color = if (focused) Color.Black else Crimson.TextPrimary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
