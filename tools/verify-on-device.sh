#!/usr/bin/env bash
#
# Build, install and prove the app actually runs, before it goes near the car.
#
# The unit suite and the Robolectric screenshots answer "is the logic right" and "does the layout
# fit", but neither of them ever starts the app. A missing permission, a Room migration that
# throws on a real database, a service that dies on launch — all of that passes the JVM suite and
# fails on the glass. This script is the step in between: it puts the build on a running Android,
# opens it, and refuses to report success unless the process is still alive and the log is clean.
#
#   tools/verify-on-device.sh                 debug build on whichever device is attached
#   tools/verify-on-device.sh release         the signed build that actually ships
#   tools/verify-on-device.sh debug -s <id>   pick a device when several are attached
#
# Exit status is 0 only if the app installed, launched, survived, and logged nothing fatal.
#
set -uo pipefail

cd "$(dirname "$0")/.."

PKG="in.odograph.tracker"
ACTIVITY="${PKG}/.MainActivity"
SETTLE_SECONDS=8

SDK_ADB="${HOME}/Library/Android/sdk/platform-tools/adb"
ADB="$(command -v adb || true)"
[ -x "$SDK_ADB" ] && ADB="$SDK_ADB"
[ -n "$ADB" ] || { echo "adb not found — run tools/device.sh status" >&2; exit 1; }

VARIANT="debug"
SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    debug|release) VARIANT="$1"; shift ;;
    -s) SERIAL="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }
ok()   { printf '\033[32m  ok\033[0m %s\n' "$*"; }

# ---------------------------------------------------------------- a device must exist

step "Finding a device"
ATTACHED="$("$ADB" devices | sed '1d;/^$/d' | grep -c "device$")"
if [ "$ATTACHED" = "0" ]; then
  echo
  echo "No device is attached, so nothing can be verified."
  echo "Start the emulator with   tools/emulator.sh start"
  echo "or attach a real one with tools/device.sh status"
  exit 1
fi
if [ "$ATTACHED" -gt 1 ] && [ -z "$SERIAL" ]; then
  "$ADB" devices -l | sed '1d;/^$/d' | sed 's/^/  /'
  fail "several devices are attached — choose one with -s <serial>"
fi
DEVICE_DESC="$(adb_ shell getprop ro.product.model 2>/dev/null | tr -d '\r') / API $(adb_ shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
ok "$DEVICE_DESC"

# ---------------------------------------------------------------- build

# Spelled out rather than upper-casing $VARIANT: macOS ships bash 3.2, where ${VARIANT^} is not a
# capitalisation but a parse error, and a `|| fail` on the *next* line does not catch it. Left as
# it was, this step printed "bad substitution", never ran Gradle, and then happily verified
# whatever stale APK was lying in the output directory.
case "$VARIANT" in
  debug)   TASK=":app:assembleDebug" ;;
  release) TASK=":app:assembleRelease" ;;
  *)       fail "unknown variant: $VARIANT" ;;
esac

step "Building $TASK"
./gradlew "$TASK" --console=plain -q || fail "the build failed"

APK_DIR="app/build/outputs/apk/${VARIANT}"
[ -d "$APK_DIR" ] || fail "no output directory at $APK_DIR — did $TASK really run?"
# Newest first, so a rebuild is never mistaken for a leftover from an older one.
APK="$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || fail "no APK produced under $APK_DIR"

# A build that did not actually rebuild is the failure this whole script exists to avoid.
if [ -n "$(find "$APK" -mmin +10 2>/dev/null)" ]; then
  fail "$APK is over ten minutes old — the build did not produce it"
fi
ok "$APK ($(du -h "$APK" | cut -f1))"

# ---------------------------------------------------------------- install

step "Installing"
INSTALL_OUT="$(adb_ install -r -d "$APK" 2>&1)"
if echo "$INSTALL_OUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match"; then
  echo "  signing key differs from the installed copy — removing it first"
  echo "  (this wipes the app's database on this device)"
  adb_ uninstall "$PKG" >/dev/null 2>&1
  INSTALL_OUT="$(adb_ install -r "$APK" 2>&1)"
fi
echo "$INSTALL_OUT" | grep -q "Success" || { echo "$INSTALL_OUT"; fail "install failed"; }
ok "installed"

# ---------------------------------------------------------------- permissions

# Granted up front so the app opens on its own screen rather than on a system dialog. Background
# location and notifications are refused by some builds; that is not a failure of the app.
step "Granting runtime permissions"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do
  if adb_ shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1; then
    ok "$p"
  else
    echo "  --  $p (not grantable here; harmless)"
  fi
done

# ---------------------------------------------------------------- launch

step "Launching"
adb_ logcat -c >/dev/null 2>&1 || true
adb_ shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb_ shell am start -n "$ACTIVITY" >/dev/null 2>&1 || fail "could not start $ACTIVITY"
echo "  waiting ${SETTLE_SECONDS}s for it to settle"
sleep "$SETTLE_SECONDS"

# ---------------------------------------------------------------- did it survive

step "Checking the app is still alive"
PID="$(adb_ shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
if [ -z "$PID" ]; then
  echo
  echo "--- crash buffer ---"
  adb_ logcat -d -b crash 2>/dev/null | tail -60
  echo "--- errors ---"
  adb_ logcat -d '*:E' 2>/dev/null | grep -i "odograph" | tail -40
  fail "the process died after launch"
fi
ok "running as pid $PID"

step "Scanning the log for fatal errors"
CRASH="$(adb_ logcat -d -b crash 2>/dev/null | grep -i "$PKG" || true)"
FATAL="$(adb_ logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" || true)"
if [ -n "$CRASH$FATAL" ]; then
  echo "$CRASH" | tail -30
  echo "$FATAL" | tail -30
  fail "a fatal exception was logged"
fi
ok "no fatal exceptions"

APP_ERRORS="$(adb_ logcat -d '*:E' 2>/dev/null | grep -i "odograph" | grep -v "FATAL" || true)"
if [ -n "$APP_ERRORS" ]; then
  printf '\033[33m  note\033[0m non-fatal errors were logged:\n'
  echo "$APP_ERRORS" | tail -15 | sed 's/^/    /'
fi

# ---------------------------------------------------------------- what it looks like

step "Capturing the screen"
OUT="build/device-screenshots"
mkdir -p "$OUT"
SHOT="${OUT}/${VARIANT}-$(date +%Y%m%d-%H%M%S).png"
adb_ exec-out screencap -p > "$SHOT" 2>/dev/null
if [ -s "$SHOT" ]; then
  ok "$SHOT"
else
  rm -f "$SHOT"
  printf '\033[33m  note\033[0m screen capture failed (harmless — the checks above still stand)\n'
fi

printf '\n\033[32mVERIFIED\033[0m  %s build runs on %s\n' "$VARIANT" "$DEVICE_DESC"
