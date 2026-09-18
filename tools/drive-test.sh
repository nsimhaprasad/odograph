#!/usr/bin/env bash
#
# Drive the emulator down a road and check the app actually recorded it.
#
# This exists because verify-on-device.sh does not. That script asks whether the app is alive, has
# not crashed, and can be photographed — and a trip recorder that records nothing passes all three.
# A release once went out where every telematics poll closed the open drive, so distance, time and
# moving time sat at zero for an entire real journey while the speedometer looked perfect. Nothing
# in the suite could have caught it: the arithmetic was all correct and all tested. What was wrong
# was a claim about the world, and only a moving car can answer those.
#
# So this moves a car. GPS fixes are injected into the emulator along a real route, and the app is
# asked over its own LAN API what it thinks happened. If the kilometres do not climb, it fails.
#
#   tools/drive-test.sh            drive the default route on the running emulator
#   tools/drive-test.sh -s <id>    pick a device
#
set -uo pipefail

cd "$(dirname "$0")/.."

PKG="in.odograph.tracker"
APP_PORT=8080
HOST_PORT=18080

# Bangalore, heading north-east out of town. Spaced about forty metres apart, so at one fix a
# second the car is doing a plausible 140 km/h rather than the 1,800 km/h that half-a-kilometre
# hops imply — a speed the recorder is quite right to treat as noise.
ROUTE_LAT=(12.97160 12.97190 12.97220 12.97250 12.97280 12.97310 12.97340 12.97370 12.97400 12.97430 12.97460 12.97490 12.97520 12.97550 12.97580)
ROUTE_LON=(77.59460 77.59490 77.59520 77.59550 77.59580 77.59610 77.59640 77.59670 77.59700 77.59730 77.59760 77.59790 77.59820 77.59850 77.59880)

# The app needs several moving fixes before it will open a trip at all, by design.
FIX_INTERVAL_S=1
SETTLE_S=8

SDK_ADB="${HOME}/Library/Android/sdk/platform-tools/adb"
ADB="$(command -v adb || true)"
[ -x "$SDK_ADB" ] && ADB="$SDK_ADB"
[ -n "$ADB" ] || { echo "adb not found" >&2; exit 1; }

SERIAL=""
[ "${1:-}" = "-s" ] && SERIAL="${2:-}"
adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }
ok()   { printf '\033[32m  ok\033[0m %s\n' "$*"; }

step "Checking the app is up"
adb_ shell pidof "$PKG" >/dev/null 2>&1 || fail "$PKG is not running — install it first"
adb_ forward "tcp:${HOST_PORT}" "tcp:${APP_PORT}" >/dev/null 2>&1 \
  || fail "could not forward the app's LAN port"

live() { curl -s -m 5 "http://127.0.0.1:${HOST_PORT}/api/live" 2>/dev/null; }
field() { live | sed -n "s/.*\"$1\":\([0-9.eE+-]*\).*/\1/p" | head -1; }

# Wait for the server rather than probing once. It binds a moment after the app starts, so a
# single probe straight after an install fails against an app that is perfectly healthy — which it
# did, and reported as a broken LAN server.
probe=""
for _ in $(seq 1 20); do
  probe="$(live)"
  [ -n "$probe" ] && break
  sleep 1
done
[ -n "$probe" ] || fail "the app's API did not answer on :${APP_PORT} after 20s — is the LAN server enabled?"
ok "API answering"

step "Granting location and starting from a standstill"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb_ shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1 || true
done
# Several fixes at one spot: the recorder must NOT open a trip for a parked car.
for _ in 1 2 3 4 5; do
  adb_ emu geo fix "${ROUTE_LON[0]}" "${ROUTE_LAT[0]}" >/dev/null 2>&1
  sleep "$FIX_INTERVAL_S"
done
sleep 3
PARKED_KM="$(field distanceKm)"
ok "parked distance reads ${PARKED_KM:-0} km"

step "Driving ${#ROUTE_LAT[@]} fixes east"
for i in "${!ROUTE_LAT[@]}"; do
  adb_ emu geo fix "${ROUTE_LON[$i]}" "${ROUTE_LAT[$i]}" >/dev/null 2>&1
  printf '  fix %2d  %s,%s\n' "$((i + 1))" "${ROUTE_LAT[$i]}" "${ROUTE_LON[$i]}"
  sleep "$FIX_INTERVAL_S"
done
echo "  settling ${SETTLE_S}s"
sleep "$SETTLE_S"

step "Asking the app what it recorded"
DIST_KM="$(field distanceKm)"
MOVING_S="$(field movingS)"
ELAPSED_S="$(field elapsedS)"
ODO_KM="$(field odoKm)"
echo "  distanceKm=${DIST_KM:-?}  movingS=${MOVING_S:-?}  elapsedS=${ELAPSED_S:-?}  odoKm=${ODO_KM:-?}"

# The field names are the API's, not the LiveState's. Getting this wrong once already produced a
# confident red FAIL against an app that was recording perfectly well, which is its own lesson:
# a check that cannot parse its own evidence is worse than no check.
[ -n "$DIST_KM" ] || fail "could not read distanceKm from /api/live — the check is broken, not necessarily the app"

# The route above is roughly half a kilometre. Anything under a tenth is not a recorded drive.
awk -v d="${DIST_KM:-0}" 'BEGIN { exit !(d > 0.1) }' \
  || fail "only ${DIST_KM:-0} km recorded — the drive was not captured.
    This is the check that would have caught the release where every telematics poll
    closed the open trip: the app looked perfectly healthy and recorded nothing."
ok "distance climbed to ${DIST_KM} km"

awk -v m="${MOVING_S:-0}" 'BEGIN { exit !(m > 0) }' \
  || fail "moving time never advanced — the app saw the fixes but never counted them as motion"
ok "moving time ${MOVING_S}s"

awk -v e="${ELAPSED_S:-0}" 'BEGIN { exit !(e > 0) }' \
  || fail "elapsed time never advanced — no trip was open to measure it against"
ok "elapsed ${ELAPSED_S}s"

printf '\n\033[32mRECORDED\033[0m  %s km over %ss moving, %ss elapsed\n' "$DIST_KM" "$MOVING_S" "$ELAPSED_S"
