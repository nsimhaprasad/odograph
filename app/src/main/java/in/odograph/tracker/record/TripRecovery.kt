package `in`.odograph.tracker.record

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
     * @return the id of the freshly started trip.
     */
    fun recoverAndStart(dao: OdographDao, nowFromGnss: Long?): Long {
        var seedLat: Double? = null
        var seedLon: Double? = null

        dao.openTrip()?.let { orphan ->
            val points = dao.pointsFor(orphan.id)
            if (points.isEmpty()) {
                // Powered on and off without ever getting a fix. Nothing worth keeping.
                dao.deleteTrip(orphan.id)
            } else {
                val stats = TripStats.compute(
                    points.map { Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated) }
                )
                val first = points.first()
                val last = points.last()
                dao.finishTrip(
                    orphan.id, last.t, stats.distanceM, stats.durationS,
                    stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps
                )
                if (orphan.startLat == null) dao.setOrigin(orphan.id, first.lat, first.lon)
                dao.setDestination(orphan.id, last.lat, last.lon)
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
}
