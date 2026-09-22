package com.crimson.core.filter

import com.crimson.core.model.Market
import com.crimson.core.text.phrase

/**
 * Reference data for recognising US broadcast locals.
 *
 * Two separate jobs are done here:
 *  - identify a channel as a local affiliate at all, and
 *  - work out which market it belongs to, so the four allowed markets can be kept and every other
 *    market dropped.
 *
 * Getting the second job wrong in the "allowed" direction leaks a Chicago affiliate into the guide.
 * Getting it wrong in the "other" direction silently deletes a national channel the user wanted, so
 * the lists below deliberately leave out city names that are common English words or brand names
 * (Mobile, Jackson, Columbia, Charleston, Springfield, Madison, Independence). Missing a market
 * costs one unwanted row; a false city match costs a wanted channel.
 */
object UsMarkets {

    // ---------------------------------------------------------------- allowed markets

    /**
     * City phrases that identify an allowed market on their own. `LA` and `NY` are deliberately
     * absent: they live in [AMBIGUOUS_CITY] because they collide with `LA LIGA` and `SONY`.
     */
    val ALLOWED_CITY: Map<Market, List<List<String>>> = mapOf(
        Market.LOS_ANGELES to listOf(
            "LOS ANGELES", "ANAHEIM", "LONG BEACH", "SANTA ANA", "PASADENA",
        ).map(::phrase),
        Market.NEW_YORK to listOf(
            "NEW YORK", "NYC", "BROOKLYN", "MANHATTAN", "THE BRONX", "LONG ISLAND",
            "NEWARK", "NEW JERSEY",
        ).map(::phrase),
        Market.MIAMI to listOf(
            "MIAMI", "FORT LAUDERDALE", "FT LAUDERDALE", "MIAMI DADE", "SOUTH FLORIDA",
        ).map(::phrase),
        Market.TAMPA to listOf(
            "TAMPA", "TAMPA BAY", "ST PETERSBURG", "SAINT PETERSBURG", "CLEARWATER", "SARASOTA",
        ).map(::phrase),
    )

    /**
     * Short aliases that are real market names but also common words in other languages and
     * titles. These only count as a market when something else already says the channel is local.
     */
    val AMBIGUOUS_CITY: Map<Market, List<List<String>>> = mapOf(
        Market.LOS_ANGELES to listOf("LA").map(::phrase),
        Market.NEW_YORK to listOf("NY").map(::phrase),
    )

    /** Phrases that veto an ambiguous alias outright. `LA LIGA` is not Los Angeles. */
    val AMBIGUOUS_VETO: List<List<String>> = listOf(
        "LA LIGA", "LA CASA", "LA RED", "LA VOZ", "LA ROSA", "LA U", "LA UNO", "LA DOS",
        "LA SEXTA", "LA NACION", "LA TELE", "LA MEGA", "LA KALLE", "LA ZONA",
        "NY GIANTS", "NY JETS", "NY YANKEES", "NY METS", "NY KNICKS", "NY RANGERS",
    ).map(::phrase)

    /** Call signs for the four allowed markets. A hit here is decisive. */
    val ALLOWED_CALLSIGNS: Map<Market, Set<String>> = mapOf(
        Market.LOS_ANGELES to setOf(
            "KABC", "KCBS", "KNBC", "KTTV", "KTLA", "KCAL", "KCOP", "KOCE", "KMEX", "KVEA",
            "KWHY", "KJLA", "KSCI", "KDOC", "KLCS", "KRCA", "KAZA", "KBEH", "KFTR", "KXLA",
            "KHIZ", "KVCR", "KPXN", "KTBN", "KNET",
        ),
        Market.NEW_YORK to setOf(
            "WABC", "WCBS", "WNBC", "WNYW", "WPIX", "WWOR", "WNET", "WLNY", "WXTV", "WNJU",
            "WFUT", "WMBC", "WNYE", "WRNN", "WPXN", "WNJN", "WLIW", "WFME", "WTBY", "WDVB",
        ),
        Market.MIAMI to setOf(
            "WPLG", "WFOR", "WTVJ", "WSVN", "WSFL", "WPBT", "WLTV", "WSCV", "WGEN", "WAMI",
            "WPXM", "WBFS", "WHFT", "WJAN", "WDLP", "WFUN", "WTVK",
        ),
        Market.TAMPA to setOf(
            "WFTS", "WTSP", "WFLA", "WTVT", "WTOG", "WEDU", "WMOR", "WXPX", "WVEA", "WCLF",
            "WTTA", "WUSF", "WRMD", "WXAX",
        ),
    )

    // ---------------------------------------------------------------- other markets

    /**
     * Cities outside the allowed four. A match here classifies the channel as a local for a market
     * the user did not ask for, so it is dropped with [Market.OTHER].
     */
    val OTHER_CITY: List<List<String>> = listOf(
        "CHICAGO", "PHILADELPHIA", "DALLAS", "FORT WORTH", "HOUSTON", "ATLANTA",
        "WASHINGTON DC", "BOSTON", "PHOENIX", "SEATTLE", "DETROIT", "MINNEAPOLIS",
        "ST PAUL", "DENVER", "ORLANDO", "SACRAMENTO", "SAN FRANCISCO", "SAN JOSE",
        "OAKLAND", "SAN DIEGO", "ST LOUIS", "SAINT LOUIS", "PITTSBURGH", "CHARLOTTE",
        "RALEIGH", "DURHAM", "BALTIMORE", "INDIANAPOLIS", "NASHVILLE", "CLEVELAND",
        "KANSAS CITY", "COLUMBUS", "SALT LAKE CITY", "SAN ANTONIO", "LAS VEGAS", "AUSTIN",
        "NEW ORLEANS", "MILWAUKEE", "CINCINNATI", "PORTLAND", "HARTFORD", "BUFFALO",
        "MEMPHIS", "BIRMINGHAM", "OKLAHOMA CITY", "RICHMOND", "LOUISVILLE",
        "JACKSONVILLE", "ALBUQUERQUE", "FRESNO", "TULSA", "GRAND RAPIDS", "KNOXVILLE",
        "OMAHA", "DAYTON", "HONOLULU", "ANCHORAGE", "BOISE", "SPOKANE", "TUCSON",
        "EL PASO", "HARRISBURG", "ALBANY", "ROCHESTER", "SYRACUSE", "TOLEDO", "WICHITA",
        "LITTLE ROCK", "SHREVEPORT", "CHATTANOOGA", "DES MOINES", "NORFOLK",
        "GREENSBORO", "WEST PALM BEACH", "FORT MYERS", "SAVANNAH", "LEXINGTON",
        "BAKERSFIELD", "STOCKTON", "GREEN BAY", "BATON ROUGE", "TALLAHASSEE",
        "COLORADO SPRINGS", "FORT WAYNE", "HUNTSVILLE", "PENSACOLA", "SIOUX FALLS",
        "CEDAR RAPIDS", "PORTLAND MAINE", "BURLINGTON", "SCRANTON", "YOUNGSTOWN",
    ).map(::phrase)

    /** Call signs for markets outside the allowed four, grouped only to keep the list readable. */
    val OTHER_CALLSIGNS: Set<String> = setOf(
        // Chicago
        "WLS", "WBBM", "WMAQ", "WFLD", "WGN", "WCIU", "WTTW", "WPWR", "WSNS", "WCPX",
        // Philadelphia
        "WPVI", "KYW", "WCAU", "WTXF", "WPHL", "WPSG", "WHYY", "WUVP",
        // Dallas / Fort Worth
        "WFAA", "KTVT", "KXAS", "KDFW", "KDFI", "KTXA", "KERA", "KDAF", "KUVN",
        // Houston
        "KTRK", "KHOU", "KPRC", "KRIV", "KTXH", "KIAH", "KUHT", "KXLN",
        // Atlanta
        "WSBT", "WGCL", "WXIA", "WAGA", "WPCH", "WATL", "WPBA", "WUVG",
        // Washington DC
        "WJLA", "WUSA", "WTTG", "WDCA", "WETA", "WDCW", "WFDC",
        // Boston
        "WCVB", "WHDH", "WFXT", "WSBK", "WGBH", "WLVI", "WUNI", "WBTS",
        // Phoenix
        "KNXV", "KPHO", "KPNX", "KSAZ", "KUTP", "KAET", "KASW", "KTVW",
        // Seattle
        "KOMO", "KIRO", "KING", "KCPQ", "KZJO", "KSTW", "KCTS", "KUNS",
        // Detroit
        "WXYZ", "WWJ", "WDIV", "WJBK", "WKBD", "WTVS", "WMYD",
        // Minneapolis
        "KSTP", "WCCO", "KARE", "KMSP", "WFTC", "KTCA",
        // Denver
        "KMGH", "KCNC", "KUSA", "KDVR", "KWGN", "KRMA", "KTVD",
        // Orlando
        "WFTV", "WKMG", "WESH", "WOFL", "WRBW", "WMFE", "WKCF",
        // Sacramento
        "KXTV", "KOVR", "KCRA", "KTXL", "KMAX", "KVIE", "KSPX",
        // San Francisco
        "KGO", "KPIX", "KNTV", "KTVU", "KICU", "KQED", "KRON", "KBCW", "KDTV",
        // San Diego
        "KGTV", "KFMB", "KNSD", "KSWB", "KPBS", "KUSI",
        // St Louis
        "KTVI", "KMOV", "KSDK", "KDNL", "KPLR", "KETC",
        // Pittsburgh
        "WTAE", "KDKA", "WPXI", "WPGH", "WPCW", "WQED",
        // Charlotte / Raleigh
        "WSOC", "WBTV", "WCNC", "WJZY", "WCCB", "WTVI",
        "WTVD", "WNCN", "WRAL", "WLFL", "WRAZ", "WUNC",
        // Baltimore
        "WMAR", "WJZ", "WBAL", "WBFF", "WNUV", "WMPT",
        // Indianapolis
        "WRTV", "WISH", "WTHR", "WXIN", "WTTV", "WFYI",
        // Nashville
        "WKRN", "WTVF", "WSMV", "WZTV", "WUXP", "WNPT",
        // Cleveland
        "WEWS", "WOIO", "WKYC", "WJW", "WUAB", "WVIZ",
        // Kansas City
        "KMBC", "KCTV", "KSHB", "WDAF", "KCPT",
        // Columbus
        "WSYX", "WBNS", "WCMH", "WTTE", "WWHO", "WOSU",
        // Salt Lake City
        "KTVX", "KUTV", "KSL", "KSTU", "KJZZ", "KUED",
        // San Antonio / Austin
        "KSAT", "KENS", "WOAI", "KABB", "KMYS", "KLRN",
        "KVUE", "KEYE", "KXAN", "KTBC", "KNVA", "KLRU",
        // Las Vegas
        "KTNV", "KLAS", "KSNV", "KVVU", "KVCW", "KLVX",
        // New Orleans
        "WGNO", "WWL", "WDSU", "WVUE", "WUPL", "WYES",
        // Milwaukee
        "WISN", "WDJT", "WTMJ", "WITI", "WVTV", "WMVS",
        // Cincinnati / Dayton
        "WCPO", "WKRC", "WLWT", "WXIX", "WSTR", "WCET",
        "WKEF", "WHIO", "WDTN", "WRGT", "WBDT", "WPTD",
        // Portland
        "KATU", "KOIN", "KGW", "KPTV", "KRCW", "KOPB",
        // Hartford
        "WTNH", "WFSB", "WVIT", "WTIC", "WCCT", "WEDH",
        // Buffalo
        "WKBW", "WIVB", "WGRZ", "WUTV", "WNLO", "WNED",
        // Memphis
        "WATN", "WREG", "WMCT", "WHBQ", "WLMT", "WKNO",
        // Birmingham
        "WBMA", "WIAT", "WVTM", "WBRC", "WTTO", "WBIQ",
        // Oklahoma City / Tulsa
        "KOCO", "KWTV", "KFOR", "KOKH", "KOCB", "KETA",
        "KTUL", "KOTV", "KJRH", "KOKI", "KMYT", "KOED",
        // Richmond / Norfolk
        "WRIC", "WTVR", "WWBT", "WRLH", "WUPV", "WCVE",
        "WAVY", "WVEC", "WTKR", "WGNT",
        // Louisville
        "WHAS", "WLKY", "WAVE", "WDRB", "WBKI", "WKPC",
        // Jacksonville
        "WJXX", "WJAX", "WTLV", "WFOX", "WCWJ", "WJCT",
        // Albuquerque / Fresno
        "KOAT", "KRQE", "KOBF", "KASA", "KWBQ", "KNME",
        "KFSN", "KGPE", "KSEE", "KMPH", "KAIL", "KVPT",
        // Grand Rapids / Knoxville
        "WZZM", "WWMT", "WOOD", "WXMI", "WZPX", "WGVU",
        "WATE", "WVLT", "WBIR", "WTNZ", "WSJK",
        // Omaha / Honolulu
        "KETV", "WOWT", "KMTV", "KPTM", "KXVO", "KYNE",
        "KITV", "KGMB", "KHNL", "KHON", "KFVE", "KHET",
    )

    // ---------------------------------------------------------------- shared signals

    /** Category words that say "these are local affiliates". */
    val CATEGORY_LOCAL_TOKENS: Set<String> = setOf(
        "LOCAL", "LOCALS", "LOCALES", "AFFILIATE", "AFFILIATES", "NETWORKS", "DMA", "OTA",
    )

    /**
     * Broadcast network names. These never make a channel local on their own — `FOX NEWS` and
     * `ABC NEWS LIVE` are national feeds — but they corroborate a city or call-sign match.
     */
    val NETWORK_NAMES: List<List<String>> = listOf(
        "ABC", "CBS", "NBC", "FOX", "CW", "PBS", "MYNETWORKTV", "MYNETWORK", "MNTV", "MY TV",
        "TELEMUNDO", "UNIVISION", "UNIMAS", "ION", "AZTECA", "ESTRELLA", "COZI", "METV",
        "ANTENNA TV", "BOUNCE", "GRIT", "DABL", "LAFF", "CHARGE",
    ).map(::phrase)

    /**
     * Four-letter words beginning with K or W that the call-sign pattern would otherwise claim.
     * A false call-sign match drops a national channel, so this list errs long.
     */
    val CALLSIGN_STOPWORDS: Set<String> = setOf(
        "KIDS", "WEST", "WILD", "WORK", "WORD", "WAVE", "WARS", "WARM", "WOOD", "WHAT",
        "WITH", "WHEN", "WALK", "WALL", "WEEK", "WINE", "KNOW", "WRAP", "WIDE", "WIND",
        "WING", "WISE", "WOKE", "WOLF", "WORE", "WORN", "KEYS", "KICK", "KIND", "KNEE",
        "KEEP", "WAIT", "WANT", "WARN", "WASH", "WEAR", "WELL", "WENT", "WERE", "WHOM",
        "WIFE", "WAYS", "WEBS", "WEED", "WEBB", "KILO", "KITE", "KING", "WISH", "WINS",
        "WORLD", "WATCH", "WOMEN", "WHERE", "KOREA", "WAGON", "WHITE",
    )

    /**
     * A US broadcast call sign: four letters starting with K or W, optionally suffixed with
     * `-TV`, `-DT`, `-HD`, `-CD`, `-LD` or a digit. Tokenising has already stripped the hyphen,
     * so the suffix arrives as a separate token and is checked by the caller.
     */
    val CALLSIGN_PATTERN = Regex("^[KW][A-Z]{3}$")

    /** Suffix tokens that confirm a preceding four-letter token really is a call sign. */
    val CALLSIGN_SUFFIXES: Set<String> = setOf("TV", "DT", "CD", "LD", "LP", "DT1", "DT2", "TV1")

    /** Flattened lookup from call sign to its allowed market. */
    val CALLSIGN_TO_MARKET: Map<String, Market> =
        ALLOWED_CALLSIGNS.entries.flatMap { (market, signs) -> signs.map { it to market } }.toMap()
}
