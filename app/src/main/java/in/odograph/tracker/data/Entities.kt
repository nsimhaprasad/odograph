package `in`.odograph.tracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    val movingS: Long = 0,
    val maxSpeedMps: Float = 0f,
    val avgSpeedMps: Double = 0.0,
    val slowestKmMps: Double = 0.0,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val clusterId: Long? = null,
    /** Resolved place this trip started from. Null until recovery assigns it. */
    val startPlaceId: Long? = null,
    /** Resolved place this trip ended at. */
    val endPlaceId: Long? = null,
    /** When this trip was accepted by the optional webhook. Null means never sent. */
    val syncedAt: Long? = null,
    // Reserved for v2 (OBD-II). Always null in v1 — a nullable column costs nothing today
    // and saves a migration later.
    val socStart: Double? = null,
    val socEnd: Double? = null,
    val energyKwh: Double? = null,
    /** Rupees the energy on this drive cost, from the weighted rate of its recent recharges. */
    val costInr: Double? = null,
    /** Metres climbed this drive, deadbanded against GNSS altitude noise. Battery consumption depends on it. */
    val elevGainM: Double = 0.0,
    /** Metres descended this drive. Always >= 0, so a there-and-back shows both directions. */
    val elevLossM: Double = 0.0
)

@Entity(tableName = "points", indices = [Index(value = ["tripId", "t"])])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val accuracyM: Float,
    val interpolated: Boolean
)

/**
 * A battery/charge snapshot taken by the iSMART poller while a trip is open.
 *
 * The poller is best-effort and out of the GPS capture path, so these rows are allowed to be
 * sparse (roughly one per 30 s when the vehicle network responds within the poll budget) and a
 * sample can legitimately carry a null SOC when the charging frame does not arrive.
 */
@Entity(tableName = "battery", indices = [Index(value = ["tripId", "t"])])
data class BatteryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val t: Long,
    val socPercent: Double? = null,
    val charging: Boolean? = null,
    val rangeKm: Double? = null,
    val chargingPowerKw: Double? = null,
    val workingVoltage: Double? = null,
    val workingCurrent: Double? = null,
    /** Odometer the car quoted on this frame, km. The fill book's distance context. */
    val odometerKm: Double? = null,
    /** Energy stored in the traction battery per the car, kW·h — the charge-status cross-check. */
    val batteryEnergyKwh: Double? = null,
    /** Minutes until the car considers the session complete, when plug-in charging. */
    val chargeTimeRemainingMin: Int? = null,
    /** km driven since the last charge finished, per the car. Null while actually charging. */
    val distanceSinceLastChargeKm: Double? = null,
    /** kW·h used since the last charge, per the car. Null while actually charging. */
    val powerUsageSinceLastChargeKwh: Double? = null
)

/**
 * A place the car repeatedly starts from or ends at.
 *
 * Clustering finds these; naming them is a separate, human step. A user [label] always wins over
 * the reverse-geocoded [autoName], because "Office" is more useful than "Koramangala 5th Block".
 */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val visits: Int = 0,
    val label: String? = null,
    val autoName: String? = null,
    val geocodedAt: Long? = null
) {
    val displayName: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: autoName?.takeIf { it.isNotBlank() }
            ?: "%.4f, %.4f".format(lat, lon)
}

/**
 * One plug-in charging session, aggregated by the poller from consecutive charging frames.
 *
 * Independent of trips on purpose: a parked charge belongs to no drive, and its frames live under
 * whichever open (and later discarded) trip the box had at the time, so the session has to carry
 * its own record or it dies with that trip.
 *
 * A row is "open" (awaiting more frames) while [kind] is null; the first non-charging frame, or a
 * gap longer than the session threshold, closes it by classifying it (SLOW/FAST) and pricing it.
 */
@Entity(tableName = "charge_events", indices = [Index(value = ["startTime"])])
data class ChargeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val startSoc: Double? = null,
    /** Timestamp of the last charging frame seen, i.e. the session end while open. */
    val endTime: Long? = null,
    val endSoc: Double? = null,
    /** kW·h added this session, derived from the SOC swing so slept-though nights cost nothing fake. */
    val energyKwh: Double = 0.0,
    val peakPowerKw: Double? = null,
    /** How many charging frames reported a power reading this session; the evidence for a kind. */
    val samplesTotal: Int = 0,
    /** How many of those readings sat at or above the fast threshold; the consistent majority. */
    val samplesAbove: Int = 0,
    /** Driver-entered fast-charger tariff, ₹/kW·h before GST. Null until they price the session. */
    val enteredRateInr: Double? = null,
    /** Driver-entered total bill in ₹, GST already included. Overrides the per-kWh tariff. */
    val enteredBillInr: Double? = null,
    /** GST percent applied on top of [enteredRateInr], frozen at entry time. */
    val gstRatePct: Double? = null,
    /** Ordinal of BatteryMath.ChargeKind. Null while the session is still open. */
    val kind: Int? = null,
    /** Rupees for the session's energy, snapshot at close with the rates then configured. */
    val costInr: Double? = null,
    /**
     * kW·h the wall meter actually delivered (charger company app, Qubo smart-plug, grid meter).
     * Driver-entered from the external app after the fact; null until entered. Cost is billed on
     * this when present because you pay for what the wall delivered, not what the battery
     * absorbed; the gap to [energyKwh] is the charging loss the driver references.
     */
    val deliveredKwh: Double? = null,
    /** The place this session charged at, resolved at session start. Null when unassigned. */
    val placeId: Long? = null,
    /** Latitude the box saw when the session opened; kept raw for a nameless public spot. */
    val lat: Double? = null,
    /** Longitude the box saw when the session opened; kept raw for a nameless public spot. */
    val lon: Double? = null
)

/**
 * One row per local day the box was alive and the poller reached the MG servers.
 *
 * Records the captured window, so the dashboard can be honest about what it did not see (a night
 * the box slept through is a gap between two rows, and a day with no row is a day no polling
 * happened at all).
 */
@Entity(tableName = "daily_telemetry")
data class DailyTelemetryEntity(
    @PrimaryKey val day: Int,
    val firstPollAt: Long,
    val lastPollAt: Long
)

/**
 * A fast charge the driver never priced, carried until they APPLY (priced → the row is deleted)
 * or IGNORE (ignoredAt is set).
 *
 * The live prompt only exists while the app is open; a charge the car locked and nobody priced
 * would otherwise be lost forever the moment the prompt is dismissed. This row is the durable
 * form of that ask: it resurfaces at the next drive start or app open, until one verdict is made.
 */
@Entity(tableName = "price_reminders")
data class PriceReminderEntity(
    /** The closed charge session this reminder asks the driver to price. */
    @PrimaryKey val eventId: Long,
    val raisedAt: Long,
    /** Set when the driver declines (IGNORE); null means still owed an answer. */
    val ignoredAt: Long? = null
)
