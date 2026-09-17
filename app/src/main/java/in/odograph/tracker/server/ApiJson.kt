package `in`.odograph.tracker.server

import `in`.odograph.tracker.core.Period
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyEffRow
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.ParkedBatteryRow
import `in`.odograph.tracker.data.PeriodCharges
import `in`.odograph.tracker.data.PeriodCost
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.RouteTripEff
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.record.TripRecorderService

/**
 * Hand-rolled JSON for the LAN API, mirroring [in.odograph.tracker.sync.SheetsJson]'s convention
 * of building strings directly. org.json's Android stub returns nothing useful in JVM tests, and
 * strings are easy to assert on, so the API payloads stay byte-for-byte inspectable.
 */
object ApiJson {

    private fun num(d: Double): String = "%.4f".format(d).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun numOrNull(d: Double?): String = if (d == null) "null" else num(d)

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")

    private fun kv(key: String, value: String): String = "\"$key\":$value"

    // ---- live ----

    fun live(state: TripRecorderService.LiveState): String = buildString {
        append("{")
        append(kv("hasFix", state.hasFix.toString())).append(",")
        append(kv("speedKmh", num(state.speedMps * 3.6))).append(",")
        append(kv("distanceKm", num(state.distanceM / 1000.0))).append(",")
        append(kv("elapsedS", state.elapsedS.toString())).append(",")
        append(kv("movingS", state.movingS.toString())).append(",")
        append(kv("batterySocPercent", numOrNull(state.batterySocPercent))).append(",")
        append(kv("batteryCharging", state.batteryCharging?.toString() ?: "null")).append(",")
        append(kv("batteryRangeKm", numOrNull(state.batteryRangeKm))).append(",")
        append(kv("batteryRangeAtFullKm", numOrNull(state.batteryRangeAtFullKm))).append(",")
        append(kv("batteryMileageKmPerKwh", numOrNull(state.batteryMileageKmPerKwh))).append(",")
        append(kv("telematicsConnected", state.telematicsConnected?.toString() ?: "null")).append(",")
        append(kv("odoKm", numOrNull(state.odoKm))).append(",")
        append(kv("odoDriftKm", numOrNull(state.odoDriftKm)))
        append("}")
    }

    // ---- trips ----

    fun trips(trips: List<TripEntity>): String = buildString {
        append("[")
        trips.forEachIndexed { i, t ->
            if (i > 0) append(",")
            append("{")
            append(kv("id", t.id.toString())).append(",")
            append(kv("startedAt", t.startedAt.toString())).append(",")
            append(kv("distanceKm", num(t.distanceM / 1000.0))).append(",")
            append(kv("elapsedS", t.durationS.toString())).append(",")
            append(kv("movingS", t.movingS.toString())).append(",")
            append(kv("energyKwh", numOrNull(t.energyKwh))).append(",")
            append(kv("costInr", numOrNull(t.costInr))).append(",")
            append(kv("startPlaceId", t.startPlaceId?.toString() ?: "null")).append(",")
            append(kv("endPlaceId", t.endPlaceId?.toString() ?: "null"))
            append("}")
        }
        append("]")
    }

    // ---- charges ----

    fun charges(events: List<ChargeEventEntity>): String = buildString {
        append("[")
        events.forEachIndexed { i, e ->
            if (i > 0) append(",")
            append("{")
            append(kv("id", e.id.toString())).append(",")
            append(kv("startTime", e.startTime.toString())).append(",")
            append(kv("endTime", e.endTime?.toString() ?: "null")).append(",")
            append(kv("energyKwh", num(e.energyKwh))).append(",")
            append(kv("deliveredKwh", numOrNull(e.deliveredKwh))).append(",")
            append(kv("lossPct", numOrNull(BatteryMath.lossPct(e.energyKwh, e.deliveredKwh)))).append(",")
            append(kv("kind", when(e.kind) { 1 -> "\"fast\""; 0 -> "\"slow\""; else -> "null" })).append(",")
            append(kv("costInr", numOrNull(e.costInr))).append(",")
            append(kv("enteredRateInr", numOrNull(e.enteredRateInr))).append(",")
            append(kv("placeId", e.placeId?.toString() ?: "null"))
            append("}")
        }
        append("]")
    }

    // ---- places ----

    fun places(places: List<PlaceEntity>): String = buildString {
        append("[")
        places.forEachIndexed { i, p ->
            if (i > 0) append(",")
            append("{")
            append(kv("id", p.id.toString())).append(",")
            append(kv("lat", num(p.lat))).append(",")
            append(kv("lon", num(p.lon))).append(",")
            append(kv("visits", p.visits.toString())).append(",")
            append(kv("name", "\"${esc(p.displayName)}\""))
            append("}")
        }
        append("]")
    }

    // ---- routes ----

    fun routes(trips: List<RouteTripEff>, places: Map<Long, PlaceEntity>): String = buildString {
        append("[")
        trips.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{")
            append(kv("startPlaceId", r.startId.toString())).append(",")
            append(kv("endPlaceId", r.endId.toString())).append(",")
            append(kv("startName", "\"${esc(places[r.startId]?.displayName ?: "?")}\"")).append(",")
            append(kv("endName", "\"${esc(places[r.endId]?.displayName ?: "?")}\"")).append(",")
            append(kv("distanceKm", num(r.distanceM / 1000.0))).append(",")
            append(kv("energyKwh", num(r.energyKwh)))
            append("}")
        }
        append("]")
    }

    // ---- cost ----

    fun cost(period: String, trips: PeriodCost, charges: PeriodCharges): String = buildString {
        append("{")
        append(kv("period", "\"$period\"")).append(",")
        append(kv("tripDrives", trips.drives.toString())).append(",")
        append(kv("tripKm", num(trips.distanceM / 1000.0))).append(",")
        append(kv("tripEnergyKwh", num(trips.energyKwh))).append(",")
        append(kv("tripCostInr", num(trips.costInr))).append(",")
        append(kv("chargeSessions", charges.sessions.toString())).append(",")
        append(kv("chargeEnergyKwh", num(charges.energyKwh))).append(",")
        append(kv("chargeCostInr", num(charges.costInr)))
        append("}")
    }

    // ---- range ----

    fun range(days: List<DailyEffRow>): String = buildString {
        append("[")
        days.forEachIndexed { i, d ->
            if (i > 0) append(",")
            append("{")
            append(kv("day", d.day.toString())).append(",")
            append(kv("drives", d.drives.toString())).append(",")
            append(kv("distanceKm", num(d.distanceM / 1000.0))).append(",")
            append(kv("energyKwh", num(d.energyKwh)))
            append("}")
        }
        append("]")
    }

    // ---- drain ----

    fun drain(frames: List<ParkedBatteryRow>): String = buildString {
        append("[")
        frames.forEachIndexed { i, f ->
            if (i > 0) append(",")
            append("{")
            append(kv("t", f.t.toString())).append(",")
            append(kv("socPercent", numOrNull(f.socPercent)))
            append("}")
        }
        append("]")
    }

    // ---- telemetry ----

    fun telemetry(days: List<DailyTelemetryEntity>): String = buildString {
        append("[")
        days.forEachIndexed { i, d ->
            if (i > 0) append(",")
            append("{")
            append(kv("day", d.day.toString())).append(",")
            append(kv("firstPollAt", d.firstPollAt.toString())).append(",")
            append(kv("lastPollAt", d.lastPollAt.toString()))
            append("}")
        }
        append("]")
    }
}