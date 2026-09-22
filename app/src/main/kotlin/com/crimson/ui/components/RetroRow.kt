package com.crimson.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel

/**
 * One thing that can appear in a poster row: a film, a series, or a channel.
 *
 * Deliberately one type rather than three. A row does not care what it is showing, and the
 * screens that build rows — Browse, On Demand, search results — all want to mix them.
 */
@Immutable
data class PosterItem(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val kind: Kind,
    /** A year, a channel number, a category — whatever belongs under the name. */
    val caption: String? = null,
    val isFavorite: Boolean = false,
) {
    enum class Kind { MOVIE, SERIES, CHANNEL }
}

/**
 * A horizontally scrolling row of posters, the shape every streaming service settled on because
 * it works: a heading, and as many covers as fit with more off the right-hand edge.
 *
 * It is driven the same two ways as everything else in the app. A remote moves along the row with
 * Left and Right and the row scrolls to follow the highlight, because a `LazyRow` brings a newly
 * focused child into view by itself. A mouse hovers to highlight, clicks to open, and turns the
 * wheel to scroll — Compose sends a vertical wheel to a horizontal scroller, which is what a
 * viewer expects here.
 */
@Composable
fun RetroRow(
    title: String,
    subtitle: String?,
    items: List<PosterItem>,
    theme: GuideTheme,
    onSelect: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocus: FocusRequester? = null,
    onFocusItem: (PosterItem) -> Unit = {},
    // Sized so a heading and a full card — cover, title and caption — fit above the button bar
    // on a 540 dp screen, with the next row just showing underneath to say the page scrolls.
    posterWidth: androidx.compose.ui.unit.Dp = 96.dp,
) {
    if (items.isEmpty()) return
    val state = rememberLazyListState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = title, style = theme.labelStyle(theme.infoTitle))
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = subtitle,
                    style = theme.captionStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(text = "${items.size}", style = theme.captionStyle(theme.highlight))
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(items, key = { "${it.kind}:${it.id}" }) { item ->
                PosterCard(
                    item = item,
                    theme = theme,
                    width = posterWidth,
                    onSelect = { onSelect(item) },
                    onFocus = { if (it) onFocusItem(item) },
                    focusRequester = if (firstItemFocus != null && item == items.first()) firstItemFocus else null,
                )
            }
        }
    }
}

/** One cover. Channels get a wide 16:9 tile, films and series the usual 2:3 poster. */
@Composable
fun PosterCard(
    item: PosterItem,
    theme: GuideTheme,
    width: androidx.compose.ui.unit.Dp,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val aspect = if (item.kind == PosterItem.Kind.CHANNEL) 16f / 9f else 0.67f

    Column(
        modifier = modifier
            .width(width)
            .retroPanel(
                theme = theme,
                base = if (focused) theme.highlight else theme.panel,
                corner = 4.dp,
                edge = if (focused) theme.highlight else theme.panelEdge,
                edgeWidth = if (focused) 2.dp else 1.dp,
                gloss = focused,
            )
            .tvInteractive(
                onSelect = onSelect,
                onFocus = {
                    focused = it
                    onFocus(it)
                },
                focusRequester = focusRequester,
            )
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .retroPanel(theme, theme.background, corner = 3.dp, edge = theme.panelEdge),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.name,
                    contentScale = if (item.kind == PosterItem.Kind.CHANNEL) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (item.kind == PosterItem.Kind.CHANNEL) 6.dp else 0.dp),
                )
            } else {
                Text(
                    text = item.name.take(18),
                    style = theme.captionStyle(theme.infoDetail),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(6.dp),
                )
            }
            if (item.isFavorite) {
                Text(
                    text = "★",
                    color = theme.highlight,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            color = if (focused) theme.highlightText else theme.cellText,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!item.caption.isNullOrBlank()) {
            Text(
                text = item.caption,
                color = if (focused) theme.highlightText else theme.infoDetail,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The search box.
 *
 * A text field on a television has one problem that needs solving explicitly: `BasicTextField`
 * reports Up and Down as consumed even on one line, so once the on-screen keyboard is dismissed a
 * remote can never leave it. The same fix the login screen uses applies — the field hands Up and
 * Down to the focus manager itself, and Select brings the keyboard back. A mouse and keyboard
 * just type.
 */
@Composable
fun RetroSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    placeholder: String = "Search films, series and channels",
    focusRequester: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .retroPanel(
                theme = theme,
                base = theme.background,
                corner = 4.dp,
                edge = if (focused) theme.highlight else theme.panelEdge,
                edgeWidth = if (focused) 2.dp else 1.dp,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "🔍", color = theme.highlight, fontSize = theme.detailSize)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                ),
                cursorBrush = SolidColor(theme.highlight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                keyboard?.show(); true
                            }
                            else -> false
                        }
                    },
            )
            if (value.isEmpty()) {
                Text(text = placeholder, style = theme.captionStyle())
            }
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            var clearHovered by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .retroPanel(
                        theme = theme,
                        base = if (clearHovered) theme.highlight else theme.panelSelected,
                        corner = 3.dp,
                        edge = theme.panelEdge,
                        gloss = true,
                    )
                    .tvInteractive(
                        onSelect = { onValueChange("") },
                        onHover = { clearHovered = it },
                        focusTarget = false,
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "CLEAR",
                    color = if (clearHovered) theme.highlightText else theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** A row heading with no posters under it, for sections that hold something else. */
@Composable
fun SectionLabel(title: String, subtitle: String?, theme: GuideTheme, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(text = title, style = theme.labelStyle(theme.infoTitle))
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(text = subtitle, style = theme.captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Filler used while a row's contents are still being worked out. */
@Composable
fun RowPlaceholder(theme: GuideTheme, text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .retroPanel(theme, theme.panel, corner = 4.dp, edge = theme.panelEdge),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = theme.captionStyle(), color = Color.Unspecified)
    }
}
