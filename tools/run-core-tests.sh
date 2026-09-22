#!/usr/bin/env bash
# Compiles and runs the pure-Kotlin core module's unit tests without Gradle.
#
# The normal way to run these is `./gradlew :core:test`. This script exists because the
# development container has no access to Maven Central or maven.google.com, so Gradle cannot
# resolve a single dependency there. kotlinc (from a GitHub release) plus the distribution's
# JUnit 4 jar is enough to compile and run the core module, which is deliberately free of
# Android and third-party dependencies for exactly this reason.
#
# Usage: tools/run-core-tests.sh [TestClassName ...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC="${KOTLINC:-/home/claude/opt/kotlinc/bin/kotlinc}"
JUNIT="${JUNIT:-/usr/share/java/junit4.jar}"
HAMCREST="${HAMCREST:-/usr/share/java/hamcrest-core.jar}"
OUT="$ROOT/build/test"

if [[ ! -x "$KOTLINC" ]]; then echo "kotlinc not found at $KOTLINC" >&2; exit 1; fi
if [[ ! -f "$JUNIT" ]]; then echo "junit4 jar not found at $JUNIT" >&2; exit 1; fi

rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"

echo "==> compiling core main"
"$KOTLINC" "$ROOT/core/src/main/kotlin" -d "$OUT/main" -nowarn 2>&1 | grep -v "^Picked up" || true

echo "==> compiling core tests"
"$KOTLINC" "$ROOT/core/src/test/kotlin" \
  -cp "$OUT/main:$JUNIT:$HAMCREST" \
  -d "$OUT/test" -nowarn 2>&1 | grep -v "^Picked up" || true

# Discover every compiled class whose name ends in Test.
mapfile -t CLASSES < <(cd "$OUT/test" && find . -name '*Test.class' -not -name '*$*' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)

if [[ $# -gt 0 ]]; then CLASSES=("$@"); fi
if [[ ${#CLASSES[@]} -eq 0 ]]; then echo "no test classes found" >&2; exit 1; fi

echo "==> running ${#CLASSES[@]} test class(es)"
KOTLIN_STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
java -cp "$OUT/main:$OUT/test:$JUNIT:$HAMCREST:$KOTLIN_STDLIB" \
  org.junit.runner.JUnitCore "${CLASSES[@]}" 2>&1 | grep -v "^Picked up"
