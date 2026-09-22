package com.crimson.core.filter

import com.crimson.core.text.Tokens
import com.crimson.core.text.phrase

/**
 * Countries outside the allowlist, recognised so the import can tell "this is Germany" apart from
 * "I have no idea what this is".
 *
 * That distinction matters twice. A category whose name says `DE | SPORT` is definitively not
 * wanted and is never fetched, which is where the import saves most of its bandwidth and memory.
 * A category called `24/7 CHANNELS` has no country marker at all, so it must still be fetched and
 * filtered channel by channel. Without this list every non-allowlisted category would fall into
 * the second case and the whole provider would be downloaded.
 *
 * The list is also what makes `reports/discovery.md` useful: prefixes that match nothing here and
 * nothing in [CountryDetector] are reported as unrecognised, so the token lists can be extended
 * from the provider's real data rather than guessed at.
 */
object ForeignCountries {

    /**
     * Two- and three-letter codes plus full names for the countries and regions that turn up as
     * Xtream prefixes. Only whole-token matches count, same as everywhere else.
     */
    private val TOKENS: Set<String> = setOf(
        // Europe
        "DE", "DEU", "GER", "GERMANY", "DEUTSCH", "DEUTSCHLAND",
        "FR", "FRA", "FRANCE", "FRENCH",
        "ES", "ESP", "SPAIN", "ESPANA", "SPANISH",
        "IT", "ITA", "ITALY", "ITALIA", "ITALIAN",
        "NL", "NLD", "HOLLAND", "NETHERLANDS", "DUTCH",
        "BE", "BEL", "BELGIUM", "BELGIQUE",
        "PT", "PRT", "POR", "PORTUGAL", "PORTUGUESE",
        "PL", "POL", "POLAND", "POLSKA",
        "RO", "ROU", "ROMANIA", "ROMANIAN",
        "RU", "RUS", "RUSSIA", "RUSSIAN",
        "UA", "UKR", "UKRAINE",
        "SE", "SWE", "SWEDEN", "SVERIGE",
        "NO", "NOR", "NORWAY", "NORGE",
        "DK", "DNK", "DEN", "DENMARK", "DANMARK",
        "FI", "FIN", "FINLAND", "SUOMI",
        "IS", "ISL", "ICELAND",
        "IE", "IRL", "IRELAND", "EIRE",
        "CH", "CHE", "SWITZERLAND", "SUISSE",
        "AT", "AUT", "AUSTRIA", "OSTERREICH",
        "GR", "GRC", "GREECE", "GREEK",
        "TR", "TUR", "TURKEY", "TURKIYE", "TURKISH",
        "CZ", "CZE", "CZECH", "CESKA",
        "SVK", "SLOVAKIA",
        "HU", "HUN", "HUNGARY", "MAGYAR",
        "BG", "BGR", "BUL", "BULGARIA",
        "HR", "HRV", "CROATIA", "HRVATSKA",
        "RS", "SRB", "SERBIA", "SRBIJA",
        "SI", "SVN", "SLOVENIA",
        "BA", "BIH", "BOSNIA",
        "MK", "MKD", "MACEDONIA",
        "AL", "ALB", "ALBANIA", "SHQIP",
        "LT", "LTU", "LITHUANIA",
        "LV", "LVA", "LATVIA",
        "EE", "EST", "ESTONIA",
        "EX YU", "EXYU", "BALKAN", "BALKANS", "SCANDINAVIA", "NORDIC",
        "CY", "CYP", "CYPRUS",
        "MT", "MLT", "MALTA",
        "KO", "KOS", "KOSOVO",
        "CG", "MNE", "MONTENEGRO", "CRNA GORA",
        "BY", "BLR", "BELARUS",
        "EU", "EUROPE", "EUROPEAN",
        // Americas other than the US
        "CA", "CAN", "CANADA", "CANADIAN", "QUEBEC",
        "MX", "MEX", "MEXICO", "MEXICAN",
        "BR", "BRA", "BRAZIL", "BRASIL",
        "AR", "ARG", "ARGENTINA",
        "CL", "CHL", "CHILE",
        "CO", "COL", "COLOMBIA",
        "PE", "PER", "PERU",
        "VE", "VEN", "VENEZUELA",
        "EC", "ECU", "ECUADOR",
        "UY", "URY", "URUGUAY",
        "PY", "PRY", "PARAGUAY",
        "BO", "BOL", "BOLIVIA",
        "CR", "CRI", "COSTA RICA",
        "PA", "PAN", "PANAMA",
        "GT", "GTM", "GUATEMALA",
        "HN", "HND", "HONDURAS",
        "NI", "NIC", "NICARAGUA",
        "SV", "SLV", "EL SALVADOR",
        "DO", "DOM", "DOMINICANA", "REPUBLICA DOMINICANA",
        "PR", "PRI", "PUERTO RICO",
        "CU", "CUB", "CUBA",
        "LAT", "LATAM", "LATINO", "LATIN", "LATIN AMERICA", "SOUTH AMERICA", "CENTRAL AMERICA",
        "AMERICA LATINA", "SUDAMERICA", "CARIBBEAN", "CARIBE", "CRB", "CARIB",
        // Middle East and Africa
        "AR EG", "EG", "EGY", "EGYPT",
        "SA", "SAU", "SAUDI", "SAUDI ARABIA",
        "AE", "ARE", "UAE", "EMIRATES", "DUBAI",
        "QA", "QAT", "QATAR",
        "KW", "KWT", "KUWAIT",
        "BH", "BHR", "BAHRAIN",
        "OM", "OMN", "OMAN",
        "IQ", "IRQ", "IRAQ",
        "SY", "SYR", "SYRIA",
        "LB", "LBN", "LEBANON",
        "JO", "JOR", "JORDAN",
        "PS", "PSE", "PALESTINE",
        "IL", "ISR", "ISRAEL",
        "IR", "IRN", "IRAN", "PERSIAN", "FARSI",
        "MA", "MAR", "MOROCCO", "MAROC",
        "DZ", "DZA", "ALGERIA", "ALGERIE",
        "TN", "TUN", "TUNISIA", "TUNISIE",
        "LY", "LBY", "LIBYA",
        "SD", "SDN", "SUDAN",
        "NG", "NGA", "NIGERIA",
        "GH", "GHA", "GHANA",
        "KE", "KEN", "KENYA",
        "ZA", "ZAF", "SOUTH AFRICA",
        "ET", "ETH", "ETHIOPIA",
        "SOMALIA", "AFR", "AFRICA", "AFRIQUE", "AFRICAN", "ARABIC", "ARABIA", "MENA",
        "ARA", "ARB", "BEE", "BEIN", "KU", "KURD", "KURDISTAN", "MU", "MUS", "MAURITIUS",
        // Asia and Oceania other than JP and KR
        "ASIA", "ASIAN",
        "CN", "CHN", "CHINA", "CHINESE", "MANDARIN", "CANTONESE",
        "HK", "HKG", "HONG KONG",
        "TW", "TWN", "TAIWAN", "TAI",
        "IN", "IND", "INDIA", "INDIAN", "HINDI", "TAMIL", "TELUGU", "PUNJABI", "MALAYALAM", "BENGALI", "KANNADA", "BOLLYWOOD",
        "PK", "PAK", "PAKISTAN", "URDU",
        "BD", "BGD", "BANGLADESH",
        "LK", "LKA", "SRI LANKA",
        "NP", "NPL", "NEPAL",
        "AF", "AFG", "AFGHANISTAN",
        "TH", "THA", "THAILAND", "THAI",
        "VN", "VNM", "VIETNAM", "VT",
        "PH", "PHL", "PHILIPPINES", "FILIPINO", "TAGALOG",
        "ID", "IDN", "INDONESIA",
        "MY", "MYS", "MALAYSIA",
        "SG", "SGP", "SINGAPORE",
        "MM", "MMR", "MYANMAR",
        "KH", "KHM", "CAMBODIA",
        "MN", "MNG", "MONGOLIA",
        "KZ", "KAZ", "KAZAKHSTAN", "KA", "KAZACHSTAN",
        "AZ", "AZE", "AZERBAIJAN",
        "AM", "ARM", "ARMENIA",
        "GE", "GEO", "GEORGIA",
        "AU", "AUS", "AUSTRALIA", "AUSSIE",
        "NZ", "NZL", "NEW ZEALAND",
        "TJ", "TJK", "TAJIKISTAN",
        "UZ", "UZB", "UZBEKISTAN",
        "WORLD", "GLOBAL", "INT", "INTERNATIONAL",
        // Korea, but the wrong one
        "NORTH KOREA", "DPRK", "KOREA DPR",
    )

    private val PHRASES: List<List<String>> =
        TOKENS.filter { it.contains(' ') }.map(::phrase)

    private val SINGLE: Set<String> =
        TOKENS.filterNot { it.contains(' ') }.toSet()

    /**
     * Returns the foreign marker found in prefix position, or null.
     *
     * Short codes get an extra condition. `IN`, `IT`, `IS`, `AT`, `NO`, `CAN`, `DEN` and `PER` are
     * ordinary English words as well as country codes, so a code of three characters or fewer only
     * counts when punctuation closes it — the `DE|`, `FR:`, `[IT]` shape of a real provider prefix.
     * `IN THE MIX` is closed by a space and is left alone. Full country names are unambiguous and
     * need only be in prefix position.
     */
    fun detectPrefix(tokens: Tokens): String? {
        for (p in PHRASES) {
            if (tokens.hasPhraseInPrefix(p)) return p.joinToString(" ")
        }
        for ((i, t) in tokens.list.withIndex()) {
            if (i >= Tokens.PREFIX_WINDOW) break
            if (t !in SINGLE) continue
            if (t.length <= SHORT_CODE_LENGTH && !tokens.isHardBoundary(i)) continue
            return t
        }
        return null
    }

    /** Codes at or below this length must be closed by punctuation to count. */
    private const val SHORT_CODE_LENGTH = 3

    /**
     * Like [detectPrefix] but also matches multi-word region names anywhere in the string, since
     * `LATIN AMERICA` and `SOUTH AMERICA` are unambiguous wherever they appear.
     */
    fun detect(tokens: Tokens): String? {
        detectPrefix(tokens)?.let { return it }
        for (p in PHRASES) {
            if (p.size > 1 && tokens.hasPhrase(p)) return p.joinToString(" ")
        }
        return null
    }

    fun allTokens(): Set<String> = TOKENS
}
