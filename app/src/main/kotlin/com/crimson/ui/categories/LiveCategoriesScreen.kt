package com.crimson.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crimson.data.db.CategoryEntity
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroHeader
import com.crimson.ui.theme.RetroPage
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel

data class CategoryItem(
    val id: String?,
    val name: String,
    val isFavorite: Boolean = false,
    val isSpecial: Boolean = false,
    val count: Int? = null,
)

const val ALL_CHANNELS_ID = "__all__"
const val FAVORITE_CHANNELS_ID = "__favorites__"

/**
 * Live TV categories, the step before the guide.
 *
 * Every row works from the remote (Select opens, Play/Pause favourites) and from a mouse (click
 * opens; the star at the right end of each row is a click target that toggles the favourite
 * without being a D-pad focus stop).
 */
@Composable
fun LiveCategoriesScreen(
    categories: List<CategoryEntity>,
    favoriteCategoryIds: Set<String>,
    totalChannelCount: Int,
    favoriteChannelCount: Int,
    categoryChannelCounts: Map<String, Int> = emptyMap(),
    nowMs: Long,
    theme: GuideTheme,
    onSelectCategory: (categoryId: String?, categoryName: String) -> Unit,
    onToggleFavoriteCategory: (categoryId: String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // All Channels, Favourite Channels, the pinned favourite groups, then every category.
    val items = remember(categories, favoriteCategoryIds, totalChannelCount, favoriteChannelCount, categoryChannelCounts) {
        val list = ArrayList<CategoryItem>()
        list.add(CategoryItem(id = ALL_CHANNELS_ID, name = "ALL CHANNELS", isSpecial = true, count = totalChannelCount))
        if (favoriteChannelCount > 0) {
            list.add(CategoryItem(id = FAVORITE_CHANNELS_ID, name = "FAVORITE CHANNELS", isSpecial = true, count = favoriteChannelCount))
        }
        for (cat in categories.filter { it.categoryId in favoriteCategoryIds }) {
            list.add(CategoryItem(id = cat.categoryId, name = cat.name, isFavorite = true, count = categoryChannelCounts[cat.categoryId]))
        }
        for (cat in categories) {
            list.add(
                CategoryItem(
                    id = cat.categoryId,
                    name = cat.name,
                    isFavorite = cat.categoryId in favoriteCategoryIds,
                    count = categoryChannelCounts[cat.categoryId],
                )
            )
        }
        list
    }

    val listState = rememberLazyListState()
    var focusedIndex by remember { mutableStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    val focusedItem = items.getOrNull(focusedIndex)
    val canFavourite = focusedItem != null && focusedItem.id != null && !focusedItem.isSpecial

    fun open(item: CategoryItem) {
        when (item.id) {
            ALL_CHANNELS_ID -> onSelectCategory(null, "ALL CHANNELS")
            FAVORITE_CHANNELS_ID -> onSelectCategory(FAVORITE_CHANNELS_ID, "FAVORITE CHANNELS")
            else -> onSelectCategory(item.id, item.name)
        }
    }

    RetroPage(
        theme = theme,
        modifier = modifier,
        header = {
            RetroHeader(
                title = "LIVE TV",
                subtitle = "Choose a category to open the guide",
                nowMs = nowMs,
                theme = theme,
                trailing = "${categories.size} CATEGORIES",
            )
        },
        buttons = listOf(
            BarButton("BACK", onBack, key = "◄", keyColor = theme.keyBlue),
            BarButton(
                label = if (focusedItem?.isFavorite == true) "UNSTAR GROUP" else "STAR GROUP",
                onClick = { focusedItem?.id?.let(onToggleFavoriteCategory) },
                key = "▶‖",
                keyColor = theme.keyYellow,
                enabled = canFavourite,
            ),
            BarButton("OPEN", { focusedItem?.let(::open) }, key = "OK", keyColor = theme.keyGreen, enabled = focusedItem != null),
            BarButton("SETTINGS", onOpenSettings, key = "MENU", keyColor = theme.keyBlue),
        ),
        hint = "PLAY/PAUSE STARS A GROUP  •  CLICK ★ WITH A MOUSE",
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { index, item -> "${item.id}#$index" }) { index, item ->
                var hasFocus by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .retroPanel(
                            theme = theme,
                            base = if (hasFocus) theme.highlight else if (item.isSpecial) theme.panelSelected else theme.panel,
                            corner = 4.dp,
                            edge = if (hasFocus) theme.highlight else theme.panelEdge,
                            gloss = hasFocus,
                        )
                        .tvInteractive(
                            onSelect = { open(item) },
                            onFocus = {
                                hasFocus = it
                                if (it) focusedIndex = index
                            },
                            focusRequester = if (index == 0) firstFocus else null,
                            onPlayPause = {
                                if (item.id != null && !item.isSpecial) onToggleFavoriteCategory(item.id)
                            },
                        )
                        .padding(start = 18.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val textColour = if (hasFocus) theme.highlightText else theme.cellText
                    Text(
                        text = item.name,
                        style = theme.labelStyle(textColour, bold = item.isSpecial || item.isFavorite),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    if (item.count != null) {
                        Text(
                            text = "${item.count} CH",
                            style = theme.captionStyle(if (hasFocus) theme.highlightText else theme.infoDetail),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (!item.isSpecial && item.id != null) {
                        StarToggle(
                            starred = item.isFavorite,
                            focusedRow = hasFocus,
                            theme = theme,
                            onToggle = { onToggleFavoriteCategory(item.id) },
                        )
                    } else {
                        Spacer(Modifier.width(44.dp))
                    }
                }
            }
        }
    }
}

/**
 * The star at the end of a row. A mouse click target only: it is deliberately not a focus stop,
 * so a remote moves straight down the list and uses Play/Pause to star instead.
 */
@Composable
fun StarToggle(
    starred: Boolean,
    focusedRow: Boolean,
    theme: GuideTheme,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(44.dp)
            .retroPanel(
                theme = theme,
                base = when {
                    hovered -> theme.highlight
                    focusedRow -> theme.background
                    else -> theme.panelSelected
                },
                corner = 3.dp,
                edge = if (hovered) theme.highlightText else theme.panelEdge,
                gloss = starred,
            )
            .tvInteractive(onSelect = onToggle, onHover = { hovered = it }, focusTarget = false)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (starred) "★" else "☆",
            color = when {
                hovered -> theme.highlightText
                starred -> theme.highlight
                else -> theme.infoDetail
            },
            fontSize = theme.detailSize,
            fontWeight = FontWeight.Bold,
        )
    }
}
