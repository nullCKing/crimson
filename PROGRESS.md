# Progress

Written so a session with no memory of this one can pick it up. Read `DECISIONS.md` for why
things are the way they are.

**Status (2026-09-25): version 1.3 (`dist/crimson-1.3.apk`, versionCode 4): streaming-app
player controls, captions no longer doubled, English audio first with a track picker; see
"Version 1.3" below.**

**Status (2026-09-24, later): version 1.2 (`dist/crimson-1.2.apk`, versionCode 3) adds
automatic theme-song skipping, per show; see "Version 1.2" below.**

**Status (2026-09-24): version 1.1 (`dist/crimson-1.1.apk`, versionCode 2) adds captions,
dialogue boost, video brightness, every college game, a fixed search, and smooth edges
everywhere; see the section below. Verified on an API 25 TV emulator (the closest renderer to
Fire OS 5 available) against the mock server; not yet on the user's Fire TV, which was not
reachable over ADB that day.**

**Earlier status (2026-09-22): Crimson builds (`gradlew build` green: core tests, 17 screenshot tests,
lint, minified release; `gradlew release` verifies the APK installs on Fire OS 5+), every page has
been driven on an Android TV emulator against the mock server, and it now runs on the user's
real Fire TV (2nd-gen box, Fire OS 5.2.9.5 / Android 5.1) with their real account.**

### Version 1.3, 2026-09-25

After using 1.2 (installed on the Fire TV by the user): captions were doubled on *Widow's Bay*,
Down opening a menu felt wrong, and dubbed anime was hard to get. Checked on `crimson_tv_25`:

- Controls: Down shows them (time bar focused), Down again reaches the buttons, Right x3 reaches
  Audio & Subtitles, OK opens it, Back returns focus to that button; six quick Rights moved the
  preview 0:30 -> 1:30 and playback jumped there on its own.
- Captions: a film with a subtitle track in the file (`server.py --captions`, odd ids) shows one
  box.
- Audio: `server.py --dual-audio` serves a file with "Japanese" marked default and English
  second; English played, the menu listed both, choosing Japanese switched to it.

### Version 1.2, 2026-09-24

Theme-song skipping, asked for by show (The Office, South Park, Hunter x Hunter opening and
ending), automatic. Checked on `crimson_tv_25` with `server.py --themes`:

- Episode 1: skipped 25 -> 55 s by the (mock) database's times.
- Episodes 2 and 3: themes played; after episode 3 the 30 s opening was learned.
- Episode 4: opening heard at 1:42.96 (theme starts 1:40), skipped to 2:10.02 (ends 2:10.00),
  "Skipped the opening theme" shown. After a rewind into episode 3's cold open: turned down
  1.4 s in, skipped 2.96 s in to 75.02 s (ends 75.00).
- Ending switched on in the menu, learned from two episodes, then recognised after a seek into
  the middle of it and skipped to 954.96 s (ends 955.00).
- Against the real databases on API 25 (no ISRG X2 there): IntroDB and TheIntroDB both answered
  through the bundled roots.
- Offline, on the episodes' AAC-decoded audio (`ThemesOnRealAudioTest`): both themes learned from
  two episodes and skipped in two others, within 25 ms of their ends.

Not done: real episodes of the three shows (the provider's files) and the user's Fire TV, which
was not reachable over ADB. Hunter x Hunter's ending changes with each arc; each new one is
learned after two episodes with it.

### Version 1.1, 2026-09-24

Asked for after a few days' use. Built, and checked on the `crimson_tv_25` emulator:

- **Captions** — file track, broadcast 608, or OpenSubtitles with automatic sync; menu in the
  player (Down/Menu) and a Settings section. Checked: a film with an embedded track shows it; a
  film without one found its IMDb id in the index (tt6263850), fetched the mock service's file
  2.7 s early and synced it to +2.8 s after 90 s (captions matched the burned-in line); the
  release build fetched 9 real English files from the live Stremio service. Live: menu and
  "shown when broadcast" status; real 608 captions not seen (the mock streams have none).
- **Dialogue boost** — on the test film, peaks held at −1 dBFS and the loudest 5 s stretch
  3.6 dB quieter; unit tests cover quiet speech lifted >5 dB and a 15 dB narrower quiet/loud gap.
  Not listened to. The Dolby-decode path is unexercised (the emulator has no AC-3 decoder).
- **Video brightness** — 5–100%, video only; checked in the player and on live TV.
- **Sports** — FBS/FCS/Division I, poll ranks, split rows; checked against the live ESPN feed.
- **Search** — query kept across a result and back, cleared on leaving, fresh on a new game;
  symmetric keyboard; caret before the hint. Checked on the emulator.
- **Edges** — rounded clips removed, `tvInteractive` doubling fixed, smooth logo downscale, no
  RGB_565, sharper launcher art. Checked by enlarging emulator screenshots.

Not done: nothing on the user's box (ADB offline); no OpenSubtitles key is configured, so the
REST path is untested against the live API (its JSON parser is unit-tested).

### Fire TV crash, 2026-09-22 (fixed)

On the user's Fire TV (AFTS, Android 5.1) the app died as Home opened after the channel import:
`SIGSEGV` on `RenderThread` in `libRScpp ScriptIntrinsicBlur::setInput`. Android 5's renderer
blurs text shadows with RenderScript, which crashes on that device; the wordmark's red glow was
the first blurred text drawn. Blurred text shadows are now drawn only on Android 9+
(`BlurredTextShadows`), and the channel-number overlay lost its redundant one. Verified on the
device: profile → channels → guide → Home with the real account, no crash. Ruled out on the way,
with the real account's data replayed on the emulator (`--snapshot`) and a 65k-film synthetic
catalogue (`--big`): API levels used by app and `core` (all ≤ 21), SQLite features and variable
limits, memory (live Java heap 11–27 MB, peak 66 MB PSS), timezone, screen density.

## Crimson, 2026-09-22: the fork and the redesign

Forked from RetroGuide at `1f935f7` plus its uncommitted work (On Demand, Browse, curated lists,
packages, extra EPG feeds), renamed to `com.crimson`, and given a new interface.

### Built

1. **Profiles.** `data/profile/ProfileStore` (encrypted, JSON in EncryptedSharedPreferences). A
   `Session` per profile in `AppContainer` holds its Xtream client, its own Room database
   (`crimson_<id>.db`) and its own DataStore. "Who's watching?" on launch; add/edit/delete, with
   sign-in validated before saving.
2. **Navigation** is a back stack of `Route`s in the view model; a controller per page.
3. **Home / TV Shows / Movies**: billboard + spotlight + endless rows from `core/catalog/Feed.kt`,
   resolved five rows at a time as the viewer nears the end. Top 10 rows, Continue Watching, My
   List, Because You Watched, ~60 genre/mood/decade rows, then the provider's categories.
4. **Recommendations**: `tools/make-title-index.py` → `assets/title_index.tsv` (22,430 films and
   8,690 series with IMDb genres, rating and votes, 1.5 MB, ~0.5 MB in the APK). Applied to the
   cached catalogue after import as indexed UPDATEs; on the mock catalogue 593 of 608 films and
   138 of 140 series matched, in ~8–10 s on the emulator.
5. **Title pages**, **VOD player controls**, **resume and progress** (`watch_progress` table),
   **My List** (`my_list` table), next-episode autoplay.
6. **Live TV** page with lineups, per-section rows with now-playing, a hero preview on the shared
   player, the TV guide re-skinned (RetroGuide's canvas grid with Crimson's colours), and
   **Browse by Country** (`core/live/WorldRegions`, 130+ countries/regions with flags; categories
   the import skipped are fetched live).
7. **Sports** from ESPN's scoreboard (14 leagues, one-minute cache), on its own page and as a Home
   row; selecting a game searches Live TV for its network, then its other networks, then the teams.
8. **Search** with an on-screen keyboard; hardware keyboards type straight in; `SearchRank` orders
   results; single-kind results show as a grid.
9. **Design system** in `ui/components` and `ui/theme/Crimson.kt`; launcher icon and TV banner
   regenerated (`tools/make-art.py`).
10. **Mock server** gained a real-title VOD catalogue with artwork, seasons/episodes and a
    seekable film served with byte ranges (`tools/mock-xtream/vod.py`).
11. **`tools/verify-on-device.ps1`** rewritten for the new flow.

### Verified on the Android TV emulator (API 30, host GPU), mock server

| Check | Result |
| --- | --- |
| First profile, bad password, second profile, switching | works; provider's "Invalid credentials" shown |
| Import → Home | channels, guide, library, title-index join all complete; Home fills in when they land |
| Home billboard, spotlight on focus, row pinning, Top 10, endless load-more | works (screenshots) |
| Title page → play episode → seek/pause → Back → Resume S1:E1 → Continue Watching on Home | works |
| Live TV preview on the shared player, Select to full screen, Up/Down zap, last channel, guide | works; 8 tunes, first frame avg 144 ms (87–371) |
| Browse by Country, live-fetched category | works |
| Sports with real ESPN data, game → search → fallback to teams | works |
| Filter change from Settings | 52 ms in the app |
| Memory after import (release build) | ~104 MB PSS (includes the player) |
| Frame time scrolling Home rows vertically (release, warm) | p50 19 ms, p90 46 ms — emulator only |
| Minified release build | runs end to end |

### Not verified / known gaps

- **No real Fire TV Stick run and no real provider account yet.** The numbers that matter —
  import time for a 200k-title catalogue on a Stick's wifi, memory during it, frame times — need
  `.\tools\verify-on-device.ps1 -Device <ip>:5555 -Reset` on hardware.
- Frame times on the emulator are above 16 ms when scrolling rows vertically. The two biggest
  costs found (blurred focus shadow, per-image scrims) are gone; the rest needs a real device's
  profile to be worth chasing.
- Sports → channel search depends on the provider carrying a channel named like the network. The
  fallbacks (other networks, team names) help with event channels; regional networks
  ("NBC Sports Phil") may still find nothing.
- Artwork quality depends entirely on the provider: films get a backdrop only from
  `get_vod_info`, fetched when a card keeps focus for ~320 ms, and cached.
- `strings.xml` still carries RetroGuide-era strings the new screens do not use (lint does not
  flag them); the UI text is inline.
- The `LiveCategoriesScreen`, `HomeScreen`, `OnDemandScreen` and `BrowseScreen` of RetroGuide were
  removed; the curated-lists asset was replaced by the title index (the `CuratedLists` code in
  `core/catalog` remains, tested, but unused by the app).

---

# RetroGuide history (inherited)

Everything below is RetroGuide's progress log, kept because the engine it describes is Crimson's
engine. Screen names in it refer to RetroGuide's UI, which no longer exists here.

## 2026-09-22 (latest): On Demand, Browse, curated lists and channel packages

1. **Movies and TV Shows became one On Demand screen** with a films/series switch. The two old
   screens are gone; their details dialogs moved to `ui/vod/DetailsDialogs.kt` unchanged.
2. **A Browse screen**: search over channels, films and series at once; the viewer's starred
   channels; the channel packages; and curated rows. Search is debounced and runs against the
   database, so it answers as you type and works offline.
3. **Curated rows from IMDb's published datasets.** `tools/make-curated-lists.py` turns
   `title.basics` and `title.ratings` into `app/src/main/assets/curated_lists.txt` — 46 lists,
   2,717 titles, 65 KB — and the app shows each list as the subset this account actually carries,
   with the provider's own poster art. The same rows appear on the On Demand screen.
4. **Channel packages.** `American Cable` is a hand-built 68-entry lineup (broadcast, news,
   entertainment, kids, factual, music, sports) and resolves **62 of 68** on the reference
   account. A package is a guide category, so the guide, favourites and filters all work on it.
5. **The on-demand catalogue is cached locally** — 196,953 films and 45,470 series on the
   reference account — with the title-matching key computed at import and stored in an indexed
   column, so neither search nor the curated rows ever load the catalogue into memory. Measured
   on the emulator: the whole catalogue takes about six minutes to fetch (the panel refuses the
   one-shot request, so it goes category by category) and the database ends up around 90 MB.
   Matching reaches **85% of the curated films and 81% of the series**.
6. **A wrong-country EPG match was fixed.** The provider's XMLTV is global, so its Portuguese
   `CNN` and a Russian feed were name-matching US channels and putting Lisbon and Moscow
   schedules on them. Name matches now stand aside for a channel that has its own id entry in the
   same document, and cannot cross a country boundary declared by an id suffix.

Still to verify on hardware: how long the catalogue import takes over a Stick's wifi, and the
memory ceiling during it. The import is deliberately last in the chain — channels, then guide
data, then the catalogue — so that a 1 GB device never runs two streaming imports at once.

## 2026-09-22 (later): guide data from many sources, by default

The provider's own XMLTV covers 888 of 11,130 channels and no amount of matching will change
that — it simply has no data for the rest. So the app now reads a built-in list of free public
XMLTV feeds as well, on by default, and fills in from its own channel names whatever no feed
covers. `DECISIONS.md` has the measured yield of every feed and the reasoning for each one that
was left out.

1. **Eleven public feeds, built in, on by default** (`core/epg/EpgSource.kt`). Roughly 50 MB a
   refresh, measured on the real account: channels with listings went from **808 to 1,197**, and
   re-scoring afterwards showed only 43 channels of headroom left in these feeds. They run in
   yield-per-megabyte order, each one sees only the channels still missing data, and a feed is
   skipped when nothing is left for it. `US_LOCALS1` was measured and rejected: 55 MB for two
   channels. Settings has an off switch for metered connections, which also deletes those rows.
2. **More listings derived from channel names.** Day-and-month dates (`Thu 16 Oct 19:00`),
   bare times (`8pm WWE Monday Night RAW`), `ENDED` markers, and show loops in streaming
   categories. Numbered slots (`AMAZON UK EVENT 5`) are deliberately left as "No Information".
3. **A real bug fixed: every refresh was duplicating the whole guide.** (Confirmed fixed on the
   device afterwards: 80,625 rows for 80,625 distinct slots.) `programs` had
   `OnConflictStrategy.IGNORE` but no unique constraint for it to act on, so the table had grown
   to 217,548 rows for 52,846 real programmes and the database to 76 MB. There is now a unique
   index on (channelKey, startMs), which also enforces source priority — the provider writes
   first and a public feed can never overwrite it.

## 2026-09-22: retro chrome, mouse support, and the real provider's guide

What changed, in the order it matters:

1. **The guide on the real account was empty and now is not.** The first import stored 0
   programmes; the same document now yields ~51,000 for ~970 channels. The likely cause was
   `xmltv.php` served as a gzip file without a `Content-Encoding` header, now sniffed by magic
   number. Beyond that, matching by `epg_channel_id` alone could only ever reach 9.6% of the
   list, so XMLTV `<channel>` display names are matched to channel names too
   (`core/epg/ChannelMatcher`), and the ~6,500 event slots and ~650 24/7 loops — which no feed
   covers — get listings synthesised from their own names (`core/epg/EventChannelEpg`). The
   import logs a full report as `RetroEpg` and the Settings screen shows one line of it under
   "Refresh now". `DECISIONS.md` has the numbers.
2. **Mouse support everywhere.** Hover highlights, click selects, the wheel changes rows or
   channels, star buttons and a bottom button bar stand in for the remote keys a mouse lacks, and
   a toolbar appears over full-screen video while the pointer moves. `ui/input/TvInput.kt` is the
   one modifier all of it goes through. Verified by `adb input tap` on the emulator (which
   delivers clicks as touches); real hover has not been seen on hardware yet.
3. **The 2000s look.** Gradients, bevels, gloss, hard text shadows, scanlines, coloured-key
   button bars, a bezelled clock. `ui/theme/RetroChrome.kt` for composed screens, `GuideGrid.kt`
   for the drawn grid, all numbers in `GuideTheme`. Screenshot baselines re-recorded.
4. **Direct channel entry** from number keys (keyboard or a numeric remote), the Info key, and
   G/F/I keyboard shortcuts while watching.

Still unverified: a real mouse hovering (the emulator turns the host mouse into touch); how long
the ~50 MB of feed downloads takes on a Fire Stick's wifi (on the emulator the whole EPG pass is
about 90 seconds); and the time zone the provider writes into event-slot names — it is read as the device's zone; if events
sit a few hours off, the "Guide time offset" setting corrects it.

The emulator's debug install (`com.retroguide.debug`) is signed in to the real account; the
release package (`com.retroguide`) is also installed there with no account. To look at the
app's database on the emulator, pull all three files with a binary-safe redirect (PowerShell's
`>` re-encodes): `adb exec-out run-as com.retroguide.debug cat databases/retroguide.db > f`
and the same for `-wal` and `-shm`, then open with sqlite3.


---

## The constraint that shapes everything

This was built in a container whose egress policy returns 403 for `dl.google.com`,
`maven.google.com`, `repo1.maven.org`, `services.gradle.org`, `plugins.gradle.org` and
`repo.maven.apache.org`, and which has no hardware virtualisation (`/dev/kvm` absent, no `vmx`/`svm`
CPU flags). Four routes were tried for the SDK — direct download, the Ubuntu `android-sdk`
packages, alternate Maven mirrors, GitHub. Only GitHub, PyPI and npm are reachable.

So the source was written blind, and the first Gradle build, the first screenshot tests and the
first emulator run all happened afterwards, on a Windows machine, on 2026-09-21. What that first
contact changed is in `DECISIONS.md` under "First build and first run on a device". The short
version: every dependency version resolved as written, the app compiled after two wrong imports,
and the things that were actually broken were the ones only a screen can show — a PRAGMA that
throws on Android, outlined text drawn twice stroked, and D-pad focus on the login and settings
screens.

---

## What is done and verified here

| Area | Evidence |
| --- | --- |
| ✅ Filter engine | 107 unit tests pass (`tools/run-core-tests.sh`). Every prefix style in the spec, every country token, whole-token matching, allowed and disallowed markets, national channels kept, unknown country dropped. |
| ✅ Filter accuracy against ground truth | `reports/discovery.md`: precision 1.0000, recall 1.0000 over 5,181 labelled channels, zero disagreements, zero markets mis-assigned. |
| ✅ Import efficiency | The category short-circuit avoids downloading 4,629 of 5,181 channels — 89% of the catalogue never fetched. |
| ✅ Tokeniser, name cleaner | Unit tested, including `LATINO`/`LA`, `SONY`/`NY`, `L.A.` vs `US.ESPN`, unicode `ᴴᴰ`. |
| ✅ Channel numbering | Stability across refreshes, across a channel disappearing and returning, alphabetical determinism, block overflow. |
| ✅ XMLTV time parsing | Offsets in every form, half-hour and 45-minute zones, truncated stamps, malformed input, the manual offset setting. |
| ✅ Short-EPG base64 | Round trip, padding, UTF-8, plain-text passthrough. |
| ✅ Guide geometry | Notches on both edges, clipping, minimum cell width at the right edge, gap filling with no holes or overlaps. |
| ✅ Guide navigation | Every D-pad and media-key rule in spec section 5.3, as a pure state machine. |
| ✅ Streaming JSON reader | 20,000-object array streamed; large-integer handling. |
| ✅ Mock Xtream server | Runs; serves login, categories, streams, short EPG, a 187 MB / 542,046-programme XMLTV (gzip verified byte-identical), an endless MPEG-TS and a sliding-window HLS playlist over FFmpeg-generated media. Bad credentials answer `auth: 0`. |
| ✅ Discovery tool | Runs against the mock server using the shipping filter and parser. |
| ✅ Launcher art | 320×180 TV banner and five launcher icon densities, generated and visually checked. |
| ✅ Gradle build | `./gradlew build` is green: `:core` (107 tests), `:app` debug and minified release APKs, lint, and the seven Roborazzi screenshot tests. Needs a JDK 17+ and the Android SDK; the Foojay plugin fetches the JDK 17 that `core` asks for. |
| ✅ Screenshot tests | Baselines recorded under `app/src/test/screenshots/`. The guide, the details dialog, the future-programme box, the empty state, a six-row theme and the banner all render as intended. |
| ✅ Emulator run | `tools/verify-on-device.ps1` runs end to end on an API 30 Android TV AVD with 1 GB of RAM: sign-in, import, playback, banner, guide navigation, the details dialog, channel up/down, last channel, settings and an instant filter change. `reports/device-verification.md` and `screenshots/` are its output. |

## What the first build found

The predictions above the line were close: every dependency version resolved as written, the
Compose text APIs compiled unchanged, and the one `@UnstableApi` call site outside
`ExoPlayerController` (the `PlayerView` setup in `MainActivity`) was caught by lint, not the
compiler. Two imports were wrong (`Modifier.focusable` lives in `androidx.compose.foundation`;
`item` is a `LazyListScope` member). The Robolectric qualifier string had its parts in the wrong
order, and the release variant cannot run the screenshot tests at all, so it no longer tries.

What only running it could find, all fixed and all recorded in `DECISIONS.md`: the database
crashed on first open (`execSQL("PRAGMA journal_mode=WAL")` throws on Android), every white cell
title was drawn stroked (the measurer's cached paint kept the outline pass's stroke), the login
fields could not be left with a D-pad, and the settings rows could not be reached because the root
held focus.

## Definition of Done, as it stands

| Definition-of-Done item | State |
| --- | --- |
| ⬜ Signed release APK builds with one command into `dist/` | `./gradlew build` produces a minified release APK signed with the debug key. `tools/make-keystore.ps1` has not been run, so no release key exists yet and `./gradlew release` has not been exercised with one. |
| ✅ Login works | Verified on the emulator against the mock server. The bad-credentials path is written and the mock server answers `auth: 0`, but the error has not been seen on screen. |
| ⬜ Guide matches the reference style | The guide has now been seen — `screenshots/04_guide.png` and the Roborazzi baselines — and reads as a cable guide. **It has still not been compared with `reference/`.** `GuideTheme.kt` remains the one file to edit. |
| ✅ Guide navigation on a device | Up/Down/Left/Right, Rewind/Fast Forward paging, Select on a current programme (tunes) and on a future one (details dialog), Back. |
| ✅ Smooth scrolling | Measured on the emulator only; see the table below and its caveat. |
| ✅ Playback, banner, last channel | Plays the mock server's MPEG-TS; the banner shows number, name, now-with-progress and next; Play/Pause returns to the previous channel. Auto-reconnect is written but has not been provoked. |
| ✅ Only one stream open at a time | One socket to the stream port throughout, including with the guide's preview window up. |
| ✅ Peak memory measured | See the table below. |
| ✅ Screenshot tests pass | Seven Roborazzi tests, recorded and passing in `./gradlew build`. |
| ✅ Real-server check | A real account is signed in on the emulator: 11,130 channels imported, the guide populated, event slots and loops listed from their names. Playback on it has not been measured. |
| ⬜ Real Fire TV Stick | Not yet. The emulator is a 1 GB API 30 Android TV image; Fire OS 6 (API 25) in particular is untested. `.\tools\verify-on-device.ps1 -Device <ip>:5555` is ready for it. |
| ✅ No credentials in git history or the APK | `secrets/` is gitignored, nothing is compiled in, and `reports/discovery.md` contains no URLs, hosts or credentials. |

---

## Next session: start here

1. **Run it on a real Fire TV Stick.** `adb connect <ip>:5555` then
   `.\tools\verify-on-device.ps1 -Device <ip>:5555`. The emulator numbers below come from a
   software-rendered AVD and say little about a Stick; the frame timing in particular needs real
   hardware, and Fire OS 6 (API 25) has never run the app.
2. **Get `reference/` in front of you** and compare it with `screenshots/04_guide.png` and the
   Roborazzi baselines. `ui/theme/GuideTheme.kt` is the only file that should need editing.
3. **Create the release key** with `tools\make-keystore.ps1`, then `.\gradlew.bat release`, and back
   the `.jks` up somewhere other than this machine.
4. **When a real Xtream account exists**, drop `secrets/xtream.json` in, re-run
   `tools/discover/run.sh`, and read the "Unrecognised prefixes" table at the end of the report.
   That table is the whole point of the discovery step: whatever appears there often is a naming
   convention `CountryDetector` or `ForeignCountries` should learn. Add the tokens, re-run, repeat.
5. **Provoke the reconnect path**: stop the mock server mid-stream and watch for the
   "Reconnecting…" label, the error panel after thirty seconds, and Select retrying.

Building on a new machine needs a JDK 17 or newer on `JAVA_HOME` and an Android SDK on
`ANDROID_HOME` (platform 35, build-tools 35.0.0); the verify script installs the SDK itself.

### Measurements

Taken by `tools/verify-on-device.ps1` on 2026-09-21 against an API 30 Android TV AVD with 1 GB of
RAM, `-gpu swiftshader_indirect` (software rendering), on a Ryzen 7 5800H under WHPX. The full
`dumpsys` output is in `reports/`.

| Measurement | Target | Actual (emulator) |
| --- | --- | --- |
| Peak memory, import of 5,181 channels and 539,944 programmes | well under a 1 GB device | 94.8 MB total PSS after the import (Dalvik heap 19 MB, native 3 MB) |
| Peak memory, guide open and scrolling | — | not separately measured; the windowed query keeps the programme map at a few dozen rows |
| Time to first frame, channel change | as low as possible | 125–360 ms for a channel change (five tunes); 1,434 ms for the first play after launch (cold decoder) |
| Janky frames while scrolling the guide | low | 22 of 35 frames (63%) over 25 rapid D-pad presses; 50th percentile 18 ms, 90th 30 ms, 99th 32 ms. **Software-rendered emulator; a real device is the only meaningful number.** |
| Filter change round trip | under 100 ms | **70 ms** from the toggle to the new channel list (392 channels), timed in the app; 72 ms on the previous run |
| Import duration | — | ~15 s for the channels (89% of the catalogue never fetched), ~23 s for the 187 MB XMLTV |

---

## Known limitations

- **The guide has been seen on an emulator, not compared with the reference images.** Built from
  prose descriptions; `reference/` is still the thing to check it against.
- **All measurements are from a software-rendered emulator**, not a Fire TV Stick.
- **A cold start took about five seconds on the emulator** right after boot (`Displayed` in
  logcat). Keystore setup for the encrypted credentials, Room and WorkManager all happen on the
  first launch; worth measuring on a Stick before deciding it matters.
- **Exclusion keywords are chosen from a fixed list** in settings rather than typed. Editing free
  text with a D-pad is miserable; the underlying setting is plain text and accepts anything, so a
  future version could add a keyboard or a phone companion.
- **Channel logos are not drawn.** `stream_icon` is stored and `GuideChannel.logoUrl` carries it,
  but the channel column shows number-over-call-sign only. Coil is already a dependency; drawing
  them into the Canvas needs an image cache with a hard size cap, which on a 1 GB stick is worth
  doing deliberately rather than by default.
- **Looping test media resets its timestamps** each time round, so a long soak against the mock
  server's `.ts` endpoint may show a hiccup at the loop point. That is the mock server, not the
  player.
- **Fire OS 6 (API 25) is untested** and is the riskiest of the three targets — `minSdk 25` is
  declared and core library desugaring is on, but nothing has run there. The emulator was API 30.
- **The on-screen keyboard drives the login form on a real remote.** Its Next and Done keys move
  between fields and sign in; with the keyboard dismissed, Up, Down and Select do the same. The
  verify script switches the keyboard off because it types with `input text`.
- **No captions or subtitles**, by design. See below.

---

## Suggested next steps, in order

1. **Captions and subtitles.** The first thing anyone will want. `PlayerController` exists as an
   interface for exactly this: add a `tracks: StateFlow<List<TextTrack>>` and a
   `selectTextTrack(id)`, implement them over ExoPlayer's `TrackSelectionParameters`, and add a
   selector to the player overlay. Nothing in the UI needs restructuring.
2. **Channel logos in the guide**, with a size-capped Coil cache and a hard ceiling on decoded
   bitmap size.
3. **The last mile of channel matching.** About 1,500 channels are real ones that no free feed
   carries (Sky Sports Premier League, Sky Cinema Sci-Fi, regional Fox Sports). A paid source —
   Schedules Direct is $35/yr and has a documented XMLTV grabber — would cover most of the US
   ones. Substring matching was tried and rejected; see `DECISIONS.md`.
4. **An alias table for abbreviations.** `Sky Sports PL` and `Sky Sports Premier League` reduce
   to different keys, as do `U and alibi` and `alibi`. A short hand-written alias list applied
   before the key comparison would pick up a few dozen channels safely, where substring matching
   cannot.
4. **Catch-up**, since `tv_archive` is already stored per channel.
5. **VOD and series**, which is a much larger piece of work and deliberately out of scope here.
