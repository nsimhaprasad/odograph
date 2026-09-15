# Design: Charge Session Tracking Improvements

**Date:** 2026-09-15
**Status:** Approved — implementation pending

## Summary

Six related changes to the charge-session subsystem: fix the power-calculation bug that misclassifies every session as SLOW, reclassify historical sessions, make session kWh editable, track charger-delivered kWh separately with loss %, attach charge locations with labeling and stats, and bump the speedometer ceiling.

---

## 1. Fix power calculation (root cause of 30 kW → SLOW)

**File:** `record/TripRecorderService.kt:391-393`

The telematics decoder (`windsor-telematics/internal/TapV21.kt`) already applies confirmed scales:
- `chargingVoltage = raw × 0.25` → real volts
- `chargingCurrent = 1000 − raw × 0.05` → real amps

The app re-applies these scales on the already-decoded values:

```kotlin
// BROKEN: re-applies decoder scales to decoded values
ch.chargingVoltage * 0.25 * (ch.chargingCurrent - 1000) * 0.05 / 1000
```

For a real 400 V / 75 A charger this yields −4.6 kW → clamped to 0 → every session classifies SLOW.

**Fix:** `powerKw = ch.chargingVoltage * ch.chargingCurrent / 1000`

Also fix battery-frame storage (lines 413-414) — `workingVoltage` and `workingCurrent` are similarly double-scaled. Store decoded values directly:

```kotlin
workingVoltage = ch.workingVoltage,   // decoder already: raw × 2.5
workingCurrent = ch.workingCurrent,   // decoder already: 1000 − raw × 0.05
```

**Consumer impact:** `chargedKwh()` (BatteryMath) integrates `chargingPowerKw` over intervals — the fix makes that integration correct for future sessions. Historical battery-frame power is permanently wrong but only affects the range-sparkline display, not cost.

---

## 2. Historical reclassification

The raw-frame log (`RawFrames`) retains only the last 24 captures — not enough for full history. The best available reconstruction uses **average power from session time + SOC-swing energy**:

```
avgPower = energyKwh / ((endTime - startTime) / 3_600_000)
kind = FAST if avgPower >= FAST_CHARGE_KW (10.0), else SLOW
```

For a 30 kW charger session (15.34 kWh / 0.51 h ≈ 30 kW → FAST). For a home session (15 kWh / 5 h ≈ 3 kW → SLOW). This correctly recovers the vast majority of misclassified sessions.

**Migration SQL:** iterate closed charge events, recompute kind from average power, write new `kind` value.

Edge case: sessions where the box slept through the start/end may have truncated duration. Average power is still directionally correct; any remaining misclassifications are correctable via the manual kind toggle (section 3).

---

## 3. Editable session kWh + charger-delivered kWh + loss %

### DB schema v8

```
ALTER TABLE charge_events ADD COLUMN deliveredKwh REAL;
ALTER TABLE charge_events ADD COLUMN placeId INTEGER;
ALTER TABLE charge_events ADD COLUMN lat REAL;
ALTER TABLE charge_events ADD COLUMN lon REAL;
```

### Entity changes

`ChargeEventEntity` gains four nullable fields:
- `deliveredKwh: Double?` — externally measured kWh (charger company app, Qubo smart-plug, etc.)
- `placeId: Long?` — resolved place ID (FK to places table)
- `lat: Double?` — raw latitude for sessions outside 150 m of any known place
- `lon: Double?` — raw longitude for sessions outside 150 m of any known place

### Pricing dialog

The `ChargeCostDialog` becomes `ChargeEditDialog` and gains three data fields:

| Field | Source | Editable | Visible |
|---|---|---|---|
| Car kWh (energyKwh) | SOC-swing, auto | Yes | Always |
| Delivered kWh | Wall meter (driver types it from charger app / Qubo) | Yes | When entered |
| Tariff ₹/kWh | Driver | Yes | When entered |
| Total bill ₹ | Driver | Yes | When entered |

All fields are **pre-filled** from existing session values when the dialog opens (fixes the "typed numbers not visible" bug). The driver can update any field at any time — weeks or months later — by finding the session by date/time and reopening the dialog.

**Cost rule:** Bill wins. If no bill, tariff applies to `deliveredKwh ?: energyKwh` — you pay for what the wall meter measured, not what the battery absorbed. This corrects the historical under-counting.

### Loss %

```
loss% = (1 - energyKwh / deliveredKwh) × 100
```

Displayed in:
- Each charge row (when deliveredKwh is present): `"−12% loss"` in a dim accent color
- The `ChargeStats` header: a new weighted-average cell showing overall loss %

### Editable kind

The dialog gains a FAST / SLOW toggle so the driver can correct any historical misclassification manually. The toggle defaults to the auto-classified value. Changing the kind recomputes cost via the active rate for that kind (FAST/SLOW rates differ), so the cost stays consistent with the corrected classification.

---

## 4. Charge location + labeling + stats

### Capture

On session open, snapshot the box's `lastFix` lat/lon. Use the existing `PlaceResolver` (150 m greedy leader) to resolve a place:
- Within 150 m of a known place → reuse it, increment visit count
- Outside 150 m → insert a new place (auto-named from coords), no label yet

Store `placeId` (and raw `lat`/`lon` for sessions outside 150 m) on the charge event.

### Live display

When a live session has a resolved place, show a banner at the top of the drive screen or charging section:
```
charging at HOME
```
(or the place label / coordinates if unlabeled)

### Labeling

Tap any charge row → the existing `ChargeEditDialog` (now also carries place info). The place label is editable via the same `OutlinedTextField` pattern used in PlacesScreen. Renaming a charge location renames it everywhere (same PlaceEntity).

### Per-location stats (ChargingScreen)

Add a **"Locations"** section below the fast/slow split:
```
HOME          47 charges   892.3 kWh   ₹3,124.00
STATION-X      3 charges    51.2 kWh   ₹768.00
```

Sorted by total kWh descending. Tapping a location row shows all sessions at that location filtered.

### Next-time recognition

On session open, the live prompt can include the place label: `"FAST CHARGE AT HOME"`.

---

## 5. Typed numbers visible

The dialog pre-fills all fields from existing values (`enteredRateInr`, `enteredBillInr`, `energyKwh`, `deliveredKwh`). Passing `key = e.id` ensures fields reset per session when switching between sessions.

---

## 6. Speedometer

**File:** `ui/gauge/Gauge.kt:19`

```
GAUGE_MAX_KMH = 160f   // was 120f (MG Windsor tops ~165 km/h)
```

---

## Files changed

| File | Change |
|---|---|
| `record/TripRecorderService.kt` | Fix power formula, fix workingVoltage/workingCurrent storage |
| `record/ChargeLedger.kt` | Pass corrected power to kind classification |
| `core/BatteryMath.kt` | (No change — `chargeKind` logic is correct, inputs were wrong) |
| `data/Entities.kt` | Add `deliveredKwh`, `placeId`, `lat`, `lon` to ChargeEventEntity |
| `data/OdographDb.kt` | Version 8, migration 7_8, reclassification SQL |
| `data/OdographDao.kt` | Add queries for per-location stats |
| `ui/ChargingScreen.kt` | Editable dialog (prefill + kWh + delivered + kind toggle), locations section, loss % display, row updates |
| `ui/OdographApp.kt` | Live "charging at <place>" banner |
| `ui/gauge/Gauge.kt` | GAUGE_MAX_KMH = 160f |
| `record/PlaceResolver.kt` | Integrate into charge session open flow |
| Tests | Update charge-dialog tests, add migration test, add location-stats tests |

---

## Migration SQL (7 → 8)

```sql
ALTER TABLE charge_events ADD COLUMN deliveredKwh REAL;
ALTER TABLE charge_events ADD COLUMN placeId INTEGER;
ALTER TABLE charge_events ADD COLUMN lat REAL;
ALTER TABLE charge_events ADD COLUMN lon REAL;
```

Then a Kotlin-side reclassification loop:
```sql
SELECT id, energyKwh, startTime, endTime, kind FROM charge_events WHERE kind IS NOT NULL
```
For each: recompute kind from average power. Write back if changed.

---

## Non-goals

- Automatic charger-delivered kWh reading (the box can't talk to the charger — driver enters it)
- Retroactive power-from-battery-frames reconstruction (corrupted, cosmetic only)
- New PlacesScreen changes (charging-location places are the same PlaceEntity with higher visit counts)
