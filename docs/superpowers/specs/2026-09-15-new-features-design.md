# Odograph — Places, Pricing, Insights, Backup, Web App

**Status:** approved (conversation, 2026-09-15)
**Target device:** Android car box (no SIM), internet via the driver's always-on phone hotspot.
**Topology (established):** the phone hotspot is the permanent LAN. The Android box in the car, the
driver's phone, and the laptop all join it together. The box is therefore always reachable over the
hotspot network by both the laptop and any future phone app — no cellular reach back to the car, but
"remote" access is unnecessary because the hotspot travels with the box.

---

## 1. Context recap — what already exists

- **Automatic pricing is already exactly the desired rule.** `ChargeLedger` closes each session by
  power evidence: a majority of readings ≥ 10 kW classifies the session FAST (outside rate, default
  ₹25/kWh), otherwise SLOW (home rate, default ₹8/kWh). A slow session never prompts.
- **Manual price editing already exists.** Tapping any closed session in the CHARGING tab opens the
  price dialog (tariff + GST or a total bill; bill wins). The LAN dashboard `/config` edits the two
  default rates and capacity.
- **Places already exist and trips already assign to them.** `PlaceResolver` assigns every trip
  endpoint to the nearest place within 150 m (greedy-leader, running-mean centroid) and increments
  visits; trips get `startPlaceId`/`endPlaceId`. `PlaceNamer` reverse-geocodes unlabelled places via
  OSM Nominatim with a policy-clean agent and ≤1 req/s. A user label always beats the geocoded name.
- **The battery poller writes one `battery` row per MG charge frame**, with SOC, charging, power,
  range, working voltage/current. While parked, rows carry `tripId = -1` so they are invisible to
  trip-scoped queries but never lost.
- **Google Sheets sync is incremental** (watermarked per-entity: trips/charges/days) and the bundled
  Apps Script appends idempotently and rebuilds Analytics from the workbook's own history.
- **Dashboard** exists as generated HTML over Ktor (`:8080`, mDNS `odograph.local`).

This spec adds five workstreams on top of that, all in the app (per user decision) with the LAN
surface gaining only the web app + backup/restore endpoints.

## 2. Scope and non-goals

| # | Workstream | Surfaces |
|---|---|---|
| W1 | Named places via map search | App (new PLACES screen) |
| W2 | Persistent price prompts + richer MG capture | App, service, DB migration |
| W3 | INSIGHTS tab: cost, range@100, routes, drain | App (new INSIGHTS tab) |
| W4 | Backup / restore | App (SETUP) + LAN web app |
| W5 | Web app (SPA) + phone REST API | LAN web app |

**Non-goals:** OBD-II; cloud relay; navigation; changing the Sheets archive model; a written phone
app (that app consumes the `/api/*` endpoints defined here, implementation is later).

---

## W1 — Named places via map search

### Goal
Let the driver create named places ("Home", "Office", "Gym") by *searching* — like Google Maps —
and pin each on a map. Once a place exists, trips that start/end within 150 m of it automatically
count as visits (existing `PlaceResolver` path), so frequent destinations are tracked on day one
instead of after many unnamed visits.

### Design
- New **PLACES screen** in the app, opened from a "PLACES" button on the ROUTES tab (no sixth tab).
- Search box → **forward geocode** via OSM Nominatim (`/search?q=...&format=json`) with the same
  identifying `User-Agent` and ≤1 req/s discipline as `PlaceNamer`. Results listed as
  `Name · lat, lon`.
- Picking a result shows it **pinned on the existing osmdroid `RouteMap`** (centered, draggable not
  required — tap-to-place on a selected search result: pin at result coordinates, tap "USE THIS
  LOCATION"). A name field defaults to the result's short name, editable to "Home"/"Office"/etc.
- Saving **creates a `places` row** with the pinned lat/lon, the user label, and `visits = 0`. If the
  exact result coordinates already fall within 150 m of an existing place, that place is *re-labelled
  and re-positioned* instead of duplicated.
- The screen lists existing places (`displayName`, visits, label) each tappable to **rename,
  re-search, or move the pin**. Non-user places created by clustering appear too, so the driver can
  promote any of them into a named place.

### New/changed code
- `geocode/PlaceNamer.kt`: add `suspend fun search(query: String): List<GeocodedSuggestion>`.
- `ui/PlacesScreen.kt`: list + search + pin + save/edit; reuses `RouteMap` and the app's
  remember-based metrics pattern.
- `data/OdographDao.kt`: add `placesNear(lat, lon)`, `insertPlace` already exists,
  `setPlaceLabel`, `updatePlacePosition` already exist; add a replace-in-place helper.
- No DB migration (places table unchanged).
- Tests: `PlaceNamerTest` forward-geocode parsing; `PlacesScreenTest` for the promote/near-duplicate
  logic via DAO.

### Error handling
- No network → "Search needs internet on the hotspot" and the list of existing places still shows
  (rename/pin-move still usable offline if coordinates are typed or an existing name is used).
- Nominatim rate/quota → back off one poll cycle (a 1 s floor), surface "try again".

---

## W2 — Persistent price prompts and richer MG capture

### Goal
A fast charge the driver didn't price at the moment (they locked the car and left it on a public
charger) must not be lost: the prompt reappears at the **next drive start** and whenever the app
opens, until the driver prices it or explicitly ignores it.

### Design
- **New table `price_reminders`** (migration `v6 → v7`):
  ```
  price_reminders(eventId INTEGER PRIMARY KEY, raisedAt INTEGER NOT NULL, ignoredAt INTEGER NULL)
  ```
  `eventId` references a `charge_events.id` whose `kind = FAST` and which closed **unpriced**
  (`enteredRateInr IS NULL AND enteredBillInr IS NULL`).
- **Write**: in `TripRecorderService` where a FAST session closes today (`Change.Closed`), also upsert
  a reminder row when the session has no driver-entered price.
- **Raise**: the existing `ChargePrompt` state is loaded with the newest *unignored* reminder:
  - at the start of a trip (when a trip opens), and
  - whenever the app regains foreground (the CHARGING screen loads reminders instead of nothing).
  The dialog shows the session's kWh and current default cost (computed from `energyKwh × outside rate`
  if `costInr` is null). "APPLY" → `saveChargeCost` then delete the row; "SKIP/IGNORE" → set
  `ignoredAt` (never raised again; the session is still editable in CHARGING, where applying deletes
  the reminder too).
- **Open-session pre-pricing** stays: an open fast session still prompts the moment it is detected.

### New/changed code
- `OdographDb` v7: new table + migration; bump `@Database(version = 7)`.
- `OdographDao`: `upsertReminder(eventId, raisedAt)`, `pendingReminders()`, `ignoredReminders()`,
  `dismissReminder(eventId)` (set ignoredAt), `clearReminder(eventId)` (delete via APPLY).
- `TripRecorderService`: write reminder on unstriced FAST close; raise newest reminder into
  `pendingChargePrompt` at trip-open; expose a refresh trigger.
- `ChargingScreen`/`OdographApp`: surface reminders the same way as live prompts when the app opens;
  APPLY/IGNORE wire into the DAO.
- **MG capture**: add `battery` columns `odometerKm`, `batteryEnergyKwh`, `chargeTimeRemainingMin`,
  `distanceSinceLastChargeKm`, `powerUsageSinceLastChargeKwh` (all nullable REAL). The poller copies
  the matching `ChargeStatus` fields into each battery row.
- **Tests**: `MigrationTest` v6→v7 (table + columns); `ReminderTest` (unpriced fast → row; priced →
  none; APPLY clears; IGNORE stops; CHARGING-tab apply clears); serialization of new battery fields.

---

## W3 — INSIGHTS tab

New **INSIGHTS** tab in the app (sixth tab) holding four readouts, all computed from existing data.

### 3a. Cost dashboard
- Buckets: **today**, **last 7 days**, **last 30 days**, rollup from closed trips
  (`endedAt` within window) and closed charge sessions (`startTime` within window).
- Metrics per bucket: drives, km, trip kWh (`SUM(energyKwh)`), trip ₹, charge kWh, charge ₹,
  total ₹, ₹/km, effective ₹/kWh (total ₹ ÷ total trip kWh).
- New DAO query `periodCost(fromMs)`: one pass over trips + charges in the window.
- Reuses `ChargingScreen`'s `ChargeStats` conventions for display.

### 3b. Range@100 regression chart
- For each of the last ~60 days, daily average efficiency from that day's closed trips
  (`SUM(kWh)/SUM(km)×100`, only trips meeting `MIN_EFFICIENCY_DISTANCE_M` + `MIN_EFFICIENCY_ENERGY_KWH`);
  day with no qualifying drive is a gap.
- Range@100 = `capacityKwh / eff × 100`, clamped to `0..<capacity×0.5` sanity (ignore nonsense days).
- Drawn as a **Compose `Canvas` sparkline** with a simple linear-trend line and the two labelled
  numbers: latest, and earliest in the window. Small enough for the car screen.
- New DAO query `dailyEfficiency(fromMs)` returning `(day, distanceM, energyKwh)`.

### 3c. Best / worst mileage routes
- Per direction: group **closed** trips by `(startPlaceId, endPlaceId)` where both places are known
  and the trip carries `energyKwh` within the valid-efficiency guards.
- A route is only quoted once its **total km in that direction ≥ 30 km** (MIN_ROUTE_KM) **and** it has
  **≥ 3 valid-energy drives** (MIN_ROUTE_DRIVES). A 2 km errand never ranks.
- For each route compute `avg kWh/100 km` and count; sort best-first and worst-first; show both
  lists with `displayName → displayName`, drives, km, avg efficiency.
- **Pair rule (user's requirement)**: a directional pair A→B / B→A is only *compared* (shown side by
  side as "out vs back") when *both* directions separately pass the km + drives bars. The lists show
  each qualifying direction regardless of its partner.
- New DAO query `routeEfficiency()` mirroring `routeSummaries` but adding
  `AVG(energyKwh/distanceM×100)` over the guarded subset; or compute in Kotlin from
  `tripEnergies()` + joins (choose the DAO query for cleanliness).
- Constants live in `BatteryMath`: `MIN_ROUTE_KM = 30_000.0`, `MIN_ROUTE_DRIVES = 3`.

### 3d. 12-hour drain readout
- Source: battery rows with `tripId = -1` (parked), where `charging != true` and `socPercent` is finite.
- Find **windows ≥ 12 h** where the box was parked-not-charging: a run of parked rows with gap
  between consecutive rows ≤ `CHARGE_SESSION_GAP_MS` (else split); a window is usable only if its
  first and last SOC are both non-null and the true elapsed ≥ 12 h.
- Per window: `dropPct = socFirst - socLast` (positive = drain), `dropKwh = capacity × dropPct/100`,
  `perDay = dropPct × 24h/elapsed`.
- Readouts: **latest** window and **worst** (max per-day) with its date. Show as
  `"3.1 %/day · 1.5 kWh`".
- New DAO query `parkedBatteryFrames(fromMs, toMs)` ordered by `t`; windowing in a
  `core/VampireDrain.kt` pure function (unit-testable).
- Guards: dropPct coerced ≥ 0 for display (charging or regen can make it negative); a window that
  overlaps a charge event's `startTime..endTime` is discarded (a park that included a charge is not a
  drain window).

### New/changed code
- `data/OdographDao.kt`: `periodCost`, `dailyEfficiency`, `parkedBatteryFrames`, `routeEfficiency`.
- `core/Analytics.kt` (new): `dailyRangeSeries`, `drainWindows`, `routeRankings` (pure, unit-tested).
- `ui/InsightsScreen.kt` (new): 4 sections, reuses `Stat`/`SmallStat`/`rememberMetrics`.
- `ui/OdographApp.kt`: add `Tab.INSIGHTS`.
- Tests: `AnalyticsTest`, `InsightsScreenTest`, DAO query tests, route-pair-bar test.

---

## W4 — Backup / restore

### Goal
Downgrade-proof the archive without a laptop: copy the SQLite store out and put a copy back, in-app
and over the LAN web app.

### Design
- **Snapshot**: run `PRAGMA wal_checkpoint(TRUNCATE)` on the open DB, then copy `odograph.db`
  (WAL already folded in; `-wal`/`-shm` are empty post-checkpoint). Ship as `odograph-backup-<ts>.db`.
- **In-app (SETUP)**: button **"Backup now"** → system-storage file picker (SAF `CreateDocument`)
  writes the snapshot. Button **"Restore from backup"** → SAF `OpenDocument`; flow:
  1. Pause `TripRecorderService` capture (guard: refuse while a trip is open and moving).
  2. `OdographDb.get(ctx).close()` + drop singleton; copy the picked file over `odograph.db`;
     delete stale `-wal`/`-shm`.
  3. Reopen via `OdographDb.get(ctx)`; fast-forward any re-opened orphan trip through `TripRecovery`.
  4. Resume capture. On any failure → original DB file kept as `odograph.db.prev` and `get()` reopened
     from it, with an error surfaced.
- **LAN web app**: `GET /backup` streams the snapshot as `application/octet-stream`
  (works while the harness is idle; single-click in a browser). `POST /restore` accepts the uploaded
  file, runs the same swap with the same guard, and only the box performs it (the web app pages
  redirect back to `/config` after writing a result message).
- Guard applies to both paths: restore is refused if a trip is open with `moved` distance (a partial
  drive must not be clobbered mid-roll; it will instead complete and the driver restores afterwards).

### New/changed code
- `data/OdographDb.kt`: `fun snapshotTo(file: File)`, `fun replaceWith(file: File)` (both checkpoint +
  copy/swap) plus `closeAndReset()`.
- `record/TripRecorderService.kt`: `pause()/resume()` and an `isRestoring` latch shared with the UI.
- `server/DashboardServer.kt`: `GET /backup`, `POST /restore` (raw body file capture; no multipart
  dependency).
- `ui/SetupScreen.kt`: Backup / Restore buttons + status text.
- Tests: DB snapshot/swap round-trip (write trips → snapshot → wipe → restore → rows intact),
  restore-refusal-while-moving, `POST /restore` happy + failure handler via existing server test
  harness if present.

---

## W5 — Web app (SPA) and phone REST API

### Goal
A real browser application on the laptop — charts, tables, cost, routes, drain, places — and the
machine-readable JSON endpoints a future phone app will use. All LAN-only over the hotspot (per the
approved topology + design decision).

### Design
- **Ktor serves a static SPA**: `web/` assets (index.html, app.js, style.css — vanilla JS, no build
  step, no framework; fetch to the JSON API, small inline sparkline/SVG renderer). Served at
  `/` replacing the current generated dashboard for browsers (the generated pages stay reachable at
  their old paths during transition; the SPA becomes `/`).
- **REST/JSON API** (same Ktor, one `routing` block), all gated like today's exports by the
  SETUP → LAN DATA toggle:

  | Endpoint | Returns |
  |---|---|
  | `GET /api/live` | live SOC, range, charging, charge power, current trip id/distance, last poll |
  | `GET /api/trips` | closed trips (id, start/end, km, duration, kWh, ₹, soc range, elevation, place names) |
  | `GET /api/charges` | charge sessions (times, SOC, kWh, peak kW, kind, ₹, driver price) |
  | `GET /api/places` | places with visits and display names |
  | `GET /api/routes` | per-direction route efficiency + counts (W3c ranking input) |
  | `GET /api/cost?bucket=today\|7d\|30d` | W3a bucket |
  | `GET /api/range` | W3b daily range series |
  | `GET /api/drain` | W3d latest + worst window |
  | `GET /api/telemetry` | per-day coverage windows |
  | `GET /backup` · `POST /restore` | W4 |

- JSON shaped by small serializer functions (repo has no existing REST layer; plain `respondText` +
  hand-rolled JSON matches the SheetsJson pattern and avoids new dependencies).
- The phone API is the same endpoints; the spreadsheet remains the archive.

### New/changed code
- `server/DashboardServer.kt`: static `web/` serving + the `/api/*` block.
- `server/ApiJson.kt` (new): serializer helpers (esc/num like `SheetsJson`).
- `web/index.html`, `web/app.js`, `web/style.css` (new): dashboard = live header (SOC/range/charge),
  cost chart, range@100 chart, routes table, drain cards, places list, telemetry coverage strip,
  backup/restore buttons.
- Keeps `DashboardHtml` generated pages for now (renamed nav), later migratable out.
- Tests: `ApiJsonTest` for each endpoint's payload shape (string assertions, `org.json` is stubbed in
  JVM tests), a route-presence probe (`GET /api/live` returns 200 JSON when toggle on).

---

## 6. Cross-cutting decisions

- **DB migration v6 → v7** — single migration adding `price_reminders` table + the five `battery`
  columns from W2. `MigrationTest` verifies both and an upgrade-from-v6 run preserves data.
- **Single writer rule preserved**: the service stays the only writer; API/UI are read-only.
  Backup/restore is the sole exception and is serialized behind the restore latch.
- **No new runtime dependencies**: Nominatim + osmdroid + Ktor + Room all already present. Vanilla JS
  for the SPA. SAF uses platform APIs.
- **Constants** (`BatteryMath`): `MIN_ROUTE_KM = 30_000.0`, `MIN_ROUTE_DRIVES = 3`,
  `MIN_DRAIN_WINDOW_MS = 12h`, reuse `CHARGE_SESSION_GAP_MS`, `MIN_EFFICIENCY_*`.

## 7. Build order (each commits + stays green)

1. W2 DB migration + reminders + MG capture (foundation: migration, DAO, service, dialog wiring).
2. W1 named places (map search → pin → save) — independent, good demo.
3. W3 INSIGHTS (cost → range → routes → drain) — pure math first, then DAO, then UI.
4. W4 backup/restore (in-app, then `/backup` + `/restore`).
5. W5 web app + `/api` (SPA last; API endpoints tested alongside each W3 readout where trivial).

Then: full `:app:testDebugUnitTest`, `assembleRelease`, push, refresh the GitHub release asset, and
re-deploy the Apps Script only if the `battery`/`charge_events` payload shape changes (it must stay
backward-compatible per the existing append/upsert contract).

## 8. Testing strategy

- Pure math (`Analytics.kt`, `VampireDrain`, route rankings, forward geocoder parsing) unit-tested
  with `org.json` stubbed (string assertions, per repo convention).
- DAO/Room tests via the existing in-memory harness; `MigrationTest` extended to v7.
- Service tests for reminder raise-on-close and raise-at-trip-open.
- Screenshot/UI tests follow existing `ScreenshotTest`/`RoutesScreenTest` patterns.
- Server tests assert `/api/*` JSON shape and the export-toggle gate.
- Full `testDebugUnitTest` suite must stay green before each commit; APK build verified per milestone.

---
**Sign-off:** user approved scope (2026-09-15): all five workstreams, everything in the app, LAN-only
web app + phone API, sheet unchanged as archive.