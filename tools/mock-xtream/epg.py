"""
XMLTV generation for the mock Xtream server.

Everything here is a generator. A provider's `xmltv.php` for a few thousand channels over several
days is tens of megabytes, and the point of the exercise is to hand the app something big enough
to break a naive parser, so the server must not build it in memory either.

Programme times are derived from a fixed epoch rather than from `now`, so re-running the server
produces a stable guide and screenshots taken days apart still line up.
"""

import datetime
import hashlib

TITLES = {
    "Movies": [
        "The Long Afternoon", "Harbour Lights", "Nine Miles North", "The Glass Quarter",
        "Salt and Iron", "A Quiet Signal", "The Last Ferry", "Winterline",
        "Cold Harbour", "The Paper Kingdom", "Riverbend", "Every Other Sunday",
    ],
    "Sports": [
        "Live: Championship Football", "Live: Premier League", "Live: Baseball Tonight",
        "Live: Basketball Doubleheader", "SportsCenter", "Match of the Day",
        "Live: Grand Slam Tennis", "Live: Championship Golf", "Boxing Classics",
        "Live: Motor Racing", "Around the Horn", "Pardon the Interruption",
    ],
    "News": [
        "Evening News", "Morning Report", "World News Tonight", "Business Today",
        "The Briefing", "Weather Watch", "Nightly News", "News at Ten",
        "Sunday Politics", "The Newsroom", "Market Wrap", "Headlines",
    ],
    "Kids": [
        "Pebble and Bean", "The Sock Drawer", "Captain Compass", "Tiny Engineers",
        "Marsh Street", "Doodle Club", "The Bird Who Forgot", "Sprocket Town",
        "Lantern Lane", "Puddle Jumpers", "Nine Lives of Norman", "Paper Planes",
    ],
    "Series": [
        "Northgate", "The Auditors", "Case Notes", "Hollow Creek", "Second Shift",
        "The Understudy", "Mercer & Vine", "Low Tide", "The Reckoners",
        "Quarter Past", "Shoreline", "The Long Game", "Ward Six", "Bellwether",
    ],
}

CATEGORY_TAGS = {
    "Movies": "Movie",
    "Sports": "Sports",
    "News": "News",
    "Kids": "Children's",
    "Series": "Series",
}

RATINGS = ["TV-G", "TV-PG", "TV-14", "TV-MA", "G", "PG", "PG-13", "R"]

DESCRIPTIONS = [
    "A long-running favourite returns with an episode that ties up more than it opens.",
    "Our correspondents report from three continents on the day's developing stories.",
    "The team faces its toughest test of the season in front of a sold-out crowd.",
    "An unexpected visitor forces everyone to reconsider what they thought they knew.",
    "Coverage continues with analysis, highlights and reaction from both camps.",
    "A quiet character study that takes its time and rewards the patience.",
    "Two old rivals meet again, and neither is quite the person they used to be.",
    "The investigation turns up a detail that changes the shape of the whole case.",
]

# Programme grids are anchored here so the guide is reproducible between runs.
EPOCH = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)


def _pick(options, *seed_parts):
    h = hashlib.sha1("|".join(str(p) for p in seed_parts).encode("utf-8")).hexdigest()
    return options[int(h[:8], 16) % len(options)]


def _xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
             .replace('"', "&quot;"))


def _fmt(dt):
    return dt.strftime("%Y%m%d%H%M%S +0000")


def programmes_for(channel, start, end):
    """
    Yields (start, stop, title, description, category, rating) for one channel between two times.

    Slot length varies per channel but always divides into the half hour, so the generated guide
    lines up with the grid the way a real one does. Roughly one channel in twelve is given no
    programmes at all, which is what exercises the "No Information" filler path.
    """
    epg_id = channel["epg_channel_id"]
    if not epg_id:
        return
    if int(hashlib.sha1(epg_id.encode()).hexdigest()[:4], 16) % 12 == 0:
        return  # a channel the provider has no data for

    slot_minutes = _pick([30, 30, 60, 60, 60, 90, 120], epg_id, "slot")
    genre = _pick(list(TITLES.keys()), epg_id, "genre")

    # Align the first slot to the epoch so slots never drift.
    minutes_since_epoch = int((start - EPOCH).total_seconds() // 60)
    aligned = minutes_since_epoch - (minutes_since_epoch % slot_minutes)
    cursor = EPOCH + datetime.timedelta(minutes=aligned)

    index = aligned // slot_minutes
    while cursor < end:
        stop = cursor + datetime.timedelta(minutes=slot_minutes)
        # A sports or movie channel keeps its genre; others mix in some variety.
        slot_genre = genre if genre in ("Sports", "Movies") else _pick(
            [genre, genre, genre, "News", "Series"], epg_id, index)
        title = _pick(TITLES[slot_genre], epg_id, index, "title")
        if slot_genre == "Series":
            season = (index % 6) + 1
            episode = (index % 13) + 1
            title = "%s: S%dE%d" % (title, season, episode)
        yield (
            cursor,
            stop,
            title,
            _pick(DESCRIPTIONS, epg_id, index, "desc"),
            CATEGORY_TAGS[slot_genre],
            _pick(RATINGS, epg_id, index, "rating"),
        )
        cursor = stop
        index += 1


def stream_xmltv(channels, days_back=1, days_forward=3, now=None):
    """
    Yields the XMLTV document as UTF-8 byte chunks.

    Channel elements come first, then programmes, so a streaming parser sees the channel map before
    it needs it.
    """
    now = now or datetime.datetime.now(datetime.timezone.utc)
    start = now - datetime.timedelta(days=days_back)
    end = now + datetime.timedelta(days=days_forward)

    yield b'<?xml version="1.0" encoding="UTF-8"?>\n'
    yield b'<tv generator-info-name="retroguide-mock-xtream">\n'

    with_epg = [c for c in channels if c["epg_channel_id"]]

    for c in with_epg:
        yield ('  <channel id="%s">\n    <display-name>%s</display-name>\n'
               '    <icon src="%s" />\n  </channel>\n' % (
                   _xml_escape(c["epg_channel_id"]),
                   _xml_escape(c["name"]),
                   _xml_escape(c["stream_icon"]),
               )).encode("utf-8")

    buf = []
    size = 0
    for c in with_epg:
        cid = _xml_escape(c["epg_channel_id"])
        for (s, e, title, desc, cat, rating) in programmes_for(c, start, end):
            buf.append(
                '  <programme start="%s" stop="%s" channel="%s">\n'
                '    <title lang="en">%s</title>\n'
                '    <desc lang="en">%s</desc>\n'
                '    <category lang="en">%s</category>\n'
                '    <rating system="VCHIP"><value>%s</value></rating>\n'
                '  </programme>\n' % (
                    _fmt(s), _fmt(e), cid,
                    _xml_escape(title), _xml_escape(desc), _xml_escape(cat), _xml_escape(rating),
                )
            )
            size += 1
            if size >= 200:
                yield "".join(buf).encode("utf-8")
                buf = []
                size = 0
    if buf:
        yield "".join(buf).encode("utf-8")

    yield b"</tv>\n"


def short_epg_for(channel, limit=4, now=None):
    """The `get_short_epg` payload: the next few programmes, base64 encoded like a real provider."""
    import base64
    now = now or datetime.datetime.now(datetime.timezone.utc)
    out = []
    window_end = now + datetime.timedelta(hours=12)
    for (s, e, title, desc, cat, rating) in programmes_for(channel, now, window_end):
        if e <= now:
            continue
        out.append({
            "id": str(abs(hash((channel["stream_id"], s.isoformat()))) % 10**9),
            "epg_id": str(channel["stream_id"]),
            "title": base64.b64encode(title.encode("utf-8")).decode("ascii"),
            "lang": "en",
            "start": s.strftime("%Y-%m-%d %H:%M:%S"),
            "end": e.strftime("%Y-%m-%d %H:%M:%S"),
            "description": base64.b64encode(desc.encode("utf-8")).decode("ascii"),
            "channel_id": channel["epg_channel_id"],
            "start_timestamp": str(int(s.timestamp())),
            "stop_timestamp": str(int(e.timestamp())),
        })
        if len(out) >= limit:
            break
    return out
