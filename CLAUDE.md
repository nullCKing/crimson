# CLAUDE.md

Orientation for a Claude Code session picking this project up.

## What this is

Crimson: an Android TV / Fire TV app for Xtream Codes accounts that looks and behaves like a
premium streaming service — profiles, an endless home of recommendation rows, title pages,
resume — with live TV, a guide, a by-country channel directory and live sports built in.

It is a fork of RetroGuide (a 2000s-cable-box guide for the same accounts; github.com/nullCKing/
retroguide, local copy at `D:\iptv\retroguide\retroguide`). The engine underneath — import,
filter, EPG pipeline, player, guide grid — is RetroGuide's and is kept deliberately; the UI is new.

## Read these first, in order

1. **`PROGRESS.md`** — what is done, what is verified and how, and what is not.
2. **`DECISIONS.md`** — every non-obvious choice. The Crimson section is at the top; RetroGuide's
   decisions follow and still apply to the engine. Check here before "fixing" something odd.
3. **`README.md`** — features, build, test and run instructions.

## Layout

```
core/                 Plain Kotlin, no Android, unit-tested. Put logic here whenever you can.
  catalog/            Feed rows as data + their SQL, title index, search ranking, channel packages
  sports/             ESPN scoreboard parser, game → channel search terms
  live/               Countries/regions for the directory
  filter/ epg/ guide/ json/ text/ numbering/   RetroGuide's engine
app/data/             Room (one database per profile), Xtream client, importers, profiles, sports
app/ui/CrimsonViewModel.kt   Session, back stack (Route), player, guide, import, settings
app/ui/<page>/        One controller + screen per page: feed (Home/Shows/Movies), details, search,
                      live (Live TV + directory), sports, mylist, profiles, player, guide, settings
app/ui/components/    The design system. Every card, button and row is built from here.
app/ui/theme/         Crimson.kt (palette/type), CrimsonIcons.kt, GuideTheme.kt (canvas grid)
```

## Commands

```powershell
# Set per call in a new shell (see memory): GRADLE_USER_HOME=D:\gradle, ANDROID_HOME=D:\Android\Sdk
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat recordRoborazziDebug     # after any UI change; look at app\src\test\screenshots\
.\gradlew.bat build                    # tests, lint, debug and minified release
.\gradlew.bat release                  # signed APK into dist\

cd tools\mock-xtream ; python server.py --port 8080     # testuser / testpass, 10.0.2.2 on emulator
.\tools\verify-on-device.ps1 -Device emulator-5554      # full scripted run with screenshots
python tools\make-title-index.py <dir with IMDb .tsv.gz>   # regenerate the title index asset
python tools\make-art.py                                # launcher icon and TV banner
```

## Things to be careful about

- **Everything RetroGuide's CLAUDE.md said about the engine still holds**: don't weaken the filter
  (precision/recall 1.0 on 5,181 labelled channels), memory is the binding constraint (1 GB Fire
  Stick Lite; stream, batch, window — no `.toList()` over a provider's list), **one ExoPlayer,
  ever** (the Live TV preview, the guide preview and full screen all share it), keep the four EPG
  sources in their order and the unique index on `programs(channelKey, startMs)`.
- **Every interactive element goes through `Modifier.tvInteractive`**, and cards through
  `cardFocus`. Focus is white (ring + lift); red means "important" (primary action, live,
  progress, selected), never "cursor". A primary button is red at rest and white under focus — a
  white-at-rest primary made focus ambiguous on the profile editor.
- **Scrolling lists set their own `BringIntoViewSpec`** (`PivotScroll` for rows, `RevealScroll`
  for document pages). Android TV's default is a 30% pivot, which scrolls headings away. The
  spec's animation must be a spring: the target is recomputed every frame and a tween restarted
  each frame barely moves (that bug made rows crawl at 1 px/frame).
- **Initial focus goes through `InitialFocus`**, which re-requests after the route transition —
  the outgoing page releases focus a moment after the new page claims it. The root Box is
  focusable only on Watching and Guide.
- **Recommendation rows are data** (`FeedPlanner`) turned into SQL (`CatalogSql`) against columns
  the title index fills in (`genres`, `imdbRating`, `imdbVotes`). Add a row by adding a spec, not
  a query. Rows too short for an account are skipped, so the plan can over-ask.
- **The title index is generated** from IMDb's datasets by `tools/make-title-index.py`. Bump
  `TITLE_INDEX_VERSION` in the view model when you regenerate it, so catalogues re-join. Don't
  scrape imdb.com.
- **ESPN's scoreboard is unofficial.** Everything in `core/sports` treats every field as optional;
  keep it that way.
- **Performance**: no blurred shadows on anything that scrolls (removing one took scrolling from
  24 to 18 ms/frame on the emulator), scrims drawn once over a crossfade, not per image. The
  emulator's frame times are a smoke test; a real Fire Stick run has not been done.
- **Never commit `secrets/`.** Crimson has its own keystore (`crimson-release.jks`).
