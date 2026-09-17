#!/usr/bin/env bash
#
# Find, connect to and report on an Odograph target device.
#
# The box and the phone live on the house network and this laptop is not always on it, so the
# usual failure is not a broken adb setup but a route that does not exist. This script says which
# of those it is, because the two look identical from `adb devices` — an empty list.
#
#   tools/device.sh status              what adb can see, and why it can see nothing
#   tools/device.sh connect <host[:port]>   attach a device already listening (default port 5555)
#   tools/device.sh pair <host:port> <code> Android 11+ wireless pairing, code from the phone
#   tools/device.sh usb                 turn a USB-attached device into a network one
#   tools/device.sh discover            hunt for adb on this LAN, by mDNS and by port scan
#
set -uo pipefail

SDK_ADB="${HOME}/Library/Android/sdk/platform-tools/adb"
ADB="$(command -v adb || true)"
[ -x "$SDK_ADB" ] && ADB="$SDK_ADB"

if [ -z "$ADB" ]; then
  echo "adb not found. Install it with:  brew install --cask android-platform-tools" >&2
  exit 1
fi

ADB_PORT_DEFAULT=5555

die() { echo "error: $*" >&2; exit 1; }

# Everything the laptop can tell us about why a device is or is not reachable.
cmd_status() {
  echo "adb:      $ADB"
  echo "version:  $("$ADB" version | sed -n 2p)"
  echo
  echo "devices:"
  "$ADB" devices -l | sed '1d;/^$/d' | sed 's/^/  /' || true
  local count
  count="$("$ADB" devices | sed '1d;/^$/d' | wc -l | tr -d ' ')"
  if [ "$count" = "0" ]; then
    echo "  (none)"
  fi
  echo
  echo "this laptop:"
  local ip gw
  ip="$(ipconfig getifaddr en0 2>/dev/null || echo '?')"
  gw="$(route -n get default 2>/dev/null | awk '/gateway/ {print $2}')"
  echo "  address   ${ip}"
  echo "  gateway   ${gw:-?}"
  echo
  if [ "$count" = "0" ]; then
    cat <<'HINT'
Nothing is attached. In order of likelihood:

  1. The laptop is on a different network from the device. Compare the gateway
     above with the device's own Wi-Fi address — if the first three octets differ,
     no adb setup will help until they are on the same LAN or a VPN joins them.
  2. Wireless debugging was never enabled, or the device rebooted since it was.
     It does not survive a reboot. Re-run `tools/device.sh usb` over a cable, or
     pair afresh from Developer options > Wireless debugging.
  3. The device is attached by USB but has not authorised this computer. Look for
     the "Allow USB debugging?" dialog on its screen.
HINT
  fi
}

# Attach to a device that is already listening on a TCP port.
cmd_connect() {
  local target="${1:-}"
  [ -n "$target" ] || die "usage: tools/device.sh connect <host[:port]>"
  case "$target" in
    *:*) ;;
    *) target="${target}:${ADB_PORT_DEFAULT}" ;;
  esac

  local host="${target%%:*}"
  echo "checking route to ${host} ..."
  if ! ping -c 1 -W 1500 "$host" >/dev/null 2>&1; then
    echo "  ${host} does not answer a ping."
    echo "  It is on another network, powered down, or blocking ICMP. Trying adb anyway."
  fi

  echo "connecting to ${target} ..."
  "$ADB" connect "$target" || die "adb connect failed"
  "$ADB" devices -l | sed '1d;/^$/d' | sed 's/^/  /'
}

# Android 11+ pairing. The code and its port are shown on the device, under
# Developer options > Wireless debugging > Pair device with pairing code, and both
# change every time that dialog is opened.
cmd_pair() {
  local target="${1:-}" code="${2:-}"
  [ -n "$target" ] && [ -n "$code" ] || die "usage: tools/device.sh pair <host:port> <code>"
  echo "pairing with ${target} ..."
  "$ADB" pair "$target" "$code" || die "pairing failed — the code and port both expire quickly, so re-read them from the device"
  echo
  echo "Paired. Now connect on the *debugging* port, which differs from the pairing port:"
  echo "  tools/device.sh connect <host>:<port from the Wireless debugging screen>"
}

# Promote a cable-attached device to a network one, so the cable can go away.
cmd_usb() {
  echo "waiting for a USB device (authorise the prompt on its screen if one appears) ..."
  "$ADB" wait-for-device || die "no device appeared"

  local serial ip
  serial="$("$ADB" devices | sed '1d;/^$/d' | head -1 | cut -f1)"
  echo "attached: ${serial}"

  ip="$("$ADB" -s "$serial" shell ip route 2>/dev/null | awk '/src/ {for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -1 | tr -d '\r')"
  [ -n "$ip" ] || die "the device reports no Wi-Fi address — connect it to Wi-Fi first"

  echo "device address: ${ip}"
  "$ADB" -s "$serial" tcpip "$ADB_PORT_DEFAULT" || die "could not switch to TCP mode"
  sleep 2
  "$ADB" connect "${ip}:${ADB_PORT_DEFAULT}" || die "could not connect over the network"
  echo
  echo "The cable can come out now. This lasts until the device reboots."
  echo "Reconnect later with:  tools/device.sh connect ${ip}"
}

# Look for anything adb-shaped on the current LAN.
cmd_discover() {
  local ip subnet
  ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
  [ -n "$ip" ] || die "this laptop has no en0 address"
  subnet="${ip%.*}"

  echo "mDNS (Android 11+ wireless debugging advertises itself here):"
  "$ADB" mdns services 2>/dev/null | sed '1d' | sed 's/^/  /' | grep -v '^  *$' || true
  echo

  echo "scanning ${subnet}.0/24 for adb on port ${ADB_PORT_DEFAULT} ..."
  # Hits are written to a file rather than counted in a variable: each probe runs in its own
  # subshell to get the parallelism, and a subshell cannot raise a count in its parent, so a
  # counter here would report "nothing found" however many devices answered.
  local hits; hits="$(mktemp)"
  for i in $(seq 1 254); do
    ( nc -z -G 1 -w 1 "${subnet}.${i}" "$ADB_PORT_DEFAULT" 2>/dev/null \
        && echo "${subnet}.${i}:${ADB_PORT_DEFAULT}" >> "$hits" ) &
  done
  wait

  if [ -s "$hits" ]; then
    sed 's/^/  found /' "$hits"
    echo
    echo "Attach one with:  tools/device.sh connect <address>"
  else
    echo "  (nothing advertising adb on this network)"
  fi
  rm -f "$hits"
}

case "${1:-status}" in
  status)   cmd_status ;;
  connect)  shift; cmd_connect "$@" ;;
  pair)     shift; cmd_pair "$@" ;;
  usb)      cmd_usb ;;
  discover) cmd_discover ;;
  *)        sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//' ; exit 1 ;;
esac
