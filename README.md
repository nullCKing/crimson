# RetroGuide

A full-screen IPTV player for Fire TV and Android TV, with a programme guide styled after a
2000s DirecTV / Comcast cable box. Xtream Codes accounts only.

Two jobs, in this order:

1. A fast, stable full-screen live player with instant channel switching.
2. A guide that looks and behaves like a cable guide, showing a filtered set of channels.

Out of scope in this build: captions and subtitles, VOD, series, recording, catch-up, M3U,
multiple accounts, external EPG sources. The player sits behind an interface (`PlayerController`)
precisely so a track selector can be added without touching the UI.

---

## Build

You need a JDK 17 or newer and an Android SDK with platform 35 and build-tools 35.0.0, found
through `ANDROID_HOME` or `local.properties`. Gradle downloads everything else, including the
JDK 17 toolchain that `core` pins.

```
git clone <this repo>
cd retroguide

# Once: create the release signing key.
powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1

# Build the signed release APK into dist\.
.\gradlew.bat release
```

On macOS or Linux, `./gradlew release` and `tools/make-keystore.sh` do the same thing.

`release` builds `:app:assembleRelease` and copies the APK to `dist/`. Without
`secrets/keystore.properties` the build still succeeds, signed with the debug key, and warns that
the result cannot be used for updates.

**The signing key must never change.** Android identifies an app by package name *and* signing
certificate, so an APK signed with a different key will not install over an existing one — the
user has to uninstall first and loses their settings and channel list. `secrets/` is gitignored;
back the `.jks` file up somewhere else.

Install it with `adb install -r dist\RetroGuide-release.apk`, or sideload it to a Fire Stick the
usual way.

---

## Run the mock Xtream server

Nothing about development depends on a real provider being up.

```
cd tools\mock-xtream
python server.py --port 8080
```

It generates 5,181 channels across 44 countries in 213 categories, with the country marker written
a different way in almost every one, US locals for 28 markets, and XMLTV covering four days —
187 MB and 542,046 programmes, on purpose, because surviving that is the point.

Test media is generated locally with FFmpeg on first run: colour bars with a burned-in timecode
and a tone, served as an endless MPEG-TS and as a sliding-window HLS playlist. Nothing is
downloaded and nothing is copied from anywhere, so there is no licensing question about it.

Useful flags:

| Flag | Effect |
| --- | --- |
| `--channels 20000` | generate a larger catalogue |
| `--epg-days 7` | more guide data, for a harder memory test |
| `--no-media` | skip FFmpeg; the API still works |
| `--public-streams` | redirect playback to public test streams instead of local media |
| `--verbose` | log every request |

`--public-streams` uses only streams published by their owners for developer testing, or US
government works in the public domain: NASA TV (public domain), the Blender Foundation's open
movies under Creative Commons Attribution as served by Mux for hls.js testing, Apple's own HLS
example stream, and Unified Streaming's *Tears of Steel* demo. They are listed in
`tools/mock-xtream/server.py`.

Sign in from the app with:

| Field | Value |
| --- | --- |
| Server | `http://10.0.2.2:8080` on an emulator, or `http://<your LAN IP>:8080` on a real stick |
| Username | `testuser` |
| Password | `testpass` |

---

## Run the tests

```
.\gradlew.bat :core:test              # the filter engine and everything else in core
.\gradlew.bat :app:testDebugUnitTest  # screenshot tests
.\gradlew.bat recordRoborazziDebug    # re-record screenshot baselines after a UI change
.\gradlew.bat verifyRoborazziDebug    # check the UI against the committed baselines
```

The `core` module has no Android and no third-party dependencies, so it can also be compiled and
tested with nothing but `kotlinc` and a JUnit jar:

```
tools/run-core-tests.sh
```

That exists because the environment this was built in has no access to Google's Maven repository
and cannot run Gradle at all. It is the fallback, not the normal path.

### Verify on a device

```
powershell -ExecutionPolicy Bypass -File tools\verify-on-device.ps1
```

Installs the SDK if needed, starts the mock server, runs the tests, builds and installs the APK,
creates a 1080p Android TV AVD with 1 GB of RAM, drives the app with `input keyevent`, captures a
screenshot at each step, and records `dumpsys meminfo` and `dumpsys gfxinfo`. Results land in
`reports/device-verification.md` and `screenshots/`.

Against a real Fire TV Stick, which is a better test of the 1 GB constraint:

```
adb connect 192.168.1.50:5555
powershell -ExecutionPolicy Bypass -File tools\verify-on-device.ps1 -Device 192.168.1.50:5555
```

### The discovery report

```
tools/discover/run.sh
```

Runs the shipping filter against a live server — `secrets/xtream.json` if it exists, the mock
server otherwise — and writes `reports/discovery.md`: every category and how it was classified,
sample channel names per country, every channel the local detector fired on with the market it
matched or the reason it was dropped, the counts, and the most common prefixes the country lists
did not recognise. No URLs, host names or credentials ever appear in it.

Against the mock server, whose channels carry the country and market they were generated with, the
report also scores the filter: currently **precision 1.0000, recall 1.0000** over 5,181 channels
with zero disagreements.

`secrets/xtream.json`:

```json
{ "host": "http://server:port", "username": "...", "password": "..." }
```

---

## Where things live

### The filter rules

`core/src/main/kotlin/com/retroguide/core/filter/`

| File | Contents |
| --- | --- |
| `ChannelFilter.kt` | the engine: category verdicts, per-channel decisions |
| `CountryDetector.kt` | country tokens for US, UK, JP, KR, with negative phrases |
| `ForeignCountries.kt` | countries outside the allowlist, so their categories are skipped unfetched |
| `UsMarkets.kt` | city names and call signs, by market |
| `LocalDetector.kt` | whether a US channel is a local, and which market |

Defaults are in `FilterRules` in `core/.../model/Models.kt`. The user's own rules live in DataStore
(`data/settings/SettingsStore.kt`) and the settings screen edits them.

### The theme

`app/src/main/kotlin/com/retroguide/ui/theme/GuideTheme.kt`

One data class holding every colour, size, row count and spacing the guide and banner use. The
grid is drawn onto a Canvas, so there are no styles or XML attributes anywhere else — changing
`rowsVisible` from 5 to 6, or `movieCell` from magenta to green, is a one-line edit. A screenshot
test renders the guide with a modified copy to prove it.

### Everything else

```
core/          filter, naming, numbering, EPG parsing, guide geometry and navigation, JSON reader
               (plain Kotlin, no Android, fully unit-tested)
app/data/      Room, the Xtream HTTP client, the import pipeline, XMLTV, settings, credentials
app/domain/    the guide repository
app/player/    PlayerController and its ExoPlayer implementation
app/ui/        Compose screens; the grid is a single Canvas
tools/         mock server, discovery tool, device verification, art and keystore scripts
```

---

## How the filtering works

Only channels from the United States, United Kingdom, Japan and South Korea are kept, and US
local affiliates only from Los Angeles, New York, Miami and Tampa. National US channels are kept
whatever city they mention.

Filtering happens **during import**, never after. Categories are classified first and most are
never fetched at all — against the mock server that avoids downloading 4,629 of 5,181 channels.
What is fetched is parsed with a streaming JSON reader and evaluated one channel at a time, so
peak memory is a function of the batch size, not of the provider's catalogue.

Three decisions in the filter are worth knowing about, and `DECISIONS.md` explains the rest:

- **Tokenising records whether punctuation closed a token.** That is what tells `DE| SPORT`
  (Germany) apart from `IN THE MIX` (a sentence). Short country codes need a punctuation boundary;
  full country names do not.
- **The filter is deliberately asymmetric.** Wrongly calling a national channel "local" deletes
  something the user wanted; wrongly calling a local "national" leaves one extra row. So
  `LA`, `NY`, and word-like call signs such as `WAVE` and `KING` only count as markets when
  something else corroborates them, and ambiguous city names are left out of the lists entirely.
- **`NETWORK` is not a locals indicator**, despite appearing in the original spec, because
  `USA NETWORK`, `CARTOON NETWORK` and `FOOD NETWORK` are national channels.

Removing a country or market in settings is a database query over rows already on the device, with
no network call. Adding one fetches only the categories for it.

---

## Credentials and privacy

- Entered on first launch, stored with `EncryptedSharedPreferences` behind a keystore-held key.
  If the device's keystore is broken — which happens on some Fire OS builds after a reset — the
  app falls back to plain preferences and says so on the login screen rather than refusing to run.
- Nothing is compiled into the APK and nothing is committed. `secrets/` is gitignored.
- Xtream serves live streams at `/live/USER/PASS/ID.ts`, so credentials are in the stream URL by
  the provider's design. The app never logs a stream URL, and `reports/discovery.md` contains none.
- Cleartext HTTP is permitted, because most Xtream panels have no TLS at all. The login screen
  says plainly that on such a server the credentials travel unencrypted.

---

## Known limitations

See `PROGRESS.md` for the full list and what was and was not verified on hardware.
