package com.crimson.core.epg

import com.crimson.core.text.NameCleaner

/**
 * Matches guide-data channels to the provider's channels by name, for the majority of channels
 * that carry no `epg_channel_id`.
 *
 * On a real account roughly nine channels in ten have no EPG id at all, so joining on the id is
 * not enough: the XMLTV `<channel>` elements have to be matched to the stream list by their
 * `<display-name>`s. Names on the two sides are never spelled the same — `UK| SKY SPORTS MAIN
 * EVENT FHD` on one and `Sky Sports Main Event` on the other — so both are reduced to a key
 * that ignores case, spacing, punctuation, country prefixes and quality tags before comparing.
 */
object ChannelMatcher {

    /**
     * The comparison key for a channel name: cleaned of prefixes and tags by [NameCleaner], then
     * upper-cased with everything but letters and digits removed. `"UK| Sky Sports F1 FHD"` and
     * `"Sky Sports F1"` both become `"SKYSPORTSF1"`. Returns an empty string for names that carry
     * nothing usable, which callers must skip rather than match on.
     */
    fun key(name: String): String {
        val cleaned = NameCleaner.clean(stripSuffixTags(stripGroupPrefix(name)))
        val sb = StringBuilder(cleaned.length)
        for (ch in cleaned) {
            if (ch.isLetterOrDigit()) sb.append(ch.uppercaseChar())
        }
        // Six characters of pure noise ("CHANNEL", "TV") match too much to be trusted.
        val key = sb.toString()
        return if (key.length < 2 || key in TOO_GENERIC) "" else key
    }

    /**
     * XMLTV ids often carry a country suffix (`SkyNews.uk`, `ESPN.us`) that display names do
     * not. Taking the key of the id as well as of the names lets a well-formed id match on its
     * own when a provider ships an XMLTV with no display names at all.
     */
    fun keyOfId(xmltvId: String): String {
        val dot = xmltvId.lastIndexOf('.')
        val base = if (dot > 0 && xmltvId.length - dot <= 4) xmltvId.substring(0, dot) else xmltvId
        return key(base)
    }

    /**
     * The country an XMLTV id declares, as a two-letter code, or null.
     *
     * `SkyNews.uk` is British, `CNN.pt` is Portuguese, `CNN.us` is American. That suffix is the
     * only thing separating the several dozen channels in the world called CNN, and a guide that
     * ignores it will put a Lisbon schedule on an Atlanta channel — which is exactly what
     * happened before this existed.
     */
    fun countryOfId(xmltvId: String): String? {
        val dot = xmltvId.lastIndexOf('.')
        if (dot <= 0 || xmltvId.length - dot != 3) return null
        val code = xmltvId.substring(dot + 1).uppercase()
        if (!code.all { it in 'A'..'Z' }) return null
        // The filter calls the United Kingdom UK; XMLTV files call it either.
        return if (code == "GB") "UK" else code
    }

    /**
     * `TV| A&E`, `TUBI| NEWS 12`, `PLUTO: CRIME` — a short group label before a pipe or colon
     * that is not a country code (NameCleaner already drops those) and is not part of the name.
     *
     * Public because the same label has to come off a name that is being used as a programme
     * title, not only one being used as a matching key.
     */
    fun stripGroupPrefix(name: String): String {
        val match = GROUP_PREFIX.find(name) ?: return name
        val rest = name.substring(match.range.last + 1)
        return if (rest.isBlank()) name else rest
    }

    private val GROUP_PREFIX = Regex("""^\s*[A-Za-z0-9+&]{2,8}\s*[|:]\s*""")

    private fun stripSuffixTags(name: String): String {
        var s = name.trim()
        // "(Backup)", "[HD]", "- Alt" style trailers.
        while (true) {
            val trimmed = s.trimEnd()
            val open = trimmed.lastIndexOfAny(charArrayOf('(', '['))
            if (open > 0 && (trimmed.endsWith(')') || trimmed.endsWith(']'))) {
                s = trimmed.substring(0, open)
            } else {
                return trimmed
            }
        }
    }

    private val TOO_GENERIC = setOf("TV", "CHANNEL", "HD", "LIVE", "SPORTS", "NEWS", "MOVIES")
}
