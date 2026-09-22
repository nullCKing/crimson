package com.crimson.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.core.epg.XmltvTime
import com.crimson.core.model.Country
import com.crimson.core.model.Market
import com.crimson.ui.UiState
import com.crimson.ui.components.Avatar
import com.crimson.ui.components.PageBackground
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType
import androidx.compose.runtime.withFrameNanos
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActions(
    val onCountries: (Set<Country>) -> Unit,
    val onMarkets: (Set<Market>) -> Unit,
    val onExclusions: (List<String>) -> Unit,
    val onOffset: (Int) -> Unit,
    val onFormat: (String) -> Unit,
    val onRefreshEpg: () -> Unit,
    val onExtraEpgSources: (Boolean) -> Unit,
    val onLivePreviews: (Boolean) -> Unit,
    val onRebuildCatalogue: () -> Unit,
    val onSwitchProfile: () -> Unit,
    val onEditProfile: () -> Unit,
)

private enum class Section(val label: String, val icon: ImageVector) {
    PROFILE("Profile", CrimsonIcons.People),
    LIVE("Live TV", CrimsonIcons.LiveTv),
    GUIDE("Guide Data", CrimsonIcons.Guide),
    LIBRARY("Movies & Shows", CrimsonIcons.Movie),
    PLAYBACK("Playback", CrimsonIcons.Play),
    HELP("Remote & Help", CrimsonIcons.Info),
}

/**
 * Settings: a rail of sections on the left, the section's options on the right. Moving along the
 * rail shows each section; Right steps into it.
 *
 * The channel filter is RetroGuide's and keeps its asymmetry: unticking a country or a market is
 * a database query and the lineup changes at once, while ticking one back on fetches that
 * country's categories. The rows say so rather than leaving the viewer to wonder.
 */
@Composable
fun SettingsScreen(ui: UiState, actions: SettingsActions) {
    var section by rememberSaveable { mutableStateOf(Section.PROFILE) }
    val first = remember { FocusRequester() }
    com.crimson.ui.components.InitialFocus(first)
    PageBackground {
        Row(Modifier.fillMaxSize().padding(start = Crimson.ScreenPadding, top = 30.dp, end = 32.dp)) {
            Column(Modifier.width(220.dp).fillMaxHeight()) {
                Text("Settings", style = CrimsonType.Headline)
                Spacer(Modifier.height(4.dp))
                Text(ui.profile?.name ?: "", style = CrimsonType.Body)
                Spacer(Modifier.height(20.dp))
                Section.entries.forEachIndexed { index, s ->
                    RailItem(
                        s.label, s.icon, selected = s == section,
                        onFocus = { section = s },
                        focusRequester = if (index == 0) first else null,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.width(28.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (section) {
                        Section.PROFILE -> item { ProfileSection(ui, actions) }
                        Section.LIVE -> item { LiveSection(ui, actions) }
                        Section.GUIDE -> item { GuideSection(ui, actions) }
                        Section.LIBRARY -> item { LibrarySection(ui, actions) }
                        Section.PLAYBACK -> item { PlaybackSection(ui, actions) }
                        Section.HELP -> item { HelpSection() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSection(ui: UiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.profile?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Avatar(p.avatar, 64.dp, Modifier.clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(p.name, style = CrimsonType.Title)
                    Text(p.serverUrl, style = CrimsonType.Caption)
                    Text(
                        listOfNotNull(
                            ui.accountExpiry?.let { "Expires ${formatTime(it)}" },
                            "${ui.totalChannelCount} channels",
                            "${ui.favoriteChannelCount} favorites",
                        ).joinToString("  ·  "),
                        style = CrimsonType.Caption,
                    )
                }
            }
        }
        SectionTitle("Profile")
        ActionRow("Switch profile", "Go back to Who's watching?", CrimsonIcons.People, actions.onSwitchProfile)
        ActionRow("Edit this profile", "Name, picture, or the Xtream login it uses", CrimsonIcons.Edit, actions.onEditProfile)
        Text(
            if (ui.profilesEncrypted) "Profiles and their passwords are encrypted on this device."
            else "This device's keystore is unavailable, so profiles are stored unencrypted.",
            style = CrimsonType.Caption,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun LiveSection(ui: UiState, actions: SettingsActions) {
    val rules = ui.settings.rules
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Countries in your lineup")
        Text("Removing a country is instant. Adding one reads its channels from your provider.", style = CrimsonType.Caption)
        Country.entries.forEach { country ->
            ToggleRow(
                label = country.displayName,
                checked = country in rules.countries,
                detail = if (country in rules.countries) null else "Adding this fetches new categories",
                onToggle = {
                    val next = if (country in rules.countries) rules.countries - country else rules.countries + country
                    if (next.isNotEmpty()) actions.onCountries(next)
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        SectionTitle("US local channels")
        Text("National US channels are always kept. These are the local affiliates shown.", style = CrimsonType.Caption)
        Market.DEFAULT_ALLOWED.forEach { market ->
            ToggleRow(
                label = market.displayName,
                checked = market in rules.markets,
                onToggle = {
                    actions.onMarkets(if (market in rules.markets) rules.markets - market else rules.markets + market)
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        SectionTitle("Hide channels containing")
        listOf("PPV", "ADULT", "XXX", "24/7", "RADIO", "TEST").forEach { word ->
            val on = rules.excludeKeywords.any { it.equals(word, ignoreCase = true) }
            ToggleRow(
                label = word,
                checked = on,
                onToggle = {
                    actions.onExclusions(
                        if (on) rules.excludeKeywords.filterNot { it.equals(word, ignoreCase = true) }
                        else rules.excludeKeywords + word
                    )
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        SectionTitle("Previews")
        ToggleRow(
            label = "Play live previews on the Live TV page",
            checked = ui.settings.livePreviews,
            detail = "Uses your one connection while you browse, as a live TV service does",
            onToggle = { actions.onLivePreviews(!ui.settings.livePreviews) },
        )
    }
}

@Composable
private fun GuideSection(ui: UiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Guide data")
        ActionRow(
            if (ui.isRefreshingEpg) "Refreshing… ${ui.epgRefreshDetail.orEmpty()}" else "Refresh the guide now",
            listOfNotNull(
                ui.settings.lastEpgRefreshAt.takeIf { it > 0 }?.let { "Last refreshed ${formatTime(it)}" } ?: "Never refreshed",
                ui.settings.lastEpgSummary.takeIf { it.isNotBlank() },
            ).joinToString("  ·  "),
            CrimsonIcons.Refresh,
            actions.onRefreshEpg,
        )
        ToggleRow(
            label = "Use free public guide listings",
            checked = ui.settings.useExtraEpgSources,
            detail = "Fills in channels your provider has no listings for. Off saves about 50 MB per refresh.",
            onToggle = { actions.onExtraEpgSources(!ui.settings.useExtraEpgSources) },
        )
        StepperRow(
            label = "Guide time offset",
            value = ui.settings.epgOffsetHours,
            range = XmltvTime.MANUAL_OFFSET_HOURS,
            detail = "Shift programme times if your provider publishes the wrong time zone. Left and Right adjust.",
            onChange = actions.onOffset,
        )
    }
}

@Composable
private fun LibrarySection(ui: UiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Movies & TV Shows")
        Text(
            "Recommendations come from matching your provider's catalogue to IMDb's published ratings and genres. " +
                (ui.libraryStatus ?: "Your catalogue is up to date."),
            style = CrimsonType.Caption,
        )
        ActionRow(
            "Rebuild the catalogue",
            "Re-reads every movie and series from your provider and rebuilds the rows. Takes a few minutes on a large account.",
            CrimsonIcons.Refresh,
            actions.onRebuildCatalogue,
        )
    }
}

@Composable
private fun PlaybackSection(ui: UiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Stream format")
        ToggleRow(
            label = "Use HLS (.m3u8) instead of MPEG-TS (.ts)",
            checked = ui.settings.streamFormat == "m3u8",
            detail = "Your provider allows: ${ui.allowedFormats.joinToString(", ")}. Try this if live channels stutter.",
            onToggle = { actions.onFormat(if (ui.settings.streamFormat == "m3u8") "ts" else "m3u8") },
        )
    }
}

@Composable
private fun HelpSection() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Remote")
        Help("Browsing", "Arrows move, OK opens, BACK goes back. On Home, BACK returns to the top of the page first.")
        Help("Movies and shows", "OK pauses and plays. LEFT and RIGHT skip 10 seconds; REWIND and FAST FORWARD skip 30.")
        Help("Live TV", "UP and DOWN change channel. OK opens the guide. PLAY/PAUSE returns to the last channel. INFO shows what's on.")
        Help("Guide", "REWIND and FAST FORWARD move two hours. PLAY/PAUSE stars a channel. Type a channel number to jump to it.")
        Help("Sports", "Select any game to search the live channels for the network showing it.")
        Help("Mouse", "Hover to highlight, click to select, scroll the wheel to change channel.")
    }
}

@Composable
private fun Help(title: String, body: String) {
    Column {
        Text(title, style = CrimsonType.Label.copy(fontWeight = FontWeight.Bold))
        Text(body, style = CrimsonType.Body)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = CrimsonType.Overline.copy(color = Crimson.TextTertiary), modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RailItem(label: String, icon: ImageVector, selected: Boolean, onFocus: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White else if (selected) Crimson.SurfaceHigh else Color.Transparent, tween(120), label = "rail")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .tvInteractive(onSelect = onFocus, onFocus = { focused = it; if (it) onFocus() }, focusRequester = focusRequester)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (focused) Color.Black else if (selected) Crimson.Red else Crimson.TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = CrimsonType.Label.copy(fontSize = 13.sp, color = if (focused) Color.Black else if (selected) Crimson.TextPrimary else Crimson.TextSecondary))
    }
}

@Composable
private fun RowShell(
    onSelect: () -> Unit,
    onLeft: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Crimson.SurfaceHigh else Crimson.Surface, tween(120), label = "row")
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .tvInteractive(onSelect = onSelect, onFocus = { focused = it }, onLeft = onLeft, onRight = onRight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { content(focused) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit, detail: String? = null) {
    RowShell(onSelect = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = CrimsonType.Label.copy(fontSize = 14.sp))
                if (!detail.isNullOrBlank()) Text(detail, style = CrimsonType.Caption)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked)
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .size(40.dp, 22.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Crimson.Red else Crimson.SurfaceHigh)
            .border(1.dp, Crimson.Stroke, RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun ActionRow(label: String, detail: String?, icon: ImageVector, onSelect: () -> Unit) {
    RowShell(onSelect = onSelect) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (focused) Color.White else Crimson.TextTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = CrimsonType.Label.copy(fontSize = 14.sp))
                if (!detail.isNullOrBlank()) Text(detail, style = CrimsonType.Caption, maxLines = 3)
            }
            Icon(CrimsonIcons.ChevronRight, null, tint = Crimson.TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, range: IntRange, detail: String, onChange: (Int) -> Unit) {
    RowShell(
        onSelect = {},
        onLeft = { if (value > range.first) onChange(value - 1); true },
        onRight = { if (value < range.last) onChange(value + 1); true },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = CrimsonType.Label.copy(fontSize = 14.sp))
                Text(detail, style = CrimsonType.Caption)
            }
            Text("◀", style = CrimsonType.Label.copy(color = Crimson.TextTertiary))
            Text(
                (if (value >= 0) "+" else "") + "$value h",
                style = CrimsonType.Title.copy(fontSize = 16.sp),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Text("▶", style = CrimsonType.Label.copy(color = Crimson.TextTertiary))
        }
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(epochMs))
