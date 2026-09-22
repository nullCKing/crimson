# -*- coding: utf-8 -*-
"""Build the app's title index from IMDb's published datasets.

The recommendation rows ("Crime Dramas", "Top 10 Movies", "Because you watched ...") are SQL
queries over the viewer's own catalogue. A provider's catalogue only knows file names, so to ask
"which of these are well-rated crime dramas" each title first has to be joined to what IMDb knows
about it: genres, rating and how many people rated it. This tool writes that knowledge, for every
title popular enough to matter, as a compact asset the app joins against once, after it caches
the catalogue.

IMDb publishes title.basics and title.ratings at https://datasets.imdbws.com/ for personal and
non-commercial use. Reading those is the sanctioned way to get this data; scraping imdb.com is not.

Output: app/src/main/assets/title_index.tsv, one title per line, sorted by votes ascending
    <name>\t<year>\t<kind>\t<rating>\t<votes>\t<genres>     (kind: M movie, S series)

Ascending order matters: the app applies the file top to bottom, so when two IMDb titles share a
name and the provider's copy carries no year, the more popular one is applied last and wins.
"""
import gzip, os, sys

SRC = sys.argv[1] if len(sys.argv) > 1 else r'C:\Users\chris\AppData\Local\Temp\imdb'
OUT = 'app/src/main/assets/title_index.tsv'

MIN_VOTES_MOVIE = 4000
MIN_VOTES_SERIES = 2000

print('reading ratings...')
ratings = {}
with gzip.open(os.path.join(SRC, 'title.ratings.tsv.gz'), 'rt', encoding='utf-8') as fh:
    next(fh)
    for line in fh:
        tid, rating, votes = line.rstrip('\n').split('\t')
        ratings[tid] = (float(rating), int(votes))

print('reading basics...')
rows = []
with gzip.open(os.path.join(SRC, 'title.basics.tsv.gz'), 'rt', encoding='utf-8') as fh:
    next(fh)
    for line in fh:
        parts = line.rstrip('\n').split('\t')
        if len(parts) < 9:
            continue
        tid, ttype, primary, original, adult, start, end, runtime, genres = parts[:9]
        if adult == '1' or start == r'\N' or not start.isdigit():
            continue
        r = ratings.get(tid)
        if not r:
            continue
        rating, votes = r
        if ttype in ('movie', 'tvMovie') and votes >= MIN_VOTES_MOVIE:
            kind = 'M'
        elif ttype in ('tvSeries', 'tvMiniSeries') and votes >= MIN_VOTES_SERIES:
            kind = 'S'
        else:
            continue
        if '\t' in primary:
            continue
        g = '' if genres == r'\N' else genres
        rows.append((primary, int(start), kind, rating, votes, g))

rows.sort(key=lambda t: t[4])
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, 'w', encoding='utf-8', newline='\n') as fh:
    fh.write('# Generated from IMDb datasets (https://datasets.imdbws.com/) by\n')
    fh.write('# tools/make-title-index.py. Do not edit by hand; re-run the tool.\n')
    for name, year, kind, rating, votes, genres in rows:
        fh.write(f'{name}\t{year}\t{kind}\t{rating:.1f}\t{votes}\t{genres}\n')
movies = sum(1 for r in rows if r[2] == 'M')
print(f'wrote {movies:,} movies and {len(rows) - movies:,} series -> {OUT} '
      f'({os.path.getsize(OUT) / 1024:.0f} KB)')
