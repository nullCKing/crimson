package com.crimson.core.catalog

import com.crimson.core.epg.ChannelMatcher

/**
 * A hand-built channel lineup: the guide a cable box would have shown, rather than everything the
 * provider happens to carry.
 *
 * A provider's list is nine thousand rows of PPV slots, foreign feeds and 24/7 loops. Nobody sat
 * down and chose them. A real cable guide was chosen — a few dozen channels in a deliberate
 * order — and that is what makes it browsable. So a package is a list of channels by name, in the
 * order a lineup would put them, and the app shows the viewer's own copy of each one it can find.
 *
 * Matching goes through [ChannelMatcher], the same key comparison the guide data uses, so
 * `US| ESPN 2 FHD`, `ESPN2` and `ESPN 2 HD` all resolve to the same entry. [aliases] exist for the
 * cases a key cannot reach: a channel that was renamed (`Spike` to `Paramount Network`), or one
 * the provider abbreviates differently (`FS1` for `Fox Sports 1`).
 */
data class PackageChannel(
    /** What the lineup calls it. */
    val name: String,
    /** Other names the same channel goes by. */
    val aliases: List<String> = emptyList(),
) {
    /** Every comparison key this entry will accept, most preferred first. */
    val keys: List<String>
        get() = (listOf(name) + aliases).map(ChannelMatcher::key).filter { it.isNotEmpty() }
}

/** A named lineup, in sections, in the order a cable guide would number them. */
data class ChannelPackage(
    val id: String,
    val name: String,
    val description: String,
    val sections: List<Section>,
) {
    data class Section(val name: String, val channels: List<PackageChannel>)

    val channels: List<PackageChannel> get() = sections.flatMap { it.channels }
    val size: Int get() = sections.sumOf { it.channels.size }
}

object ChannelPackages {

    private fun ch(name: String, vararg aliases: String) = PackageChannel(name, aliases.toList())

    /**
     * Expanded basic cable with a sports tier — the lineup an American household would have had.
     *
     * Kept to the channels that were actually on such a guide: the four networks and PBS, the
     * news channels, the general entertainment tier, kids, factual, music, and a sports package.
     * Nothing else. Adding "everything that might be interesting" is how it turns back into nine
     * thousand rows.
     */
    val AMERICAN_CABLE = ChannelPackage(
        id = "us_cable",
        name = "American Cable",
        description = "Broadcast, news, entertainment, kids, factual and a full sports tier",
        sections = listOf(
            ChannelPackage.Section(
                "Broadcast",
                listOf(
                    ch("ABC"), ch("CBS"), ch("NBC"), ch("FOX"), ch("The CW", "CW"), ch("PBS"),
                ),
            ),
            ChannelPackage.Section(
                "News",
                listOf(
                    ch("CNN"), ch("Fox News", "Fox News Channel", "FNC"), ch("MSNBC"),
                    ch("CNBC"), ch("HLN", "Headline News"),
                    ch("The Weather Channel", "Weather Channel"),
                ),
            ),
            ChannelPackage.Section(
                "Entertainment",
                listOf(
                    ch("USA Network", "USA"), ch("TNT"), ch("TBS"), ch("FX"), ch("FXX"),
                    ch("AMC"), ch("A&E", "AE"), ch("Bravo"), ch("E!", "E Entertainment"),
                    ch("Lifetime"), ch("Hallmark Channel", "Hallmark"),
                    ch("Paramount Network", "Spike", "Spike TV"), ch("Syfy", "Sci Fi Channel"),
                    ch("TV Land", "TVLand"), ch("Comedy Central"), ch("truTV"), ch("WE tv", "WE"),
                    ch("Oxygen"), ch("ION Television", "ION"),
                ),
            ),
            ChannelPackage.Section(
                "Kids and Family",
                listOf(
                    ch("Disney Channel", "Disney"), ch("Disney XD"), ch("Disney Junior", "Disney Jr"),
                    ch("Nickelodeon", "Nick"), ch("Nick Jr", "Nick Junior"), ch("Cartoon Network"),
                    ch("Freeform", "ABC Family"), ch("Boomerang"),
                ),
            ),
            ChannelPackage.Section(
                "Factual and Lifestyle",
                listOf(
                    ch("Discovery Channel", "Discovery"), ch("History", "History Channel"),
                    ch("National Geographic", "Nat Geo"), ch("Animal Planet"), ch("TLC"),
                    ch("Investigation Discovery", "ID"), ch("Science Channel", "Science"),
                    ch("Travel Channel"), ch("Food Network"), ch("HGTV"),
                ),
            ),
            ChannelPackage.Section(
                "Music",
                listOf(ch("MTV"), ch("VH1"), ch("CMT"), ch("BET")),
            ),
            ChannelPackage.Section(
                "Sports",
                listOf(
                    ch("ESPN"), ch("ESPN2", "ESPN 2"), ch("ESPNU", "ESPN U"), ch("ESPNews", "ESPN News"),
                    ch("Fox Sports 1", "FS1"), ch("Fox Sports 2", "FS2"),
                    ch("CBS Sports Network", "CBS Sports", "CBSSN"),
                    ch("NFL Network", "NFL"), ch("NFL RedZone", "NFL Red Zone", "RedZone"),
                    ch("NBA TV"), ch("MLB Network", "MLB"), ch("NHL Network", "NHL"),
                    ch("Golf Channel", "Golf"), ch("Tennis Channel", "Tennis"),
                    ch("SEC Network", "SEC"), ch("Big Ten Network", "BTN"),
                    ch("ACC Network", "ACCN", "ACC"),
                    ch("Stadium"),
                    ch("SportsGrid"),
                    ch("Fubo Sports Network", "Fubo Sports"),
                ),
            ),
        ),
    )

    val JAPANESE_TV = ChannelPackage(
        id = "jp_tv",
        name = "Japanese TV",
        description = "Broadcast, entertainment, anime and sports channels from Japan",
        sections = listOf(
            ChannelPackage.Section(
                "Broadcast",
                listOf(
                    ch("NHK General TV", "NHK General", "NHK G", "NHK"),
                    ch("NHK Educational TV", "NHK Educational", "NHK E"),
                    ch("NHK BS", "NHK BS1", "NHK BS Premium"),
                    ch("NHK World", "NHK World HD", "NHK World Japan", "NHK World Premium"),
                    ch("Nippon TV", "NTV"),
                    ch("TV Asahi"),
                    ch("TBS Television", "TBS", "BS-TBS"),
                    ch("TV Tokyo", "BS TV Tokyo"),
                    ch("Fuji Television", "Fuji TV"),
                    ch("Tokyo MX", "Tokyo Now"),
                    ch("BS11"),
                    ch("BS12"),
                ),
            ),
            ChannelPackage.Section(
                "Entertainment and Cinema",
                listOf(
                    ch("WOWOW Prime"),
                    ch("WOWOW Live"),
                    ch("WOWOW Cinema", "WOWOW Cinema Movies"),
                    ch("Star Channel 1", "Star Channel"),
                    ch("Star Channel 2"),
                    ch("Star Channel 3"),
                    ch("Movie Plus"),
                    ch("Toei Channel"),
                    ch("Nihon Eiga Senmon"),
                    ch("Mystery Channel"),
                ),
            ),
            ChannelPackage.Section(
                "Anime and Kids",
                listOf(
                    ch("Animax"),
                    ch("AT-X"),
                    ch("Kids Station"),
                    ch("Cartoon Network Japan"),
                    ch("Disney Channel Japan"),
                ),
            ),
            ChannelPackage.Section(
                "Sports",
                listOf(
                    ch("J Sports 1", "J Sports 1 Sports"),
                    ch("J Sports 2", "J Sports 2 Sports"),
                    ch("J Sports 3", "J Sports 3 Sports"),
                    ch("J Sports 4", "J Sports 4 Sports"),
                    ch("GAORA", "GAORA Sports"),
                    ch("Sky A"),
                    ch("Nittele G+"),
                    ch("Fuji TV One"),
                    ch("Fuji TV Two"),
                ),
            ),
            ChannelPackage.Section(
                "News and Music",
                listOf(
                    ch("Nikkei CNBC"),
                    ch("CGTN"),
                    ch("MTV Japan"),
                    ch("Music On TV"),
                    ch("Space Shower TV"),
                ),
            ),
        ),
    )

    val KOREAN_TV = ChannelPackage(
        id = "kr_tv",
        name = "Korean TV",
        description = "Terrestrial networks, cable, drama and entertainment channels from South Korea",
        sections = listOf(
            ChannelPackage.Section(
                "Terrestrial Networks",
                listOf(
                    ch("KBS1", "KBS 1", "KBS 1 TV", "KBS1 TV"),
                    ch("KBS2", "KBS 2"),
                    ch("KBS World"),
                    ch("MBC"),
                    ch("SBS"),
                    ch("EBS1", "EBS 1", "EBS"),
                    ch("EBS2", "EBS 2"),
                ),
            ),
            ChannelPackage.Section(
                "General Cable and News",
                listOf(
                    ch("JTBC"),
                    ch("JTBC 2", "JTBC2"),
                    ch("TV Chosun"),
                    ch("Channel A"),
                    ch("MBN"),
                    ch("tvN"),
                    ch("YTN"),
                    ch("Yonhap News TV", "Yonhap News"),
                    ch("Arirang TV", "Arirang"),
                ),
            ),
            ChannelPackage.Section(
                "Drama and Movies",
                listOf(
                    ch("OCN", "OCN TV"),
                    ch("OCN Movies"),
                    ch("Catch On 1"),
                    ch("Catch On 2"),
                    ch("SBS Plus"),
                    ch("SBS FunE"),
                    ch("tvN Drama"),
                    ch("MBC Drama"),
                    ch("KBS Drama"),
                ),
            ),
            ChannelPackage.Section(
                "Sports",
                listOf(
                    ch("MBC Sports Plus", "MBC Sports TV", "MBC Sports"),
                    ch("KBS N Sports"),
                    ch("SBS Sports"),
                    ch("SBS Golf"),
                    ch("SBS Biz"),
                    ch("JTBC Golf"),
                    ch("tvN Sports"),
                    ch("SPOTV"),
                    ch("SPOTV2"),
                ),
            ),
            ChannelPackage.Section(
                "Kids and Lifestyle",
                listOf(
                    ch("Tooniverse"),
                    ch("EBS Kids"),
                    ch("Daekyo Kids TV"),
                    ch("Animax Korea"),
                    ch("Mnet"),
                    ch("MBC Music"),
                ),
            ),
        ),
    )

    val ALL = listOf(AMERICAN_CABLE, JAPANESE_TV, KOREAN_TV)

    fun byId(id: String): ChannelPackage? = ALL.firstOrNull { it.id == id }
}

/**
 * Resolves a package against the viewer's channels.
 *
 * [pick] is given every channel whose name matches an entry and returns the one to use, so the
 * caller decides what "best" means — normally the highest-numbered duplicate is the worst and the
 * one with guide data is the best, and only the caller knows which have guide data.
 */
class PackageResolver<T>(channels: List<T>, nameOf: (T) -> String) {

    private val byKey = HashMap<String, MutableList<T>>(channels.size)

    init {
        for (channel in channels) {
            val key = ChannelMatcher.key(nameOf(channel))
            if (key.isEmpty()) continue
            byKey.getOrPut(key) { ArrayList(1) }.add(channel)
        }
    }

    /** Every candidate for one lineup entry, in the order its keys were listed. */
    fun candidates(entry: PackageChannel): List<T> =
        entry.keys.firstNotNullOfOrNull { byKey[it] } ?: emptyList()

    /** The lineup as the viewer can watch it: one channel per entry, entries they lack omitted. */
    fun resolve(
        pkg: ChannelPackage,
        pick: (PackageChannel, List<T>) -> T? = { _, found -> found.firstOrNull() },
    ): List<Pair<PackageChannel, T>> {
        val out = ArrayList<Pair<PackageChannel, T>>(pkg.size)
        val used = HashSet<T>()
        for (entry in pkg.channels) {
            val found = candidates(entry).filterNot { it in used }
            val chosen = pick(entry, found) ?: continue
            used.add(chosen)
            out.add(entry to chosen)
        }
        return out
    }
}
