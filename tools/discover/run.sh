#!/usr/bin/env bash
# Compiles and runs the discovery tool against a live Xtream server.
#
# Reads secrets/xtream.json if it exists, otherwise falls back to the local mock server
# (tools/mock-xtream/server.py). Writes reports/discovery.md.
#
# Usage: tools/discover/run.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KOTLINC="${KOTLINC:-/home/claude/opt/kotlinc/bin/kotlinc}"
OUT="$ROOT/build/discover"

if [[ ! -x "$KOTLINC" ]]; then
  echo "kotlinc not found at $KOTLINC. Set KOTLINC, or run ./gradlew :tools:discover:run" >&2
  exit 1
fi

mkdir -p "$OUT"
echo "==> compiling core"
"$KOTLINC" "$ROOT/core/src/main/kotlin" -d "$OUT/core" -nowarn 2>&1 | grep -v "^Picked up" || true

echo "==> compiling discover"
"$KOTLINC" "$ROOT/tools/discover/Discover.kt" -cp "$OUT/core" -d "$OUT/tool" -nowarn 2>&1 \
  | grep -v "^Picked up" || true

echo "==> running"
KOTLIN_STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
java -cp "$OUT/core:$OUT/tool:$KOTLIN_STDLIB" com.crimson.tools.Discover "$ROOT" 2>&1 \
  | grep -v "^Picked up"
