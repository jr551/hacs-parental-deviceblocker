#!/bin/sh
# Runs inside the Android build image. Signing material is mounted as secrets.
set -eu

set -a
# shellcheck disable=SC1091
. /run/secrets/parental_device_blocker_signing
set +a

test -n "${SIGNING_KEY_ID:-}"
export PDB_STORE_FILE=/run/secrets/parental_device_blocker_keystore

gradle --no-daemon assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
aapt=$(find /opt /usr -path '*/build-tools/*/aapt' -type f 2>/dev/null | head -1)
test -n "$aapt"
"$aapt" dump badging "$apk" > /tmp/badging.txt
if grep -q application-debuggable /tmp/badging.txt; then
  echo "Refusing to publish a debuggable APK." >&2
  exit 1
fi
grep -q "package: name='lol.rowe.blocker' " /tmp/badging.txt
