#!/usr/bin/env bash
#
# A virtual head unit shaped like the real one.
#
# The thermal box runs 1920x1080 at ~238 dpi, which is what makes its window 1291x726 dp — the
# geometry every layout decision in DriverScreen and Metrics is written against. A stock phone AVD
# is 411x891 dp in portrait and would exercise the tall arrangement forever while never touching
# the one that actually ships. So the AVD here is pinned to 1920x1080 at 240 dpi in landscape,
# which lands on 1280x720 dp: the nearest standard density bucket to the box, and within 1% of it.
#
#   tools/emulator.sh create    build the AVD (once)
#   tools/emulator.sh start     boot it and wait until it is actually usable
#   tools/emulator.sh stop      shut it down
#   tools/emulator.sh status    is it running
#   tools/emulator.sh delete    remove the AVD entirely
#
set -uo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"

# The `avdmanager` on PATH from Homebrew resolves the SDK relative to its own install prefix rather
# than to ANDROID_HOME, so against this SDK it reports "Valid system image paths are: null" however
# the environment is set. The Android CLI inside the SDK's own cmdline-tools has no such problem,
# and it is what replaced avdmanager anyway.
ANDROID_CLI="$SDK/cmdline-tools/latest/bin/android"

AVD_NAME="odograph_box"
IMAGE="system-images;android-34;google_apis;arm64-v8a"

# The CLI offers only a handful of coarse profiles. This is the closest landscape starting point,
# and every dimension that matters is overwritten below in any case.
BASE_PROFILE="medium_tablet"

# The box, as close as a standard density bucket allows.
LCD_WIDTH=1920
LCD_HEIGHT=1080
LCD_DENSITY=240

die()  { echo "error: $*" >&2; exit 1; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  ok\033[0m %s\n' "$*"; }

require_tools() {
  [ -x "$EMULATOR" ] || die "no emulator at $EMULATOR — install it with:
  yes | sdkmanager --sdk_root=\"$SDK\" 'emulator' '$IMAGE'"
  [ -x "$ANDROID_CLI" ] || die "no Android CLI at $ANDROID_CLI
  brew install --cask android-commandlinetools, then link it into this SDK:
  ln -s /opt/homebrew/share/android-commandlinetools/cmdline-tools/latest \"$SDK/cmdline-tools/latest\""
}

avd_dir() { echo "$HOME/.android/avd"; }

cmd_create() {
  require_tools
  local avd; avd="$(avd_dir)"
  if [ -d "${avd}/${AVD_NAME}.avd" ]; then
    ok "$AVD_NAME already exists (remove it with: tools/emulator.sh delete)"
    return 0
  fi

  export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"

  step "Creating a base AVD from the $BASE_PROFILE profile"
  "$ANDROID_CLI" emulator create "$BASE_PROFILE" --no-metrics >/dev/null 2>&1 \
    || die "the Android CLI could not create a device"
  [ -d "${avd}/${BASE_PROFILE}.avd" ] || die "expected ${avd}/${BASE_PROFILE}.avd"

  step "Renaming it to $AVD_NAME"
  mv "${avd}/${BASE_PROFILE}.avd" "${avd}/${AVD_NAME}.avd"
  rm -f "${avd}/${BASE_PROFILE}.ini"
  cat > "${avd}/${AVD_NAME}.ini" <<EOF
avd.ini.encoding=UTF-8
path=${avd}/${AVD_NAME}.avd
path.rel=avd/${AVD_NAME}.avd
target=android-34
EOF

  step "Shaping it like the box"
  # The CLI fetches whatever image its profile prefers — an API 35 Play Store tablet at 2560x1600.
  # Both are overridden here: the box runs API 34, and its geometry is what the layouts are written
  # against. Every key is stripped before being re-added, because a duplicate key leaves the
  # emulator reading whichever copy it happened to parse first.
  local cfg="${avd}/${AVD_NAME}.avd/config.ini"
  [ -f "$cfg" ] || die "no config.ini at $cfg"
  local tmp; tmp="$(mktemp)"
  grep -vE "^(AvdId|avd\.ini\.displayname|hw\.lcd\.width|hw\.lcd\.height|hw\.lcd\.density|hw\.initialOrientation|hw\.keyboard|hw\.ramSize|skin\.dynamic|skin\.name|skin\.path|showDeviceFrame|image\.sysdir\.1|tag\.id|tag\.ids|tag\.displaynames)=" "$cfg" > "$tmp"
  cat >> "$tmp" <<EOF
AvdId=${AVD_NAME}
avd.ini.displayname=Odograph Box (${LCD_WIDTH}x${LCD_HEIGHT} @${LCD_DENSITY}dpi)
hw.lcd.width=${LCD_WIDTH}
hw.lcd.height=${LCD_HEIGHT}
hw.lcd.density=${LCD_DENSITY}
hw.initialOrientation=landscape
hw.keyboard=yes
hw.ramSize=2048
skin.dynamic=yes
skin.name=${LCD_WIDTH}x${LCD_HEIGHT}
showDeviceFrame=no
image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/
tag.id=google_apis
tag.ids=google_apis
tag.displaynames=Google APIs
EOF
  mv "$tmp" "$cfg"

  ok "${LCD_WIDTH}x${LCD_HEIGHT} @ ${LCD_DENSITY}dpi landscape, API 34"
  ok "= $(( LCD_WIDTH * 160 / LCD_DENSITY ))x$(( LCD_HEIGHT * 160 / LCD_DENSITY )) dp, against the box's 1291x726 dp"
}

running_serial() {
  "$ADB" devices 2>/dev/null | awk '/^emulator-/ && /device$/ {print $1; exit}'
}

cmd_start() {
  require_tools
  [ -d "$(avd_dir)/${AVD_NAME}.avd" ] || cmd_create

  local existing; existing="$(running_serial)"
  if [ -n "$existing" ]; then
    ok "already running as $existing"
    return 0
  fi

  step "Booting $AVD_NAME"
  # -no-snapshot-load forces a cold boot, which is what verification wants: a restored snapshot can
  # carry a stale copy of the app and an already-migrated database, so a broken migration passes.
  #
  # Software rendering rather than the host GPU, deliberately. With -gpu auto the emulator's render
  # thread wedges after a dozen or so reinstalls: the app's main thread then blocks forever inside
  # RenderProxy::setStopped and the whole screen ANRs with not one app frame in the trace. That
  # looks exactly like a UI regression and costs an hour to prove it is not one, twice now. This
  # AVD exists to take screenshots and UI dumps, never to measure frame times, so the slower
  # renderer costs nothing that matters here.
  local log="${TMPDIR:-/tmp}/odograph-emulator.log"
  nohup "$EMULATOR" -avd "$AVD_NAME" \
    -no-snapshot-load \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    > "$log" 2>&1 &

  echo "  waiting for the device to appear ..."
  "$ADB" wait-for-device || die "the emulator never appeared — see $log"

  echo "  waiting for Android to finish booting (a cold boot takes a minute or two) ..."
  local tries=0
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    tries=$((tries + 1))
    [ "$tries" -gt 240 ] && die "boot did not complete in four minutes — see $log"
    sleep 1
  done

  # Animations make a screenshot taken straight after launch unreliable.
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$s" 0 >/dev/null 2>&1 || true
  done

  ok "booted as $(running_serial)"
  echo
  echo "Now verify a build against it:  tools/verify-on-device.sh debug"
}

cmd_stop() {
  local s; s="$(running_serial)"
  [ -n "$s" ] || { ok "nothing running"; return 0; }
  "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
  ok "stopped $s"
}

cmd_status() {
  local s; s="$(running_serial)"
  if [ -n "$s" ]; then
    echo "running: $s"
    echo "  model    $("$ADB" -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    echo "  api      $("$ADB" -s "$s" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
    echo "  $("$ADB" -s "$s" shell wm size 2>/dev/null | tr -d '\r')"
    echo "  $("$ADB" -s "$s" shell wm density 2>/dev/null | tr -d '\r')"
  else
    echo "not running"
  fi
  echo
  echo "AVDs:"
  ls "$(avd_dir)" 2>/dev/null | grep '\.ini$' | sed 's/\.ini$//' | sed 's/^/  /' || echo "  (none)"
}

cmd_delete() {
  cmd_stop
  rm -rf "$(avd_dir)/${AVD_NAME}.avd" "$(avd_dir)/${AVD_NAME}.ini"
  ok "deleted $AVD_NAME"
}

case "${1:-status}" in
  create) cmd_create ;;
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  delete) cmd_delete ;;
  *)      sed -n '3,18p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
