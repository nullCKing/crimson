# CLAUDE.md

Orientation for a Claude Code session picking this project up.

## What this is

An Android TV / Fire TV IPTV player for Xtream Codes accounts, with a guide styled after a 2000s
DirecTV / Comcast cable box. Full build spec behind it; `PLAN.md` has the milestones.

## Read these first, in order

1. **`PROGRESS.md`** — the handover. States precisely which Definition-of-Done items are verified
   and which are not, and has a "Next session: start here" section. Do what it says.
2. **`DECISIONS.md`** — every non-obvious choice and its reason. Check here before "fixing"
   something that looks odd; most of the odd-looking things are deliberate and the reason is
   written down.
3. **`README.md`** — build, test and run instructions.

## Where it stands

The source was written in an environment that could reach neither Google's Maven nor an emulator,
so nothing Android-side was compiled until 2026-09-21. Since then: **`./gradlew build` is green**
(107 `core` tests, seven Roborazzi screenshot tests, lint, debug and minified release APKs), and
`tools/verify-on-device.ps1` has driven the whole app on an Android TV emulator against the mock
server — sign-in, import, playback, banner, guide, settings, filter change. `PROGRESS.md` has the
measurements and the exact list of what is still unverified: a real Fire TV Stick, a real Xtream
account, and the comparison with the reference screenshots.

Building needs a JDK 17 or newer and an Android SDK (platform 35, build-tools 35.0.0) on
`ANDROID_HOME`; `core` pins a JDK 17 toolchain and the Foojay plugin in `settings.gradle.kts`
downloads one if the machine has none. The verify script installs the SDK and the emulator itself.

## Priorities from the spec

1. A fast, stable full-screen player with instant channel switching.
2. The guide.
3. Everything else.

Out of scope, deliberately: captions and subtitles, recording, catch-up, M3U, multiple accounts.
VOD, series and external EPG sources were out of scope in the original spec and have since been
added. `PlayerController` is an interface so captions can be
added later without restructuring the UI — that is the intended next feature.

## Layout

```
core/          Plain Kotlin, no Android, fully unit-tested. Filter engine, name cleaning,
               channel numbering, XMLTV time parsing, guide geometry, guide navigation,
               streaming JSON reader. Put logic here whenever you can — it is the only
               code that can be tested without a device.
app/data/      Room, Xtream HTTP client, streaming import, XMLTV, settings, credentials
app/domain/    Guide repository
app/player/    PlayerController and its ExoPlayer implementation
app/ui/        Compose screens. The grid is drawn on a single Canvas, not composed.
               ui/theme/RetroChrome.kt is the set-top-box look (gradients, bevels, button
               bars); ui/input/TvInput.kt is the one modifier that makes rows work from a
               remote and a mouse alike; ui/components/RetroRow.kt is the poster row and the
               search field that Browse and On Demand are built from.
core/catalog/  Curated lists, title matching and the channel packages. Android-free, tested.
tools/make-curated-lists.py
               Regenerates app/src/main/assets/curated_lists.txt from IMDb's published
               datasets. Run it on a developer machine; the device never sees the TSVs.
tools/         Mock Xtream server, discovery tool, device verification, art, keystore
```

## Commands

```powershell
# Tests
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat recordRoborazziDebug     # re-record screenshot baselines
.\gradlew.bat verifyRoborazziDebug     # check against them

# Build
.\gradlew.bat release                  # signed APK into dist\

# Mock server (needed for anything end to end)
cd tools\mock-xtream ; python server.py --port 8080
# user testuser / pass testpass
# emulator reaches it at http://10.0.2.2:8080

# Everything on a device, in one command
.\tools\verify-on-device.ps1
.\tools\verify-on-device.ps1 -Device 192.168.1.50:5555   # a real Fire Stick

# The filter's accuracy against the mock server's ground truth
bash tools/discover/run.sh             # or run Discover.kt however is convenient
```

`tools/run-core-tests.sh` compiles and runs the core tests with `kotlinc` and a JUnit jar, no
Gradle. It exists only because the originating environment could not run Gradle at all. On a
normal machine, use `./gradlew :core:test`.

## Things to be careful about

- **Do not weaken the filter to make a test pass.** It is deliberately asymmetric: wrongly calling
  a national channel "local" deletes something the user wanted, while wrongly calling a local
  "national" leaves one extra row. `DECISIONS.md` explains each guard (`LA` vs `LA LIGA`, `WAVE`
  vs the Louisville call sign, `NETWORK` vs `USA NETWORK`). Currently precision and recall are both
  1.0000 against 5,181 labelled channels; keep it that way.
- **Memory is the binding constraint.** Target is a Fire TV Stick Lite with 1 GB of RAM shared
  with everything else. The import filters as it parses and writes in batches of 250; the XMLTV
  parser holds one `<programme>` at a time; the guide queries a window of visible rows. Do not
  introduce a `.toList()` over a provider's stream list or a `body.string()` on XMLTV.
- **One ExoPlayer, ever.** Xtream accounts are commonly sold with a single connection, so a second
  player would lock the user out of their own service. The guide's preview and the full-screen
  view share one instance.
- **The guide has been seen on an emulator but never compared with the reference images.** It was
  built from written descriptions because the reference screenshots were unreachable. If the user
  has them, compare and adjust — `ui/theme/GuideTheme.kt` holds every colour, size and count in one
  place, and a screenshot test proves changing it works.
- **Every interactive element goes through `Modifier.tvInteractive`.** Hover moves focus, click
  is Select, and the highlight is focus. Do not add a `clickable` or a second highlight state;
  star buttons and the button bars are `focusTarget = false` so a D-pad skips them.
- **The guide has four sources of listings, in this order**: the provider's XMLTV joined by
  `epg_channel_id` *and* by display name; the built-in public XMLTV feeds in
  `core/epg/EpgSource.kt`, which only ever see the channels still missing data; the per-channel
  short EPG for rows on screen; and listings derived from the channel's own name for event slots
  and loops (`core/epg/EventChannelEpg.kt`). Priority is enforced by the unique index on
  `programs(channelKey, startMs)` plus the order the sources are read in — do not remove that
  index, and do not reorder the reads. When the grid is empty, read the `RetroEpg` line in
  logcat or the summary under Settings → Refresh now before touching the importer.
- **The curated lists are generated, not hand-typed.** `app/src/main/assets/curated_lists.txt`
  comes from `tools/make-curated-lists.py`, which reads IMDb's published datasets
  (https://datasets.imdbws.com/). Do not edit the asset; re-run the tool. Do not scrape imdb.com:
  their terms forbid it and the markup changes constantly, which is why the datasets exist.
- **A title is matched by `core/catalog/TitleMatcher`, a channel by `core/epg/ChannelMatcher`.**
  They are different problems — a film has a year and packaging tags, a channel has a country
  prefix and a quality tag — and neither should be made to serve the other.
- **Do not add an EPG feed to the built-in list without measuring it.** Every feed there was
  scored against a real 11,130-channel account; the table is in `DECISIONS.md`. Two things that
  look helpful and are not: `US_LOCALS1` (55 MB, filled two channels) and substring matching
  between channel names (`SKY CINEMA OSCARS` contains `CARS`).
- **Never commit `secrets/`.** Credentials, the signing keystore and `keystore.properties` live
  there and it is gitignored. The signing key must never change, or updates stop installing over
  existing versions.

## Measurements

`PROGRESS.md` has the numbers from the emulator: peak memory after the import, time to first frame
on a channel change, janky frame percentage while scrolling, and the filter-change round trip (the
app logs the last two as `RetroPlayer` / `RetroGuideVM` lines that the verify script reads). The
emulator renders in software, so treat its frame timing as a smoke test; the numbers that matter
come from `.\tools\verify-on-device.ps1 -Device <ip>:5555` against a real Stick, which has not
been done yet. Replace the table when you have them.
