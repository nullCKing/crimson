package com.crimson.core.live

import com.crimson.core.filter.CountryDetector
import com.crimson.core.filter.ForeignCountries
import com.crimson.core.text.Tokenizer

/**
 * A country or region a live category belongs to, for the "browse by country" directory.
 *
 * The import already decides, for every category, whether it is one of the four kept countries
 * ([com.crimson.core.model.Country]) or carries a foreign marker ([ForeignCountries]). This turns
 * either verdict into something a person can read — a name and a flag — and groups the dozens of
 * spellings a provider uses (`DE`, `GER`, `GERMANY`, `DEUTSCH`) into one entry.
 */
data class Region(
    val code: String,
    val name: String,
    /** A flag emoji, or a globe for multi-country regions. */
    val flag: String,
    val tokens: Set<String>,
) {
    /** A region that is not one country: "Latin America", "Arabic", "International". */
    val isGroup: Boolean get() = code.length != 2
}

object WorldRegions {

    private fun flagOf(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
        val cc = if (code == "UK") "GB" else code
        return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    private fun r(code: String, name: String, vararg tokens: String) =
        Region(code, name, flagOf(code), (tokens.toList() + code).toSet())

    val ALL: List<Region> = listOf(
        r("US", "United States", "USA", "AMERICA", "AMERICAN"),
        r("UK", "United Kingdom", "GB", "GBR", "BRITAIN", "BRITISH", "ENGLAND", "SCOTLAND", "WALES"),
        r("CA", "Canada", "CAN", "CANADIAN", "QUEBEC"),
        r("MX", "Mexico", "MEX", "MEXICAN"),
        r("JP", "Japan", "JPN", "JAPAN", "JAPANESE"),
        r("KR", "South Korea", "KOR", "KOREA", "KOREAN", "SOUTH KOREA"),
        r("DE", "Germany", "DEU", "GER", "GERMANY", "DEUTSCH", "DEUTSCHLAND"),
        r("FR", "France", "FRA", "FRANCE", "FRENCH"),
        r("ES", "Spain", "ESP", "SPAIN", "ESPANA", "SPANISH"),
        r("IT", "Italy", "ITA", "ITALY", "ITALIA", "ITALIAN"),
        r("NL", "Netherlands", "NLD", "HOLLAND", "NETHERLANDS", "DUTCH"),
        r("BE", "Belgium", "BEL", "BELGIUM", "BELGIQUE"),
        r("PT", "Portugal", "PRT", "POR", "PORTUGAL", "PORTUGUESE"),
        r("PL", "Poland", "POL", "POLAND", "POLSKA"),
        r("RO", "Romania", "ROU", "ROMANIA", "ROMANIAN"),
        r("RU", "Russia", "RUS", "RUSSIA", "RUSSIAN"),
        r("UA", "Ukraine", "UKR", "UKRAINE"),
        r("SE", "Sweden", "SWE", "SWEDEN", "SVERIGE"),
        r("NO", "Norway", "NOR", "NORWAY", "NORGE"),
        r("DK", "Denmark", "DNK", "DEN", "DENMARK", "DANMARK"),
        r("FI", "Finland", "FIN", "FINLAND", "SUOMI"),
        r("IS", "Iceland", "ISL", "ICELAND"),
        r("IE", "Ireland", "IRL", "IRELAND", "EIRE"),
        r("CH", "Switzerland", "CHE", "SWITZERLAND", "SUISSE"),
        r("AT", "Austria", "AUT", "AUSTRIA", "OSTERREICH"),
        r("GR", "Greece", "GRC", "GREECE", "GREEK"),
        r("TR", "Turkey", "TUR", "TURKEY", "TURKIYE", "TURKISH"),
        r("CZ", "Czechia", "CZE", "CZECH", "CESKA"),
        r("SK", "Slovakia", "SVK", "SLOVAKIA"),
        r("HU", "Hungary", "HUN", "HUNGARY", "MAGYAR"),
        r("BG", "Bulgaria", "BGR", "BUL", "BULGARIA"),
        r("HR", "Croatia", "HRV", "CROATIA", "HRVATSKA"),
        r("RS", "Serbia", "SRB", "SERBIA", "SRBIJA"),
        r("SI", "Slovenia", "SVN", "SLOVENIA"),
        r("BA", "Bosnia", "BIH", "BOSNIA"),
        r("MK", "North Macedonia", "MKD", "MACEDONIA"),
        r("AL", "Albania", "ALB", "ALBANIA", "SHQIP"),
        r("LT", "Lithuania", "LTU", "LITHUANIA"),
        r("LV", "Latvia", "LVA", "LATVIA"),
        r("EE", "Estonia", "EST", "ESTONIA"),
        r("CY", "Cyprus", "CYP", "CYPRUS"),
        r("MT", "Malta", "MLT", "MALTA"),
        r("XK", "Kosovo", "KO", "KOS", "KOSOVO"),
        r("ME", "Montenegro", "CG", "MNE", "MONTENEGRO", "CRNA GORA"),
        r("BY", "Belarus", "BLR", "BELARUS"),
        r("BR", "Brazil", "BRA", "BRAZIL", "BRASIL"),
        r("AR", "Argentina", "ARG", "ARGENTINA"),
        r("CL", "Chile", "CHL", "CHILE"),
        r("CO", "Colombia", "COL", "COLOMBIA"),
        r("PE", "Peru", "PER", "PERU"),
        r("VE", "Venezuela", "VEN", "VENEZUELA"),
        r("EC", "Ecuador", "ECU", "ECUADOR"),
        r("UY", "Uruguay", "URY", "URUGUAY"),
        r("PY", "Paraguay", "PRY", "PARAGUAY"),
        r("BO", "Bolivia", "BOL", "BOLIVIA"),
        r("CR", "Costa Rica", "CRI", "COSTA RICA"),
        r("PA", "Panama", "PAN", "PANAMA"),
        r("GT", "Guatemala", "GTM", "GUATEMALA"),
        r("HN", "Honduras", "HND", "HONDURAS"),
        r("NI", "Nicaragua", "NIC", "NICARAGUA"),
        r("SV", "El Salvador", "SLV", "EL SALVADOR"),
        r("DO", "Dominican Republic", "DOM", "DOMINICANA", "REPUBLICA DOMINICANA"),
        r("PR", "Puerto Rico", "PRI", "PUERTO RICO"),
        r("CU", "Cuba", "CUB", "CUBA"),
        r("EG", "Egypt", "AR EG", "EGY", "EGYPT"),
        r("SA", "Saudi Arabia", "SAU", "SAUDI", "SAUDI ARABIA"),
        r("AE", "United Arab Emirates", "ARE", "UAE", "EMIRATES", "DUBAI"),
        r("QA", "Qatar", "QAT", "QATAR"),
        r("KW", "Kuwait", "KWT", "KUWAIT"),
        r("BH", "Bahrain", "BHR", "BAHRAIN"),
        r("OM", "Oman", "OMN", "OMAN"),
        r("IQ", "Iraq", "IRQ", "IRAQ"),
        r("SY", "Syria", "SYR", "SYRIA"),
        r("LB", "Lebanon", "LBN", "LEBANON"),
        r("JO", "Jordan", "JOR", "JORDAN"),
        r("PS", "Palestine", "PSE", "PALESTINE"),
        r("IL", "Israel", "ISR", "ISRAEL"),
        r("IR", "Iran", "IRN", "IRAN", "PERSIAN", "FARSI"),
        r("MA", "Morocco", "MAR", "MOROCCO", "MAROC"),
        r("DZ", "Algeria", "DZA", "ALGERIA", "ALGERIE"),
        r("TN", "Tunisia", "TUN", "TUNISIA", "TUNISIE"),
        r("LY", "Libya", "LBY", "LIBYA"),
        r("SD", "Sudan", "SDN", "SUDAN"),
        r("NG", "Nigeria", "NGA", "NIGERIA"),
        r("GH", "Ghana", "GHA", "GHANA"),
        r("KE", "Kenya", "KEN", "KENYA"),
        r("ZA", "South Africa", "ZAF", "SOUTH AFRICA"),
        r("ET", "Ethiopia", "ETH", "ETHIOPIA"),
        r("SO", "Somalia", "SOMALIA"),
        r("MU", "Mauritius", "MUS", "MAURITIUS"),
        r("CN", "China", "CHN", "CHINA", "CHINESE", "MANDARIN", "CANTONESE"),
        r("HK", "Hong Kong", "HKG", "HONG KONG"),
        r("TW", "Taiwan", "TWN", "TAIWAN", "TAI"),
        r("IN", "India", "IND", "INDIA", "INDIAN", "HINDI", "TAMIL", "TELUGU", "PUNJABI", "MALAYALAM", "BENGALI", "KANNADA", "BOLLYWOOD"),
        r("PK", "Pakistan", "PAK", "PAKISTAN", "URDU"),
        r("BD", "Bangladesh", "BGD", "BANGLADESH"),
        r("LK", "Sri Lanka", "LKA", "SRI LANKA"),
        r("NP", "Nepal", "NPL", "NEPAL"),
        r("AF", "Afghanistan", "AFG", "AFGHANISTAN"),
        r("TH", "Thailand", "THA", "THAILAND", "THAI"),
        r("VN", "Vietnam", "VNM", "VIETNAM", "VT"),
        r("PH", "Philippines", "PHL", "PHILIPPINES", "FILIPINO", "TAGALOG"),
        r("ID", "Indonesia", "IDN", "INDONESIA"),
        r("MY", "Malaysia", "MYS", "MALAYSIA"),
        r("SG", "Singapore", "SGP", "SINGAPORE"),
        r("MM", "Myanmar", "MMR", "MYANMAR"),
        r("KH", "Cambodia", "KHM", "CAMBODIA"),
        r("MN", "Mongolia", "MNG", "MONGOLIA"),
        r("KZ", "Kazakhstan", "KAZ", "KAZAKHSTAN", "KA", "KAZACHSTAN"),
        r("AZ", "Azerbaijan", "AZE", "AZERBAIJAN"),
        r("AM", "Armenia", "ARM", "ARMENIA"),
        r("GE", "Georgia", "GEO", "GEORGIA"),
        r("AU", "Australia", "AUS", "AUSTRALIA", "AUSSIE"),
        r("NZ", "New Zealand", "NZL", "NEW ZEALAND"),
        r("TJ", "Tajikistan", "TJK", "TAJIKISTAN"),
        r("UZ", "Uzbekistan", "UZB", "UZBEKISTAN"),
        r("KP", "North Korea", "NORTH KOREA", "DPRK", "KOREA DPR"),
        r("BALKANS", "Balkans (Ex-Yu)", "EX YU", "EXYU", "BALKAN", "BALKANS"),
        r("NORDIC", "Scandinavia", "SCANDINAVIA", "NORDIC"),
        r("EUROPE", "Europe", "EU", "EUROPE", "EUROPEAN"),
        r("LATAM", "Latin America", "LAT", "LATAM", "LATINO", "LATIN", "LATIN AMERICA", "SOUTH AMERICA", "CENTRAL AMERICA", "AMERICA LATINA", "SUDAMERICA"),
        r("CARIB", "Caribbean", "CARIBBEAN", "CARIBE", "CRB", "CARIB"),
        r("ARABIC", "Arabic", "ARABIC", "ARABIA", "MENA", "ARA", "ARB", "BEE", "BEIN"),
        r("AFRICA", "Africa", "AFR", "AFRICA", "AFRIQUE", "AFRICAN"),
        r("KURD", "Kurdish", "KU", "KURD", "KURDISTAN"),
        r("ASIA", "Asia", "ASIA", "ASIAN"),
        r("INTL", "International", "WORLD", "GLOBAL", "INT", "INTERNATIONAL"),
    )

    private val byToken: Map<String, Region> = HashMap<String, Region>().apply {
        for (region in ALL) for (t in region.tokens) putIfAbsent(t, region)
    }

    val OTHER = Region("OTHER", "Other", "📺", emptySet())

    fun byCode(code: String?): Region? = ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** The region a marker token (as the import stored it) stands for. */
    fun forToken(token: String?): Region? = token?.uppercase()?.trim()?.let(byToken::get)

    /**
     * The region of a category, from what the import recorded: a kept-country code, a foreign
     * marker, or failing both, whatever marker the name itself carries.
     */
    fun forCategory(countryCode: String?, foreignMarker: String?, name: String): Region {
        forToken(countryCode)?.let { return it }
        forToken(foreignMarker)?.let { return it }
        val tokens = Tokenizer.tokenize(name)
        CountryDetector.detect(tokens, prefixOnly = true)?.country?.code?.let { code ->
            forToken(code)?.let { return it }
        }
        ForeignCountries.detect(tokens)?.let { marker -> forToken(marker)?.let { return it } }
        return OTHER
    }

    /** Case- and accent-insensitive "does this region match what the viewer typed". */
    fun matches(region: Region, query: String): Boolean {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return true
        return region.name.uppercase().contains(q) ||
            region.code.equals(q, ignoreCase = true) ||
            region.tokens.any { it.startsWith(q) }
    }
}
