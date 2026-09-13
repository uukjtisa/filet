#!/usr/bin/env bash
# The host half of the M9 "browser with no Filet installed" gate.
#
# Run this, then run NearbyExternalClientTest. The test starts Filet's LAN server on the
# phone and writes a session file; this reads it, reaches the phone through `adb forward`,
# and behaves like a browser: load the page, POST the PIN, read the listing, download a file.
# Nothing here knows anything about Filet beyond its HTTP surface.
#
#   ./tools/nearby-client.sh <serial>
set -uo pipefail

ADB="${ADB:-D:/Android-SDK/platform-tools/adb.exe}"
SERIAL="${1:?usage: nearby-client.sh <adb serial>}"
# Matches Gates.DEVICE_PATH in the instrumented tests. Run this alongside
# tools/run-gates.sh, which grants the app the storage access this folder needs.
GATES=/storage/emulated/0/Download/filet-gates
HOST_PORT="${HOST_PORT:-18400}"

dev() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" shell "$@"; }

dev "mkdir -p $GATES && : > $GATES/nearby-client-ready.txt"
dev "rm -f $GATES/nearby-session.txt $GATES/nearby-done.txt"
echo "waiting for the device to start sharing..."

for _ in $(seq 1 240); do
    raw="$(dev "cat $GATES/nearby-session.txt 2>/dev/null" | tr -d '\r')"
    [ -n "$raw" ] && break
    sleep 0.5
done
[ -n "$raw" ] || { echo "the test never started a server"; dev "rm -f $GATES/nearby-client-ready.txt"; exit 1; }

port="$(echo "$raw"  | sed -n 's/^port=//p')"
pin="$(echo "$raw"   | sed -n 's/^pin=//p')"
token="$(echo "$raw" | sed -n 's/^token=//p')"
name="$(echo "$raw"  | sed -n 's/^name=//p')"
echo "device is serving on :$port, pin $pin, offering $name"

"$ADB" -s "$SERIAL" forward "tcp:$HOST_PORT" "tcp:$port" >/dev/null
base="http://127.0.0.1:$HOST_PORT"
jar="$(mktemp)"

# 1. The gate page, with no PIN - it has to be reachable or there is nowhere to type one.
page_status="$(curl -s -o /dev/null -w '%{http_code}' "$base/")"

# 2. Unlock, keeping the session cookie exactly as a browser would.
curl -s -c "$jar" -X POST -d "{\"pin\":\"$pin\"}" "$base/api/unlock" >/dev/null

# 3. The listing.
listing="$(curl -s -b "$jar" "$base/api/list")"
listed=""
echo "$listing" | grep -q "$name" && listed="$name"

# 4. The download itself.
status="$(curl -s -b "$jar" -o /dev/null -w '%{http_code}' "$base/f/$token")"
body="$(curl -s -b "$jar" "$base/f/$token")"

report="page=$page_status
status=$status
listed=$listed
body<<<$body>>>"
echo "$report"

printf '%s\n' "$report" | dev "cat > $GATES/nearby-done.txt"
dev "rm -f $GATES/nearby-client-ready.txt"
"$ADB" -s "$SERIAL" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1
rm -f "$jar"
