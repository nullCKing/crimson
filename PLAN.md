# Plan

A retro cable-guide IPTV player for Fire TV, Xtream Codes accounts only.

## Constraint that shapes the plan

The development container cannot reach `dl.google.com` or any Maven repository (403 from the
egress proxy) and has no hardware virtualisation, so it can neither build the APK nor run an
emulator. It *can* run the Kotlin compiler and Python. The work therefore splits in two:

| Done and verified in the container | Done on the developer's Windows machine |
| --- | --- |
| `core` module: filter, naming, numbering, EPG parsing, guide geometry and navigation | Gradle build of `app` |
| All `core` unit tests (`tools/run-core-tests.sh`) | JVM screenshot tests |
| Mock Xtream server, fixtures, test media | Signed release APK |
| Discovery report against the mock server | Emulator run: D-pad, screenshots, gfxinfo, meminfo |
| Every Android source file, written but not compiled | Playback against the mock server and public test streams |

`tools/verify-on-device.ps1` is the single command for the right-hand column.

## Milestones

1. **Environment and skeleton** — repo, module layout, Kotlin toolchain, test runner. *Done.*
2. **Filter engine** — tokeniser, country detection, US local detection, exclusion keywords, with
   the false-positive cases from the spec as tests. *Done.*
3. **Core support modules** — name cleaning, channel numbering, XMLTV time, short-EPG base64,
   programme categories, guide geometry, guide navigation. *Done.*
4. **Mock Xtream server** — `player_api.php` and `xmltv.php`, 5,000+ channels across 30+ countries
   with mixed prefix styles, US locals for 20+ cities, XMLTV across several days, looping TS and
   HLS test media.
5. **Discovery report** — `tools/discover` against the mock server, producing `reports/discovery.md`;
   feed its unrecognised-prefix list back into the token lists.
6. **Data layer** — Room schema, OkHttp Xtream client, streaming JSON import that filters as it
   reads and writes survivors in batches.
7. **EPG pipeline** — streaming XMLTV parser with gzip, −2h/+72h window, short-EPG fallback,
   pruning, 12-hour WorkManager refresh.
8. **Player** — Media3 behind an interface, one reused instance, channel banner, last channel,
   backoff reconnect, wake lock.
9. **Guide screen** — theme object, custom-drawn grid, info panel, preview window, clock, D-pad
   and media-key handling wired to `GuideNavigator`.
10. **Settings** — DataStore rules, instant removal, incremental addition with progress.
11. **Screenshot tests** — Roborazzi for the guide and the banner at 1920×1080.
12. **Release** — keystore, signing config, one-command build to `dist/`, verification script.
13. **Documentation** — README, final PROGRESS summary.

## Priorities if something has to give

The spec's order: a fast, stable full-screen player first; the guide second; everything else after.
Captions, subtitles, VOD, recording, catch-up, M3U and multiple accounts are out of scope, but the
player sits behind an interface so a track selector can be added without touching the UI.
