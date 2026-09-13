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
    val workingCurrent: Double? = null
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
    /** Ordinal of BatteryMath.ChargeKind. Null while the session is still open. */
    val kind: Int? = null,
    /** Rupees for the session's energy, snapshot at close with the rates then configured. */
    val costInr: Double? = null
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
