package `in`.odograph.tracker.core

data class Endpoint(val lat: Double, val lon: Double)

/**
 * Groups endpoints into "places".
 *
 * Grid snapping is the obvious approach and it is wrong: two points 70 m apart land in different
 * cells whenever a cell boundary runs between them. Snapping guarantees "same cell implies
 * nearby" but not "nearby implies same cell", and it is the second property we need. Enlarging
 * the grid only moves the boundary.
 *
 * Greedy leader clustering instead: assign an endpoint to the nearest known place within
 * [radiusM], else start a new one. Distance to a centroid is a real metric, so proximity
 * behaves the way intuition expects.
 */
class PlaceIndex(private val radiusM: Double = 150.0) {

    private val centroids = mutableListOf<Endpoint>()
    private val counts = mutableListOf<Int>()

    val size: Int get() = centroids.size

    fun centroid(index: Int): Endpoint = centroids[index]

    fun assign(e: Endpoint): Int {
        val nearest = centroids.indices.minByOrNull {
            Geo.haversineMetres(centroids[it].lat, centroids[it].lon, e.lat, e.lon)
        }
        if (nearest != null) {
            val d = Geo.haversineMetres(
                centroids[nearest].lat, centroids[nearest].lon, e.lat, e.lon
            )
            if (d <= radiusM) {
                // Running mean keeps the centroid honest as repeat visits accumulate.
                val n = counts[nearest]
                centroids[nearest] = Endpoint(
                    lat = (centroids[nearest].lat * n + e.lat) / (n + 1),
                    lon = (centroids[nearest].lon * n + e.lon) / (n + 1)
                )
                counts[nearest] = n + 1
                return nearest
            }
        }
        centroids += e
        counts += 1
        return centroids.size - 1
    }
}

object RouteCluster {
    /** Counts how often each origin-to-destination pair occurs. Direction matters. */
    fun group(
        trips: List<Pair<Endpoint, Endpoint>>,
        radiusM: Double = 150.0
    ): Map<String, Int> {
        val places = PlaceIndex(radiusM)
        return trips
            .map { (from, to) -> "${places.assign(from)}>${places.assign(to)}" }
            .groupingBy { it }
            .eachCount()
    }
}
