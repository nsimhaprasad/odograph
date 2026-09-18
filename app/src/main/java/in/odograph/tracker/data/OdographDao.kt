package `in`.odograph.tracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OdographDao {

    @Insert
    fun insertTrip(trip: TripEntity): Long

    fun startTrip(startedAt: Long): Long = insertTrip(TripEntity(startedAt = startedAt))

    @Insert
    fun appendPoint(point: PointEntity): Long

    @Query("SELECT * FROM trips WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun openTrip(): TripEntity?

    /**
     * One page of drives, newest first.
     *
     * The list screen reads this rather than the whole table. Rendering was always lazy, but the
     * loading was not: every drive ever made came into memory to show the dozen that fit on the
     * glass, which is fine for a year and not for a decade.
     */
    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT :limit OFFSET :offset")
    fun tripsPage(limit: Int, offset: Int): List<TripEntity>

    @Query("SELECT COUNT(*) FROM trips")
    fun tripCount(): Int

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun allTrips(): List<TripEntity>

    /** Every metre every finished trip covered — the app's own lifetime odometer, plus the baseline. */
    @Query("SELECT COALESCE(SUM(distanceM), 0) FROM trips WHERE endedAt IS NOT NULL")
    fun trackedDistanceM(): Double

    /** The most recent odometer the car itself quoted, km — the ground truth for drift checks. */
    @Query("SELECT odometerKm FROM battery WHERE odometerKm IS NOT NULL ORDER BY t DESC LIMIT 1")
    fun latestCarOdoKm(): Double?

    @Query("SELECT * FROM trips WHERE id = :id")
    fun tripById(id: Long): TripEntity?

    /** How many points a drive has, without reading a single one of them. */
    @Query("SELECT COUNT(*) FROM points WHERE tripId = :tripId")
    fun pointCountFor(tripId: Long): Int

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY t ASC")
    fun pointsFor(tripId: Long): List<PointEntity>

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY t DESC LIMIT 1")
    fun lastPointOf(tripId: Long): PointEntity?

    // ---- battery snapshots ----

    @Insert
    fun insertBattery(sample: BatteryEntity): Long

    @Query("SELECT * FROM battery WHERE tripId = :tripId ORDER BY t DESC LIMIT 1")
    fun lastBatteryOf(tripId: Long): BatteryEntity?

    @Query("SELECT * FROM battery WHERE tripId = :tripId ORDER BY t ASC")
    fun batteryRangeFor(tripId: Long): List<BatteryEntity>

    /**
     * Writes the trip-level charge summary. Each value is only ever replaced with a real reading:
     * COALESCE keeps whatever is stored when the argument is null, so a trip that produced no
     * charge data keeps nulls (meaning "no data"), never a fabricated zero.
     */
    @Query(
        "UPDATE trips SET socStart = COALESCE(:socStart, socStart), socEnd = COALESCE(:socEnd, socEnd), " +
            "energyKwh = COALESCE(:energyKwh, energyKwh) WHERE id = :id"
    )
    fun setChargeSummary(id: Long, socStart: Double?, socEnd: Double?, energyKwh: Double?)

    @Query("UPDATE trips SET costInr = :costInr WHERE id = :id")
    fun setTripCost(id: Long, costInr: Double?)

    /** Every instrumented trip's energy, newest first, for rolling efficiency. */
    /**
     * Recent instrumented drives, newest first, for the rolling efficiency figure.
     *
     * Bounded because the consumer only ever looks at the last handful — BatteryMath's window is
     * ten — while this query would otherwise return every instrumented drive the car has ever
     * made, growing without limit for the life of the app to answer a question about ten of them.
     */
    @Query(
        """SELECT distanceM, energyKwh FROM trips
           WHERE endedAt IS NOT NULL AND energyKwh IS NOT NULL
           ORDER BY startedAt DESC LIMIT :limit"""
    )
    fun tripEnergies(limit: Int = RECENT_EFFICIENCY_TRIPS): List<TripEnergy>

    /** Every instrumented drive. Only for the analyses that genuinely need the whole history. */
    @Query("SELECT distanceM, energyKwh FROM trips WHERE endedAt IS NOT NULL AND energyKwh IS NOT NULL ORDER BY startedAt DESC")
    fun allTripEnergies(): List<TripEnergy>

    /** Sum of the (positive) energy every drive consumed, kW·h — the car's lifetime fuel bill. */
    @Query("SELECT COALESCE(SUM(CASE WHEN energyKwh > 0 THEN energyKwh ELSE 0 END), 0) FROM trips")
    fun totalEnergyKwh(): Double

    /** Sum of every known drive cost — a trip never billed (unknown rate) contributes nothing. */
    @Query("SELECT COALESCE(SUM(costInr), 0) FROM trips")
    fun totalCostInr(): Double

    @Query("DELETE FROM battery WHERE tripId = :id")
    fun deleteBatteryFor(id: Long)

    // ---- parked battery frames (the drain window's source) ----

    /**
     * SOC snapshots taken while no trip was open, so a drive never owns them. These are what a
     * parked-night vampire-drain reading is computed from; charging frames are excluded upstream.
     */
    @Query("SELECT t, socPercent FROM battery WHERE tripId = -1 ORDER BY t ASC")
    fun parkedBatteryFrames(): List<ParkedBatteryRow>

    /** The -1 parked frames are meter records, not history: rows older than this are unneeded. */
    @Query("DELETE FROM battery WHERE tripId = -1 AND t < :olderThanMs")
    fun pruneParkedFrames(olderThanMs: Long)

    // ---- price reminders ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertReminder(reminder: PriceReminderEntity)

    /** The newest fast charge still owed an answer, oldest ask down to the newest one. */
    @Query("SELECT * FROM price_reminders WHERE ignoredAt IS NULL ORDER BY raisedAt DESC LIMIT 1")
    fun newestPendingReminder(): PriceReminderEntity?

    /** Marks the driver's decline; the reminder stops resurfacing. */
    @Query("UPDATE price_reminders SET ignoredAt = :at WHERE eventId = :eventId AND ignoredAt IS NULL")
    fun ignoreReminder(eventId: Long, at: Long)

    /** The driver priced it: the ask is answered, so the durable row goes away. */
    @Delete
    fun deleteReminder(reminder: PriceReminderEntity)

    // ---- charge sessions ----

    @Insert
    fun insertChargeEvent(event: ChargeEventEntity): Long

    /** The charge still awaiting more frames, i.e. not yet closed and priced. */
    @Query("SELECT * FROM charge_events WHERE kind IS NULL ORDER BY startTime DESC LIMIT 1")
    fun openChargeEvent(): ChargeEventEntity?

    @Query(
        "UPDATE charge_events SET endTime = :endTime, endSoc = :endSoc, " +
            "energyKwh = :energyKwh, peakPowerKw = :peakPowerKw, " +
            "samplesTotal = :samplesTotal, samplesAbove = :samplesAbove WHERE id = :id"
    )
    fun advanceChargeEvent(
        id: Long, endTime: Long, endSoc: Double?, energyKwh: Double,
        peakPowerKw: Double?, samplesTotal: Int, samplesAbove: Int
    )

    @Query("UPDATE charge_events SET kind = :kind, costInr = :costInr WHERE id = :id")
    fun closeChargeEvent(id: Long, kind: Int, costInr: Double)

    /**
     * Records what the driver actually paid for a (closed) session — a tariff with GST added on
     * top, or a total bill — and rewrites the session's price from it. Kind is left untouched:
     * pricing a session never re-labels it.
     */
    @Query(
        "UPDATE charge_events SET enteredRateInr = :enteredRateInr, enteredBillInr = :enteredBillInr, " +
            "gstRatePct = :gstRatePct, costInr = :costInr WHERE id = :id"
    )
    fun setChargeCost(
        id: Long, enteredRateInr: Double?, enteredBillInr: Double?,
        gstRatePct: Double?, costInr: Double?
    )

    /** Marks a still-open session as driver-priced so its close applies the entered cost. */
    @Query(
        "UPDATE charge_events SET enteredRateInr = :enteredRateInr, enteredBillInr = :enteredBillInr, " +
            "gstRatePct = :gstRatePct WHERE id = :id"
    )
    fun setChargeCostLedger(id: Long, enteredRateInr: Double?, enteredBillInr: Double?, gstRatePct: Double?)

    @Query("SELECT * FROM charge_events WHERE id = :id")
    fun chargeEvent(id: Long): ChargeEventEntity?

    /** Attaches the resolved charge location to a session the moment it opens. */
    @Query(
        "UPDATE charge_events SET placeId = :placeId, lat = :lat, lon = :lon WHERE id = :id"
    )
    fun setChargePlace(id: Long, placeId: Long, lat: Double, lon: Double)

    /** The driver's wall-meter kWh for a session; null clears it. */
    @Query("UPDATE charge_events SET deliveredKwh = :deliveredKwh WHERE id = :id")
    fun setChargeDelivered(id: Long, deliveredKwh: Double?)

    /**
     * Driver-corrections to a closed session, written wholesale when the edit dialog is applied:
     * the battery-side kWh, the wall-meter kWh, a price (tariff + frozen GST, or a total bill —
     * bill wins), and an explicit FAST/SLOW re-labelling. Cost is recomputed by the caller.
     */
    @Query(
        "UPDATE charge_events SET energyKwh = :energyKwh, deliveredKwh = :deliveredKwh, " +
            "kind = :kind, enteredRateInr = :enteredRateInr, enteredBillInr = :enteredBillInr, " +
            "gstRatePct = :gstRatePct, costInr = :costInr WHERE id = :id"
    )
    fun setChargeEdit(
        id: Long, energyKwh: Double, deliveredKwh: Double?, kind: Int,
        enteredRateInr: Double?, enteredBillInr: Double?, gstRatePct: Double?, costInr: Double?
    )

    /**
     * The most recent completed charge sessions that began before [ms] — the fills this drive
     * burned through. Priced-only, newest first, so a road trip where some refills were home and
     * some were fast gets both voices heard.
     */
    @Query("SELECT * FROM charge_events WHERE kind IS NOT NULL AND startTime < :ms ORDER BY startTime DESC LIMIT :limit")
    fun fillsBefore(ms: Long, limit: Int = 5): List<ChargeEventEntity>

    @Query("SELECT * FROM charge_events ORDER BY startTime ASC")
    fun allChargeEvents(): List<ChargeEventEntity>

    @Query("SELECT * FROM charge_events WHERE startTime >= :fromMs AND startTime < :toMs ORDER BY startTime DESC")
    fun chargeEventsBetween(fromMs: Long, toMs: Long): List<ChargeEventEntity>

    // ---- daily coverage ----

    /** The day's row exists in three flavours: absent, fresh, or already widened. */
    @Query("INSERT OR IGNORE INTO daily_telemetry(day, firstPollAt, lastPollAt) VALUES(:day, :first, :last)")
    fun insertTelemetryDayIfAbsent(day: Int, first: Long, last: Long)

    @Query("UPDATE daily_telemetry SET lastPollAt = :last WHERE day = :day")
    fun setTelemetryDayLast(day: Int, last: Long)

    @Query("SELECT * FROM daily_telemetry ORDER BY day ASC")
    fun allTelemetryDays(): List<DailyTelemetryEntity>

    @Query("SELECT * FROM daily_telemetry WHERE day = :day")
    fun telemetryDay(day: Int): DailyTelemetryEntity?

    @Query("SELECT COUNT(*) FROM points WHERE tripId = :tripId")
    fun pointCount(tripId: Long): Int

    @Query(
        """UPDATE trips SET endedAt = :endedAt, distanceM = :distanceM,
           durationS = :durationS, movingS = :movingS, maxSpeedMps = :maxSpeedMps,
           avgSpeedMps = :avgSpeedMps, slowestKmMps = :slowestKmMps,
           elevGainM = :elevGainM, elevLossM = :elevLossM WHERE id = :id"""
    )
    fun finishTrip(
        id: Long, endedAt: Long, distanceM: Double, durationS: Long,
        movingS: Long, maxSpeedMps: Float, avgSpeedMps: Double, slowestKmMps: Double,
        elevGainM: Double, elevLossM: Double
    )

    @Query("UPDATE trips SET startLat = :lat, startLon = :lon WHERE id = :id")
    fun setOrigin(id: Long, lat: Double, lon: Double)

    @Query("UPDATE trips SET endLat = :lat, endLon = :lon WHERE id = :id")
    fun setDestination(id: Long, lat: Double, lon: Double)

    @Query("UPDATE trips SET startedAt = :startedAt WHERE id = :id")
    fun setStartedAt(id: Long, startedAt: Long)

    /**
     * Corrects one stored top speed, leaving everything else about the trip alone.
     *
     * Surgical on purpose: recomputing a whole finished trip would also move its distance, its
     * energy and its cost, and a repair that quietly rewrites what a drive cost is not a repair.
     */
    @Query("UPDATE trips SET maxSpeedMps = :maxSpeedMps WHERE id = :id")
    fun setMaxSpeed(id: Long, maxSpeedMps: Float)

    /** Closed drives, oldest first, for a pass that has to look at each one's points. */
    @Query("SELECT * FROM trips WHERE endedAt IS NOT NULL ORDER BY startedAt ASC")
    fun closedTrips(): List<TripEntity>

    @Query("UPDATE trips SET clusterId = :clusterId WHERE id = :id")
    fun setCluster(id: Long, clusterId: Long)

    @Query("SELECT * FROM trips WHERE endedAt IS NOT NULL AND syncedAt IS NULL ORDER BY startedAt")
    fun unsyncedTrips(): List<TripEntity>

    @Query("UPDATE trips SET syncedAt = :at WHERE id = :id")
    fun markSynced(id: Long, at: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    fun deleteTrip(id: Long)

    /** Points and charge samples only matter while their trip exists. */
    @Query("DELETE FROM points WHERE tripId = :id")
    fun deletePointsFor(id: Long)

    // ---- docs export deltas ----

    /**
     * Closed trips the docs export has not yet uploaded. [beforeMs] is the settle grace: a trip's
     * energy/cost is finalised by the battery poller a moment after it closes, so exporting
     * something still settling would freeze an incomplete row on the workbook forever.
     */
    @Query("SELECT * FROM trips WHERE endedAt IS NOT NULL AND endedAt < :beforeMs AND id > :fromId ORDER BY id ASC")
    fun docsNewTrips(fromId: Long, beforeMs: Long): List<TripEntity>

    /** Charge sessions the docs export has not yet uploaded. Closed means stable. */
    @Query("SELECT * FROM charge_events WHERE kind IS NOT NULL AND id > :fromId ORDER BY id ASC")
    fun docsNewCharges(fromId: Long): List<ChargeEventEntity>

    /**
     * Battery frames the backup has not yet sent, oldest first.
     *
     * Bounded per sync so one upload cannot grow without limit after a long gap; the watermark
     * simply advances and the rest follows on the next run. Parked frames are included — they
     * carry the overnight drain and the near-full readings the pack is measured from, so a backup
     * without them restores a car with no battery history.
     */
    @Query("SELECT * FROM battery WHERE id > :fromId ORDER BY id ASC LIMIT :limit")
    fun docsNewBattery(fromId: Long, limit: Int = 2_000): List<BatteryEntity>

    /** Coverage days newer than the watermark, oldest first (today's mutable row included). */
    @Query("SELECT * FROM daily_telemetry WHERE day > :fromDay ORDER BY day ASC")
    fun telemetryDaysAfter(fromDay: Int): List<DailyTelemetryEntity>

    // ---- places ----

    @Insert
    fun insertPlace(place: PlaceEntity): Long

    @Query("SELECT * FROM places")
    fun allPlaces(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    fun placeById(id: Long): PlaceEntity?

    @Query("SELECT * FROM places WHERE autoName IS NULL AND label IS NULL")
    fun placesNeedingNames(): List<PlaceEntity>

    @Query("UPDATE places SET lat = :lat, lon = :lon, visits = :visits WHERE id = :id")
    fun updatePlacePosition(id: Long, lat: Double, lon: Double, visits: Int)

    @Query("UPDATE places SET label = :label WHERE id = :id")
    fun setPlaceLabel(id: Long, label: String?)

    @Query("UPDATE places SET autoName = :autoName, geocodedAt = :at WHERE id = :id")
    fun setPlaceAutoName(id: Long, autoName: String?, at: Long)

    @Query("UPDATE trips SET startPlaceId = :startId, endPlaceId = :endId WHERE id = :id")
    fun setTripPlaces(id: Long, startId: Long?, endId: Long?)

    /**
     * Per-place charge totals for the locations section: how many sessions, and the kWh and cost
     * they carried, grouped by the place the session charged at. The join resolves the label the
     * driver gave the spot; fallback coordinates live… on the charge row's own lat/lon.
     */
    @Query(
        """SELECT c.placeId AS placeId, p.label AS label, p.autoName AS autoName,
                  COUNT(*) AS sessions, COALESCE(SUM(c.energyKwh), 0) AS kwh,
                  COALESCE(SUM(c.costInr), 0) AS costInr
           FROM charge_events c
           LEFT JOIN places p ON p.id = c.placeId
           WHERE c.placeId IS NOT NULL
           GROUP BY c.placeId
           ORDER BY kwh DESC"""
    )
    fun chargePlaceStats(): List<ChargePlaceStatsRow>

    /** Most repeated origin-to-destination pairs. Direction matters: the return leg is its own route. */
    @Query(
        """SELECT startPlaceId AS startId, endPlaceId AS endId, COUNT(*) AS drives,
                  AVG(distanceM) AS avgDistanceM, AVG(durationS) AS avgDurationS,
                  AVG(movingS) AS avgMovingS, MAX(maxSpeedMps) AS bestMaxSpeedMps,
                  SUM(distanceM) AS totalDistanceM
           FROM trips
           WHERE endedAt IS NOT NULL AND startPlaceId IS NOT NULL AND endPlaceId IS NOT NULL
           GROUP BY startPlaceId, endPlaceId
           ORDER BY drives DESC, totalDistanceM DESC"""
    )
    fun routeSummaries(): List<RouteSummary>

    @Query(
        """SELECT startPlaceId AS startId, endPlaceId AS endId, COUNT(*) AS drives,
                  AVG(distanceM) AS avgDistanceM, AVG(durationS) AS avgDurationS,
                  AVG(movingS) AS avgMovingS, MAX(maxSpeedMps) AS bestMaxSpeedMps,
                  SUM(distanceM) AS totalDistanceM
           FROM trips
           WHERE endedAt IS NOT NULL AND startPlaceId IS NOT NULL AND endPlaceId IS NOT NULL
             AND startedAt >= :fromMs AND startedAt < :toMs
           GROUP BY startPlaceId, endPlaceId
           ORDER BY drives DESC, totalDistanceM DESC"""
    )
    fun routeSummariesBetween(fromMs: Long, toMs: Long): List<RouteSummary>

    @Query(
        """SELECT COUNT(*) AS drives,
                  COALESCE(SUM(distanceM), 0) AS distanceM,
                  COALESCE(SUM(durationS), 0) AS durationS,
                  COALESCE(SUM(movingS), 0) AS movingS,
                  COALESCE(MAX(maxSpeedMps), 0) AS bestMaxSpeedMps
           FROM trips
           WHERE endedAt IS NOT NULL AND startedAt >= :fromMs AND startedAt < :toMs"""
    )
    fun periodTotals(fromMs: Long, toMs: Long): PeriodTotals

    /** Calendar months in the device's own timezone, newest first. */
    @Query(
        """SELECT strftime('%Y-%m', startedAt / 1000, 'unixepoch', 'localtime') AS month,
                  COUNT(*) AS drives,
                  COALESCE(SUM(distanceM), 0) AS distanceM,
                  COALESCE(SUM(durationS), 0) AS durationS
           FROM trips
           WHERE endedAt IS NOT NULL
           GROUP BY month
           ORDER BY month DESC"""
    )
    fun monthlyTotals(): List<MonthTotal>

    // ---- cost buckets ----

    /** Drive side of a cost bucket: distance, energy, paid money and drive count since [fromMs]. */
    @Query(
        """SELECT COUNT(*) AS drives,
                  COALESCE(SUM(distanceM), 0) AS distanceM,
                  COALESCE(SUM(energyKwh), 0) AS energyKwh,
                  COALESCE(SUM(costInr), 0) AS costInr
           FROM trips
           WHERE endedAt IS NOT NULL AND startedAt >= :fromMs"""
    )
    fun periodCost(fromMs: Long): PeriodCost

    /** Recharge side of a cost bucket: sessions, energy and paid money since [fromMs]. */
    @Query(
        """SELECT COUNT(*) AS sessions,
                  COALESCE(SUM(energyKwh), 0) AS energyKwh,
                  COALESCE(SUM(costInr), 0) AS costInr
           FROM charge_events
           WHERE kind IS NOT NULL AND startTime >= :fromMs"""
    )
    fun periodCharges(fromMs: Long): PeriodCharges

    // ---- range@100 ----

    /** Per calendar day: distance and energy for closed, instrumented drives. RANGE@100 source. */
    @Query(
        """SELECT CAST(strftime('%Y%m%d', startedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS day,
                  COUNT(*) AS drives,
                  COALESCE(SUM(distanceM), 0) AS distanceM,
                  COALESCE(SUM(energyKwh), 0) AS energyKwh
           FROM trips
           WHERE endedAt IS NOT NULL AND energyKwh IS NOT NULL AND startedAt >= :fromMs
           GROUP BY day
           ORDER BY day ASC"""
    )
    fun dailyEfficiency(fromMs: Long): List<DailyEffRow>

    /**
     * Closed, instrumented drives with the conditions they were made in.
     *
     * One row per drive rather than a join over its samples: every efficiency question asked of
     * the history is "what did this cost, and in what conditions", and rejoining thousands of
     * battery rows to answer it is the shape of query that stops being free once there are years
     * of them. Bounded, newest first.
     */
    @Query(
        """SELECT startedAt, distanceM, movingS, energyKwh, avgTempC FROM trips
           WHERE endedAt IS NOT NULL AND energyKwh IS NOT NULL AND distanceM > 0
           ORDER BY startedAt DESC LIMIT :limit"""
    )
    fun efficiencySamples(limit: Int = EFFICIENCY_SAMPLE_LIMIT): List<EfficiencySampleRow>

    /**
     * Recent battery frames taken near a full charge, for measuring what the pack holds.
     *
     * Near full because the capacity sum divides by the state of charge, so its error grows as
     * that figure falls. Parked frames count: a car sitting on a charger is exactly where a
     * near-full reading comes from.
     */
    @Query(
        """SELECT * FROM battery
           WHERE socPercent >= :minSoc AND batteryEnergyKwh IS NOT NULL
           ORDER BY t DESC LIMIT :limit"""
    )
    fun highSocBattery(minSoc: Double, limit: Int = 200): List<BatteryEntity>

    /** The mean outside temperature a drive was made in, from its own frames. */
    @Query("SELECT AVG(exteriorTempC) FROM battery WHERE tripId = :tripId AND exteriorTempC IS NOT NULL")
    fun avgTempFor(tripId: Long): Double?

    @Query("UPDATE trips SET avgTempC = :avgTempC WHERE id = :id")
    fun setAvgTemp(id: Long, avgTempC: Double?)

    // ---- route efficiency ----

    /**
     * Every placed, energy-instrumented closed drive, newest first. The BEST/WORST ROUTES table
     * is computed from these in Analytics, where the per-trip quality bars live next to the math.
     */
    @Query(
        """SELECT startPlaceId AS startId, endPlaceId AS endId, distanceM, energyKwh
           FROM trips
           WHERE endedAt IS NOT NULL AND startPlaceId IS NOT NULL AND endPlaceId IS NOT NULL
             AND energyKwh IS NOT NULL
           ORDER BY startedAt DESC"""
    )
    fun routeTripsForEfficiency(): List<RouteTripEff>
}

/**
 * How many recent drives the conditioned efficiency figures look at.
 *
 * Generous, because these split into buckets — day against night, city against highway — and each
 * bucket needs enough drives of its own to say anything. Still bounded, so a decade of history
 * costs the same as a year.
 */
const val EFFICIENCY_SAMPLE_LIMIT = 400

/** How many recent drives the rolling efficiency figure is allowed to consider. */
const val RECENT_EFFICIENCY_TRIPS = 50

/** Drives per page in the trip list. Comfortably more than fills any window the box can give us. */
const val TRIP_PAGE_SIZE = 40

data class RouteSummary(
    val startId: Long,
    val endId: Long,
    val drives: Int,
    val avgDistanceM: Double,
    val avgDurationS: Double,
    val avgMovingS: Double,
    val bestMaxSpeedMps: Float,
    val totalDistanceM: Double
)

data class PeriodTotals(
    val drives: Int,
    val distanceM: Double,
    val durationS: Long,
    val movingS: Long,
    val bestMaxSpeedMps: Float
)

data class TripEnergy(
    val distanceM: Double,
    val energyKwh: Double
)

data class MonthTotal(
    val month: String,
    val drives: Int,
    val distanceM: Double,
    val durationS: Long
)

/** A parked (no open drive) battery frame, source of the overnight vampire-drain window. */
data class ParkedBatteryRow(
    val t: Long,
    val socPercent: Double?
)

/** Drive totals for a cost bucket. */
data class PeriodCost(
    val drives: Int,
    val distanceM: Double,
    val energyKwh: Double,
    val costInr: Double
)

/** Recharge totals for a cost bucket. */
data class PeriodCharges(
    val sessions: Int,
    val energyKwh: Double,
    val costInr: Double
)

/** One calendar day's closeable drive totals, feeding the RANGE@100 regression of efficiency. */
data class DailyEffRow(
    val day: Int,
    val drives: Int,
    val distanceM: Double,
    val energyKwh: Double
)

/** One closeable, placed drive with the raw ingredients of route efficiency. */
/** One drive, and the conditions it was made in. */
data class EfficiencySampleRow(
    val startedAt: Long,
    val distanceM: Double,
    val movingS: Long,
    val energyKwh: Double,
    val avgTempC: Double?
)

data class RouteTripEff(
    val startId: Long,
    val endId: Long,
    val distanceM: Double,
    val energyKwh: Double
)

/** One charge location's totals: sessions, kWh delivered to the battery, and its cost. */
data class ChargePlaceStatsRow(
    val placeId: Long,
    val label: String?,
    val autoName: String?,
    val sessions: Int,
    val kwh: Double,
    val costInr: Double
)
