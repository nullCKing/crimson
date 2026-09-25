"""
Replays a real account's catalogue from a RetroGuide/Crimson database (--snapshot <db>).

For reproducing bugs that only a real provider's data shows. The database is one the app itself
wrote (a device pull, never committed): its channels, live categories, films and series are served
back through the mock API with the provider's own names, categories and artwork URLs. Only the
channels the app kept are in such a database, so the filter sees a pre-filtered lineup; that is
enough for everything after the import.
"""
import sqlite3

from fixtures import Catalog
from vod import VodCatalog


def _categories(db, table):
    rows = db.execute(
        "SELECT DISTINCT categoryId, categoryName FROM %s WHERE categoryId IS NOT NULL" % table
    ).fetchall()
    return [{"category_id": cid, "category_name": name, "parent_id": 0} for cid, name in rows]


def load_live(path):
    db = sqlite3.connect(path)
    categories = [
        {"category_id": cid, "category_name": name, "parent_id": 0,
         "truth_country": None, "truth_market": None, "truth_locals": False}
        for cid, name in db.execute("SELECT categoryId, name FROM categories")
    ]
    channels = []
    for sid, num, name, cid, epg, icon, archive in db.execute(
        "SELECT streamId, number, originalName, categoryId, epgChannelId, streamIcon, tvArchive FROM channels"
    ):
        channels.append({
            "num": num, "name": name, "stream_type": "live", "stream_id": sid,
            "stream_icon": icon or "", "epg_channel_id": epg or "", "added": "1600000000",
            "category_id": cid, "custom_sid": "", "tv_archive": archive or 0, "direct_source": "",
            "tv_archive_duration": 3, "truth_country": None, "truth_market": None, "truth_kept": True,
        })
    return Catalog(categories, channels)


class SnapshotVodCatalog(VodCatalog):

    def __init__(self, path):
        db = sqlite3.connect(path)
        self.movie_categories = _categories(db, "vod")
        self.series_categories = _categories(db, "series")
        self.movies, self.movie_info = [], {}
        for i, (sid, name, cid, icon, rating, ext, year) in enumerate(db.execute(
            "SELECT streamId, name, categoryId, icon, rating, containerExtension, titleYear FROM vod"
        )):
            self.movies.append({
                "num": i + 1, "name": name, "stream_type": "movie", "stream_id": sid,
                "stream_icon": icon, "rating": rating, "added": None, "category_id": cid,
                "container_extension": ext,
            })
            self.movie_info[sid] = (name, year or 2020, 7.0, [])
        self.series, self.series_meta = [], {}
        for i, (sid, name, cid, cover, plot, rating, release, year) in enumerate(db.execute(
            "SELECT seriesId, name, categoryId, cover, plot, rating, releaseDate, titleYear FROM series"
        )):
            self.series.append({
                "num": i + 1, "name": name, "series_id": sid, "cover": cover, "plot": plot,
                "rating": rating, "releaseDate": release, "category_id": cid,
            })
            self.series_meta[sid] = (name, year or 2020, 7.0, [], 1 + (sid % 4))
