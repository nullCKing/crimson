# Device verification

Produced by `tools/verify-on-device.ps1` on 2026-09-21 18:15.

Device: `emulator-5554`
APK: `app-debug.apk`
Package: `com.retroguide.debug`

## Results
- Mock server: mock-xtream: 5181 channels, 213 categories
- Unit tests: PASS
- Screenshot tests: recorded
- Build: debug APK (app-debug.apk)
- Device: emulator-5554, mock server reachable at http://10.0.2.2:8080
- Peak memory (TOTAL PSS): 94.8 MB
- Frames: 35 rendered, 22 janky (62.86%)
- Time to first frame: 5 tunes, average 494 ms, range 125-1434 ms
- Filter toggle round trip (includes key delays): 2863 ms
- Filter change applied in the app: 70 ms, 392 channels after the change
- Sockets open to the mock server's port: 1 (1 or 0 expected; a value above 1 means two streams)
## Artefacts

- `screenshots/` — one image per navigation step, numbered in order.
- `reports/meminfo.txt` — full `dumpsys meminfo` output.
- `reports/gfxinfo.txt` — full `dumpsys gfxinfo` output, including the frame-time histogram.
- `reports/player-log.txt` — the player's own log, including time to first frame per tune.

## What to look at

Open the screenshots in order and compare them with `reference/`. The things worth checking by
eye are the ones no assertion covers: whether the deep navy reads right on a real panel, whether
the yellow highlight is legible from a sofa, and whether the cell text is large enough at 1080p.
