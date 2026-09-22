# Device verification

Produced by `tools/verify-on-device.ps1` on 2026-09-22 16:29.

Device: `emulator-5554`
APK: `app-release.apk`
Package: `com.crimson`

## Results
- Mock server: mock-xtream: 5181 channels, 213 categories
- Unit tests: PASS
- Screenshot tests: recorded
- Device: emulator-5554, mock server reachable at http://192.168.56.1:8080
- Library joined to the title index: 593 films, 138 series
- Memory after the import (TOTAL PSS): 104.1 MB
- Home rows: 1119 frames rendered, 1037 janky (92.67%)
- Time to first frame: 8 tunes, average 144 ms, range 87-371 ms
- Filter toggle round trip (includes key delays): 1105 ms
- Filter change applied in the app: 52 ms, 417 channels after the change
- Sockets open to the mock server's port: 0 (1 or 0 expected; a value above 1 means two streams)
## Artefacts

- `screenshots/` — one image per navigation step, numbered in order.
- `reports/meminfo.txt` — full `dumpsys meminfo` output.
- `reports/gfxinfo.txt` — full `dumpsys gfxinfo` output, including the frame-time histogram.
- `reports/player-log.txt` — the player's own log, including time to first frame per tune.

## What to look at

Open the screenshots in order. The things worth checking by eye are the ones no assertion
covers: whether the near-black and the red read right on a real panel, whether the white focus
ring is obvious from a sofa, and whether the row text is large enough at 1080p.
