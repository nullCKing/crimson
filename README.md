# Crimson

A streaming-app front end for Xtream Codes accounts, for Android TV and Fire TV. It looks and
behaves like a premium streaming service — profiles, an endless home page of recommendation rows,
title pages with seasons and episodes, resume and My List — with live TV and sports built in:
a lineup to browse with previews, a TV guide, every channel in the account by country, and live
scores that take you to the channel showing the game.

Crimson is a fork of **RetroGuide** (github.com/nullCKing/retroguide), which was a 2000s-cable-box
guide for the same accounts. Everything underneath — the streaming import, the channel filter,
the guide data pipeline with its eleven public XMLTV feeds, the single-ExoPlayer player, the
canvas-drawn guide grid — is RetroGuide's, kept and still tested. The interface on top is new.

---

## What's in it

| | |
| --- | --- |
| **Profiles** | "Who's watching?" on launch. Each profile is one Xtream login with a name and an avatar, and has its own database, settings, My List and viewing history. Adding a profile signs in before saving, so a saved profile is a working one. |
| **Home, TV Shows, Movies** | A billboard for a featured title, then rows that never run out: Continue Watching, Live Sports, Top 10 Movies and TV, what's on American Cable now, My List, Because You Watched…, around sixty mood and genre rows ("Crime Thrillers", "Hidden Gems", "'90s Action", "Binge-Worthy TV Shows"), then every one of the provider's own categories. Moving onto a card turns the top of the page into a spotlight with that title's artwork, rating and synopsis. |
| **Recommendations** | The provider's catalogue is joined to an IMDb title index (genres, rating, popularity for ~31,000 films and series) once after import. Every row is then a SQL query over the viewer's own catalogue, foreign-language dubs and adult categories excluded, one copy per title in the best language. |
| **Title pages** | Artwork, rating, year, age rating, runtime, genres, synopsis, cast. Play / Resume Sn:En / Start Over / My List. Seasons and episodes with stills and progress. More Like This. |
| **Player** | Films and episodes get a streaming player's controls: scrubber, ±10 s on Left/Right, ±30 s on Rewind/Fast-forward, pause, Next Episode and an autoplay countdown. Progress is saved every ten seconds. Live gets a channel banner with what's on, a progress bar and what's next; Up/Down zap through whatever lineup you tuned from. |
| **Live TV** | Opens on a recommended lineup — American Cable by default, Japanese and Korean TV a click away — in rows by section with what each channel is showing now. The focused channel previews in the hero on the same single player, so Select continues the stream without a re-tune. TV Guide opens the grid on that lineup. |
| **Browse by Country** | Every live category the provider has, grouped by country with flags, searchable. Countries the import never kept are read from the provider when opened and play directly. |
| **Sports** | Scores and schedules for NFL, college football, NBA, MLB, NHL, WNBA, college basketball, MLS and the big European soccer leagues, from ESPN's public scoreboard, refreshed every minute. Selecting a game searches Live TV for its network ("NBC" → your NBC), falling back to its other networks and then the teams. |
| **Search** | An on-screen keyboard beside results that narrow as you type, across channels, films and series. Ranking puts the exact name first ("nbc" → NBC before CNBC and MSNBC). |

---

## Build

You need a JDK 17 or newer and an Android SDK with platform 35 and build-tools 35.0.0, found
through `ANDROID_HOME` or `local.properties`. Gradle downloads everything else, including the
JDK 17 toolchain that `core` pins.

```
# Once: create the release signing key (secrets/, gitignored).
powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1

# Build the signed release APK into dist\.
.\gradlew.bat release
```

On macOS or Linux, `./gradlew release` and `tools/make-keystore.sh` do the same. Without
`secrets/keystore.properties` the release is signed with the debug key and a warning says so.

**The signing key must never change**, or updates stop installing over existing versions. Crimson
has its own key and its own application id (`com.crimson`), so it installs alongside RetroGuide.

Install with `adb install -r dist\app-release.apk`, or sideload to a Fire Stick the usual way.

---

## Run the mock Xtream server

```
cd tools\mock-xtream
python server.py --port 8080
```

5,181 live channels across 44 countries in 213 categories, four days of XMLTV, and an on-demand
catalogue of 608 films and 140 series built from the app's own IMDb title index — real titles,
provider-style names (`EN - Heat (1995)`), genre categories, a few French dubs and an adult
category that the feeds must keep out. Artwork comes from picsum.photos, so the emulator needs
internet for pictures. Test media is generated with FFmpeg on first run: an endless live stream
and a three-minute seekable film for every title and episode.

Create a profile with:

| Field | Value |
| --- | --- |
| Server | `http://10.0.2.2:8080` on an emulator, `http://<your LAN IP>:8080` on a real stick |
| Username | `testuser` |
| Password | `testpass` |

`--public-streams` redirects live playback to public test streams; `--no-media` skips FFmpeg.

---

## Tests

```
.\gradlew.bat :core:test              # filter, feeds, sports parser, search ranking, regions, …
.\gradlew.bat :app:testDebugUnitTest  # screenshot tests of every main screen
.\gradlew.bat recordRoborazziDebug    # re-record screenshot baselines after a UI change
.\gradlew.bat build                   # everything, including lint and the minified release
```

### On a device

```
powershell -ExecutionPolicy Bypass -File tools\verify-on-device.ps1 -Device emulator-5554
```

Starts the mock server, runs the tests, installs, creates a profile, waits for the import, and
drives Home, a film (play, seek, pause, resume), Live TV (preview, full screen, zapping), the
guide and Settings with `input keyevent`, capturing a screenshot at each step and recording
memory, frame timing and time to first frame into `reports/device-verification.md`. On an
emulator it clears the app's data first; on a real device pass `-Reset` to do the same (it
deletes the profiles on that device).

---

## Where things live

```
core/                 Plain Kotlin, no Android, unit-tested.
  catalog/Feed.kt       The recommendation rows as data (FeedPlanner) and their SQL (CatalogSql)
  catalog/TitleIndex.kt Reads the IMDb title index asset
  catalog/SearchRank.kt Search ordering
  sports/Sports.kt      ESPN scoreboard parser, leagues, game → channel search terms
  live/WorldRegions.kt  Countries and regions for the directory, from category markers
  filter/, epg/, guide/, json/, text/, numbering/   RetroGuide's engine
app/data/profile/     Profiles (encrypted)
app/data/db/          Room: channels, programmes, catalogue, My List, watch progress
app/data/sports/      ESPN client with a one-minute cache
app/data/xtream/      Xtream client; channel and library importers (enrichment in LibraryImporter)
app/ui/               CrimsonViewModel (session, back stack, player, guide) and one controller per
                      page: feed/, details/, search/, live/, sports/
app/ui/components/    The design system: cards, rows, buttons, chips, nav, keyboard, avatars
app/ui/theme/         Crimson.kt (palette, type), CrimsonIcons.kt, GuideTheme.kt (the grid)
tools/                Mock server, title index and art generators, device verification, keystore
```

`app/src/main/assets/title_index.tsv` is generated by `tools/make-title-index.py` from IMDb's
published datasets (https://datasets.imdbws.com/, personal and non-commercial use). Re-run it to
refresh; never edit the asset by hand.

---

## Third-party data

- **IMDb datasets** — title.basics and title.ratings, for genres, ratings and popularity. Used as
  IMDb publishes them for personal, non-commercial use.
- **ESPN scoreboard** — `site.api.espn.com/apis/site/v2/sports/…/scoreboard`, public and
  unauthenticated but undocumented. If it changes shape the Sports page shows no games rather
  than failing.
- **Public XMLTV guides** — the eleven feeds RetroGuide measured and kept; see `DECISIONS.md`.
