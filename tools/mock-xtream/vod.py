"""
A believable on-demand catalogue for the mock server.

Built from the app's own IMDb title index, so the titles are real and the app's recommendation
rows (which join the catalogue to that same index) have something to find: a few hundred films
and a hundred-odd series, named the way providers name them ("EN - Heat (1995)"), in genre
categories, with a handful of foreign-language copies and an adult category to prove the feeds
filter those out. Artwork comes from picsum.photos, seeded per title so it is stable.

Nothing here resembles any real provider's catalogue beyond the titles themselves.
"""
import os
import random
import time

HERE = os.path.dirname(os.path.abspath(__file__))
INDEX = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "title_index.tsv")

MOVIE_CATEGORIES = [
    ("101", "EN - ACTION", "Action"),
    ("102", "EN - COMEDY", "Comedy"),
    ("103", "EN - DRAMA", "Drama"),
    ("104", "EN - HORROR", "Horror"),
    ("105", "EN - SCI-FI & FANTASY", "Sci-Fi"),
    ("106", "EN - FAMILY & KIDS", "Family"),
    ("107", "EN - DOCUMENTARY", "Documentary"),
    ("108", "EN - CRIME & THRILLER", "Crime"),
    ("109", "NEW RELEASES 2025/2026", None),
    ("110", "FR - FILMS", None),
    ("111", "XXX | ADULTS ONLY", None),
]
SERIES_CATEGORIES = [
    ("201", "EN - DRAMA SERIES", "Drama"),
    ("202", "EN - COMEDY SERIES", "Comedy"),
    ("203", "EN - CRIME SERIES", "Crime"),
    ("204", "EN - SCI-FI SERIES", "Sci-Fi"),
    ("205", "EN - DOCUSERIES", "Documentary"),
    ("206", "EN - ANIMATION", "Animation"),
]

CAST = ["Alex Morgan", "Jamie Reyes", "Sam Whitaker", "Priya Nair", "Tom Hale", "Lena Ortiz",
        "Chris Doyle", "Maya Chen", "Owen Price", "Nadia Farouk", "Ben Carter", "Ivy Lindqvist"]
DIRECTORS = ["R. Kowalski", "Ana Beltran", "D. Okafor", "Hannah Weiss", "Marcus Lee"]
PLOTS = {
    "Action": "When a routine job goes wrong, a former operative has one night to settle an old score before the whole city pays for it.",
    "Comedy": "Two mismatched friends take one small lie much too far, and a quiet weekend turns into the most chaotic week of their lives.",
    "Drama": "Over one unforgettable summer, a family is forced to face the secret that has shaped every one of their lives.",
    "Horror": "Something in the old house has been waiting a very long time, and the new owners are exactly what it wanted.",
    "Sci-Fi": "At the edge of known space, a small crew discovers a signal that should not exist — and a choice that could end humanity.",
    "Family": "A curious kid and an unlikely companion set out on an adventure that will change their little town forever.",
    "Documentary": "Filmed over several years, this intimate portrait follows the people behind one of the defining stories of our time.",
    "Crime": "A detective with nothing left to lose follows a trail of small crimes to a conspiracy much bigger than anyone imagined.",
    "Animation": "In a world where anything can happen, one small hero discovers that the biggest adventures start close to home.",
}


def _load_index():
    movies, series = [], []
    try:
        with open(INDEX, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("#"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 6:
                    continue
                name, year, kind, rating, votes, genres = parts[:6]
                row = (name, int(year), float(rating), int(votes), genres.split(",") if genres else [])
                (movies if kind == "M" else series).append(row)
    except OSError:
        pass
    return movies, series


def _art(kind, ident, w, h):
    return "https://picsum.photos/seed/crimson-%s-%d/%d/%d" % (kind, ident, w, h)


class VodCatalog:
    def __init__(self, seed=20260922, movie_count=520, series_count=140):
        rnd = random.Random(seed)
        movies, series = _load_index()
        # The index is sorted by votes ascending: the tail is the most popular.
        picked_movies = movies[-movie_count:] + rnd.sample(movies[:-movie_count], min(60, max(0, len(movies) - movie_count)))
        picked_series = series[-series_count:]
        now = int(time.time())

        self.movie_categories = [{"category_id": c[0], "category_name": c[1], "parent_id": 0} for c in MOVIE_CATEGORIES]
        self.series_categories = [{"category_id": c[0], "category_name": c[1], "parent_id": 0} for c in SERIES_CATEGORIES]
        self.movies = []
        self.movie_info = {}
        next_id = 50001
        for name, year, rating, votes, genres in picked_movies:
            category = self._category_for(genres, MOVIE_CATEGORIES, year)
            style = rnd.random()
            label = ("EN - %s (%d)" % (name, year)) if style < 0.7 else ("%s (%d)" % (name, year)) if style < 0.9 else ("%s (%d) 4K" % (name, year))
            item = {
                "num": len(self.movies) + 1,
                "name": label,
                "stream_type": "movie",
                "stream_id": next_id,
                "stream_icon": _art("m", next_id, 300, 450),
                "rating": "%.1f" % rating,
                "rating_5based": round(rating / 2, 1),
                "added": str(now - rnd.randint(0, 120) * 86400),
                "category_id": category,
                "container_extension": "mp4",
            }
            self.movies.append(item)
            self.movie_info[next_id] = (name, year, rating, genres)
            next_id += 1
        # Foreign copies of popular films, and an adult category, both of which the app's feeds
        # must keep out of the browsing rows.
        for name, year, rating, votes, genres in picked_movies[-25:]:
            self.movies.append({
                "num": len(self.movies) + 1, "name": "FR - %s (%d)" % (name, year), "stream_type": "movie",
                "stream_id": next_id, "stream_icon": _art("m", next_id, 300, 450), "rating": "%.1f" % rating,
                "added": str(now), "category_id": "110", "container_extension": "mp4",
            })
            self.movie_info[next_id] = (name, year, rating, genres)
            next_id += 1
        for i in range(3):
            self.movies.append({
                "num": len(self.movies) + 1, "name": "XXX Adult Title %d" % (i + 1), "stream_type": "movie",
                "stream_id": next_id, "stream_icon": None, "rating": "0", "added": str(now),
                "category_id": "111", "container_extension": "mp4",
            })
            self.movie_info[next_id] = ("Adult Title %d" % (i + 1), 2020, 0.0, [])
            next_id += 1

        self.series = []
        self.series_meta = {}
        sid = 70001
        for name, year, rating, votes, genres in picked_series:
            category = self._category_for(genres, SERIES_CATEGORIES, year)
            genre = next((g for g in genres if g in PLOTS), "Drama")
            self.series.append({
                "num": len(self.series) + 1,
                "name": "EN - %s" % name,
                "series_id": sid,
                "cover": _art("s", sid, 300, 450),
                "plot": PLOTS[genre],
                "cast": ", ".join(rnd.sample(CAST, 3)),
                "director": rnd.choice(DIRECTORS),
                "genre": ", ".join(genres),
                "releaseDate": "%d-09-01" % year,
                "last_modified": str(now - rnd.randint(0, 60) * 86400),
                "rating": "%.1f" % rating,
                "backdrop_path": [_art("sb", sid, 1280, 720)],
                "category_id": category,
            })
            seasons = 1 + (sid % 4)
            self.series_meta[sid] = (name, year, rating, genres, seasons)
            sid += 1

    @staticmethod
    def _category_for(genres, categories, year):
        if year >= 2025 and categories is MOVIE_CATEGORIES:
            return "109"
        for cid, _, genre in categories:
            if genre and genre in genres:
                return cid
        return categories[0][0]

    # ------------------------------------------------------------------ payloads

    def vod_streams(self, category_id=None):
        if category_id:
            return [m for m in self.movies if m["category_id"] == category_id]
        return self.movies

    def series_list(self, category_id=None):
        if category_id:
            return [s for s in self.series if s["category_id"] == category_id]
        return self.series

    def vod_info(self, vod_id):
        name, year, rating, genres = self.movie_info.get(vod_id, ("Unknown", 2020, 7.0, []))
        rnd = random.Random(vod_id)
        genre = next((g for g in genres if g in PLOTS), "Drama")
        runtime = rnd.randint(88, 164) * 60
        return {
            "info": {
                "name": name,
                "description": PLOTS[genre],
                "plot": PLOTS[genre],
                "duration_secs": str(runtime),
                "duration": "%02d:%02d:00" % (runtime // 3600, (runtime % 3600) // 60),
                "releasedate": "%d-06-15" % year,
                "rating": "%.1f" % rating,
                "genre": ", ".join(genres),
                "cast": ", ".join(rnd.sample(CAST, 4)),
                "director": rnd.choice(DIRECTORS),
                "mpaa_rating": rnd.choice(["PG", "PG-13", "R", "TV-MA"]),
                "cover_big": _art("m", vod_id, 300, 450),
                "movie_image": _art("m", vod_id, 300, 450),
                "backdrop_path": [_art("mb", vod_id, 1280, 720)],
                "youtube_trailer": "",
            },
            "movie_data": {"stream_id": vod_id, "name": name, "container_extension": "mp4"},
        }

    def series_info(self, series_id):
        meta = self.series_meta.get(series_id)
        if meta is None:
            return {"info": {}, "seasons": [], "episodes": {}}
        name, year, rating, genres, seasons = meta
        genre = next((g for g in genres if g in PLOTS), "Drama")
        rnd = random.Random(series_id)
        episodes = {}
        for season in range(1, seasons + 1):
            eps = []
            for ep in range(1, rnd.randint(6, 10) + 1):
                eid = series_id * 1000 + season * 100 + ep
                eps.append({
                    "id": str(eid),
                    "episode_num": ep,
                    "title": "%s - S%02dE%02d - %s" % (name, season, ep, rnd.choice(
                        ["Pilot", "The Long Night", "Crossroads", "Ghosts", "Homecoming", "The Offer",
                         "Blackout", "Fault Lines", "Endgame", "Second Chances", "Undertow", "The Reckoning"])),
                    "container_extension": "mp4",
                    "season": season,
                    "info": {
                        "plot": "Tensions rise as a discovery forces everyone to choose a side.",
                        "movie_image": _art("e", eid, 640, 360),
                        "duration_secs": str(rnd.randint(40, 58) * 60),
                        "releasedate": "%d-10-%02d" % (year + season - 1, ep),
                    },
                })
            episodes[str(season)] = eps
        return {
            "info": {
                "name": name,
                "cover": _art("s", series_id, 300, 450),
                "plot": PLOTS[genre],
                "cast": ", ".join(rnd.sample(CAST, 4)),
                "director": rnd.choice(DIRECTORS),
                "genre": ", ".join(genres),
                "releaseDate": "%d-09-01" % year,
                "rating": "%.1f" % rating,
                "backdrop_path": [_art("sb", series_id, 1280, 720)],
            },
            "seasons": [{"season_number": s, "name": "Season %d" % s} for s in range(1, seasons + 1)],
            "episodes": episodes,
        }
