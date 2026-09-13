# Odograph

A trip recorder and EV energy tracker that lives permanently in a car — an old Android phone
running this app is the box that turns the car into a surveyed vehicle.

Built initially for an MG Windsor EV, Odograph records every drive on-device, tracks how many
kilowatt-hours and rupees each drive actually costs, and reads live battery state from the car
itself — all without a sim, a cloud, or an account. Cookies: none. Data lives in the car.

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## What it does

- **Records every drive** — a foreground service logs GPS traces, speed, and per-trip stats
  (distance, moving time, max/avg speed, elevation gain and descent) and keeps recording while
  Maps or Spotify are in front. Starts itself on ignition.
- **Battery honesty** — polls the MG iSMART servers on a polite schedule and shows live charge %,
  range-at-full, and real km/kWh on the driving screen. No MCU taps, no OBD dongle.
- **Rupees per drive** — each drive shows start→end charge, kWh consumed, and the cost at the
  your own tariff; after a fast charge it even asks what the bill was, so the numbers stay true.
- **Charging diary** — a CHARGE screen separates fast home fills from slow charges, logs every
  session (power, energy, price), and distinguishes a real fast charger from a surge: a 11 kW
  spike at home is still a slow charge if the rest of the session says so.
- **Offline-first dashboard** — the box serves `http://<box-ip>:8080` over the car's hotspot:
  charts, a trip planner priced from your real consumption, raw MG frame dumps, and a
  self-contained `/archive.html` you save once and open on a laptop forever after.
- **Data that follows you** — trip CSV, per-trip GPX, share-sheet export over Bluetooth, plus an
  *optional* Google Apps Script webhook that accumulates drives in a sheet you own (a capability
  URL, not a credential).

## Screens

| Tab | Purpose |
|---|---|
| DRIVE | Analog speed gauge, speed-limit alerts, live SOC, range, and real km/kWh |
| TRIPS | Every drive, newest first: route map, elevation, start→end charge, kWh, cost |
| ROUTES | Most-driven routes and places, tap-to-name them |
| CHARGE | Charge diary: fast/slow sessions, kWh, tariffs, GST, total spend |
| SETUP | Probe readout, share/export, speed-alert setup |

## How it's built

- **Kotlin / Jetpack Compose**, single-`Activity` UI, no navigation framework — the five tabs are
  five composables.
- **Room** database; the 1→2 schema migration is tested against exported DDL, not hand-written
  ALTERs.
- **Ktor (CIO)** embedded server and **NSD/Bonjour** — the dashboard shows up as `Odograph` in
  Safari without hunting for an IP.
- **osmdroid** renders routes as vectors; every map is clipped and staged the same way for testing.
- **windsor-telematics** (sibling repo, compose-included as `../windsor-telematics`) is the
  reverse-engineered MG iSMART TAP client that speaks the car's binary protocol and decodes each
  frame byte-for-byte against golden captures from a real vehicle.
- **Robolectric** unit tests that run the actual Compose, Room, and network code on a JVM — no
  emulator — plus a screenshot renderer that treats the UI as a build tool.

### Repo layout

```
app/                         the Android app
  src/main/java/in/odograph/tracker/
    record/    foreground service, GPS + telemetry loops, charge ledger
    data/      Room entities, DAO, migrations
    core/      battery math, trip stats, geo
    ui/        compose screens (driver, trips, routes, charge, setup)
    server/    embedded dashboard + raw MG frame capture
    export/    CSV / GPX / share intent
    sync/      optional Apps-Script webhook
    diag/      crash breadcrumbs
  src/test/    220+ unit tests incl. full UI rendering, migrations, ledger
docs/
  INSTALL.md        building, installing, permissions, dashboard access
  APPS_SCRIPT.md    optional Google-Sheet webhook script
```

## Build

Requires a JDK 17 and an Android SDK (path in `local.properties`).

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # signed with your keystore.properties
```

The MG client must sit next to this repo at `../windsor-telematics` (the composite build keeps
you pinned to its exact code and runs its golden decoders on every build).

## Install

Point a box at the APK — ADB over WiFi or a file manager on a USB stick — grant
location-always, notifications, and a battery-optimisation exemption, then open
`http://<box-ip>:8080/config` from a laptop to paste any optional webhook URL. The full ladder of
"won't auto-start after ignition" vendor quirks is in [`docs/INSTALL.md`](docs/INSTALL.md).

## Tests

```bash
./gradlew :app:testDebugUnitTest                    # the default suite (no screenshots)
./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # render screens
```

Screenshots are intentionally excluded from the default run: they render the UI with Robolectric's
native graphics runtime, which loads once per JVM and must not share a process with legacy-graphics
tests. Run them with the `screenshots` flag when you touch layout.

## Privacy

- Everything is on the box. The only outbound calls are the optional webhook and the MG iSMART
  poller (your own car, your own account).
- Telegram-style: the dashboard and `/frames` page are served on your LAN. Raw MG frames echo the
  account's session tokens, so treat the `/frames` URL as a password and never paste it anywhere.
- The webhook is a capability token — one revocable URL, no OAuth, no Play Services, no stored
  credential on a device that lives in a car.

## Contributing

Open an issue before sending a pull request — much of this app is the shape of one person's
car, and the fights worth having are about design, not style. If a change touches the decoder,
run the sibling `windsor-telematics` golden tests too, since a bytes-literal frame is the one
thing a regression test will not warn you about.

## License

[MIT](LICENSE) &copy; 2026 nsimhaprasad