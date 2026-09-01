# Odograph — Drive Tracker Design

**Status:** approved (conversation, 2026-09-01)
**Target device:** Portronics Tune Prime (Android 14, Snapdragon 662, 4 GB / 64 GB, **no SIM inserted**, built-in GNSS)
**Target screen:** MG Windsor EV — 15.6" "Grandview" head unit, via wireless CarPlay/Android Auto projection

---

## 1. Problem

Every drive in the car produces data nobody keeps: how far, how long, how fast, which route,
how often. The car's own trip computer resets and forgets. The goal is an app that starts
itself when the car starts, records the drive with no interaction, and makes the accumulated
history analysable later on a Mac.

## 2. Constraints (established, not assumed)

| # | Constraint | Consequence |
|---|---|---|
| C1 | The box is powered by the car. Ignition off = **unannounced power cut**. | No `onDestroy`, no "save trip" moment. Must write incrementally and recover on next boot. |
| C2 | The app renders into a **projected** CarPlay/AA session at an unknown negotiated resolution. | Fully resolution-independent UI. No bitmaps, no hardcoded `dp`. |
| C3 | Touch returns over the projection link: single-touch, imprecise, laggy. | Large targets, tap-only, no gestures, no drag. |
| C4 | **No SIM**, but a phone hotspot is up ~99% of the time. | Treat network as *usually present, never guaranteed*. Every feature degrades to an offline path; nothing hard-requires connectivity. A-GPS, NTP, map tiles and reverse geocoding all work in the common case. |
| C5 | No access to the vehicle bus. | No speed/SoC/range from the car. GNSS is the only motion source. OBD is out of scope (v2). |
| C6 | Play Services presence is **unverified** on this box. | Use `LocationManager`, not `FusedLocationProviderClient`. Zero GMS dependency. |
| C7 | Sideload path is **unverified** (unknown-sources, developer options, ADB). | Must be probed before any real build effort. |

## 3. Non-goals

- Navigation (Google Maps is better)
- Media playback or control
- A companion phone app + sync protocol (the share sheet already moves files)
- Cloud backend or account (explicitly rejected: local-only for privacy and zero cost)
- OBD-II / vehicle data — **deferred to v2** by decision

## 4. Architecture

Four components, one SQLite database. **The service is the only writer**; everything else reads.
That single rule removes the need for any locking, sync, or cache-invalidation design.

```
boot ─► BootReceiver ─► TripRecorderService  (foreground, type=location)
                              │ LocationManager GPS_PROVIDER @ 1 Hz
                              ▼
                    Room / SQLite (WAL)  ── trips · points
                       ▲        │        ▲
              TripRecovery   ClusterUI   HttpServer + ExportWriter
             (boot: close    (Compose)   (:8080 + mDNS, CSV/GPX/HTML)
              orphan trip)
```

| Component | Single responsibility |
|---|---|
| `TripRecorderService` | Own GPS capture; append points. Nothing else. |
| `TripRecovery` | On boot: close the trip the power cut orphaned; compute its totals; backfill the new trip's origin from the previous trip's last point. |
| `ClusterUi` | Read-only. Driver view (3 fixed numbers + gauge) and Detailed view (gauge + route trace + stats). Day/night themes. |
| `HttpServer` / `ExportWriter` | Serve the analysis dashboard; emit CSV, GPX, and a self-contained HTML archive. Entirely disposable — rebuildable from raw points. |

## 5. Key design decisions

**D1 — Recovery, not closure.** A trip is never "ended"; it is *recovered* on the next boot.
`trips.ended_at IS NULL` at startup means the power was cut mid-trip. Totals are computed
during recovery from the points table, never held in memory.

**D2 — GNSS time, not system time.** The hotspot usually supplies NTP, but "usually" is not a
guarantee and a wrong clock silently corrupts every timestamp it touches. `Location.getTime()`
carries GNSS-derived UTC, is authoritative, and needs no network. Keeping it costs nothing, so
we do not trade a guarantee for an assumption. All trip timestamps come from the fix.

**D3 — `LocationManager`, not Fused.** Removes the Play Services dependency (C6) and gives raw
1 Hz GNSS, which is what we actually want. `Location.getSpeed()` is Doppler-derived and more
accurate than differentiating positions.

**D4 — Origin backfill.** With A-GPS the first fix normally arrives in seconds, but the hotspot
may not have associated yet at ignition, and a cold fix without it takes 30–60 s. Trip N's origin
is seeded from trip N−1's final point and flagged `interpolated = 1`. Cheap insurance; honest
about which points were measured.

**D5 — `ACQUIRING` never `0 km/h`.** Before first fix the cluster states that it has no fix.
A zero is a measurement claim.

**D6 — Two view modes.** Driver view is ruthlessly minimal (fixed positions, ≤2 s glance).
Detailed view is for the passenger and for parked review — map trace, charts, scrolling allowed.

**D7 — Day and night are separate palettes, not an inversion.** Glow is disabled on the light
ground; every accent darkens for contrast. Manual toggle plus an automatic sunrise/sunset ramp.

**D8 — Tiles are an enhancement layer, never a dependency.** The route always renders as a
polyline from our own points, with no network. On top of that, `osmdroid` draws OpenStreetMap
tiles from a local disk cache (chosen over the Google Maps SDK because of C6). Tiles are
immutable, so a corridor driven once with the phone's hotspot on renders offline thereafter —
and the routes you drive most are cached first. With an empty cache and no network the map is
blank and the polyline still draws; that fallback is required behaviour, not a defect.

**D9 — Export ladder, three independent paths.** (1) Every trip auto-writes CSV+GPX to disk;
(2) embedded web dashboard when box and Mac share a network (phone hotspot); (3) `ACTION_SEND`
share sheet, which yields Bluetooth-to-Mac for free without any Bluetooth code.

## 6. Data model

```sql
CREATE TABLE trips (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at      INTEGER NOT NULL,      -- epoch ms, GNSS-derived
  ended_at        INTEGER,               -- NULL = orphaned, awaiting recovery
  distance_m      REAL    NOT NULL DEFAULT 0,
  duration_s      INTEGER NOT NULL DEFAULT 0,
  moving_s        INTEGER NOT NULL DEFAULT 0,
  max_speed_mps   REAL    NOT NULL DEFAULT 0,
  avg_speed_mps   REAL    NOT NULL DEFAULT 0,
  start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL,
  cluster_id      INTEGER,               -- assigned by route clustering
  soc_start REAL, soc_end REAL, energy_kwh REAL   -- v2 / OBD, always NULL in v1
);

CREATE TABLE points (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_id      INTEGER NOT NULL REFERENCES trips(id),
  t            INTEGER NOT NULL,   -- epoch ms from the fix
  lat REAL NOT NULL, lon REAL NOT NULL,
  speed_mps    REAL NOT NULL,
  bearing_deg  REAL,
  altitude_m   REAL,
  accuracy_m   REAL,
  interpolated INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_points_trip ON points(trip_id, t);
```

Journal mode is **WAL**; every point is committed as it arrives. Worst case on power loss is
the loss of one in-flight row, not a trip.

## 7. Stat definitions (pinned, previously ambiguous)

- **Distance** — sum of great-circle distance between consecutive non-interpolated points,
  discarding segments where `accuracy_m > 25` to reject GPS jitter while stationary.
- **Duration** — `ended_at − started_at`.
- **Moving time** — total time across points where `speed_mps > 0.5` (1.8 km/h).
- **Average speed** — `distance_m / moving_s`. Moving average, not wall-clock, so traffic
  lights don't drag it down.
- **Max speed** — highest single-fix `speed_mps` with `accuracy_m <= 15`.
- **"Lowest speed"** — *rejected as specified*: it is always 0. Replaced by **slowest moving
  kilometre**, the worst rolling 1 km split, which is the useful traffic signal.
- **Most visited route** — clustering on the **(origin, destination) pair**, not path shape.
  Origins and destinations are snapped to a ~150 m grid and clustered; a cluster is named
  by the user later. This is the 90% answer without map-matching.

## 8. Risks and their mitigations

| Risk | Mitigation |
|---|---|
| Sideloading blocked on the box | **Probe APK first** (Phase 0) before any real build effort |
| Box has no usable GNSS provider | Probe APK reports providers and time-to-first-fix |
| No Play Services | Already avoided by D3 |
| `BOOT_COMPLETED` doesn't fire / firmware kills the service | Probe records it; fallbacks in order: battery-optimization exemption → `targetSdk 28` → `AccessibilityService` → become the launcher |
| Unknown resolution breaks layout | Resolution-independent canvas; probe reports actual metrics |
| Wrong clock at boot | D2 — GNSS time |
| Web dashboard unreachable | D9 — two other export paths that need no network |

**D10 — Place names come from cluster centroids, not trips.** Reverse geocoding runs against a
*place* the first time it is created, never per trip, so a hundred commutes to the same office
cost one lookup that is then cached forever. Android's built-in `Geocoder` has no backend without
Play Services (C6), so lookups go to OSM Nominatim with a proper User-Agent. A trip with no name
displays its coordinates; naming never blocks recording.

## 9. Phasing

- **v1** — everything in this document
- **v2** — OBD-II energy data (deferred by decision), per-route km/kWh
- **v3** — voice assistant, accessibility-service utilities
