package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.PlaceEntity

/**
 * Creates a named place from a search pick, without ever duplicating one.
 *
 * If the coordinates already fall inside an existing place's radius, that place is re-labelled
 * and re-positioned instead of a second row appearing — otherwise every slightly-off search pick
 * would raise two "Home" rows and the route table's GROUP BY would split one commute in two.
 * Drives that start or end near it then count visits automatically, exactly like an auto-cluster.
 */
class PlaceRepo(
    private val dao: OdographDao,
    private val radiusM: Double = 150.0
) {

    fun createOrReposition(lat: Double, lon: Double, label: String?): PlaceEntity {
        val cleaned = label?.trim()?.takeIf { it.isNotBlank() }
        val existing = dao.allPlaces()
            .map { it to Geo.haversineMetres(it.lat, it.lon, lat, lon) }
            .filter { (_, d) -> d <= radiusM }
            .minByOrNull { (_, d) -> d }
            ?.first
        return if (existing != null) {
            dao.updatePlacePosition(existing.id, lat, lon, existing.visits)
            dao.setPlaceLabel(existing.id, cleaned)
            dao.placeById(existing.id)!!
        } else {
            val id = dao.insertPlace(PlaceEntity(lat = lat, lon = lon, visits = 0, label = cleaned))
            dao.placeById(id)!!
        }
    }

    /** True when the coordinates already belong to a known place — the "already have this" case. */
    fun nearest(lat: Double, lon: Double): PlaceEntity? = dao.allPlaces()
        .map { it to Geo.haversineMetres(it.lat, it.lon, lat, lon) }
        .filter { (_, d) -> d <= radiusM }
        .minByOrNull { (_, d) -> d }
        ?.first
}