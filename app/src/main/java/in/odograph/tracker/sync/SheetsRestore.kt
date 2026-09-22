package `in`.odograph.tracker.sync

import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rebuilding the database from the sheet.
 *
 * The other half of a backup, and the half that decides whether the first half was worth doing.
 * A sheet that is only ever written to is a record of what happened; one that can be read back is
 * the difference between a box dying and a box being replaced.
 *
 * Everything here is deliberately suspicious of its input. The sheet is a document a person can
 * open and edit — that is most of its value over a binary snapshot — which means a restore has to
 * assume rows have been sorted, columns widened, a cell cleared by accident, and a number turned
 * into text by a spreadsheet being helpful. A restore that trusts its input is one that replaces a
 * working history with a broken one.
 */
object SheetsRestore {

    /**
     * Schemas this build knows how to read.
     *
     * Older ones are not merely "missing a column": the meaning of what is there can have changed,
     * and guessing is how a restore puts the wrong values in the right names. A sheet from a newer
     * build is refused for the same reason from the other direction.
     */
    val SUPPORTED_SCHEMAS = setOf(8, 9, 13)

    data class Snapshot(
        val schema: Int?,
        val places: List<PlaceEntity>,
        val trips: List<TripEntity>,
        val points: List<PointEntity>,
        val battery: List<BatteryEntity>,
        val charges: List<ChargeEventEntity>,
        val telemetry: List<DailyTelemetryEntity>
    ) {
        val rows: Int
            get() = places.size + trips.size + points.size +
                battery.size + charges.size + telemetry.size
    }

    data class Result(val ok: Boolean, val message: String, val restored: Int = 0)

    /**
     * Reads a backup document, or explains why it will not.
     *
     * Refusing is the useful behaviour here far more often than salvaging. A half-understood
     * restore leaves a database that looks populated and is quietly wrong, which is worse than the
     * empty one it replaced, because nothing afterwards will suggest looking at it.
     */
    fun parse(body: String): kotlin.Result<Snapshot> = runCatching {
        val root = JSONObject(body)
        require(root.optString("kind") == "odograph-backup") {
            "that does not look like an Odograph backup — check the sheet's web-app link"
        }
        val schema = root.optInt("schema").takeIf { it > 0 }
            ?: SheetsJson.SCHEMA_VERSION  // an older export predates the field; assume its own era
        require(schema in SUPPORTED_SCHEMAS) {
            "the backup is schema $schema and this build reads ${SUPPORTED_SCHEMAS.sorted()}"
        }

        Snapshot(
            schema = schema,
            places = root.rows("places").map { it.place() },
            trips = root.rows("trips").map { it.trip() },
            points = root.rows("points").map { it.point() },
            battery = root.rows("battery").map { it.battery() },
            charges = root.rows("charges").map { it.charge() },
            telemetry = root.rows("telemetry").map { it.day() }
        )
    }

    /**
     * Replaces the database with the backup's contents.
     *
     * Replace rather than merge, and the choice is not a shortcut. Merging two histories that share
     * row ids and reference each other by them produces a third history belonging to neither: a
     * trip pointing at someone else's place, a charge session attached to a drive that is not the
     * one it happened on. A restore is for a box that has lost its data, and the honest thing is
     * to say plainly that this replaces what is there.
     *
     * The insert order follows the references — places before the trips that name them, trips
     * before the points and frames that belong to them — so nothing is ever written pointing at a
     * row that does not exist yet.
     */
    fun apply(dao: OdographDao, snapshot: Snapshot, clear: () -> Unit): Result {
        if (snapshot.rows == 0) {
            return Result(false, "the backup is empty — nothing was changed")
        }
        return runCatching {
            clear()
            snapshot.places.forEach { dao.insertPlace(it) }
            snapshot.trips.forEach { dao.insertTrip(it) }
            snapshot.points.forEach { dao.appendPoint(it) }
            snapshot.battery.forEach { dao.insertBattery(it) }
            snapshot.charges.forEach { dao.insertChargeEvent(it) }
            snapshot.telemetry.forEach {
                dao.insertTelemetryDayIfAbsent(it.day, it.firstPollAt, it.lastPollAt)
            }
            Result(
                true,
                "restored ${snapshot.trips.size} drives, ${snapshot.places.size} places, " +
                    "${snapshot.charges.size} charge sessions and ${snapshot.points.size} points",
                snapshot.rows
            )
        }.getOrElse { Result(false, "the restore failed part-way: ${it.message}") }
    }

    // ---------------------------------------------------------------- reading a spreadsheet

    private fun JSONObject.rows(name: String): List<JSONObject> {
        val array = optJSONArray(name) ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /**
     * A number from a cell, whatever the spreadsheet decided it was.
     *
     * Sheets will hand back a string where a number was written, and a blank where nothing was,
     * and both have to survive the trip. Null means the cell was empty, which is a fact worth
     * keeping rather than rounding to zero.
     */
    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val raw = get(key)
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.long(key: String): Long? = num(key)?.toLong()
    private fun JSONObject.int(key: String): Int? = num(key)?.toInt()

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.bool(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = get(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun JSONObject.place() = PlaceEntity(
        id = long("id") ?: 0L,
        lat = num("lat") ?: 0.0,
        lon = num("lon") ?: 0.0,
        visits = int("visits") ?: 0,
        label = str("label"),
        autoName = str("auto_name") ?: str("autoName"),
        geocodedAt = long("geocoded_at") ?: long("geocodedAt")
    )

    private fun JSONObject.trip() = TripEntity(
        id = long("id") ?: 0L,
        startedAt = long("start") ?: long("startedAt") ?: 0L,
        endedAt = long("end") ?: long("endedAt"),
        distanceM = num("km")?.times(1000.0) ?: num("distanceM") ?: 0.0,
        durationS = long("duration_s") ?: long("durationS") ?: 0L,
        movingS = long("moving_s") ?: long("movingS") ?: 0L,
        maxSpeedMps = (num("max_kmh")?.div(3.6) ?: num("maxSpeedMps") ?: 0.0).toFloat(),
        avgSpeedMps = num("avg_kmh")?.div(3.6) ?: num("avgSpeedMps") ?: 0.0,
        slowestKmMps = num("slowest_kmh")?.div(3.6) ?: num("slowestKmMps") ?: 0.0,
        startLat = num("start_lat"), startLon = num("start_lon"),
        endLat = num("end_lat"), endLon = num("end_lon"),
        socStart = num("soc_start"), socEnd = num("soc_end"),
        energyKwh = num("energy_kwh"), costInr = num("cost_inr"),
        elevGainM = num("climb_m") ?: 0.0, elevLossM = num("descent_m") ?: 0.0,
        avgTempC = num("avg_temp_c"),
        // Written by schema 13 onwards; an older sheet simply has no column and reads null, which
        // is the right answer — the conditions were never recorded, not recorded as nothing.
        climateShare = num("climate_share"),
        carEnergyKwh = num("car_energy_kwh"),
        carDistanceKm = num("car_distance_km"),
        clusterId = long("cluster_id"),
        startPlaceId = long("start_place_id"),
        endPlaceId = long("end_place_id")
    )

    private fun JSONObject.point() = PointEntity(
        id = 0L,
        tripId = long("trip_id") ?: long("tripId") ?: -1L,
        t = long("t_ms") ?: long("t") ?: 0L,
        lat = num("lat") ?: 0.0,
        lon = num("lon") ?: 0.0,
        speedMps = (num("speed_mps") ?: 0.0).toFloat(),
        bearingDeg = null,
        altitudeM = num("altitude_m"),
        accuracyM = (num("accuracy_m") ?: 0.0).toFloat(),
        interpolated = bool("interpolated") ?: false
    )

    private fun JSONObject.battery() = BatteryEntity(
        id = long("id") ?: 0L,
        tripId = long("trip_id") ?: long("tripId") ?: -1L,
        t = long("t_ms") ?: long("t") ?: 0L,
        socPercent = num("soc_pct") ?: num("socPercent"),
        charging = bool("charging"),
        rangeKm = num("range_km"),
        chargingPowerKw = num("charge_kw"),
        odometerKm = num("odometer_km"),
        batteryEnergyKwh = num("battery_kwh"),
        exteriorTempC = int("exterior_temp_c"),
        workingVoltage = num("working_v"),
        workingCurrent = num("working_a"),
        chargeTimeRemainingMin = int("charge_remaining_min"),
        distanceSinceLastChargeKm = num("dist_since_charge_km"),
        powerUsageSinceLastChargeKwh = num("power_since_charge_kwh"),
        climateRunning = bool("climate_on"),
        interiorTempC = int("interior_temp_c"),
        chargingType = int("charging_type"),
        pluggedIn = bool("plugged_in"),
        carCapacityKwh = num("car_capacity_kwh"),
        auxVoltage = num("aux_v"),
        carJourneyId = int("car_journey_id"),
        carJourneyDistanceRaw = int("car_journey_dist_raw"),
        engineStatusRaw = int("engine_status_raw"),
        powerModeRaw = int("power_mode_raw"),
        handbrake = bool("handbrake"),
        tyreFlPsi = num("tyre_fl_psi"),
        tyreFrPsi = num("tyre_fr_psi"),
        tyreRlPsi = num("tyre_rl_psi"),
        tyreRrPsi = num("tyre_rr_psi"),
        carGpsSatellites = int("car_gps_sats"),
        carGpsStatus = str("car_gps_status"),
        carSpeedKmh = num("car_speed_kmh"),
        chargerId = str("charger_id"),
        chargerSupplier = str("charger_supplier"),
        lastChargeEndKwh = num("last_charge_end_kwh"),
        staticDrainRaw = int("static_drain_raw"),
        chargeElapsedS = int("charge_elapsed_s"),
        dayDistanceRaw = int("day_dist_raw"),
        dayPowerRaw = int("day_power_raw")
    )

    private fun JSONObject.charge() = ChargeEventEntity(
        id = long("id") ?: 0L,
        startTime = long("start") ?: long("startTime") ?: 0L,
        endTime = long("end") ?: long("endTime"),
        startSoc = num("start_soc"), endSoc = num("end_soc"),
        energyKwh = num("energy_kwh") ?: 0.0,
        peakPowerKw = num("peak_kw"),
        kind = int("kind"),
        costInr = num("cost_inr"),
        deliveredKwh = num("delivered_kwh"),
        placeId = long("place_id"),
        samplesTotal = int("samples_total") ?: 0,
        samplesAbove = int("samples_above") ?: 0,
        reconstructed = bool("reconstructed") ?: false,
        enteredRateInr = num("entered_rate_inr"),
        enteredBillInr = num("entered_bill_inr"),
        gstRatePct = num("gst_rate_pct")
    )

    private fun JSONObject.day() = DailyTelemetryEntity(
        day = int("day") ?: 0,
        firstPollAt = long("first_poll_ms") ?: 0L,
        lastPollAt = long("last_poll_ms") ?: 0L
    )
}
