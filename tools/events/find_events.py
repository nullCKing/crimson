#!/usr/bin/env python3
"""
Finds the live events buried in an XMLTV guide, for the Sports page's Extra tab.

A curated guide such as EPGenius's lists tens of thousands of programmes a day across thousands
of channels. Somewhere in there is a wrestling card on a local station, a rodeo, a high school
game, a bowling final, sumo, a jousting tournament: things worth watching live that no
scoreboard lists. Telling them apart from the news, talk shows, pregame shows, replays and
reality competitions around them is a judgement about each title, which is what a small LLM is
good at and what keyword rules are bad at.

    1. Read the guide(s) and keep programmes in the next HOURS (optionally only on the channels a
       playlist carries).
    2. Reduce to distinct titles (with a little of the description); most repeat every day.
    3. Ask Claude Haiku, in batches, which are events and what kind, for titles not already in the
       verdict cache. A day's new titles cost cents.
    4. Write events.json.gz (what the app downloads) and the updated cache.

Without ANTHROPIC_API_KEY it falls back to keyword rules, which find less and admit more noise,
so the pipeline and the app can be exercised before a key exists.

    python find_events.py --guide https://.../epg6.xml.gz --out events.json.gz --cache cache.json.gz
"""

import argparse
import concurrent.futures
import datetime
import gzip
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

MODEL = "claude-haiku-5-5"
# Claude Haiku 5.5, per million tokens, prompts up to 100K tokens (for the cost line in the log).
PRICE_IN, PRICE_OUT = 0.10, 0.50
BATCH = 120
WORKERS = 4
CACHE_DAYS = 21

SYSTEM = """You find live events in a TV guide for a viewer who loves watching unusual live events and \
competitions on TV and wants a list of them, because they are buried among thousands of channels.

You get numbered guide entries: title | description | channel | whether the guide marks it live.
Return the entries that are coverage of an actual event or competition happening at broadcast time \
(or same-day/first-run coverage of one): a game, match, race, bout, card, meet, tournament round, \
rodeo, parade, festival, competition, ceremony with a contest, esports final, chess match, and so on. \
Strange and obscure is the point: high school and small-college games, minor and regional leagues, \
pro wrestling cards, bowling, rodeo and bull riding, sumo, lumberjack and strongman contests, \
cornhole, darts, snooker, poker finals, drone racing, medieval combat, dog shows, spelling bees, \
eating contests, local parades. Big-league and big-college games count too, but mark them mainstream.

Do NOT return:
- any soccer / football (association football), futsal or beach soccer, in any language or league;
- news, weather, talk, analysis, pregame, postgame, halftime, highlights, magazine, preview and \
  review shows, scoreboard or "around the league" shows, podcasts, interviews;
- replays and classics of past events ("Classic", "Greatest", "Rewind", a year in the past, "Replay");
- documentaries, biographies, films, scripted shows, and produced reality competitions \
  (baking/cooking/dating/talent shows, game shows);
- generic filler titles that name no event (e.g. just "Boxing" with no description of a card), \
  unless the guide marks it live and it is on a channel that only shows that sport.

For each event return: i (its number), kind (what it is in 1-3 words, Title Case, e.g. \
"Pro Wrestling", "High School Football", "Sumo", "Rodeo", "Bowling", "Parade", "College Volleyball"), \
headline (the event's name as a viewer would say it, without channel names, "Live:", "(L)" or \
decorative characters, at most 80 characters), and mainstream (true for NFL, NBA, MLB, NHL, WNBA, \
NCAA Division I football and men's basketball, the PGA Tour and the big tennis tours; false otherwise).
Return only events; leave everything else out."""

# Guide decoration that says "live" or "new" and is not part of the name.
SUPERSCRIPT = re.compile(r"[ᴬ-ᵪᶜ-ᶿ⁰-₟ʰ-˿]+")
LIVE_MARK = re.compile(r"^\s*(?:live\s*[:\-|]\s*|\(l\)\s*|\[live\]\s*)|\s*(?:\(live\)|\[live\]|- live)\s*$", re.I)
SOCCER = re.compile(r"soccer|f[uú]tbol|futsal|association football|\bfutebol\b|\bcalcio\b|fu[sß]ball|"
                    r"\bla ?liga\b|super ?lig\b|premier league(?! darts)|bundesliga|serie a\b|ligue 1|eredivisie|"
                    r"liga mx|\bmls\b|\bnwsl\b|\buefa\b|\bfifa\b|champions league|europa league|copa (am[eé]rica|libertadores|sudamericana)|"
                    r"\bfa cup\b|\befl\b|championship: .* vs|a-league|\bspl\b|scottish premiership", re.I)


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def open_source(path_or_url):
    """A path or URL, gzip or not, as a binary stream."""
    if re.match(r"^https?://", path_or_url):
        req = urllib.request.Request(path_or_url, headers={"User-Agent": "crimson-events/1.0"})
        data = urllib.request.urlopen(req, timeout=300).read()
    else:
        with open(path_or_url, "rb") as f:
            data = f.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return io.BytesIO(data)


def xmltv_ms(s):
    if not s:
        return None
    m = re.match(r"(\d{14})\s*([+-]\d{4})?", s.strip())
    if not m:
        return None
    t = datetime.datetime.strptime(m.group(1), "%Y%m%d%H%M%S").replace(tzinfo=datetime.timezone.utc)
    if m.group(2):
        sign = 1 if m.group(2)[0] == "+" else -1
        t -= datetime.timedelta(hours=int(m.group(2)[1:3]), minutes=int(m.group(2)[3:5])) * sign
    return int(t.timestamp() * 1000)


def clean_title(raw):
    """The title without guide decoration, and whether that decoration said "live"."""
    t = raw or ""
    live = bool(re.search(r"ᴸᶦᵛᵉ", t))
    t = SUPERSCRIPT.sub("", t)
    if LIVE_MARK.search(t):
        live = True
        t = LIVE_MARK.sub("", t)
    return re.sub(r"\s+", " ", t).strip(" -|:"), live


def playlist_ids(path_or_url):
    text = open_source(path_or_url).read().decode("utf-8", "replace")
    return set(re.findall(r'tvg-id="([^"]+)"', text))


def read_guides(sources, now_ms, hours, only_channels):
    """Programmes in the window: (channel, start, stop, title, desc, live, channel name)."""
    end_ms = now_ms + hours * 3600_000
    progs, names = [], {}
    for src in sources:
        log(f"reading {src}")
        for _, el in ET.iterparse(open_source(src), events=("end",)):
            if el.tag == "channel":
                dn = el.findtext("display-name")
                if dn:
                    names[el.get("id")] = dn.strip()
                el.clear()
            elif el.tag == "programme":
                ch = el.get("channel")
                if only_channels is None or ch in only_channels:
                    s, e = xmltv_ms(el.get("start")), xmltv_ms(el.get("stop"))
                    if s and e and e > now_ms and s < end_ms:
                        title, marked = clean_title(el.findtext("title"))
                        desc = re.sub(r"\s+", " ", (el.findtext("desc") or "")).strip()
                        live = marked or el.find("live") is not None
                        if title:
                            progs.append((ch, s, e, title, desc, live))
                el.clear()
    return progs, names


def key_of(title, desc):
    return hashlib.sha1((title.lower() + "|" + desc[:160].lower()).encode("utf-8")).hexdigest()[:16]


# ---------------------------------------------------------------------------- the model


def classify_with_claude(batches, cache, today):
    import anthropic
    from pydantic import BaseModel

    class Found(BaseModel):
        i: int
        kind: str
        headline: str
        mainstream: bool

    class Result(BaseModel):
        events: list[Found]

    client = anthropic.Anthropic(max_retries=5)
    usage = {"in": 0, "out": 0}

    def run(batch):
        lines = [
            f"{n}. {t} | {d[:160] or '-'} | {ch or '-'} | live: {'yes' if lv else 'no'}"
            for n, (k, t, d, ch, lv) in enumerate(batch)
        ]
        response = client.messages.parse(
            model=MODEL,
            max_tokens=8000,
            system=SYSTEM,
            output_config={"effort": "low"},
            messages=[{"role": "user", "content": "\n".join(lines)}],
            output_format=Result,
        )
        usage["in"] += response.usage.input_tokens
        usage["out"] += response.usage.output_tokens
        if response.stop_reason == "max_tokens" and len(batch) > 1:
            half = len(batch) // 2
            return run(batch[:half]) + run(batch[half:])
        if response.stop_reason == "refusal" or response.parsed_output is None:
            log(f"  batch of {len(batch)} not classified ({response.stop_reason}); left for next run")
            return []
        found = {f.i: f for f in response.parsed_output.events if 0 <= f.i < len(batch)}
        out = []
        for n, (k, t, d, ch, lv) in enumerate(batch):
            f = found.get(n)
            out.append((k, {"e": True, "k": f.kind.strip()[:40], "h": f.headline.strip()[:100], "m": f.mainstream, "seen": today}
                        if f else {"e": False, "seen": today}))
        return out

    done = 0
    with concurrent.futures.ThreadPoolExecutor(WORKERS) as pool:
        for result in pool.map(run, batches):
            for k, verdict in result:
                cache[k] = verdict
            done += 1
            if done % 10 == 0:
                log(f"  {done}/{len(batches)} batches")
    cost = usage["in"] / 1e6 * PRICE_IN + usage["out"] / 1e6 * PRICE_OUT
    log(f"model: {usage['in']} input + {usage['out']} output tokens, about ${cost:.3f}")


# Keyword rules for when there is no API key: a live mark plus a word that names an event.
EVENT_WORDS = [
    ("Pro Wrestling", r"wrestl|\bwwe\b|\baew\b|\bnwa\b|\bimpact\b|\btna\b|\bring of honor\b"),
    ("Combat Sports", r"\bboxing\b|\bmma\b|\bufc\b|\bbkfc\b|bare knuckle|kickbox|\bpfl\b"),
    ("Rodeo", r"rodeo|bull riding|\bpbr\b|barrel racing"),
    ("Sumo", r"\bsumo\b|basho"),
    ("Bowling", r"bowling|\bpba\b"),
    ("Motorsport", r"nascar|indycar|grand prix|motogp|superbike|supercross|motocross|rally|drag racing|sprint car|dirt track"),
    ("High School Sports", r"high school|\bhs\b|prep "),
    ("College Sports", r"college|ncaa|\bnaia\b"),
    ("Darts & Snooker", r"\bdarts\b|snooker|\bpool\b|billiards"),
    ("Racing", r"horse racing|harness|greyhound|\bderby\b|stakes\b"),
    ("Competition", r"championship|tournament|\bfinal\b|\bvs\.?\b|\bv\.? |\bat\b"),
]
NOT_EVENTS = re.compile(r"news|weather|today|tonight|morning|countdown|pregame|post ?game|postgame|preview|review|highlight|"
                        r"classic|greatest|rewind|replay|podcast|show\b|center\b|central\b|zone\b|huddle|kickoff|report|magazine|"
                        r"scoreboard|extra\b|final score|round ?up|wrap", re.I)
MAINSTREAM = re.compile(r"\b(nfl|nba|mlb|nhl|wnba|pga tour)\b|college football|college basketball|ncaa (football|basketball)", re.I)


def classify_with_rules(items, cache, today):
    for k, t, d, ch, lv in items:
        verdict = {"e": False, "seen": today}
        if lv and not NOT_EVENTS.search(t) and not SOCCER.search(t + " " + d):
            for kind, pat in EVENT_WORDS:
                if re.search(pat, t, re.I):
                    verdict = {"e": True, "k": kind, "h": t[:100], "m": bool(MAINSTREAM.search(t)), "seen": today, "rule": True}
                    break
        cache[k] = verdict


# ---------------------------------------------------------------------------- main


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--guide", action="append", required=True, help="XMLTV path or URL (repeatable)")
    ap.add_argument("--playlist", help="only channels in this M3U (path or URL)")
    ap.add_argument("--out", default="events.json.gz")
    ap.add_argument("--cache", default="cache.json.gz", help="verdict cache, read and rewritten")
    ap.add_argument("--hours", type=int, default=72)
    ap.add_argument("--rules", action="store_true", help="keyword rules even with an API key")
    args = ap.parse_args()

    now_ms = int(time.time() * 1000)
    today = datetime.date.today().isoformat()
    only = playlist_ids(args.playlist) if args.playlist else None
    if only is not None:
        log(f"playlist: {len(only)} channel ids")

    progs, names = read_guides(args.guide, now_ms, args.hours, only)
    log(f"{len(progs)} programmes in the next {args.hours} h")

    cache = {}
    if os.path.exists(args.cache):
        try:
            cache = json.load(gzip.open(args.cache, "rt", encoding="utf-8"))
        except Exception as e:  # a damaged cache only costs a re-classification
            log(f"cache unreadable ({e}); starting empty")
    use_model = bool(os.environ.get("ANTHROPIC_API_KEY")) and not args.rules
    if not use_model:
        # Rule verdicts are not kept: a later run with a key should classify those titles.
        cache = {k: v for k, v in cache.items() if not v.get("rule")}

    # One entry per distinct title + description; the live mark and channel of its first airing.
    distinct = {}
    for ch, s, e, t, d, lv in progs:
        k = key_of(t, d)
        if k not in distinct:
            distinct[k] = (k, t, d, names.get(ch, ch), lv)
        elif lv and not distinct[k][4]:
            distinct[k] = distinct[k][:4] + (True,)
    todo = [v for k, v in distinct.items() if k not in cache]
    log(f"{len(distinct)} distinct titles, {len(todo)} not seen before")

    if todo:
        if use_model:
            batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
            log(f"classifying {len(todo)} titles with {MODEL} in {len(batches)} batches")
            classify_with_claude(batches, cache, today)
        else:
            log("no ANTHROPIC_API_KEY: keyword rules")
            classify_with_rules(todo, cache, today)
    for k in distinct:
        if k in cache:
            cache[k]["seen"] = today

    events = []
    for ch, s, e, t, d, lv in progs:
        v = cache.get(key_of(t, d))
        if not v or not v.get("e"):
            continue
        if SOCCER.search(v.get("k", "") + " " + v.get("h", "")):
            continue
        events.append({"c": ch, "s": s, "e": e, "t": v.get("h") or t, "k": v.get("k") or "Live Event",
                       "l": lv, "m": bool(v.get("m")), "d": d[:240]})
    events.sort(key=lambda x: (x["s"], x["t"]))
    kinds = {}
    for ev in events:
        kinds[ev["k"]] = kinds.get(ev["k"], 0) + 1
    log(f"{len(events)} event airings; kinds: " + ", ".join(f"{k} {n}" for k, n in sorted(kinds.items(), key=lambda x: -x[1])[:25]))

    with gzip.open(args.out, "wt", encoding="utf-8") as f:
        json.dump({"generated": now_ms, "model": MODEL if use_model else "rules", "hours": args.hours, "events": events},
                  f, ensure_ascii=False, separators=(",", ":"))

    cutoff = (datetime.date.today() - datetime.timedelta(days=CACHE_DAYS)).isoformat()
    cache = {k: v for k, v in cache.items() if v.get("seen", today) >= cutoff}
    with gzip.open(args.cache, "wt", encoding="utf-8") as f:
        json.dump(cache, f, separators=(",", ":"))
    log(f"wrote {args.out} and {args.cache} ({len(cache)} verdicts)")


if __name__ == "__main__":
    main()
