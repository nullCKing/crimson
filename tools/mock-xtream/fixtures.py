"""
Deterministic fixture data for the mock Xtream server.

Generates a catalogue shaped like a real provider's: a few thousand channels spread over thirty-odd
countries, with the country marker written a different way in almost every category, US locals for
two dozen cities, and a pile of channels whose country cannot be worked out at all.

Every generated channel also carries its **ground truth** — the country and market it really
belongs to, and whether the filter is supposed to keep it. The API never serves those fields; the
discovery tool reads them from the sidecar and uses them to score the filter, which turns "the
kept list looks about right" into a precision and recall number.

Pure standard library, so this runs on any Python 3.8+ without installing anything.
"""

import hashlib
import random

# --------------------------------------------------------------------------- prefix styles

# The ways providers write a country marker, from spec section 3.2.
PREFIX_STYLES = [
    "{c}| {n}",
    "|{c}| {n}",
    "{c}: {n}",
    "{c} - {n}",
    "{c} {n}",
    "[{c}] {n}",
    "{c} ▎{n}",
    "{c} VIP {n}",
    "{c} 4K {n}",
    "{c}•{n}",
    "({c}) {n}",
    "{n}",  # no marker on the channel: only the category says the country
]

QUALITY_SUFFIXES = ["", "", "", " HD", " FHD", " ᴴᴰ", " 4K", " [UHD]", " HEVC", " SD"]

# --------------------------------------------------------------------------- countries

# (display code variants, full name, ISO-ish key). The variants are what gets used as a prefix,
# so a single country appears as several different markers across categories.
ALLOWED_COUNTRIES = {
    "US": (["US", "USA", "US", "US", "USA", "UNITED STATES"], "United States"),
    "UK": (["UK", "UK", "GB", "UK", "GBR", "UNITED KINGDOM", "ENGLAND"], "United Kingdom"),
    "JP": (["JP", "JP", "JPN", "JAPAN"], "Japan"),
    "KR": (["KR", "KR", "KOR", "KOREA", "SOUTH KOREA"], "South Korea"),
}

OTHER_COUNTRIES = {
    "DE": (["DE", "GER", "GERMANY"], "Germany"),
    "FR": (["FR", "FRA", "FRANCE"], "France"),
    "ES": (["ES", "ESP", "SPAIN"], "Spain"),
    "IT": (["IT", "ITA", "ITALY"], "Italy"),
    "NL": (["NL", "NLD", "NETHERLANDS"], "Netherlands"),
    "PT": (["PT", "POR", "PORTUGAL"], "Portugal"),
    "PL": (["PL", "POL", "POLAND"], "Poland"),
    "RO": (["RO", "ROU", "ROMANIA"], "Romania"),
    "RU": (["RU", "RUS", "RUSSIA"], "Russia"),
    "TR": (["TR", "TUR", "TURKEY"], "Turkey"),
    "GR": (["GR", "GRC", "GREECE"], "Greece"),
    "SE": (["SE", "SWE", "SWEDEN"], "Sweden"),
    "NO": (["NO", "NOR", "NORWAY"], "Norway"),
    "DK": (["DK", "DEN", "DENMARK"], "Denmark"),
    "FI": (["FI", "FIN", "FINLAND"], "Finland"),
    "CA": (["CA", "CAN", "CANADA"], "Canada"),
    "MX": (["MX", "MEX", "MEXICO"], "Mexico"),
    "BR": (["BR", "BRA", "BRAZIL"], "Brazil"),
    "AR": (["AR", "ARG", "ARGENTINA"], "Argentina"),
    "CL": (["CL", "CHL", "CHILE"], "Chile"),
    "CO": (["CO", "COL", "COLOMBIA"], "Colombia"),
    "IN": (["IN", "IND", "INDIA"], "India"),
    "PK": (["PK", "PAK", "PAKISTAN"], "Pakistan"),
    "CN": (["CN", "CHN", "CHINA"], "China"),
    "TW": (["TW", "TWN", "TAIWAN"], "Taiwan"),
    "TH": (["TH", "THA", "THAILAND"], "Thailand"),
    "VN": (["VN", "VNM", "VIETNAM"], "Vietnam"),
    "PH": (["PH", "PHL", "PHILIPPINES"], "Philippines"),
    "ID": (["ID", "IDN", "INDONESIA"], "Indonesia"),
    "MY": (["MY", "MYS", "MALAYSIA"], "Malaysia"),
    "AU": (["AU", "AUS", "AUSTRALIA"], "Australia"),
    "NZ": (["NZ", "NZL", "NEW ZEALAND"], "New Zealand"),
    "ZA": (["ZA", "ZAF", "SOUTH AFRICA"], "South Africa"),
    "EG": (["EG", "EGY", "EGYPT"], "Egypt"),
    "SA": (["SA", "SAU", "SAUDI ARABIA"], "Saudi Arabia"),
    "AE": (["AE", "UAE", "EMIRATES"], "United Arab Emirates"),
    "IL": (["IL", "ISR", "ISRAEL"], "Israel"),
    "NG": (["NG", "NGA", "NIGERIA"], "Nigeria"),
    "KE": (["KE", "KEN", "KENYA"], "Kenya"),
    "RS": (["RS", "SRB", "SERBIA"], "Serbia"),
}

# --------------------------------------------------------------------------- channel names

US_NATIONAL = [
    "ESPN", "ESPN 2", "ESPN News", "ESPN U", "ESPN Deportes", "CNN", "HLN", "MSNBC",
    "Fox News", "Fox Business", "CNBC", "Bloomberg", "Newsmax", "Weather Channel",
    "HBO", "HBO 2", "HBO Signature", "HBO Comedy", "HBO Family", "HBO Zone",
    "Cinemax", "More Max", "Action Max", "Showtime", "Showtime 2", "Showtime Extreme",
    "Starz", "Starz Edge", "Starz Comedy", "Epix", "Epix 2", "TMC",
    "Discovery", "Discovery Life", "Animal Planet", "Science Channel", "Investigation Discovery",
    "History", "History 2", "Military History", "American Heroes", "Smithsonian",
    "National Geographic", "Nat Geo Wild", "TLC", "Travel Channel", "Food Network",
    "HGTV", "DIY Network", "Cooking Channel", "Magnolia Network",
    "AMC", "IFC", "Sundance TV", "TCM", "FX", "FXX", "FX Movie", "Paramount Network",
    "TNT", "TBS", "USA Network", "Syfy", "Bravo", "E!", "Oxygen", "Lifetime",
    "Lifetime Movies", "A&E", "Comedy Central", "MTV", "MTV 2", "VH1", "CMT", "BET",
    "BET Her", "Nickelodeon", "Nick Jr", "Teen Nick", "Nicktoons", "Cartoon Network",
    "Adult Swim", "Boomerang", "Disney Channel", "Disney Junior", "Disney XD",
    "Freeform", "Hallmark Channel", "Hallmark Movies", "UP TV", "INSP",
    "Golf Channel", "NFL Network", "NBA TV", "MLB Network", "NHL Network",
    "Tennis Channel", "Fox Sports 1", "Fox Sports 2", "CBS Sports Network",
    "Big Ten Network", "SEC Network", "ACC Network", "Motor Trend", "Speed",
    "Science", "Destination America", "OWN", "TV One", "Revolt", "Fuse",
    "Great American Family", "Reelz", "Game Show Network", "Buzzr", "Antenna TV",
    "MeTV", "Cozi TV", "Start TV", "Laff", "Grit", "Bounce", "Dabl", "Charge",
    "QVC", "HSN", "Shop LC", "Jewelry TV", "EWTN", "TBN", "Daystar",
    "C-SPAN", "C-SPAN 2", "C-SPAN 3", "NASA TV", "PBS Kids", "Create", "World Channel",
]

# A handful chosen because they would trip a naive filter.
US_TRAPS = [
    "Latino Music TV", "Sony Movies", "Smart Living Network", "Kids Zone",
    "West Coast Sports", "Wild Earth", "Wave Music", "Work Life Network",
    "LA Liga TV", "NY Giants Preseason", "America's Test Kitchen", "King of Queens 24/7",
    "The Americas Channel", "New England Sports", "World Fishing Network",
]

UK_CHANNELS = [
    "BBC One", "BBC Two", "BBC Three", "BBC Four", "BBC News", "BBC Parliament",
    "BBC Scotland", "BBC Alba", "CBBC", "CBeebies", "BBC Red Button",
    "ITV 1", "ITV 2", "ITV 3", "ITV 4", "ITV Be", "ITVX", "CITV",
    "Channel 4", "E4", "More 4", "Film 4", "4Seven", "Channel 5", "5 Star",
    "5 USA", "5 Action", "5 Select", "Sky One", "Sky Atlantic", "Sky Witness",
    "Sky Comedy", "Sky Crime", "Sky Documentaries", "Sky Nature", "Sky Arts",
    "Sky Sports Main Event", "Sky Sports Premier League", "Sky Sports Football",
    "Sky Sports Cricket", "Sky Sports Golf", "Sky Sports F1", "Sky Sports News",
    "Sky Cinema Premiere", "Sky Cinema Hits", "Sky Cinema Greats", "Sky Cinema Action",
    "Sky News", "GB News", "Talk TV", "Dave", "Gold", "W", "Alibi", "Eden",
    "Yesterday", "Drama", "Really", "Quest", "Quest Red", "Blaze", "Together TV",
    "BT Sport 1", "BT Sport 2", "TNT Sports 1", "TNT Sports 2", "Premier Sports",
    "Comedy Central UK", "MTV UK", "Nickelodeon UK", "Cartoon Network UK",
    "Discovery UK", "Animal Planet UK", "Eurosport 1", "Eurosport 2", "S4C", "STV",
]

JP_CHANNELS = [
    "NHK G", "NHK E", "NHK BS1", "NHK BS Premium", "NHK World", "Nippon TV",
    "TV Asahi", "TBS", "TV Tokyo", "Fuji TV", "WOWOW Prime", "WOWOW Live",
    "WOWOW Cinema", "Animax", "AT-X", "Kids Station", "Cartoon Network Japan",
    "Disney Channel Japan", "Tokyo MX", "BS11", "BS12", "J Sports 1", "J Sports 2",
    "J Sports 3", "J Sports 4", "GAORA", "Sky A", "Nittele G+", "Fuji TV One",
    "Fuji TV Two", "TBS Channel 1", "TBS Channel 2", "TV Asahi Channel 1",
    "Nikkei CNBC", "Music On TV", "Space Shower TV", "MTV Japan", "Toei Channel",
    "Nihon Eiga Senmon", "Star Channel", "Movie Plus", "Mystery Channel",
]

KR_CHANNELS = [
    "KBS 1", "KBS 2", "KBS World", "KBS Drama", "KBS Joy", "KBS N Sports",
    "MBC", "MBC Drama", "MBC Every1", "MBC Sports Plus", "MBC Music",
    "SBS", "SBS Plus", "SBS FunE", "SBS Sports", "SBS Golf", "SBS Biz",
    "JTBC", "JTBC 2", "JTBC Golf", "TV Chosun", "Channel A", "MBN",
    "tvN", "tvN Drama", "tvN Sports", "OCN", "OCN Movies", "Super Action",
    "Catch On 1", "Catch On 2", "Mnet", "Arirang TV", "YTN", "Yonhap News TV",
    "EBS 1", "EBS 2", "Tooniverse", "Daekyo Kids TV", "Animax Korea",
]

GENERIC_NAMES = [
    "Entertainment", "Cinema", "Sports", "News", "Kids", "Music", "Documentary",
    "Comedy", "Drama", "Action", "Lifestyle", "Travel", "Nature", "History",
    "Family", "Classic", "Premium", "Plus", "Gold", "Max", "Extra", "Live",
]

# --------------------------------------------------------------------------- US markets

# (market key, display city, call signs). The first four are the markets the filter keeps.
ALLOWED_MARKETS = [
    ("LOS_ANGELES", "Los Angeles",
     ["KABC", "KCBS", "KNBC", "KTTV", "KTLA", "KCAL", "KCOP", "KMEX", "KVEA", "KOCE"]),
    ("NEW_YORK", "New York",
     ["WABC", "WCBS", "WNBC", "WNYW", "WPIX", "WWOR", "WNET", "WXTV", "WNJU", "WLNY"]),
    ("MIAMI", "Miami",
     ["WPLG", "WFOR", "WTVJ", "WSVN", "WSFL", "WPBT", "WLTV", "WSCV", "WAMI", "WBFS"]),
    ("TAMPA", "Tampa",
     ["WFTS", "WTSP", "WFLA", "WTVT", "WTOG", "WEDU", "WMOR", "WXPX", "WVEA", "WTTA"]),
]

OTHER_MARKETS = [
    ("Chicago", ["WLS", "WBBM", "WMAQ", "WFLD", "WGN", "WCIU", "WTTW", "WPWR"]),
    ("Philadelphia", ["WPVI", "KYW", "WCAU", "WTXF", "WPHL", "WPSG", "WHYY"]),
    ("Dallas", ["WFAA", "KTVT", "KXAS", "KDFW", "KDFI", "KTXA", "KERA"]),
    ("Houston", ["KTRK", "KHOU", "KPRC", "KRIV", "KTXH", "KIAH", "KUHT"]),
    ("Atlanta", ["WGCL", "WXIA", "WAGA", "WPCH", "WATL", "WPBA"]),
    ("Washington DC", ["WJLA", "WUSA", "WTTG", "WDCA", "WETA", "WDCW"]),
    ("Boston", ["WCVB", "WHDH", "WFXT", "WSBK", "WGBH", "WLVI"]),
    ("Phoenix", ["KNXV", "KPHO", "KPNX", "KSAZ", "KUTP", "KAET"]),
    ("Seattle", ["KOMO", "KIRO", "KCPQ", "KZJO", "KSTW", "KCTS"]),
    ("Detroit", ["WXYZ", "WWJ", "WDIV", "WJBK", "WKBD", "WTVS"]),
    ("Minneapolis", ["KSTP", "WCCO", "KARE", "KMSP", "WFTC", "KTCA"]),
    ("Denver", ["KMGH", "KCNC", "KUSA", "KDVR", "KWGN", "KRMA"]),
    ("Orlando", ["WFTV", "WKMG", "WESH", "WOFL", "WRBW", "WKCF"]),
    ("Sacramento", ["KXTV", "KOVR", "KCRA", "KTXL", "KMAX", "KVIE"]),
    ("San Francisco", ["KGO", "KPIX", "KNTV", "KTVU", "KICU", "KQED"]),
    ("San Diego", ["KGTV", "KFMB", "KNSD", "KSWB", "KPBS", "KUSI"]),
    ("St Louis", ["KTVI", "KMOV", "KSDK", "KDNL", "KPLR", "KETC"]),
    ("Pittsburgh", ["WTAE", "KDKA", "WPXI", "WPGH", "WPCW", "WQED"]),
    ("Charlotte", ["WSOC", "WBTV", "WCNC", "WJZY", "WCCB", "WTVI"]),
    ("Baltimore", ["WMAR", "WJZ", "WBAL", "WBFF", "WNUV", "WMPT"]),
    ("Nashville", ["WKRN", "WTVF", "WSMV", "WZTV", "WUXP", "WNPT"]),
    ("Cleveland", ["WEWS", "WOIO", "WKYC", "WJW", "WUAB", "WVIZ"]),
    ("Portland", ["KATU", "KOIN", "KGW", "KPTV", "KRCW", "KOPB"]),
    ("Las Vegas", ["KTNV", "KLAS", "KSNV", "KVVU", "KVCW", "KLVX"]),
]

NETWORKS = ["ABC", "CBS", "NBC", "FOX", "CW", "PBS", "MyNetworkTV", "Telemundo", "Univision", "ION"]

GENRES = ["Entertainment", "Sports", "News", "Movies", "Kids", "Documentary", "Music", "General"]


def _stable_id(*parts):
    """
    A deterministic id from the channel's identity, so ids survive regeneration.

    Kept inside a signed 32-bit range because that is what real Xtream panels hand out
    (an auto-increment column), and generating anything wider would be testing a case the
    API never produces. The app itself stores stream ids as Long regardless.
    """
    h = hashlib.sha1("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()
    return int(h[:8], 16) % 2_000_000_000


class Catalog:
    def __init__(self, categories, channels):
        self.categories = categories
        self.channels = channels
        self.by_id = {c["stream_id"]: c for c in channels}
        self.by_category = {}
        for c in channels:
            self.by_category.setdefault(c["category_id"], []).append(c)

    @property
    def expected_kept(self):
        return [c for c in self.channels if c["truth_kept"]]


def build_catalog(seed=20260921, target_channels=5200):
    """Builds the whole catalogue deterministically."""
    rng = random.Random(seed)
    categories = []
    channels = []
    used_ids = set()

    def add_category(name, country_key=None, market=None, indicates_locals=False):
        cid = str(len(categories) + 1)
        categories.append({
            "category_id": cid,
            "category_name": name,
            "parent_id": 0,
            # Ground truth, stripped before serving.
            "truth_country": country_key,
            "truth_market": market,
            "truth_locals": indicates_locals,
        })
        return cid

    def add_channel(name, category_id, truth_country, truth_market=None, truth_kept=False,
                    epg=True):
        sid = _stable_id(name, category_id)
        while sid in used_ids:
            sid += 1
        used_ids.add(sid)
        channels.append({
            "num": len(channels) + 1,
            "name": name,
            "stream_type": "live",
            "stream_id": sid,
            "stream_icon": "http://127.0.0.1/logos/%d.png" % (sid % 200),
            "epg_channel_id": ("mock.%d" % sid) if epg else "",
            "added": "1600000000",
            "category_id": category_id,
            "custom_sid": "",
            "tv_archive": 1 if rng.random() < 0.25 else 0,
            "direct_source": "",
            "tv_archive_duration": 3,
            "truth_country": truth_country,
            "truth_market": truth_market,
            "truth_kept": truth_kept,
        })
        used_ids.add(sid)

    def decorate(name, code, style_index):
        style = PREFIX_STYLES[style_index % len(PREFIX_STYLES)]
        quality = rng.choice(QUALITY_SUFFIXES)
        return style.format(c=code, n=name) + quality

    # ---------------------------------------------------------------- US national
    style_counter = 0
    for genre in ["Entertainment", "Sports", "News", "Movies", "Kids", "Documentary"]:
        code = rng.choice(ALLOWED_COUNTRIES["US"][0])
        cat = add_category("%s | %s" % (code, genre.upper()), "US")
        pool = [n for n in US_NATIONAL if _genre_of(n) == genre] or US_NATIONAL
        for name in pool:
            add_channel(decorate(name, rng.choice(ALLOWED_COUNTRIES["US"][0]), style_counter),
                        cat, "US", None, True)
            style_counter += 1

    # Channels designed to trip a naive filter. They are national US and must be kept.
    trap_cat = add_category("US | 24/7 CHANNELS", "US")
    for name in US_TRAPS:
        add_channel(decorate(name, "US", style_counter), trap_cat, "US", None, True)
        style_counter += 1

    # A category with no country marker at all, holding US channels that name their own country.
    mystery_cat = add_category("24/7 SHOWS & MOVIES", None)
    for name in US_NATIONAL[:40]:
        add_channel("US| %s 24/7" % name, mystery_cat, "US", None, True)

    # ---------------------------------------------------------------- US locals
    for key, city, signs in ALLOWED_MARKETS:
        cat = add_category("US | %s LOCALS" % city.upper(), "US", key, True)
        for i, sign in enumerate(signs):
            net = NETWORKS[i % len(NETWORKS)]
            name = decorate("%s %s %d %s" % (sign, net, i + 2, city), "US", style_counter)
            add_channel(name, cat, "US", key, True)
            style_counter += 1

    # Every allowed market also appears inside a generic locals category, where only the call
    # sign or city in the channel name can identify it.
    generic_locals = add_category("US | LOCAL CHANNELS", "US", None, True)
    for key, city, signs in ALLOWED_MARKETS:
        for sign in signs[:5]:
            add_channel("US| %s %s" % (sign, city), generic_locals, "US", key, True)

    for city, signs in OTHER_MARKETS:
        cat = add_category("US | %s LOCALS" % city.upper(), "US", "OTHER", True)
        for i, sign in enumerate(signs):
            net = NETWORKS[i % len(NETWORKS)]
            name = decorate("%s %s %d %s" % (sign, net, i + 2, city), "US", style_counter)
            add_channel(name, cat, "US", "OTHER", False)
            style_counter += 1

    # ---------------------------------------------------------------- UK, JP, KR
    for key, names in (("UK", UK_CHANNELS), ("JP", JP_CHANNELS), ("KR", KR_CHANNELS)):
        codes = ALLOWED_COUNTRIES[key][0]
        chunk = max(1, len(names) // 4)
        for start in range(0, len(names), chunk):
            code = rng.choice(codes)
            genre = GENRES[(start // chunk) % len(GENRES)]
            cat = add_category("%s | %s" % (code, genre.upper()), key)
            for name in names[start:start + chunk]:
                add_channel(decorate(name, rng.choice(codes), style_counter), cat, key, None, True)
                style_counter += 1

    # ---------------------------------------------------------------- everyone else
    others = list(OTHER_COUNTRIES.items())
    per_country = max(1, (target_channels - len(channels)) // len(others))
    for key, (codes, full) in others:
        for genre in GENRES[:4]:
            code = rng.choice(codes)
            cat = add_category("%s | %s" % (code, genre.upper()), key)
            for i in range(max(1, per_country // 4)):
                base = "%s %s %d" % (full.split()[0], rng.choice(GENERIC_NAMES), i + 1)
                add_channel(decorate(base, rng.choice(codes), style_counter), cat, key, None, False)
                style_counter += 1

    # ---------------------------------------------------------------- unidentifiable
    # Channels with no country marker anywhere. The spec says to discard these.
    unknown_cat = add_category("GENERAL ENTERTAINMENT", None)
    for i in range(120):
        add_channel("%s %d" % (rng.choice(GENERIC_NAMES), i + 1), unknown_cat, None, None, False)

    # A category whose name is an English sentence beginning with a country code.
    sentence_cat = add_category("IN THE MIX", None)
    for i in range(20):
        add_channel("US| Mixtape %d" % (i + 1), sentence_cat, "US", None, True)

    return Catalog(categories, channels)


def _genre_of(name):
    lowered = name.lower()
    if any(w in lowered for w in ("espn", "sport", "nfl", "nba", "mlb", "nhl", "golf", "tennis",
                                 "big ten", "sec network", "acc network", "speed", "motor")):
        return "Sports"
    if any(w in lowered for w in ("news", "cnn", "msnbc", "cnbc", "bloomberg", "weather",
                                 "c-span", "hln", "newsmax")):
        return "News"
    if any(w in lowered for w in ("hbo", "cinemax", "showtime", "starz", "epix", "tmc", "cinema",
                                 "movie", "tcm", "max")):
        return "Movies"
    if any(w in lowered for w in ("nick", "disney", "cartoon", "boomerang", "kids", "teen",
                                 "adult swim", "pbs kids")):
        return "Kids"
    if any(w in lowered for w in ("discovery", "history", "nat geo", "national geographic",
                                 "science", "smithsonian", "animal", "nasa", "investigation")):
        return "Documentary"
    return "Entertainment"


def public_category_view(category):
    """The category as the API serves it: ground truth removed."""
    return {k: v for k, v in category.items() if not k.startswith("truth_")}


def public_channel_view(channel):
    """The channel as the API serves it: ground truth removed."""
    return {k: v for k, v in channel.items() if not k.startswith("truth_")}
