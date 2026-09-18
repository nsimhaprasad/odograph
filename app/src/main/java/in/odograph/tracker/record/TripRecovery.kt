package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Arrival
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.TripStats
import `in`.odograph.tracker.data.OdographDao

object TripRecovery {
    /**
     * The car cuts power without warning, so a trip is never closed by the trip itself.
     * On every boot we find the trip the power cut orphaned, compute its totals from the points
     * it managed to write, close it, and seed the new trip's origin from where the old one
     * stopped — because the car did not teleport while it was parked.
     *
     * The charges a drive burned through tell us what the drive cost: the energy the trip
     * actually used, billed at the blended rate of the fills that started before it.
     *
     * @return the id of the freshly started trip.
     */
    /**
     * Where the last drive left the car, when the orphan it closed had somewhere to leave it.
     *
     * Carried separately from the trip because the next trip may not exist yet: a car that boots
     * and sits there has nothing to attach an origin to.
     */
    data class Recovery(val seedLat: Double? = null, val seedLon: Double? = null)

    /**
     * How long a drive may go unrecorded and still be the same drive.
     *
     * Matched to [Arrival.BUS_ASLEEP_STILL_MS], the shortest stillness that ends a drive: inside
     * that window the existing rules would not have closed the trip anyway, so resuming cannot
     * merge two outings the recorder would otherwise have kept apart. A process restart plus a
     * warm GNSS re-acquisition fits inside it comfortably. A device that power-cycled does not,
     * and should not — the box is powered by the car, so losing power means the ignition went off.
     */
    const val RESUME_WINDOW_MS = Arrival.BUS_ASLEEP_STILL_MS

    /**
     * How far the car may have got during that gap and still be resumable.
     *
     * A drive with a hole in it is worse than two drives that meet at the hole. The distance
     * across the gap becomes a straight line where the road was not, and every figure derived
     * from it — consumption, average speed, the odometer — inherits that error with nothing left
     * to show where it came from.
     */
    const val RESUME_RADIUS_M = 250.0

    /** What to do with a trip the previous run left open. */
    sealed interface Interrupted {
        /** The recorder was interrupted mid-drive: pick the same trip back up. */
        data class Resume(val tripId: Long, val startedAt: Long, val fixes: List<Fix>) : Interrupted

        /** The drive was over, or too much of it was missed to claim otherwise. */
        data class Closed(val recovery: Recovery) : Interrupted
    }

    /**
     * Decides whether the open trip is an interrupted drive or an orphan, once the first fix
     * after a restart has arrived.
     *
     * Deferred to that first fix deliberately, rather than settled at startup. The question is how
     * long the drive has gone unrecorded, and the only two timestamps that can answer it — the
     * trip's last stored point and the fix now in hand — are both GNSS. The box has no SIM and so
     * no NITZ, which leaves its own clock free to be hours out; deciding this by comparing a GNSS
     * timestamp against `System.currentTimeMillis()` would be deciding it at random.
     *
     * The bug this exists for: the recorder shares a process with the screen, so anything that
     * kills the process mid-drive — memory pressure, a crash, Android reclaiming the app — used
     * to close the drive and start a fresh one when the car was next seen to move. One outing
     * became two, the live readout went back to zero kilometres, and from the driver's seat the
     * trip had simply reset itself.
     */
    fun resumeOrClose(
        dao: OdographDao,
        fix: Fix,
        capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        homeRateInr: Double = 8.0,
        outsideRateInr: Double = 25.0
    ): Interrupted {
        val open = dao.openTrip() ?: return Interrupted.Closed(Recovery())
        val points = dao.pointsFor(open.id)
        val last = points.lastOrNull()
            ?: return Interrupted.Closed(close(dao, open.id, capacityKwh, homeRateInr, outsideRateInr))

        val gapMs = fix.t - last.t
        val movedM = Geo.haversineMetres(last.lat, last.lon, fix.lat, fix.lon)
        val interrupted = gapMs in 0..RESUME_WINDOW_MS && movedM <= RESUME_RADIUS_M

        if (!interrupted) {
            return Interrupted.Closed(close(dao, open.id, capacityKwh, homeRateInr, outsideRateInr))
        }
        return Interrupted.Resume(
            tripId = open.id,
            startedAt = open.startedAt,
            fixes = points.map {
                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
            }
        )
    }

    /**
     * Closes or discards whatever trip the last run left open, and reports where it ended.
     *
     * Deliberately does not open a new one. A trip row that exists before the car has moved is a
     * 0 km drive sitting at the top of the history for as long as the car stays parked, and the
     * only thing that ever cleaned it up was the *next* boot noticing it had not moved. Opening
     * the row when the car actually moves means it is never wrong in the first place — see
     * [startOnMove].
     */
    fun recover(
        dao: OdographDao,
        capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        homeRateInr: Double = 8.0,
        outsideRateInr: Double = 25.0
    ): Recovery = recoverInternal(dao, capacityKwh, homeRateInr, outsideRateInr)

    /**
     * Opens a trip at the moment the car was first seen to move, back-dated to [at].
     *
     * [at] is the first fix of the departure window rather than the instant the speed threshold
     * was crossed, so the trip still begins where the car was standing and the first hundred
     * metres are not lost to the confirmation delay.
     */
    fun startOnMove(
        dao: OdographDao,
        at: Long,
        originLat: Double,
        originLon: Double,
        recovery: Recovery = Recovery()
    ): Long {
        val id = dao.startTrip(at)
        // The previous drive's endpoint wins when there is one: it is a settled position, where
        // the first fix of a departure may still be converging.
        dao.setOrigin(id, recovery.seedLat ?: originLat, recovery.seedLon ?: originLon)
        return id
    }

    fun recoverAndStart(
        dao: OdographDao,
        nowFromGnss: Long?,
        capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        homeRateInr: Double = 8.0,
        outsideRateInr: Double = 25.0
    ): Long {
        val recovered = recoverInternal(dao, capacityKwh, homeRateInr, outsideRateInr)
        val newId = dao.startTrip(nowFromGnss ?: 0L)
        val lat = recovered.seedLat
        val lon = recovered.seedLon
        if (lat != null && lon != null) dao.setOrigin(newId, lat, lon)
        return newId
    }

    /**
     * Closes one trip properly, or discards it if the car never actually went anywhere.
     *
     * Shared by the two moments a trip can end: the car parking while the recorder is still
     * running, and a boot finding a trip the last run never got to close. They used to be one
     * code path only because parking did not end anything — the box is powered by the car, so
     * ignition-off ended trips by cutting power and the next boot tidied up. That works until the
     * recorder outlives the ignition, and it merges every errand of a single outing into one drive.
     *
     * Returns where the trip ended, so the next one can start from a settled position.
     */
    fun close(
        dao: OdographDao,
        tripId: Long,
        capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        homeRateInr: Double = 8.0,
        outsideRateInr: Double = 25.0
    ): Recovery {
        val trip = dao.tripById(tripId) ?: return Recovery()
        val points = dao.pointsFor(tripId)
        val fixes = points.map {
            Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
        }

        if (fixes.isEmpty() || !TripStats.moved(fixes)) {
            // Either the engine never got a fix, or it idled without the car moving. A parked
            // session is not a trip and must not surface as a 0 km drive, so the trip, its points,
            // and any charge samples are all discarded together.
            dao.deleteTrip(tripId)
            dao.deletePointsFor(tripId)
            dao.deleteBatteryFor(tripId)
            return Recovery()
        }

        val stats = TripStats.compute(fixes)
        val first = points.first()
        val last = points.last()
        dao.finishTrip(
            tripId, last.t, stats.distanceM, stats.durationS,
            stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps,
            stats.elevGainM, stats.elevLossM
        )
        if (trip.startLat == null) dao.setOrigin(tripId, first.lat, first.lon)
        dao.setDestination(tripId, last.lat, last.lon)

        // Charge data only ever comes from the trip's own snapshots. Trips that never produced a
        // charging frame keep their nulls ("no data"), never a fabricated value, because
        // setChargeSummary refuses null arguments with COALESCE.
        val battery = dao.batteryRangeFor(tripId)
        if (battery.isNotEmpty()) {
            val energy = BatteryMath.consumedKwh(battery, capacityKwh)?.let { BatteryMath.round2(it) }
            dao.setChargeSummary(tripId, battery.first().socPercent, battery.last().socPercent, energy)
        }

        // The most recent fills that began before this drive are what it burned. Their blended
        // rate prices its energy; energy that is negative (a drive that noted charging) finances
        // nothing, so only the positive part is ever billed.
        val energy = dao.tripById(tripId)?.energyKwh
        dao.setTripCost(tripId, driveCost(dao, energy, trip.startedAt)?.let { BatteryMath.round2(it) })

        // Stamp the conditions the drive was made in while its own frames are still to hand.
        // Denormalised deliberately: every efficiency question asked later is "what did this cost,
        // and how hot was it", and rejoining the samples to answer it is the shape of query that
        // stops being free once there are years of them.
        dao.setAvgTemp(tripId, dao.avgTempFor(tripId))

        // Now that the trip has real endpoints, attach it to the places it ran between. This is
        // what makes "most visited route" answerable with a GROUP BY.
        val places = PlaceResolver(dao)
        val fresh = dao.tripById(tripId)
        dao.setTripPlaces(
            id = tripId,
            startId = places.resolve(fresh?.startLat ?: first.lat, fresh?.startLon ?: first.lon),
            endId = places.resolve(last.lat, last.lon)
        )

        return Recovery(last.lat, last.lon)
    }

    private fun recoverInternal(
        dao: OdographDao,
        capacityKwh: Double,
        homeRateInr: Double,
        outsideRateInr: Double
    ): Recovery {
        val orphan = dao.openTrip() ?: return Recovery()
        return close(dao, orphan.id, capacityKwh, homeRateInr, outsideRateInr)
    }

    /**
     * Bills a drive by the fills that completed before it started: [BatteryMath.fillsBefore]
     * respects [homeRateInr] and [outsideRateInr] snapshot at close, so a mix of slow home
     * refills and fast highway refills is blended by their energy into one honest rate. No fills,
     * or fills with no energy and thus no price, leave the cost unknown rather than a guess.
     *
     * Public because the same exact billing is what the drive screen quotes live while the drive
     * is still running — "the total for this ride" must never disagree with the trip's final bill.
     */
    fun driveCost(dao: OdographDao, energy: Double?, tripStart: Long): Double? {
        val billable = energy?.coerceAtLeast(0.0) ?: return null
        if (billable <= 0.0) return 0.0
        val fills = dao.fillsBefore(tripStart).filter { it.energyKwh > 0 && it.costInr != null }
        if (fills.isEmpty()) return null
        val avgRate = fills.sumOf { it.costInr!! } / fills.sumOf { it.energyKwh }
        return billable * avgRate
    }
}
