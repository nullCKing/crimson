package com.crimson.core.epg

/**
 * A public XMLTV feed the app pulls guide data from, on top of the provider's own `xmltv.php`.
 *
 * These are built in and on by default. A provider's XMLTV covers the channels it has ids for and
 * no more — on the account this was measured against, 888 of 11,130 — and the viewer has no way to
 * know which public feed carries the rest, so asking them to paste URLs into a settings screen
 * would be asking them to do research the app can do once. Every feed here was measured against a
 * real 11,130-channel list before being included; see `DECISIONS.md` for the yields.
 *
 * Ordering is priority: the provider's own data is used first, then these in list order, and a
 * channel that already has listings is never fetched again. [countries] restricts a feed to the
 * countries it covers, so a viewer who has turned Japan off never downloads the Japanese feed; an
 * empty set means the feed is worldwide (the streaming services, whose channels appear in every
 * country's list).
 */
data class EpgSource(
    /** Stable id, stored on each programme row so a feed's rows can be counted or removed. */
    val id: String,
    /** What the Settings screen calls it. */
    val label: String,
    val url: String,
    /** Country codes this feed is worth fetching for; empty means always. */
    val countries: Set<String> = emptySet(),
    /** Roughly how large the download is, for the log and for ordering cheap feeds first. */
    val approxMegabytes: Int = 1,
) {
    fun appliesTo(enabledCountries: Set<String>): Boolean =
        countries.isEmpty() || countries.any { it in enabledCountries }
}

/**
 * The built-in feeds, in the order they are tried.
 *
 * Cheap and high-yield first, so a slow connection gets the most guide data per megabyte and a
 * run that is cut short has already done the valuable part. The numbers in the comments are
 * channels filled on the reference account, measured on 2026-09-22.
 */
object EpgSources {

    val BUILT_IN: List<EpgSource> = listOf(
        // Plex's FAST channels: the single highest-yield feed, and the provider carries hundreds
        // of them under its own names.
        EpgSource("plex", "Plex FAST channels", "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz", approxMegabytes = 5),          // 449
        // US cable and broadcast networks.
        EpgSource("us", "US networks", "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz", setOf("US"), 7),                          // 123
        EpgSource("pluto", "Pluto TV", "https://i.mjh.nz/PlutoTV/us.xml.gz", approxMegabytes = 1),                                             // 77
        EpgSource("samsung", "Samsung TV Plus", "https://i.mjh.nz/SamsungTVPlus/us.xml.gz", approxMegabytes = 1),                              // 76
        EpgSource("roku", "Roku channels", "https://i.mjh.nz/Roku/all.xml.gz", approxMegabytes = 3),                                           // 44
        EpgSource("uk", "UK networks", "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz", setOf("UK"), 3),                          // 39
        EpgSource("freeview", "UK Freeview", "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml", setOf("UK"), 21),          // 22
        EpgSource("plexall", "Plex (full list)", "https://i.mjh.nz/Plex/all.xml.gz", approxMegabytes = 8),                                     // 22
        EpgSource("ussports", "US sports networks", "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz", setOf("US"), 1),      // 13
        EpgSource("kr", "South Korea", "https://epgshare01.online/epgshare01/epg_ripper_KR1.xml.gz", setOf("KR"), 1),                          // 10
        EpgSource("jp", "Japan", "https://epgshare01.online/epgshare01/epg_ripper_JP1.xml.gz", setOf("JP"), 2),                                // 0 here, kept for other accounts
    )

    /**
     * `US_LOCALS1` is deliberately not in the list. It is 55 MB — twice every other feed put
     * together — and filled two channels on the reference account, because a provider names its
     * local affiliates by city rather than by call sign. Matching on the call signs inside the
     * names instead was tried and is worse than nothing: `WILL & GRACE` matches WILL-DT,
     * `THE KING OF QUEENS` matches KING-DT and `DOCTOR WHO` matches WHO-DT, which would put a
     * Champaign PBS schedule on a sitcom loop. If local affiliates ever need covering, they need
     * a call sign *and* a market to agree, not a call sign alone.
     */
    fun forCountries(countries: Set<String>): List<EpgSource> =
        BUILT_IN.filter { it.appliesTo(countries) }
}
