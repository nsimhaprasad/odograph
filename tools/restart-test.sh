#!/usr/bin/env bash
#
# Kill the app in the middle of a drive and check the drive survives it.
#
# The recorder shares a process with the screen, so anything that kills that process mid-drive —
# memory pressure from the map, a crash, Android reclaiming the app in the background — takes the
# recorder with it. What used to happen next was worse than the kill: on the way back up the
# service closed whatever trip it found open and waited to start a fresh one, so one outing became
# two and the readout went back to zero kilometres. From the driver's seat the trip had reset
# itself, and nothing in the suite could see it, because every screen still rendered and every
# sum was still right. It was a claim about what survives a restart, and only a restart answers it.
#
#   tools/restart-test.sh          drive, kill, drive again, on the running emulator
#   tools/restart-test.sh -s <id>  pick a device
#
set -uo pipefail

cd "$(dirname "$0")/.."

PKG="in.odograph.tracker"
ACTIVITY="${PKG}/.MainActivity"
APP_PORT=8080
HOST_PORT=18080
DB="/data/data/${PKG}/databases/odograph.db"

# Same road as drive-test.sh, split in two: the first half before the kill, the second after.
# lat,lon pairs. Passed as arguments rather than array names: bash on macOS is 3.2, which has no
# namerefs, and the failure mode is a script that quietly drives nowhere and then reports the app
# recorded nothing — a false accusation that looks exactly like the bug being tested for.
BEFORE="12.97160,77.59460 12.97190,77.59490 12.97220,77.59520 12.97250,77.59550 12.97280,77.59580 12.97310,77.59610 12.97340,77.59640 12.97370,77.59670"
AFTER="12.97400,77.59700 12.97430,77.59730 12.97460,77.59760 12.97490,77.59790 12.97520,77.59820"

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
note() { printf '\033[33m  note\033[0m %s\n' "$*"; }

sql() { adb_ shell "run-as $PKG sqlite3 $DB \"$1\"" 2>/dev/null | tr -d '\r'; }

live() { curl -s -m 5 "http://127.0.0.1:${HOST_PORT}/api/live" 2>/dev/null; }
field() { live | sed -n "s/.*\"$1\":\([0-9.eE+-]*\).*/\1/p" | head -1; }

wait_for_api() {
  for _ in $(seq 1 25); do
    adb_ forward "tcp:${HOST_PORT}" "tcp:${APP_PORT}" >/dev/null 2>&1
    [ -n "$(field distanceKm)" ] && return 0
    sleep 1
  done
  return 1
}

drive() {
  n=0
  for pair in $1; do
    lat="${pair%%,*}"; lon="${pair##*,}"
    adb_ emu geo fix "$lon" "$lat" >/dev/null 2>&1
    n=$((n + 1))
    printf '  fix %2d  %s,%s\n' "$n" "$lat" "$lon"
    sleep 1
  done
}

step "Checking the app is up"
adb_ shell pidof "$PKG" >/dev/null 2>&1 || fail "$PKG is not running — install it first"
wait_for_api || fail "the app's LAN API never answered"
ok "the app is answering"

step "Clearing the history so the count means something"
adb_ shell am force-stop "$PKG" >/dev/null 2>&1
sql "delete from points; delete from trips;" >/dev/null
adb_ shell am start -n "$ACTIVITY" >/dev/null 2>&1
sleep 6
wait_for_api || fail "the app did not come back after the reset"
ok "starting from an empty history"

step "Driving the first half"
drive "$BEFORE"
sleep 6

before_km="$(field distanceKm)"
before_trips="$(sql 'select count(*) from trips;')"
open_before="$(sql 'select id from trips where endedAt is null;')"
printf '  distanceKm=%s  trips=%s  open=%s\n' "$before_km" "$before_trips" "$open_before"
awk -v d="${before_km:-0}" 'BEGIN { exit !(d > 0.1) }' \
  || fail "the drive never started — nothing to interrupt"
[ -n "$open_before" ] || fail "no trip is open, so this would not test anything"
# One departure, one trip. Fixes arrive on a thread pool, and the recording path was written as
# though only one thread ever ran it: two fixes landing together both got past the "nothing is
# open yet" check and opened a trip each, milliseconds apart, splitting one drive's points across
# the two. Only a real fix stream at a real rate produces the timing, so this is the only place it
# can be seen.
[ "$(printf '%s' "$open_before" | wc -l | tr -d ' ')" = "0" ] \
  || fail "one departure opened more than one trip: $(printf '%s' "$open_before" | tr '\n' ' ')"
ok "a drive is running: ${before_km} km on trip ${open_before}"

step "Killing the process the way low memory would"
# SIGKILL on the pid, not `am kill` and not force-stop. `am kill` politely declines to touch a
# process that is in the foreground or holding a foreground service, which this one is on both
# counts — it returns success, kills nothing, and the test then passes against code that has the
# bug. force-stop is the opposite error: it tells Android the app must stay dead, so nothing comes
# back and the restart never happens. A SIGKILL is what the low-memory killer actually sends, and
# START_STICKY is what brings the service back afterwards.
pid="$(adb_ shell pidof "$PKG" | tr -d '\r')"
[ -n "$pid" ] || fail "could not find the app's pid to kill"
adb_ shell "su 0 kill -9 $pid" >/dev/null 2>&1 || adb_ shell "kill -9 $pid" >/dev/null 2>&1
for _ in $(seq 1 20); do
  now="$(adb_ shell pidof "$PKG" | tr -d '\r')"
  [ "$now" = "$pid" ] || break
  sleep 1
done
still="$(adb_ shell pidof "$PKG" | tr -d '\r')"
[ "$still" = "$pid" ] && fail "the process survived SIGKILL — nothing was interrupted, so this run proves nothing"
ok "process $pid killed"

step "Letting it come back"
adb_ shell am start -n "$ACTIVITY" >/dev/null 2>&1
sleep 8
wait_for_api || fail "the app never came back after the kill"
ok "back up"

step "Driving the second half"
drive "$AFTER"
sleep 8

after_km="$(field distanceKm)"
after_trips="$(sql 'select count(*) from trips;')"
open_after="$(sql 'select id from trips where endedAt is null;')"
printf '  distanceKm=%s  trips=%s  open=%s\n' "$after_km" "$after_trips" "$open_after"

step "Checking the drive survived"
[ "$after_trips" = "$before_trips" ] \
  || fail "the interruption split one outing into $after_trips drives — the trip reset itself"
ok "still $after_trips drive after the kill, not $before_trips plus a new one"

[ "$open_after" = "$open_before" ] \
  || fail "trip $open_before was closed and $open_after opened in its place"
ok "the same trip $open_after is still running"

awk -v a="${after_km:-0}" -v b="${before_km:-0}" 'BEGIN { exit !(a >= b) }' \
  || fail "distance fell from ${before_km} km to ${after_km} km — the readout reset"
ok "distance carried across the restart: ${before_km} → ${after_km} km"

printf '\n\033[32mSURVIVED\033[0m  one drive of %s km through a mid-drive process kill\n' "$after_km"
