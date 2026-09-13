package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.BatteryMath
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
    fun recoverAndStart(
        dao: OdographDao,
        nowFromGnss: Long?,
        capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        homeRateInr: Double = 8.0,
        outsideRateInr: Double = 25.0
    ): Long {
        val places = PlaceResolver(dao)
        var seedLat: Double? = null
        var seedLon: Double? = null

        dao.openTrip()?.let { orphan ->
            val points = dao.pointsFor(orphan.id)
            val fixes = points.map { Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM) }
            if (fixes.isEmpty() || !TripStats.moved(fixes)) {
                // Either the engine never got a fix, or it idled without the car moving. A
                // parked session is not a trip and must not surface as a 0 km drive, so the
                // trip, its points, and any charge samples are all discarded together.
                dao.deleteTrip(orphan.id)
                dao.deletePointsFor(orphan.id)
                dao.deleteBatteryFor(orphan.id)
            } else {
                val stats = TripStats.compute(fixes)
                val first = points.first()
                val last = points.last()
                dao.finishTrip(
                    orphan.id, last.t, stats.distanceM, stats.durationS,
                    stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps,
                    stats.elevGainM, stats.elevLossM
                )
                if (orphan.startLat == null) dao.setOrigin(orphan.id, first.lat, first.lon)
                dao.setDestination(orphan.id, last.lat, last.lon)

                // Charge data only ever comes from the trip's own snapshots. Trips that never
                // produced a charging frame keep their nulls ("no data"), never a fabricated
                // value, because setChargeSummary refuses null arguments with COALESCE.
                val battery = dao.batteryRangeFor(orphan.id)
                if (battery.isNotEmpty()) {
                    val energy = BatteryMath.consumedKwh(battery, capacityKwh)
                        ?.let { BatteryMath.round2(it) }
                    dao.setChargeSummary(
                        orphan.id,
                        battery.first().socPercent,
                        battery.last().socPercent,
                        energy
                    )
                }

                // The most recent fills that began before this drive are what it burned. Their
                // blended rate prices its energy; energy that is negative (a drive that noted
                // charging) finances nothing, so only the positive part is ever billed.
                val energy = dao.tripById(orphan.id)?.energyKwh
                dao.setTripCost(
                    orphan.id,
                    driveCost(dao, energy, orphan.startedAt)?.let { BatteryMath.round2(it) }
                )

                // Now that the trip has real endpoints, attach it to the places it ran between.
                // This is what makes "most visited route" answerable with a GROUP BY.
                val fresh = dao.tripById(orphan.id)
                val originLat = fresh?.startLat ?: first.lat
                val originLon = fresh?.startLon ?: first.lon
                dao.setTripPlaces(
                    id = orphan.id,
                    startId = places.resolve(originLat, originLon),
                    endId = places.resolve(last.lat, last.lon)
                )

                seedLat = last.lat
                seedLon = last.lon
            }
        }

        val newId = dao.startTrip(nowFromGnss ?: 0L)
        val lat = seedLat
        val lon = seedLon
        if (lat != null && lon != null) dao.setOrigin(newId, lat, lon)
        return newId
    }

    /**
     * Bills a drive by the fills that completed before it started: [BatteryMath.fillsBefore]
     * respects [homeRateInr] and [outsideRateInr] snapshot at close, so a mix of slow home
     * refills and fast highway refills is blended by their energy into one honest rate. No fills,
     * or fills with no energy and thus no price, leave the cost unknown rather than a guess.
     */
    private fun driveCost(dao: OdographDao, energy: Double?, tripStart: Long): Double? {
        val billable = energy?.coerceAtLeast(0.0) ?: return null
        if (billable <= 0.0) return 0.0
        val fills = dao.fillsBefore(tripStart).filter { it.energyKwh > 0 && it.costInr != null }
        if (fills.isEmpty()) return null
        val avgRate = fills.sumOf { it.costInr!! } / fills.sumOf { it.energyKwh }
        return billable * avgRate
    }
}
