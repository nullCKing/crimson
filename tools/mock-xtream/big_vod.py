"""
A provider-sized, provider-messy on-demand catalogue for stress testing (--big).

The default catalogue is small and tidy on purpose, which is exactly why it cannot find the bugs
that only a real panel's catalogue exposes. This one is built from every title in the app's IMDb
index, several copies each, and carries the mess real panels send: names that are empty or null,
ratings of "", "N/A" or numbers, "added" as null or a non-number, missing categories, stream ids
repeated across categories, emoji and 300-character names, vod and series categories that share
ids, and a few hundred categories per kind.
"""
import random
import time

from vod import VodCatalog, _art, _load_index, PLOTS

LANGS = ["EN", "EN", "EN", "US", "UK", "FR", "DE", "ES", "IT", "NL", "PT", "TR", "AR", "PL", "HI"]
GENRES = ["ACTION", "COMEDY", "DRAMA", "HORROR", "SCI-FI", "FAMILY", "DOCUMENTARY", "CRIME", "THRILLER",
          "ROMANCE", "ANIMATION", "WESTERN", "WAR", "MUSIC", "SPORT", "KIDS", "4K UHD", "NETFLIX", "HBO",
          "DISNEY+", "AMAZON PRIME", "APPLE TV+", "HULU", "PARAMOUNT+", "MARVEL", "DC", "CHRISTMAS"]


class BigVodCatalog(VodCatalog):

    def __init__(self, seed=20260923, copies=3):
        rnd = random.Random(seed)
        movies, series = _load_index()
        now = int(time.time())

        # A few hundred categories per kind. Series categories reuse the film ids, as many panels do.
        self.movie_categories = []
        self.series_categories = []
        cid = 1
        for lang in sorted(set(LANGS)):
            for genre in GENRES:
                name = "%s - %s" % (lang, genre) if rnd.random() < 0.7 else "%s| %s" % (lang, genre)
                self.movie_categories.append({"category_id": str(cid), "category_name": name, "parent_id": 0})
                self.series_categories.append({"category_id": str(cid), "category_name": name + " SERIES", "parent_id": 0})
                cid += 1
        self.movie_categories.append({"category_id": str(cid), "category_name": "XXX | ADULTS 18+", "parent_id": 0})
        self.movie_categories.append({"category_id": str(cid + 1), "category_name": "", "parent_id": 0})
        self.movie_categories.append({"category_id": str(cid + 2), "category_name": None, "parent_id": 0})
        self.series_categories.append({"category_id": str(cid + 3), "category_name": "🔥 TRENDING 🔥", "parent_id": 0})
        cat_ids = [c["category_id"] for c in self.movie_categories]
        series_cat_ids = [c["category_id"] for c in self.series_categories]

        self.movies = []
        self.movie_info = {}
        next_id = 100001
        for name, year, rating, votes, genres in movies:
            for copy in range(copies):
                lang = rnd.choice(LANGS) if copy else "EN"
                style = rnd.random()
                if style < 0.55:
                    label = "%s - %s (%d)" % (lang, name, year)
                elif style < 0.75:
                    label = "%s (%d)" % (name, year)
                elif style < 0.85:
                    label = "%s: %s [%d] 4K HDR" % (lang, name, year)
                elif style < 0.9:
                    label = "|%s| %s" % (lang, name)
                elif style < 0.93:
                    label = "🎬 %s (%d) 🎬" % (name, year)
                elif style < 0.95:
                    label = "%s - %s (%d) %s" % (lang, name, year, "extended cut " * 20)
                elif style < 0.97:
                    label = ""
                else:
                    label = None
                item = {
                    "num": len(self.movies) + 1,
                    "name": label,
                    "stream_type": "movie",
                    "stream_id": next_id,
                    "stream_icon": rnd.choice([_art("m", next_id, 300, 450), "", None, "http://", "not a url"]),
                    "rating": rnd.choice(["%.1f" % rating, "%.1f" % rating, "", "N/A", None, rating, "0"]),
                    "rating_5based": rnd.choice([round(rating / 2, 1), 0, None, "", "2.5"]),
                    "added": rnd.choice([str(now - rnd.randint(0, 3000) * 86400), None, "", "0", "yesterday", now]),
                    "category_id": rnd.choice(cat_ids + [None, "", "99999"]),
                    "container_extension": rnd.choice(["mp4", "mkv", "avi", None]),
                    "custom_sid": None,
                    "direct_source": "",
                }
                self.movies.append(item)
                self.movie_info[next_id] = (name, year, rating, genres)
                # Some panels list the same stream in two categories.
                if rnd.random() < 0.02:
                    dup = dict(item)
                    dup["category_id"] = rnd.choice(cat_ids)
                    self.movies.append(dup)
                next_id += 1

        self.series = []
        self.series_meta = {}
        sid = 500001
        for name, year, rating, votes, genres in series:
            for copy in range(max(1, copies - 1)):
                lang = rnd.choice(LANGS) if copy else "EN"
                genre = next((g for g in genres if g in PLOTS), "Drama")
                label = rnd.choice(["%s - %s" % (lang, name), "%s (%d)" % (name, year), "%s| %s" % (lang, name), "", None])
                self.series.append({
                    "num": len(self.series) + 1,
                    "name": label,
                    "series_id": sid,
                    "cover": rnd.choice([_art("s", sid, 300, 450), "", None]),
                    "plot": rnd.choice([PLOTS[genre], "", None]),
                    "cast": rnd.choice(["A, B, C", "", None]),
                    "director": None,
                    "genre": rnd.choice([", ".join(genres), "", None, "Drama / Crime"]),
                    "releaseDate": rnd.choice(["%d-09-01" % year, "", None, "0000-00-00", str(year)]),
                    "last_modified": rnd.choice([str(now - rnd.randint(0, 900) * 86400), None, "", "0"]),
                    "rating": rnd.choice(["%.1f" % rating, "", None, "N/A", 0]),
                    "rating_5based": rnd.choice([round(rating / 2, 1), None]),
                    "backdrop_path": rnd.choice([[_art("sb", sid, 1280, 720)], [], None, ""]),
                    "youtube_trailer": None,
                    "episode_run_time": rnd.choice(["45", "", None, 0]),
                    "category_id": rnd.choice(series_cat_ids + [None, ""]),
                })
                self.series_meta[sid] = (name, year, rating, genres, 1 + (sid % 4))
                sid += 1
