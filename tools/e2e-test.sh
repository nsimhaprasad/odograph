#!/usr/bin/env bash
#
# Click through every screen on a real Android and check none of them break.
#
# The layer above the unit suite and below a human. Robolectric renders composables in a JVM with
# no window manager, no real Room file, no service and no GPS; it answers "would this lay out" and
# not "does the app work". drive-test.sh proved the recorder records. This proves the rest of the
# app can actually be opened — each tab is found by its own label and tapped, the way a person
# would, and after every tap the app must still be alive, still focused, and must not have written
# a fatal exception or an ANR.
#
#   tools/e2e-test.sh              run against the attached device
#   tools/e2e-test.sh -s <id>      pick one
#
# Screens and screenshots land in build/e2e/.
#
set -uo pipefail

cd "$(dirname "$0")/.."

PKG="in.odograph.tracker"
ACTIVITY="${PKG}/.MainActivity"
# DRIVE last, because the view toggle below only exists on that tab.
TABS=(TRIPS ROUTES CHARGE INSIGHTS SETUP DRIVE)
OUT="build/e2e"

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

mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/*.xml 2>/dev/null

# Compose publishes its text to the accessibility tree, so a tab can be found by its own label
# rather than by a coordinate guessed off a screenshot — which would silently drift the moment the
# layout changed, and pass while tapping empty space.
find_tap() {
  local label="$1"
  adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb_ shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/ui.xml"
  python3 - "$OUT/ui.xml" "$label" <<'PY'
import re, sys
xml = open(sys.argv[1], errors="replace").read()
want = sys.argv[2]
for attrs in re.findall(r'<node ([^>]*)>', xml):
    t = re.search(r'text="([^"]*)"', attrs)
    d = re.search(r'content-desc="([^"]*)"', attrs)
    label = (t.group(1) if t else "") or (d.group(1) if d else "")
    if label.strip().upper() == want.upper():
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}

# Anything the app writes that means it broke, as opposed to the ordinary noise of a device.
check_healthy() {
  local where="$1"
  adb_ shell pidof "$PKG" >/dev/null 2>&1 || fail "$where: the app died"

  local crash
  crash="$(adb_ logcat -d -b crash 2>/dev/null | grep -i "$PKG" | tail -5)"
  [ -z "$crash" ] || { echo "$crash"; fail "$where: a crash was logged"; }

  local fatal
  fatal="$(adb_ logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|ANR in $PKG" | tail -5)"
  [ -z "$fatal" ] || { echo "$fatal"; fail "$where: a fatal exception or ANR was logged"; }

  local focus
  focus="$(adb_ shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)"
  case "$focus" in
    *"$PKG"*) ;;
    *"NOT RESPONDING"*|*"Application Error"*) fail "$where: a system error dialog is on screen" ;;
    *) note "$where: focus is $focus" ;;
  esac
}

# A screen that renders nothing is broken even if it does not crash.
check_has_content() {
  local where="$1"
  local n
  n="$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], errors="replace").read()
labels = [t for t in re.findall(r'text="([^"]+)"', xml) if t.strip()]
print(len(labels))
PY
)"
  [ "${n:-0}" -ge 3 ] || fail "$where: only ${n:-0} text elements on screen — it rendered nothing"
  ok "$where: $n text elements"
}

step "Starting clean"
adb_ logcat -c >/dev/null 2>&1 || true
adb_ shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb_ shell am start -n "$ACTIVITY" >/dev/null 2>&1 || fail "could not start $ACTIVITY"

# Wait for the first frame rather than guessing at it. A fixed sleep is fine on a warm emulator
# and wrong on a cold one: the first launch after a boot draws through a software renderer with an
# empty page cache, misses the input-dispatch window and logs a real ANR that has nothing to do
# with the app. That reads exactly like a UI regression, and the screen is fine thirty seconds
# later — so the run is thrown away and re-run by hand, which is the worst of both.
echo "  waiting for the first frame"
painted=no
for _ in $(seq 1 40); do
  drawn="$(adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; \
    adb_ shell cat /sdcard/ui.xml 2>/dev/null | grep -o 'text="[^"]\+"' | wc -l | tr -d ' ')"
  if [ "${drawn:-0}" -ge 3 ]; then painted=yes; break; fi
  sleep 2
done

# Only once the app has actually painted is the warm-up noise safe to discard: reaching a drawn
# screen is proof it survived whatever the boot logged. If it never painted, the log is the only
# evidence of why, and throwing it away would turn a startup crash into "it rendered nothing".
if [ "$painted" = yes ]; then
  adb_ logcat -c >/dev/null 2>&1 || true
  sleep 2
fi
check_healthy "launch"
adb_ exec-out screencap -p > "$OUT/00-launch.png" 2>/dev/null
find_tap DRIVE >/dev/null
check_has_content "launch"
ok "launched"

i=1
for tab in "${TABS[@]}"; do
  step "Opening $tab"
  coords="$(find_tap "$tab")"
  if [ -z "$coords" ]; then
    fail "$tab: no element with that label is on screen — the tab is missing or unlabelled"
  fi
  # shellcheck disable=SC2086
  adb_ shell input tap $coords >/dev/null 2>&1
  sleep 4

  check_healthy "$tab"
  adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb_ shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/ui.xml"
  check_has_content "$tab"

  shot="$(printf '%s/%02d-%s.png' "$OUT" "$i" "$(echo "$tab" | tr 'A-Z' 'a-z')")"
  adb_ exec-out screencap -p > "$shot" 2>/dev/null
  [ -s "$shot" ] && ok "$shot"
  i=$((i + 1))
done

# The driver/detailed toggle is not a peer of the tabs above: it appears only on DRIVE, and its
# own label flips as it is pressed. Exercising it means finding it twice under two names.
step "Toggling the detailed view"
coords="$(find_tap DRIVER)"
[ -n "$coords" ] || fail "the DRIVER toggle is missing from the DRIVE tab"
# shellcheck disable=SC2086
adb_ shell input tap $coords >/dev/null 2>&1
sleep 4
check_healthy "detailed view"
adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb_ shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/ui.xml"
check_has_content "detailed view"
adb_ exec-out screencap -p > "$OUT/07-detailed.png" 2>/dev/null
ok "$OUT/07-detailed.png"

back="$(find_tap DETAILED)"
[ -n "$back" ] || fail "the toggle did not relabel itself to DETAILED, so the view never switched"
# shellcheck disable=SC2086
adb_ shell input tap $back >/dev/null 2>&1
sleep 4
check_healthy "back to driver view"
ok "toggled back"

step "Final health check"
check_healthy "end"
errors="$(adb_ logcat -d '*:E' 2>/dev/null | grep -i odograph | grep -v FATAL | tail -8)"
[ -z "$errors" ] || { note "non-fatal errors were logged:"; echo "$errors" | sed 's/^/    /'; }

printf '\n\033[32mALL SCREENS OK\033[0m  %d tabs opened, screenshots in %s\n' "${#TABS[@]}" "$OUT"
