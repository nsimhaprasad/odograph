package `in`.odograph.tracker.data

import androidx.room.Dao
import androidx.room.Insert
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

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun allTrips(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun tripById(id: Long): TripEntity?

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
    @Query("SELECT distanceM, energyKwh FROM trips WHERE endedAt IS NOT NULL AND energyKwh IS NOT NULL ORDER BY startedAt DESC")
    fun tripEnergies(): List<TripEnergy>

    /** Sum of the (positive) energy every drive consumed, kW·h — the car's lifetime fuel bill. */
    @Query("SELECT COALESCE(SUM(CASE WHEN energyKwh > 0 THEN energyKwh ELSE 0 END), 0) FROM trips")
    fun totalEnergyKwh(): Double

    /** Sum of every known drive cost — a trip never billed (unknown rate) contributes nothing. */
    @Query("SELECT COALESCE(SUM(costInr), 0) FROM trips")
    fun totalCostInr(): Double

    @Query("DELETE FROM battery WHERE tripId = :id")
    fun deleteBatteryFor(id: Long)

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
}

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
