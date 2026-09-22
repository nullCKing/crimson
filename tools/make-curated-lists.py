# -*- coding: utf-8 -*-
"""Build the app's curated lists from IMDb's published datasets.

IMDb publishes title.basics and title.ratings at https://datasets.imdbws.com/ for personal and
non-commercial use. Reading those is the sanctioned way to get this data; scraping imdb.com is
not, and the HTML changes constantly. This runs once on a developer machine and writes a compact
asset the app ships, so the device never downloads a gigabyte of TSV.

Output: app/src/main/assets/curated_lists.txt
    #<list id>\t<title>\t<subtitle>
    <name>\t<year>\t<kind>       (kind: M movie, S series)
"""
import gzip, os, re, sys
from collections import defaultdict

SRC = r'C:\Users\chris\AppData\Local\Temp\imdb'
OUT = 'app/src/main/assets/curated_lists.txt'

MIN_VOTES_MOVIE = 25000
MIN_VOTES_SERIES = 10000
PER_LIST = 60

print('reading ratings...')
ratings = {}
with gzip.open(os.path.join(SRC, 'title.ratings.tsv.gz'), 'rt', encoding='utf-8') as fh:
    next(fh)
    for line in fh:
        tid, rating, votes = line.rstrip('\n').split('\t')
        ratings[tid] = (float(rating), int(votes))
print(f'  {len(ratings):,} rated titles')

print('reading basics...')
movies, series = [], []
with gzip.open(os.path.join(SRC, 'title.basics.tsv.gz'), 'rt', encoding='utf-8') as fh:
    next(fh)
    for line in fh:
        parts = line.rstrip('\n').split('\t')
        if len(parts) < 9:
            continue
        tid, ttype, primary, original, adult, start, end, runtime, genres = parts[:9]
        if adult == '1':
            continue
        r = ratings.get(tid)
        if not r:
            continue
        rating, votes = r
        if start == r'\N' or not start.isdigit():
            continue
        year = int(start)
        g = [] if genres == r'\N' else genres.split(',')
        if ttype == 'movie' and votes >= MIN_VOTES_MOVIE:
            movies.append((primary, year, rating, votes, g))
        elif ttype == 'tvSeries' and votes >= MIN_VOTES_SERIES:
            series.append((primary, year, rating, votes, g))
print(f'  {len(movies):,} movies, {len(series):,} series above the vote floor')


def top(items, key=lambda t: (t[2], t[3]), n=PER_LIST, where=None):
    pool = [t for t in items if where(t)] if where else items
    return sorted(pool, key=key, reverse=True)[:n]


lists = []  # (id, title, subtitle, rows)


def add(list_id, title, subtitle, rows, kind):
    rows = [(r[0], r[1], kind) for r in rows]
    if rows:
        lists.append((list_id, title, subtitle, rows))


# ---------------------------------------------------------------- films
add('top_movies', 'IMDb Top Rated Movies', 'The highest rated films of all time',
    top(movies), 'M')
add('most_watched', 'Most Watched Movies', 'The films the most people have rated',
    top(movies, key=lambda t: t[3]), 'M')

DECADES = [(1970, '1970s'), (1980, '1980s'), (1990, '1990s'), (2000, '2000s'),
           (2010, '2010s'), (2020, '2020s')]
for start, label in DECADES:
    add(f'decade_{start}', f'Best of the {label}', f'Top rated films from {start}\u2013{start + 9}',
        top(movies, where=lambda t, s=start: s <= t[1] <= s + 9), 'M')

GENRES = ['Action', 'Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama',
          'Family', 'Fantasy', 'History', 'Horror', 'Music', 'Mystery', 'Romance', 'Sci-Fi',
          'Sport', 'Thriller', 'War', 'Western']
for genre in GENRES:
    add(f'genre_{genre.lower().replace("-", "")}', f'{genre} Movies', f'Top rated {genre.lower()}',
        top(movies, where=lambda t, g=genre: g in t[4]), 'M')

add('recent_movies', 'New This Decade', 'Well rated films from 2020 onwards',
    top(movies, where=lambda t: t[1] >= 2020), 'M')

# ---------------------------------------------------------------- television
add('top_series', 'IMDb Top Rated Series', 'The highest rated television of all time',
    top(series), 'S')
add('most_watched_series', 'Most Watched Series', 'The shows the most people have rated',
    top(series, key=lambda t: t[3]), 'S')
for start, label in DECADES:
    add(f'series_decade_{start}', f'{label} Television', f'Top rated shows that began in the {label}',
        top(series, where=lambda t, s=start: s <= t[1] <= s + 9), 'S')
for genre in ['Action', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama', 'Fantasy',
              'Mystery', 'Sci-Fi', 'Thriller']:
    add(f'series_genre_{genre.lower().replace("-", "")}', f'{genre} Series', f'Top rated {genre.lower()} television',
        top(series, where=lambda t, g=genre: g in t[4]), 'S')

# ---------------------------------------------------------------- write
os.makedirs(os.path.dirname(OUT), exist_ok=True)
titles = 0
with open(OUT, 'w', encoding='utf-8', newline='\n') as fh:
    fh.write('# Generated from IMDb datasets (https://datasets.imdbws.com/) by\n')
    fh.write('# tools/make-curated-lists.py. Do not edit by hand; re-run the tool.\n')
    for list_id, title, subtitle, rows in lists:
        fh.write(f'#{list_id}\t{title}\t{subtitle}\n')
        for name, year, kind in rows:
            if '\t' in name:
                continue
            fh.write(f'{name}\t{year}\t{kind}\n')
            titles += 1
print(f'wrote {len(lists)} lists, {titles} titles -> {OUT} '
      f'({os.path.getsize(OUT) / 1024:.0f} KB)')
