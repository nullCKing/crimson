#!/usr/bin/env bash
# Creates the release signing keystore, once.
#
# Writes secrets/retroguide-release.jks and secrets/keystore.properties, both gitignored.
#
# THE SAME KEY MUST BE USED FOR EVERY BUILD. Android identifies an app by its package name and its
# signing certificate together, so an APK signed with a different key will not install over an
# existing one — the user has to uninstall first, losing their settings and channel list. Back the
# .jks file up somewhere safe; if it is lost there is no way to produce an update that installs
# over what is already on the device.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS="$ROOT/secrets"
KEYSTORE="$SECRETS/retroguide-release.jks"
PROPS="$SECRETS/keystore.properties"
ALIAS="${1:-retroguide}"
VALIDITY_DAYS=10950   # 30 years

mkdir -p "$SECRETS"

if [[ -f "$KEYSTORE" ]]; then
  echo "A keystore already exists at $KEYSTORE." >&2
  echo "Not overwriting it: replacing the key would break updates for anyone who already has the" >&2
  echo "app installed. Delete it by hand if you are certain." >&2
  exit 1
fi

command -v keytool >/dev/null 2>&1 || {
  echo "keytool not found. Install a JDK 17 or 21." >&2
  exit 1
}

read -r -s -p "Choose a keystore password (needed for every release build): " PASSWORD
echo
if [[ ${#PASSWORD} -lt 6 ]]; then
  echo "A keystore password must be at least 6 characters." >&2
  exit 1
fi

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=RetroGuide, OU=RetroGuide, O=RetroGuide, L=, ST=, C=US"

# Gradle resolves storeFile relative to the project root.
cat > "$PROPS" <<EOF
storeFile=secrets/retroguide-release.jks
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF
chmod 600 "$PROPS" "$KEYSTORE"

cat <<EOF

Created:
  $KEYSTORE
  $PROPS

Both are gitignored and must never be committed.
Back up the .jks file. Losing it means no future build can update an installed app.

Now run:  ./gradlew release
EOF
