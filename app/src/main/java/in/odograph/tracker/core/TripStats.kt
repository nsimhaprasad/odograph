package `in`.odograph.tracker.core

data class Fix(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val accuracyM: Float,
    val interpolated: Boolean = false
)

data class Stats(
    val distanceM: Double,
    val durationS: Long,
    val movingS: Long,
    val maxSpeedMps: Float,
    val avgSpeedMps: Double,
    val slowestKmSpeedMps: Double
)

object TripStats {
    private const val ACCURACY_LIMIT_M = 25f
    private const val SPEED_ACCURACY_LIMIT_M = 15f
    private const val MOVING_THRESHOLD_MPS = 0.5f

    fun compute(fixes: List<Fix>): Stats {
        if (fixes.size < 2) return Stats(0.0, 0, 0, 0f, 0.0, 0.0)
        val sorted = fixes.sortedBy { it.t }

        // Drop inaccurate fixes first, then measure between consecutive survivors. Rejecting a
        // whole segment because one endpoint was bad would destroy the two legs either side of a
        // single glitch and silently under-report distance; bridging over it keeps the real total.
        val usable = sorted.filter { it.accuracyM <= ACCURACY_LIMIT_M }
        var distance = 0.0
        for (i in 1 until usable.size) {
            val a = usable[i - 1]
            val b = usable[i]
            distance += Geo.haversineMetres(a.lat, a.lon, b.lat, b.lon)
        }

        var movingMs = 0L
        for (i in 1 until sorted.size) {
            if (sorted[i].speedMps > MOVING_THRESHOLD_MPS) movingMs += (sorted[i].t - sorted[i - 1].t)
        }

        val durationS = (sorted.last().t - sorted.first().t) / 1000
        val movingS = movingMs / 1000
        val maxSpeed = sorted.filter { it.accuracyM <= SPEED_ACCURACY_LIMIT_M }
            .maxOfOrNull { it.speedMps } ?: 0f
        val avg = if (movingS > 0) distance / movingS else 0.0
        return Stats(distance, durationS, movingS, maxSpeed, avg, slowestKm(sorted))
    }

    /** Worst rolling 1 km split — the honest replacement for "lowest speed", which is always 0. */
    private fun slowestKm(sorted: List<Fix>): Double {
        var worst = Double.MAX_VALUE
        var lo = 0
        var acc = 0.0
        for (hi in 1 until sorted.size) {
            acc += Geo.haversineMetres(
                sorted[hi - 1].lat, sorted[hi - 1].lon, sorted[hi].lat, sorted[hi].lon
            )
            while (acc >= 1000.0 && lo < hi - 1) {
                val seconds = (sorted[hi].t - sorted[lo].t) / 1000.0
                if (seconds > 0) worst = minOf(worst, 1000.0 / seconds)
                acc -= Geo.haversineMetres(
                    sorted[lo].lat, sorted[lo].lon, sorted[lo + 1].lat, sorted[lo + 1].lon
                )
                lo++
            }
        }
        return if (worst == Double.MAX_VALUE) 0.0 else worst
    }
}
