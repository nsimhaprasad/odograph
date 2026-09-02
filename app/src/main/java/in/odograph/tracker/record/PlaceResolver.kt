package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.PlaceEntity

/**
 * Persisted counterpart to [in.odograph.tracker.core.PlaceIndex].
 *
 * Same greedy-leader rule — assign to the nearest known place within [radiusM], otherwise start a
 * new one — but backed by the database so places accumulate across ignition cycles rather than
 * being rebuilt from scratch each boot.
 */
class PlaceResolver(
    private val dao: OdographDao,
    private val radiusM: Double = 150.0
) {
    fun resolve(lat: Double, lon: Double): Long {
        val nearest = dao.allPlaces().minByOrNull {
            Geo.haversineMetres(it.lat, it.lon, lat, lon)
        }
        if (nearest != null &&
            Geo.haversineMetres(nearest.lat, nearest.lon, lat, lon) <= radiusM
        ) {
            // Running mean: repeat visits pull the centroid towards where you actually park.
            val n = nearest.visits.coerceAtLeast(1)
            dao.updatePlacePosition(
                id = nearest.id,
                lat = (nearest.lat * n + lat) / (n + 1),
                lon = (nearest.lon * n + lon) / (n + 1),
                visits = n + 1
            )
            return nearest.id
        }
        return dao.insertPlace(PlaceEntity(lat = lat, lon = lon, visits = 1))
    }
}
