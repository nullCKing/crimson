package com.crimson.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import com.crimson.core.epg.XmltvTime
import com.crimson.core.model.Country
import com.crimson.core.model.Market
import com.crimson.ui.UiState
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.BarButton
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroHeader
import com.crimson.ui.theme.RetroPage
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.labelStyle
import com.crimson.ui.theme.retroPanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings, reached with the MENU button.
 *
 * The filter rules are the point of this screen, and the asymmetry between removing and adding is
 * worth knowing while using it: unticking a country or a market is a database query and the guide
 * redraws immediately, while ticking one back on fetches the categories for it. The screen says so
 * rather than leaving the user to wonder why one direction is instant and the other is not.
 */
@Composable
fun SettingsScreen(
    ui: UiState,
    theme: GuideTheme,
    onCountries: (Set<Country>) -> Unit,
    onMarkets: (Set<Market>) -> Unit,
    onExclusions: (List<String>) -> Unit,
    onOffset: (Int) -> Unit,
    onFormat: (String) -> Unit,
    onRefreshEpg: () -> Unit,
    onExtraEpgSources: (Boolean) -> Unit,
    onRebuildCatalogue: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val rules = ui.settings.rules

    // Focus has to start on a row rather than on the screen: a D-pad move only searches the
    // focused node's siblings, so rows underneath a focused ancestor can never be reached. The
    // first row asks for focus once, when it first appears, and not again when scrolling brings
    // it back into view.
    val firstRow = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }

    RetroPage(
        theme = theme,
        modifier = modifier,
        header = {
            RetroHeader(
                title = "SETTINGS",
                subtitle = "Filters, guide data and account",
                nowMs = nowMs,
                theme = theme,
            )
        },
        buttons = listOf(
            BarButton("BACK", onClose, key = "\u25c4", keyColor = theme.keyBlue),
            BarButton("REFRESH GUIDE", onRefreshEpg, key = "A", keyColor = theme.keyRed),
        ),
        hint = "SELECT TOGGLES  \u2022  LEFT/RIGHT ADJUSTS  \u2022  MOUSE: CLICK",
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        ) {

            // ------------------------------------------------------------ countries
            item { SectionHeading("Countries", theme) }
            itemsIndexed(Country.entries.toList(), key = { _, country -> country.code }) { index, country ->
                if (index == 0) {
                    LaunchedEffect(Unit) {
                        if (!focusedOnce) {
                            focusedOnce = true
                            firstRow.requestFocus()
                        }
                    }
                }
                ToggleRow(
                    label = country.displayName,
                    checked = country in rules.countries,
                    theme = theme,
                    detail = if (country in rules.countries) null else "adding this fetches new categories",
                    onToggle = {
                        val next = if (country in rules.countries) {
                            rules.countries - country
                        } else {
                            rules.countries + country
                        }
                        // Never leave the guide with nothing in it.
                        if (next.isNotEmpty()) onCountries(next)
                    },
                    focusRequester = if (index == 0) firstRow else null,
                )
            }

            // ------------------------------------------------------------ markets
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("US local markets", theme)
                Text(
                    text = "National US channels are always kept. These are the broadcast " +
                        "affiliates whose local feeds appear in the guide.",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(6.dp))
            }
            items(Market.DEFAULT_ALLOWED.toList(), key = { it.name }) { market ->
                ToggleRow(
                    label = market.displayName,
                    checked = market in rules.markets,
                    theme = theme,
                    onToggle = {
                        val next = if (market in rules.markets) {
                            rules.markets - market
                        } else {
                            rules.markets + market
                        }
                        onMarkets(next)
                    },
                )
            }

            // ------------------------------------------------------------ exclusions
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Excluded keywords", theme)
                ExclusionRow(
                    keywords = rules.excludeKeywords,
                    theme = theme,
                    onChange = onExclusions,
                )
            }

            // ------------------------------------------------------------ guide data
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Guide data", theme)
                ToggleRow(
                    label = "Use free public guide listings",
                    checked = ui.settings.useExtraEpgSources,
                    detail = "On: the app also reads public XMLTV guides for the channels your " +
                        "provider has no listings for. Off saves about 50 MB per refresh.",
                    theme = theme,
                    onToggle = { onExtraEpgSources(!ui.settings.useExtraEpgSources) },
                )
                ActionRow(
                    label = if (ui.isRefreshingEpg) {
                        "Refreshing the guide… " + (ui.epgRefreshDetail ?: "")
                    } else "Refresh now",
                    detail = listOfNotNull(
                        ui.settings.lastEpgRefreshAt
                            .takeIf { it > 0 }
                            ?.let { "Last refreshed ${formatTime(it)}" }
                            ?: "Never refreshed",
                        ui.settings.lastEpgSummary.takeIf { it.isNotBlank() },
                    ).joinToString("  •  "),
                    theme = theme,
                    onSelect = onRefreshEpg,
                )
                ActionRow(
                    label = "Rebuild the on-demand catalogue",
                    detail = "Re-reads every film and series from your provider. Only needed if " +
                        "search or the curated rows look wrong.",
                    theme = theme,
                    onSelect = onRebuildCatalogue,
                )
                StepperRow(
                    label = "Guide time offset",
                    value = ui.settings.epgOffsetHours,
                    range = XmltvTime.MANUAL_OFFSET_HOURS,
                    theme = theme,
                    detail = "Shift programme times when the provider publishes the wrong time zone.",
                    onChange = onOffset,
                )
            }

            // ------------------------------------------------------------ stream format
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Stream format", theme)
                ToggleRow(
                    label = "Use HLS (.m3u8) instead of MPEG-TS (.ts)",
                    checked = ui.settings.streamFormat == "m3u8",
                    detail = "Your provider allows: ${ui.allowedFormats.joinToString(", ")}",
                    theme = theme,
                    onToggle = {
                        onFormat(if (ui.settings.streamFormat == "m3u8") "ts" else "m3u8")
                    },
                )
            }

            // ------------------------------------------------------------ account
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Account", theme)
                Text(
                    text = buildString {
                        ui.accountExpiry?.let { appendLine("Expires ${formatTime(it)}") }
                        appendLine("Maximum connections: ${ui.maxConnections}")
                        append(
                            if (ui.credentialsEncrypted) {
                                "Credentials are encrypted on this device."
                            } else {
                                "Credentials are stored unencrypted: this device's keystore is unavailable."
                            }
                        )
                    },
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(6.dp))
                ActionRow(
                    label = "Sign out and forget credentials",
                    detail = "Clears the channel list and guide data from this device",
                    theme = theme,
                    onSelect = onSignOut,
                )
            }

            // ------------------------------------------------------------ remote help
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeading("Remote", theme)
                Text(
                    text = "While watching: UP and DOWN change channel, SELECT opens the guide, " +
                        "PLAY/PAUSE jumps back to the last channel, INFO shows the banner.\n" +
                        "In the guide: REWIND and FAST FORWARD page the time window by two hours, " +
                        "SELECT tunes or shows details, PLAY/PAUSE stars a channel, BACK returns to the picture.\n" +
                        "With a keyboard, type a channel number to jump straight to it.\n" +
                        "With a mouse: hover to highlight, click to select, scroll the wheel to change channel, " +
                        "and use the buttons along the bottom of each screen.",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, theme: GuideTheme) {
    Text(
        text = text.uppercase(),
        style = theme.labelStyle(theme.highlight),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun RowShell(
    theme: GuideTheme,
    onSelect: () -> Unit,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .retroPanel(
                theme = theme,
                base = if (focused) theme.highlight else theme.panel,
                corner = 3.dp,
                edge = if (focused) theme.highlight else theme.panelEdge,
                gloss = focused,
            )
            .tvInteractive(
                onSelect = onSelect,
                onFocus = { focused = it },
                focusRequester = focusRequester,
                onLeft = onLeft?.let { { it(); true } },
                onRight = onRight?.let { { it(); true } },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        content(focused)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    theme: GuideTheme,
    onToggle: () -> Unit,
    detail: String? = null,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    RowShell(theme = theme, onSelect = onToggle, modifier = modifier, focusRequester = focusRequester) { focused ->
        val textColour = if (focused) theme.highlightText else theme.cellText
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A sunken check box, as on a set-top box's options page.
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(22.dp)
                    .retroPanel(
                        theme = theme,
                        base = if (checked) theme.keyGreen else theme.background,
                        corner = 2.dp,
                        edge = if (focused) theme.highlightText else theme.panelEdge,
                        gloss = checked,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Text(
                        text = "✓",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = label,
                    color = textColour,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                )
                detail?.let {
                    Text(
                        text = it,
                        color = if (focused) theme.highlightText else theme.infoDetail,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    detail: String?,
    theme: GuideTheme,
    onSelect: () -> Unit,
) {
    RowShell(theme = theme, onSelect = onSelect) { focused ->
        Column {
            Text(
                text = label,
                color = if (focused) theme.highlightText else theme.cellText,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
            detail?.let {
                Text(
                    text = it,
                    color = if (focused) theme.highlightText else theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    theme: GuideTheme,
    detail: String,
    onChange: (Int) -> Unit,
) {
    val down = { if (value > range.first) onChange(value - 1) }
    val up = { if (value < range.last) onChange(value + 1) }
    RowShell(
        theme = theme,
        onSelect = { },
        onLeft = down,
        onRight = up,
    ) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$label:  ${if (value >= 0) "+" else ""}$value hours",
                    color = if (focused) theme.highlightText else theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                )
                Text(
                    text = "$detail  Use LEFT and RIGHT to adjust.",
                    color = if (focused) theme.highlightText else theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
            // Mouse targets for the same two adjustments; not focus stops.
            StepButton("\u25c4", theme, focused, enabled = value > range.first, onClick = down)
            Spacer(Modifier.width(6.dp))
            StepButton("\u25ba", theme, focused, enabled = value < range.last, onClick = up)
        }
    }
}

@Composable
private fun StepButton(
    glyph: String,
    theme: GuideTheme,
    rowFocused: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(28.dp)
            .retroPanel(
                theme = theme,
                base = if (hovered && enabled) theme.tabFillCurrent else if (rowFocused) theme.background else theme.panelSelected,
                corner = 3.dp,
                edge = theme.panelEdge,
                gloss = true,
            )
            .tvInteractive(onSelect = { if (enabled) onClick() }, onHover = { hovered = it }, focusTarget = false),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = when {
                !enabled -> theme.infoDetail
                hovered -> theme.highlightText
                else -> theme.cellText
            },
            fontSize = theme.detailSize,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The free-text exclusion list.
 *
 * Editing text with a D-pad is miserable, so this cycles through a short set of common exclusions
 * rather than presenting a keyboard. Anything more specific is better typed on a phone, and the
 * list is stored as plain text in settings for exactly that reason.
 */
@Composable
private fun ExclusionRow(
    keywords: List<String>,
    theme: GuideTheme,
    onChange: (List<String>) -> Unit,
) {
    val suggestions = remember {
        listOf("PPV", "ADULT", "XXX", "24/7", "RADIO", "TEST")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (keywords.isEmpty()) {
                "None. A channel whose name contains an excluded word is hidden."
            } else {
                "Hiding channels containing: ${keywords.joinToString(", ")}"
            },
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
        suggestions.forEach { word ->
            ToggleRow(
                label = word,
                checked = keywords.any { it.equals(word, ignoreCase = true) },
                theme = theme,
                onToggle = {
                    val next = if (keywords.any { it.equals(word, ignoreCase = true) }) {
                        keywords.filterNot { it.equals(word, ignoreCase = true) }
                    } else {
                        keywords + word
                    }
                    onChange(next)
                },
            )
        }
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
