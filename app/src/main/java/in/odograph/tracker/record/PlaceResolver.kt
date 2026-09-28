package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.PlaceEntity

/**
 * Groups trip endpoints into named places, persistently.
 *
 * Greedy-leader clustering: assign to the nearest known place within [radiusM], otherwise start a
 * new one. Backed by the database so places accumulate across ignition cycles rather than being
 * rebuilt from scratch each boot — an in-memory version of the same rule preceded this and was
 * removed once nothing called it.
 */
class PlaceResolver(
    private val dao: OdographDao,
    private val radiusM: Double = 150.0,
    private val labeledRadiusM: Double = 1200.0
) {
    fun resolve(lat: Double, lon: Double): Long {
        val places = dao.allPlaces()

        // A named place captures within the wide radius: arriving a street away from home is
        // still arriving home. Nearest named place wins, and its centroid is left where the
        // driver anchored it — only the visit is counted, never averaged away.
        val labeled = places.filter { it.label != null }
            .map { it to Geo.haversineMetres(it.lat, it.lon, lat, lon) }
            .filter { it.second <= labeledRadiusM }
            .minByOrNull { it.second }
        if (labeled != null) {
            val p = labeled.first
            dao.updatePlacePosition(p.id, p.lat, p.lon, p.visits.coerceAtLeast(0) + 1)
            return p.id
        }

        // Otherwise the tight auto-cluster rule, unchanged.
        val nearest = places.minByOrNull {
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
