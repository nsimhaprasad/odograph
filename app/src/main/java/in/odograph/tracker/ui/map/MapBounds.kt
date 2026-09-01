package `in`.odograph.tracker.ui.map

data class Bounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

object MapBounds {
    /** Smallest span we tolerate, so a trip that barely moved still gets a visible box. */
    private const val MIN_SPAN_DEG = 0.002

    fun of(points: List<Pair<Double, Double>>): Bounds? {
        if (points.isEmpty()) return null
        return Bounds(
            minLat = points.minOf { it.first },
            minLon = points.minOf { it.second },
            maxLat = points.maxOf { it.first },
            maxLon = points.maxOf { it.second }
        )
    }

    fun padded(b: Bounds, fraction: Double = 0.15): Bounds {
        val latSpan = (b.maxLat - b.minLat).coerceAtLeast(MIN_SPAN_DEG)
        val lonSpan = (b.maxLon - b.minLon).coerceAtLeast(MIN_SPAN_DEG)
        val padLat = latSpan * fraction
        val padLon = lonSpan * fraction
        val midLat = (b.maxLat + b.minLat) / 2
        val midLon = (b.maxLon + b.minLon) / 2
        return Bounds(
            minLat = midLat - latSpan / 2 - padLat,
            minLon = midLon - lonSpan / 2 - padLon,
            maxLat = midLat + latSpan / 2 + padLat,
            maxLon = midLon + lonSpan / 2 + padLon
        )
    }
}
