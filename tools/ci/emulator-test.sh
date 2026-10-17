#!/usr/bin/env bash
# Drives Crimson on an Android TV emulator with remote-control keys and records what it shows.
#
# Run by .github/workflows/emulator-test.yml inside a booted emulator. Every step leaves a
# screenshot and the text on screen (from uiautomator) in $OUT, and the step log says what was
# pressed, so a run can be read back like a person's notes of using the app.
#
#   MODE=mock  (default) against tools/mock-xtream at 10.0.2.2:8080, with its playlist and guide
#   MODE=real  against a real account from REAL_SERVER, REAL_USER, REAL_PASS and, optionally,
#              REAL_PLAYLIST. Nothing that shows the login is captured in this mode.
set -u
APK=${APK:?path to the APK}
OUT=${OUT:-ci-out}
MODE=${MODE:-mock}
PKG=com.crimson
mkdir -p "$OUT"
LOG="$OUT/steps.md"
: > "$LOG"
n=0

say() { echo "$*"; echo "$*" >> "$LOG"; }
key() { for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; echo "  keys: $*" >> "$LOG"; }
wait_s() { sleep "$1"; }

# A screenshot plus the text on screen. Compose exposes its text to accessibility, so the
# uiautomator dump says which page is up and what it says without reading the picture.
shot() {
  n=$((n + 1))
  local name; name=$(printf '%02d-%s' "$n" "$1")
  adb exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$name.xml" >/dev/null 2>&1
  python3 - "$OUT/$name.xml" > "$OUT/$name.txt" <<'PY'
import re, sys, html
try:
    x = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    sys.exit(0)
seen = []
for m in re.finditer(r'(?:text|content-desc)="([^"]+)"', x):
    t = html.unescape(m.group(1)).strip()
    if t and t not in seen:
        seen.append(t)
print(" | ".join(seen))
PY
  rm -f "$OUT/$name.xml"
  say "- **$name**: $(cut -c1-400 "$OUT/$name.txt")"
}

# Text into whatever has the keyboard. `input text` takes no spaces and the device shell would
# read & and ?, so the text goes inside single quotes with spaces as %s.
type_text() {
  local t=${1// /%s}
  adb shell "input text '$t'"
  sleep 0.8
}

# Opens the focused field's entry panel, types, and saves with Enter.
fill() {
  local label=$1 text=$2 capture=${3:-yes}
  key DPAD_CENTER
  sleep 1.2
  [ "$capture" = yes ] && shot "panel-$label"
  type_text "$text"
  key ENTER
  sleep 1
}

# Brings Crimson back to the front if a stray Back left it (Back on Home's top leaves the app).
ensure_app() {
  if ! adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$PKG"; then
    say "  (Crimson was not in front; relaunching)"
    adb shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep 5
  fi
}

# The text of the focused element (or of the first text inside it), from the accessibility tree.
focused_text() {
  adb shell uiautomator dump /sdcard/f.xml >/dev/null 2>&1
  adb pull /sdcard/f.xml "$OUT/.focus.xml" >/dev/null 2>&1
  python3 - "$OUT/.focus.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
for node in root.iter("node"):
    if node.get("focused") == "true":
        for n in node.iter("node"):
            t = n.get("text") or n.get("content-desc")
            if t:
                print(t)
                sys.exit(0)
        sys.exit(0)
PY
}

# Opens a tab from the navigation bar by name, stepping along it by what has focus rather than
# by counting presses (Left from the first tab drops to the billboard when there is one).
TABS=("Home" "TV Shows" "Movies" "Live TV" "Sports" "My List")
to_tab() {
  ensure_app
  local want=${TABS[$1]} f i idx
  key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP
  sleep 1.5                      # the navigation bar slides in
  for i in $(seq 1 12); do
    f=$(focused_text)
    [ "$f" = "$want" ] && break
    idx=-1
    for j in "${!TABS[@]}"; do [ "${TABS[$j]}" = "$f" ] && idx=$j; done
    if [ $idx -lt 0 ]; then key DPAD_UP; sleep 1
    elif [ $idx -lt "$1" ]; then key DPAD_RIGHT
    else key DPAD_LEFT; fi
  done
  shot "nav-before-$(echo "$want" | tr ' ' '-')"
  key DPAD_CENTER
  sleep 6
}

app_mem() { adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL:" | head -1; }

say "# Crimson on the emulator ($MODE), $(date -u +%Y-%m-%dT%H:%MZ)"
adb shell settings put global window_animation_scale 1 >/dev/null 2>&1
adb install -r "$APK" | tail -1 | tee -a "$LOG"
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 8
shot first-launch

if [ "$MODE" = real ]; then
  SERVER=$REAL_SERVER; USER_=$REAL_USER; PASS=$REAL_PASS; PLAYLIST=${REAL_PLAYLIST:-}; CAP=no
else
  SERVER=http://10.0.2.2:8080; USER_=testuser; PASS=testpass
  PLAYLIST="http://10.0.2.2:8080/get.php?username=testuser&password=testpass&type=m3u_plus"; CAP=yes
fi

say "## Profile"
fill name "Tester" yes          # focus starts on the name
key DPAD_RIGHT; fill server "$SERVER" "$CAP"
key DPAD_DOWN DPAD_LEFT; fill username "$USER_" "$CAP"
key DPAD_RIGHT; fill password "$PASS" "$CAP"
if [ -n "$PLAYLIST" ]; then
  key DPAD_DOWN DPAD_LEFT; fill playlist "$PLAYLIST" "$CAP"
fi
[ "$CAP" = yes ] && shot form-filled
key DPAD_DOWN
[ "$CAP" = yes ] && shot save-focused
key DPAD_CENTER
sleep 12
shot after-save

say "## Sign in"
key DPAD_CENTER          # the profile just made is focused on the picker
sleep 45
shot home-after-import
echo "memory after import: $(app_mem)" >> "$LOG"

say "## Home: down through the rows, then Back to the top"
for i in 1 2 3 4 5 6; do key DPAD_DOWN; done
shot home-scrolled
key BACK
sleep 1.5
shot home-after-back
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_UP DPAD_UP
shot home-after-up

say "## Catalogue (given time to import)"
sleep 60
ensure_app
shot home-later

say "## Live TV"
to_tab 3
shot live-tv
key DPAD_DOWN DPAD_DOWN
sleep 5
shot live-preview
key DPAD_CENTER
sleep 10
shot watching
if [ "$MODE" != real ] && [ "${STALL:-0}" != 0 ]; then
  say "## Stall recovery (the mock stops sending after ${STALL} s)"
  sleep $((STALL + 20))
  shot after-stall
  grep -E "stalled|reopening|first frame|giving up|playback error" "$OUT/logcat.txt" | grep -i crimson | tail -20 >> "$LOG"
fi

say "## Multiview"
ensure_app
key MENU
sleep 2
shot player-menu
key DPAD_UP DPAD_CENTER     # the Multiview row sits above Captions
sleep 3
shot multiview-picker
key DPAD_DOWN DPAD_DOWN DPAD_CENTER
sleep 10
shot multiview-two
key DPAD_DOWN
sleep 1
key DPAD_CENTER             # window 3: pick a third channel
sleep 2
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
sleep 10
shot multiview-three
grep -i "CrimsonMultiView" "$OUT/logcat.txt" | tail -10 >> "$LOG"
key DPAD_LEFT DPAD_UP       # back to window 1: the sound follows
sleep 2
shot multiview-window-1
key BACK
sleep 4
shot after-multiview
key BACK
sleep 3
shot back-from-watching

say "## Sports"
to_tab 4
sleep 4
shot sports
say "## Sports > Extra"
key DPAD_DOWN DPAD_UP        # onto the chips (All Sports has focus first)
key DPAD_RIGHT DPAD_CENTER
sleep 6
shot sports-extra
key DPAD_DOWN
sleep 2
shot extra-card-focused
key DPAD_CENTER
sleep 8
shot extra-watching
key BACK
sleep 3

say "## Settings (guide summary)"
ensure_app
key MENU
sleep 3
shot settings
key DPAD_DOWN DPAD_DOWN DPAD_CENTER
sleep 2
shot settings-section

echo "memory at end: $(app_mem)" >> "$LOG"
kill $LOGCAT 2>/dev/null
grep -E "FATAL EXCEPTION|AndroidRuntime|CrimsonVM|CrimsonPlaylist|CrimsonEpg|CrimsonPlayer|CrimsonFeed" "$OUT/logcat.txt" | grep -v "^--" | tail -150 > "$OUT/logcat-crimson.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then say "**CRASH**: see logcat-crimson.txt"; fi
rm -f "$OUT/.focus.xml"
say "done"
