package `in`.odograph.tracker.ui

/** Projects lat/lon into a 0..1 box, preserving aspect so the route is not stretched. */
fun normaliseRoute(points: List<Pair<Double, Double>>): List<Pair<Float, Float>> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(0.5f to 0.5f)

    val minLat = points.minOf { it.first }
    val maxLat = points.maxOf { it.first }
    val minLon = points.minOf { it.second }
    val maxLon = points.maxOf { it.second }
    val span = maxOf(maxLat - minLat, maxLon - minLon).takeIf { it > 1e-9 }
        ?: return points.map { 0.5f to 0.5f }

    return points.map { (lat, lon) ->
        val x = ((lon - minLon) / span).toFloat().coerceIn(0f, 1f)
        val y = (1.0 - (lat - minLat) / span).toFloat().coerceIn(0f, 1f)
        x to y
    }
}
