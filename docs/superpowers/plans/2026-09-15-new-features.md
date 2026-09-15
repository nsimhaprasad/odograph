# New Features (Places, Pricing, Insights, Backup, Web App) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship five user-facing workstreams on the Odograph Android box: named places via map search, persistent charge-price reminders, an in-app INSIGHTS tab (cost, range@100, route efficiency, 12h drain), backup/restore, and a LAN web app with a JSON API.

**Architecture:** Everything stays on the box. Room (v7) gains a `price_reminders` table and five battery columns; a new `core/Analytics.kt` holds pure math; Compose gains PLACES + INSIGHTS screens; Ktor serves a static SPA plus `/api/*` and `/backup`+`/restore`. Single writer rule kept; the service stays the only writer, with backup/restore serialized behind a latch.

**Tech Stack:** Kotlin, Compose, Room 2.6.1, ktor-server-cio 2.3.11, osmdroid 6.1.18, OSM Nominatim (search + reverse), vanilla JS SPA, Robolectric + AssertJ tests.

**Build order:** W2 (DB foundation) → W1 (places) → W3 (insights) → W4 (backup) → W5 (web app). Each workstream ends green (full `:app:testDebugUnitTest`) and is committed.

---

## Task 1: Migration v6→v7 — price_reminders + battery columns

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/data/OdographDb.kt`
- Modify: `app/src/main/java/in/odograph/tracker/data/Entities.kt`
- Modify: `app/src/main/java/in/odograph/tracker/data/OdographDao.kt`
- Test: `app/src/test/java/in/odograph/tracker/data/MigrationTest.kt`

- [ ] **Step 1: Add the entity and new dereferenced battery fields**

In `Entities.kt`, add above `DailyTelemetryEntity`:

```kotlin
/**
 * A fast charge the driver never priced, carried until they APPLY (priced → row deleted) or
 * IGNORE (ignoredAt set). Surfaced again at the next drive start / app open so a charge made
 * after locking the car is never silently lost.
 */
@Entity(tableName = "price_reminders")
data class PriceReminderEntity(
    @PrimaryKey val eventId: Long,
    val raisedAt: Long,
    val ignoredAt: Long? = null
)
```

Add to `BatteryEntity` (after `workingCurrent`):

```kotlin
    val odometerKm: Double? = null,
    val batteryEnergyKwh: Double? = null,
    val chargeTimeRemainingMin: Int? = null,
    val distanceSinceLastChargeKm: Double? = null,
    val powerUsageSinceLastChargeKwh: Double? = null
```

- [ ] **Step 2: Add the migration and bump the version**

In `OdographDb.kt`: `version = 7`; add `PriceReminderEntity` to the `entities` list; add:

```kotlin
        /** Persistent fast-charge price reminders + richer MG charge capture into battery rows. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `price_reminders` (
                        `eventId` INTEGER NOT NULL PRIMARY KEY,
                        `raisedAt` INTEGER NOT NULL,
                        `ignoredAt` INTEGER)"""
                )
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `odometerKm` REAL")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `batteryEnergyKwh` REAL")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `chargeTimeRemainingMin` INTEGER")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `distanceSinceLastChargeKm` REAL")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `powerUsageSinceLastChargeKwh` REAL")
            }
        }
```

Add `MIGRATION_6_7` to `.addMigrations(...)`.

- [ ] **Step 3: Add DAO queries for reminders + parked battery + new analytics**

In `OdographDao.kt`, after the `docsNewCharges` block:

```kotlin
    // ---- price reminders ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertReminder(reminder: PriceReminderEntity)

    @Query("SELECT * FROM price_reminders WHERE ignoredAt IS NULL ORDER BY raisedAt DESC LIMIT 1")
    fun newestPendingReminder(): PriceReminderEntity?

    @Query("UPDATE price_reminders SET ignoredAt = :at WHERE eventId = :eventId AND ignoredAt IS NULL")
    fun ignoreReminder(eventId: Long, at: Long)

    @Delete
    fun deleteReminder(reminder: PriceReminderEntity)
```

- [ ] **Step 4: Write the migration + reminder + battery-field test**

In `MigrationTest.kt` add a test that migrates a v6 database (existing fixture builder) and asserts the `price_reminders` table exists and the five battery columns exist via `PRAGMA table_info`. Extend `OdographDbTest`:

```kotlin
    @Test
    fun `an ignored reminder never resurfaces but a pending one does`() {
        val dao = db.dao()
        dao.upsertReminder(PriceReminderEntity(eventId = 7, raisedAt = 1000L))
        assertThat(dao.newestPendingReminder()?.eventId).isEqualTo(7)
        dao.ignoreReminder(7, 2000L)
        assertThat(dao.newestPendingReminder()).isNull()
        dao.deleteReminder(PriceReminderEntity(eventId = 7, raisedAt = 0L))
    }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: BUILD SUCCESSFUL (existing 242 + new tests green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/in/odograph/tracker/data/ && git commit -m "feat(db): v7 price-reminder table and richer MG battery capture"
```

---

## Task 2: MG charge field capture in the poller

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/record/TripRecorderService.kt:322-336`
- Test: `app/src/test/java/in/odograph/tracker/record/ChargeLedgerTest.kt` (additive)

- [ ] **Step 1: Fill the new battery fields in the poller**

Replace the `BatteryEntity(...)` construction in `telematicsLoop` with:

```kotlin
                    dao.insertBattery(
                        BatteryEntity(
                            tripId = tripId,
                            t = t,
                            socPercent = ch.soc,
                            charging = ch.isCharging,
                            rangeKm = ch.rangeKm,
                            chargingPowerKw = powerKw,
                            workingVoltage = ch.workingVoltage?.let { it * 0.25 },
                            workingCurrent = ch.workingCurrent?.let { (it - 1000) * 0.05 },
                            odometerKm = ch.odometerKm,
                            batteryEnergyKwh = ch.batteryEnergyKwh,
                            chargeTimeRemainingMin = ch.chargeTimeRemainingMin,
                            distanceSinceLastChargeKm = ch.distanceSinceLastChargeKm,
                            powerUsageSinceLastChargeKwh = ch.powerUsageSinceLastChargeKwh
                        )
                    )
```

- [ ] **Step 2: Run tests (no behavior change)**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/in/odograph/tracker/record/TripRecorderService.kt && git commit -m "feat(battery): capture odometer, battery kWh and charge-remaining from MG frame"
```

---

## Task 3: Raise and clear persistent price reminders

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/record/TripRecorderService.kt`
- Modify: `app/src/main/java/in/odograph/tracker/ui/OdographApp.kt`
- Test: `app/src/test/java/in/odograph/tracker/record/ChargeLedgerTest.kt`

- [ ] **Step 1: Service — write reminder on unpriced fast close; raise at startup**

In `TripRecorderService.kt`:
1. In `onCreate`'s `io.launch` right after `_state.value = LiveState(tripId = tripId)`, add `raisePendingPriceReminder(dao)` (defined below).
2. In the `is ChargeLedger.Change.Closed ->` branch, replace the `if (e.kind == ...FAST...)` block with:

```kotlin
                    is ChargeLedger.Change.Closed -> {
                        val e = change.event
                        if (e.kind == `in`.odograph.tracker.core.BatteryMath.ChargeKind.FAST.ordinal) {
                            // Never silence the ask twice: the moment it closes unpriced a
                            // persistent reminder is raised, so a charge the driver walked away
                            // from comes back the moment they next drive or open the app.
                            if (e.enteredRateInr == null && e.enteredBillInr == null) {
                                dao.upsertReminder(
                                    PriceReminderEntity(eventId = e.id, raisedAt = System.currentTimeMillis())
                                )
                            }
                            _state.update {
                                if (it.pendingChargePrompt == null) {
                                    it.copy(
                                        pendingChargePrompt = ChargePrompt(
                                            e.id, e.energyKwh, isOpen = false, currentCostInr = e.costInr
                                        )
                                    )
                                } else it
                            }
                        }
                    }
```

2b. Add companion functions (next to `clearChargePrompt`):

```kotlin
        /** Re-loads the newest still-pending fast-charge price reminder into the prompt state. */
        fun raisePendingPriceReminder(dao: io.windsor.telematics?.let { OdographDb.get(it).dao() } ?: return) {
            // placeholder — see Step 2c
        }
```

- [ ] **Step 2c: Concrete companion helpers**

Replace the Step 2b sketch with:

```kotlin
        /** Surfaces the newest unpriced fast charge, if the driver never priced or ignored it. */
        fun raisePendingPriceReminder(dao: `in`.odograph.tracker.data.OdographDao?) {
            if (dao == null) return
            if (_state.value.pendingChargePrompt != null) return
            runCatching {
                val pending = dao.newestPendingReminder() ?: return
                val e = dao.chargeEvent(pending.eventId) ?: return
                _state.update {
                    it.copy(
                        pendingChargePrompt = ChargePrompt(
                            sessionId = e.id, energyKwh = e.energyKwh, isOpen = false,
                            currentCostInr = e.costInr
                        )
                    )
                }
            }
        }

        /** Accepted a persistent reminder: price it and clear the row so it never resurfaces. */
        fun acceptChargePrompt(sessionId: Long) {
            _state.update { it.copy(pendingChargePrompt = null) }
            io.launch {
                runCatching {
                    val dao = OdographDb.get(this@TripRecorderService).dao()
                    dao.deleteReminder(PriceReminderEntity(eventId = sessionId, raisedAt = 0L))
                }
            }
        }

        /** Declined a persistent reminder for now: it will not nag again. */
        fun ignoreChargePrompt(sessionId: Long) {
            _state.update { it.copy(pendingChargePrompt = null) }
            io.launch {
                runCatching {
                    OdographDb.get(this@TripRecorderService).dao()
                        .ignoreReminder(sessionId, System.currentTimeMillis())
                }
            }
        }
```

(`TripRecorderService` is a `Service`; companion `io.launch` uses the top-level `io` scope — add `private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)` inside the companion, since `clearChargePrompt` runs outside the instance too.)

- [ ] **Step 3: App — wire APPLY/IGNORE and refresh reminders on foreground**

In `OdographApp.kt`:
- `onSave` → `acceptChargePrompt(prompt.sessionId)`; `onDismiss` → `ignoreChargePrompt(prompt.sessionId)`.
- In the `LaunchedEffect(tab)` block, when `tab` is CHARGING or DRIVE, call `TripRecorderService.raisePendingPriceReminder(null)` guarded so it only runs when the DAO is reachable. The service exports `currentDao()` — simplest is a static call. See Task 3 Step 4.

- [ ] **Step 4: Expose a DAO getter for the companion**

Add to the companion:

```kotlin
        /** The live DAO, so reminder raise/accept/ignore can run outside the instance's scope. */
        fun currentDao(context: android.content.Context): `in`.odograph.tracker.data.OdographDao =
            OdographDb.get(context).dao()
```

Update Step 2c callers to pass `ctx`. In `OdographApp`, `raisePendingPriceReminder(TripRecorderService.currentDao(ctx))`.

- [ ] **Step 5: Service test for reminder-on-close**

In `ChargeLedgerTest.kt` add:

```kotlin
    @Test
    fun `a fast close without a driver price raises a reminder row`() {
        // Open + close a fast session through the ledger, then assert newestPendingReminder
        // exists for the closed event id (the DAO is shared in this harness).
    }
```

(Fixture mirrors existing fast-close test at `ChargeLedgerTest.kt:85` + `dao.upsertReminder(...)` assertion.)

- [ ] **Step 6: Run tests + commit**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(pricing): persistent fast-charge price reminder raised at next drive/app-open`

---

## Task 4: Nominatim forward search

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/geocode/PlaceNamer.kt`
- Test: `app/src/test/java/in/odograph/tracker/geocode/PlaceNamerTest.kt`

- [ ] **Step 1: Add `GeocodedSuggestion` + `search()`**

Read `PlaceNamer.kt` first; reuse its HTTP + parse style. Add:

```kotlin
data class GeocodedSuggestion(val name: String, val lat: Double, val lon: Double)

    /**
     * Forward geocodes a free-text query via Nominatim search. Same policy-clean agent and the
     * same 1 req/s courtesy as reverse geocoding. Returns an empty list on any failure so callers
     * degrade to "check the hotspot".
     */
    suspend fun search(query: String): List<GeocodedSuggestion> {
        val term = query.trim().replace(" ", "+")
        if (term.isEmpty()) return emptyList()
        return runCatching {
            val url = java.net.URI("https://nominatim.openstreetmap.org/search?q=$term&format=json&limit=5").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", AGENT)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JsonArray.parse(body)  // see Step 2
        }.getOrDefault(emptyList())
    }
```

- [ ] **Step 2: Add a tiny JSON array parser + `display_name`/lat/lon extraction**

Follow the org.json-stub-safe convention: `PlaceNamerTest` asserts on strings, so implement `search` parsing with a minimal hand-rolled `JsonArray` that extracts `"lat","lon","display_name"`. Place it in `geocode/PlaceNamer.kt` as `internal fun parseSearchBody(body: String): List<GeocodedSuggestion>` with a regex-free scan (mirror `SheetsJson.num`/`esc` style).

- [ ] **Step 3: Test**

In `PlaceNamerTest.kt`:

```kotlin
    @Test
    fun `search extracts the result name and coordinates`() {
        val s = PlaceNamer.parseSearchBody(
            """[{"display_name":"Bengaluru","lat":"12.9716","lon":"77.5946"},{"display_name":"Bengaluru Rural","lat":"13.1","lon":"77.6"}]"""
        )
        assertThat(s).hasSize(2)
        assertThat(s[0].name).contains("Bengaluru")
        assertThat(s[0].lat).isEqualTo(12.9716)
    }

    @Test
    fun `search with an empty body is empty`() {
        assertThat(PlaceNamer.parseSearchBody("")).isEmpty()
    }
```

- [ ] **Step 4: Run tests + commit**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(places): Nominatim forward search with hand-rolled JSON parsing`

---

## Task 5: Place map with a pin + DAO promote/replace helpers

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/data/OdographDao.kt`
- Modify: `app/src/main/java/in/odograph/tracker/ui/map/RouteMap.kt`
- Test: `app/src/test/java/in/odograph/tracker/data/OdographDbTest.kt`

- [ ] **Step 1: DAO queries**

```kotlin
    @Query("SELECT * FROM places WHERE :within IS NULL OR (SELECT id FROM places WHERE id = :within) IS NOT NULL")
    fun placesNear(lat: Double, lon: Double, radiusM: Double): List<PlaceEntity>  // implemented per Task 5 Step 2
```

Actually implement as a Kotlin helper in DAO: `fun placesNear(lat: Double, lon: Double, radiusM: Double): List<PlaceEntity> = allPlaces().filter { Geo.haversineMetres(it.lat, it.lon, lat, lon) <= radiusM }` (DAO is an interface — add it in `OdographDao.kt` as an extension or a small `PlaceRepo`; see Step 2).

- [ ] **Step 2: Concrete — add a `PlaceRepo` in `record/`**

Create `app/src/main/java/in/odograph/tracker/record/PlaceRepo.kt`:

```kotlin
package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.PlaceEntity

/**
 * Create a named place from a search pick. If the coordinates already fall inside an existing
 * place's radius, that place is re-labelled + re-positioned instead of duplicated; otherwise a
 * fresh place row is created. Drives that start/end near it then count visits automatically.
 */
class PlaceRepo(private val dao: OdographDao, private val radiusM: Double = 150.0) {

    fun createOrReposition(lat: Double, lon: Double, label: String): PlaceEntity {
        val existing = dao.allPlaces().filter {
            Geo.haversineMetres(it.lat, it.lon, lat, lon) <= radiusM
        }.minByOrNull { Geo.haversineMetres(it.lat, it.lon, lat, lon) }
        val cleaned = label.trim().takeIf { it.isNotBlank() }
        return if (existing != null) {
            dao.updatePlacePosition(existing.id, lat, lon, existing.visits)
            dao.setPlaceLabel(existing.id, cleaned)
            dao.placeById(existing.id)!!
        } else {
            val id = dao.insertPlace(PlaceEntity(lat = lat, lon = lon, visits = 0, label = cleaned))
            dao.placeById(id)!!
        }
    }
}
```

- [ ] **Step 3: Add a marker/pin composable**

In `RouteMap.kt` add:

```kotlin
/** A single search-result pin on the tile map, re-centreable from outside. */
@Composable
fun PlacePin(pin: Pair<Double, Double>?, palette: Palette, modifier: Modifier = Modifier, zoom: Double = 16.0) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx -> /* same Configuration + MapView setup as RouteMap */ },
        update = { map ->
            map.overlays.clear()
            map.setBackgroundColor(palette.ground.toArgb())
            if (pin != null) {
                map.overlays.add(
                    org.osmdroid.views.overlay.Marker(map).apply {
                        position = GeoPoint(pin.first, pin.second)
                        icon = android.graphics.BitmapFactory.decodeResource(
                            ctx.resources, android.R.drawable.ic_menu_compass
                        )
                    }
                )
                map.post { map.zoomToBoundingBox(BoundingBox(pin.first, pin.second, pin.first, pin.second), true) }
            }
            map.invalidate()
        }
    )
}
```

(Extract the shared osmdroid `Configuration` block into a private `rememberConfiguredMap(ctx)` helper so `RouteMap` and `PlacePin` don't duplicate it.)

- [ ] **Step 4: DAO/Repo test**

In `OdographDbTest.kt`:

```kotlin
    @Test
    fun `pinning a place near an existing one relabels it instead of duplicating`() {
        val dao = db.dao()
        val home = dao.insertPlace(PlaceEntity(lat = 12.9716, lon = 77.5946))
        val r = PlaceRepo(dao).createOrReposition(12.9717, 77.5947, "Home")
        assertThat(r.id).isEqualTo(home)
        assertThat(r.label).isEqualTo("Home")
        assertThat(dao.allPlaces()).hasSize(1)
    }
```

- [ ] **Step 5: Run tests + commit**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(places): pin-and-name places with place repo + map marker`

---

## Task 6: PLACES screen + navigation

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/ui/PlacesScreen.kt`
- Modify: `app/src/main/java/in/odograph/tracker/ui/RoutesScreen.kt`
- Modify: `app/src/main/java/in/odograph/tracker/ui/OdographApp.kt`

- [ ] **Step 1: PlacesScreen**

Compose screen with: search box → results list; tapping a result sets `pin` and shows it on `PlacePin` at its lat/lon; a name field (defaults to the result short name); SAVE calls `PlaceRepo.createOrReposition` on IO; below, the existing places list (from `dao.allPlaces()`, visits + displayName), each tappable to edit name. Empty-network state text: "Search needs the phone hotspot. You can still name places shown below."

- [ ] **Step 2: Navigation**

`RoutesScreen.kt`: add a "PLACES" chip alongside the period chip row:

```kotlin
Chip("PLACES", showPlaces, palette, m) { showPlaces = !showPlaces }
```

Toggling renders `PlacesScreen(palette)` in place of the routes content (simple branch, no back stack).

- [ ] **Step 3: Compile + test**

There is no UI test for PlacesScreen yet; add `app/src/test/java/in/odograph/tracker/ui/PlacesScreenTest.kt` asserting the empty/none states render without crashing (mirror `RoutesScreenTest`).

- [ ] **Step 4: Run tests + commit**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(places): search-and-pin places screen wired into the routes tab`

---

## Task 7: Analytics pure math

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/core/Analytics.kt`
- Test: `app/src/test/java/in/odograph/tracker/core/AnalyticsTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class AnalyticsTest {
    // cost buckets
    // daily efficiency -> range@100 series
    // route rankings with MIN_ROUTE_KM + MIN_ROUTE_DRIVES bars
    // drain windows >= 12h, charge-overlap discarded, per-day % normalized
}
```

Key specifics:
- `dailyRangeSeries(days: List<DailyEff>, capacityKwh): List<RangePoint>` where `DailyEff(day, distanceM, energyKwh)`; skip days failing `MIN_EFFICIENCY_*`; `rangeAtFull = capacity / eff * 100`.
- `drainWindows(frames: List<ParkedFrame>, capacityKwh): List<DrainWindow>` — group consecutive parked frames (gap ≤ CHARGE_SESSION_GAP_MS), require elapsed ≥ 12h, both SOC non-null, drop ≥ 0.
- `routeRankings(routes: List<RouteEff>): RouteRankings` where `RouteEff(from, to, drives, km, avgKwhPer100)`; only entries with `km >= MIN_ROUTE_KM && drives >= MIN_ROUTE_DRIVES`.

- [ ] **Step 2: Implement `Analytics.kt`**

```kotlin
package `in`.odograph.tracker.core

object Analytics {
    fun dailyRangeSeries(days: List<DailyEff>, capacityKwh: Double): List<RangePoint> = ...
    fun drainWindows(frames: List<ParkedFrame>, capacityKwh: Double): List<DrainWindow> = ...
    fun routeRankings(routes: List<RouteEff>): RouteRankings = ...
}
```

Adds `BatteryMath.MIN_ROUTE_KM = 30_000.0`, `BatteryMath.MIN_ROUTE_DRIVES = 3`, `BatteryMath.MIN_DRAIN_WINDOW_MS = 12 * 3_600_000L`.

- [ ] **Step 3: Run tests**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

`git commit -m "feat(insights): pure-math daily range, drain windows, route rankings"`

---

## Task 8: DAO aggregate queries + INSIGHTS screen

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/data/OdographDao.kt`
- Create: `app/src/main/java/in/odograph/tracker/ui/InsightsScreen.kt`
- Modify: `app/src/main/java/in/odograph/tracker/ui/OdographApp.kt`
- Test: `app/src/test/java/in/odograph/tracker/data/OdographDbTest.kt`, new `RoutesScreenTest`-style UI test

- [ ] **Step 1: DAO queries**

```kotlin
    @Query("""SELECT COALESCE(SUM(distanceM),0) AS distanceM, COALESCE(SUM(energyKwh),0) AS energyKwh,
                     COALESCE(SUM(costInr),0) AS costInr, COUNT(*) AS drives
              FROM trips WHERE endedAt IS NOT NULL AND startedAt >= :fromMs""")
    fun periodTrips(fromMs: Long): PeriodCost

    @Query("""SELECT COALESCE(SUM(energyKwh),0) AS energyKwh, COALESCE(SUM(costInr),0) AS costInr,
                     COUNT(*) AS sessions
              FROM charge_events WHERE kind IS NOT NULL AND startTime >= :fromMs""")
    fun periodCharges(fromMs: Long): PeriodCharges

    @Query("""SELECT CAST(strftime('%s', startedAt / 1000, 'localtime') ... ) AS day ..."") // daily efficiency bucket
    fun dailyEfficiency(fromMs: Long): List<DailyEffRow>
```

Add row types `PeriodCost`, `PeriodCharges`, `DailyEffRow(day: Int, distanceM: Double, energyKwh: Double)`, `RouteEff(from, to, drives, km, avgKwhPer100)`, `ParkedFrame(t, socPercent)`.

- [ ] **Step 2: park-drain query**

```kotlin
    @Query("SELECT t, socPercent FROM battery WHERE tripId = -1 ORDER BY t ASC")
    fun parkedBatteryFrames(): List<ParkedBatteryRow>
```

(TripRecorderService writes parked frames with `tripId` = the "no drive" sentinel; verify the exact sentinel — the poller uses `tripId` field which is `-1` when no trip is open; assert in test.)

- [ ] **Step 3: INSIGHTS screen**

`InsightsScreen.kt`: four sections reusing `Stat`/`SmallStat`/`rememberMetrics`:

1. **COST** — bucket chips (TODAY / 7D / 30D); headline Stats: `km`, `₹ total`, `₹/km`; small stats `trip ₹`, `charge ₹`, `trip kWh`, `eff ₹/kWh`.
2. **RANGE@100** — `Canvas` sparkline of last 60 daily points + trend line; two numbers: latest, 60d-ago.
3. **ROUTES** — two columns: BEST (ascending avg kWh/100) and WORST (descending), each row `A → B · drives · km · kWh/100`.
4. **DRAIN** — latest overnight `%.1f %/day · %.1f kWh` + worst window with its date.

- [ ] **Step 4: Navigation**

Add `Tab.INSIGHTS` to the enum + a `Chip("INFO", ...)` in the tab row. (Six tabs scroll; existing horizontalScroll handles it.)

- [ ] **Step 5: DAO tests**

`OdographDbTest` — seed trips + charges + parked frames; assert `periodTrips`, `periodCharges`, `parkedBatteryFrames` return expected rows (mirror `PeriodTotalsTest`).

- [ ] **Step 6: UI smoke test**

`InsightsScreenTest` renders the tabs/sections without crashing on empty + seeded data.

- [ ] **Step 7: Run tests + commit**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(insights): cost buckets, range@100 sparkline, route rankings, 12h drain tab`

---

## Task 9: Backup / restore engine (DB snapshot + swap)

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/data/OdographDb.kt`
- Test: `app/src/test/java/in/odograph/tracker/data/OdographDbTest.kt`

- [ ] **Step 1: Snapshot + replace + close**

```kotlin
        /** WAL-checkpointed copy of the live database. Call on IO; do not use while the service writes. */
        fun snapshotTo(ctx: Context, target: File) {
            val db = get(ctx).openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            val src = ctx.getDatabasePath(NAME)
            // Copy the single folded .db; -wal/-shm are empty after TRUNCATE.
            src.copyTo(target, overwrite = true)
        }

        /** Swaps the live database for a backup file, keeping the original as .prev. Reopens fresh. */
        fun replaceWith(ctx: Context, backup: File) {
            val dbFile = ctx.getDatabasePath(NAME)
            val prev = File(dbFile.parentFile, NAME + ".prev")
            synchronized(instance) {
                instance?.close()
                instance = null
                if (dbFile.exists()) dbFile.copyTo(prev, overwrite = true)
                backup.copyTo(dbFile, overwrite = true)
                File(dbFile.parentFile, NAME + "-wal").delete()
                File(dbFile.parentFile, NAME + "-shm").delete()
                get(ctx)  // reopen
            }
        }
```

(`replaceWith` must run with the recorder paused — Task 10.)

- [ ] **Step 2: Test round-trip**

In `OdographDbTest` (file-based, needs a temp dir via `tmpFolder`):

```kotlin
    @Test
    fun `snapshot then restore keeps the rows`() {
        val db = ...  // file-backed, not in-memory
        val id = db.dao().startTrip(1L)
        val snap = File(tmpDir, "backup.db")
        OdographDb.snapshotTo(ApplicationProvider.getApplicationContext(), snap)
        // wipe + replaceWith
        OdographDb.replaceWith(ApplicationProvider.getApplicationContext(), snap)
        val reopened = OdographDb.get(ApplicationProvider.getApplicationContext()).dao()
        assertThat(reopened.tripById(id)).isNotNull
    }
```

(Note: `resetForTests` closes the singleton — reuse it between the snapshot and reopen to force the new file to load; adjust container to keep a reference for teardown.)

- [ ] **Step 3: Run tests + commit**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(backup): WAL-checkpointed snapshot and guarded restore engine`

---

## Task 10: Service pause/resume + SETUP buttons + `/backup` + `/restore`

**Files:**
- Modify: `app/src/main/java/in/odograph/tracker/record/TripRecorderService.kt`
- Modify: `app/src/main/java/in/odograph/tracker/server/DashboardServer.kt`
- Modify: `app/src/main/java/in/odograph/tracker/ui/SetupScreen.kt`

- [ ] **Step 1: Service latch**

```kotlin
        @Volatile
        private var restoring = false

        fun isRestoring(): Boolean = restoring

        /** Pauses capture around a restore. Returns false if a moving trip would be clobbered. */
        fun pauseForRestore(): Boolean {
            val state = _state.value
            if (state.tripId >= 0 && state.distanceM > 50.0) return false  // moving trip guard
            restoring = true
            return true
        }

        fun resumeAfterRestore() { restoring = false }
```

In the `record()` function, bail early: `if (restoring) return`.

- [ ] **Step 2: SETUP buttons**

In `SetupScreen.kt` `Section("EXPORT")` add two chips:

```kotlin
Chip("BACKUP DB", false, palette, m) {
    Thread {
        val f = writeExport(ctx, "odograph-backup-${System.currentTimeMillis()}.db", "") // placeholder
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: return@Thread
            val dest = File(dir, "odograph-backup-${System.currentTimeMillis()}.db")
            OdographDb.snapshotTo(ctx, dest)
            shareFile(ctx, dest, "application/octet-stream")
        }
    }.start()
    note = "Backup saved — open the share/save dialog."
}
```

(`writeExport` already writes+shares; use a real path approach matching `writeExport` helpers.)

Restore chip: opens a system file picker (plain `Intent.ACTION_OPEN_DOCUMENT`), on result copies URI → `OdographDb.replaceWith` inside `pauseForRestore`/`resumeAfterRestore`.

- [ ] **Step 3: Ktor endpoints**

In `DashboardServer.kt` routing add:

```kotlin
get("/backup") {
    val dao = OdographDb.get(app).dao()
    if (settings.lanExportEnabled) {
        val tmp = File(cacheDir, "odograph-backup.db")
        OdographDb.snapshotTo(app, tmp)
        call.respondFile(tmp)
    } else {
        call.respondText("LAN export is off.\nTurn it on under SETUP → LAN DATA.\n", ContentType.Text.Plain)
    }
}
post("/restore") {
    if (!settings.lanExportEnabled) { respondText("off"); return@post }
    if (!TripRecorderService.pauseForRestore()) { respondText("restore refused: move the car away first"); return@post }
    val body = call.receiveChannel().readRemaining().readBytes()
    val tmp = File(cacheDir, "uploaded-backup.db")
    tmp.writeBytes(body)
    val result = runCatching { OdographDb.replaceWith(app, tmp) }
    TripRecorderService.resumeAfterRestore()
    call.respondText(if (result.isSuccess) "restored" else "restore failed: ${result.exceptionOrNull()?.message}", ContentType.Text.Plain)
}
```

(`respondFile` exists in ktor-server-cio: `io.ktor.server.response.respondFile`.)

- [ ] **Step 4: Run tests + commit**

Full `:app:testDebugUnitTest` → BUILD SUCCESSFUL (server test additions optional; add `DashboardHtmlTest`-style test asserting `/backup` honors the toggle if a harness exists, otherwise skip).
Commit: `feat(backup): in-app and LAN backup/restore with moving-trip guard`

---

## Task 11: Web app + JSON API

**Files:**
- Create: `app/src/main/web/index.html`, `app/src/main/web/app.js`, `app/src/main/web/style.css`
- Create: `app/src/main/java/in/odograph/tracker/server/ApiJson.kt`
- Modify: `app/src/main/java/in/odograph/tracker/server/DashboardServer.kt`
- Test: `app/src/test/java/in/odograph/tracker/server/ApiJsonTest.kt`

- [ ] **Step 1: ApiJson helpers**

```kotlin
object ApiJson {
    fun num(d: Double): String = "%.4f".format(d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    fun kv(key: String, value: String): String = "\"$key\":$value"
}
```

(Mirror `SheetsJson` conventions so `org.json` stays stubbed in tests.)

- [ ] **Step 2: Endpoints**

Add to `DashboardServer` routing, all gated by `settings.lanExportEnabled`:

`GET /api/live` (from `TripRecorderService.state.value`), `/api/trips`, `/api/charges`, `/api/places`, `/api/routes`, `/api/cost?bucket=today|7d|30d`, `/api/range`, `/api/drain`, `/api/telemetry`. Each is a `respondText(json, ContentType.Application.Json)` building with `ApiJson`.

- [ ] **Step 3: Static SPA**

Serve `app/src/main/web/*` under `/` for unknown paths:

```kotlin
static("/") { staticRootFolder = paths["src/main/web"]!! }
```

Use ktor `static` route (available in `io.ktor.server.http.content.*`). Keep existing generated pages at their paths; the SPA lives at `/` and calls `/api/*`.

- [ ] **Step 4: Build the SPA**

`index.html` + `app.js` (fetch live + cost + range + routes + drain + telemetry; render SOC/range header, cost bar chart, range sparkline via `<canvas>`, routes table, drain cards, telemetry coverage strip, Backup/Restore buttons hitting `/backup`+`/restore`) + `style.css` (dark, monospace-accent, mirrors the box's palette: `#0e1116` ground, `#4ec9b0` accent). No frameworks, no build step.

- [ ] **Step 5: API tests**

`ApiJsonTest.kt` asserts payload shape for each endpoint's serializers (string assertions, no org.json):

```kotlin
    @Test
    fun `live payload carries the driving numbers`() {
        val json = ApiJson.live(/* LiveState */)
        assertThat(json).contains("\"socPercent\"")
        assertThat(json).contains("\"rangeKm\"")
    }
```

(Skip true HTTP if no harness; test the serializer functions.)

- [ ] **Step 6: Run tests + commit**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
Commit: `feat(web): LAN SPA with live/API endpoints, backup and restore buttons`

---

## Task 12: Full pass + release

- [ ] **Step 1: Everything green**

`./gradlew :app:testDebugUnitTest --console=plain -q` → BUILD SUCCESSFUL
`./gradlew :app:lintDebug --console=plain -q` → BUILD SUCCESSFUL

- [ ] **Step 2: Build release**

`./gradlew :app:assembleRelease --console=plain -q` → APK at `app/build/outputs/apk/release/app-release.apk`

- [ ] **Step 3: Commit any stragglers**

`git status` clean; one final commit if anything is left: `chore: final commit for new-features batch`

- [ ] **Step 4: Push + refresh release asset**

```bash
git push
gh release upload v0.1.0 app/build/outputs/apk/release/app-release.apk --clobber
```

- [ ] **Step 5: Report**

Summarize per-workstream changes, the new DB version (7) and its Apps Script compatibility stance (payload unchanged → script not redeployed).