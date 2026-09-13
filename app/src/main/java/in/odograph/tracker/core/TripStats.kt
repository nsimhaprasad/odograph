package `in`.odograph.tracker.core

data class Fix(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val accuracyM: Float,
    val interpolated: Boolean = false,
    /** Metres above the WGS84 ellipsoid. Null when the provider has no altitude, as network does. */
    val altitudeM: Double? = null
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

    /** Displacement must exceed the fix uncertainty by this much before it counts as movement. */
    private const val NOISE_FACTOR = 1.5f

    /**
     * A GNSS-reported direction of travel is velocity, so a parked receiver reads zero while a
     * drive reads tens of km/h. When the provider supplies no velocity at all (network fixes),
     * the displacement of the car from where it started stands in: genuine driving leaves the
     * origin, while parked GPS jitter is a bounded random walk around it.
     */
    private const val MIN_DIRECTION_MPS = 1.0f
    private const val MIN_DRIVE_M = 50.0

    /**
     * Whether these fixes describe a trip worth keeping. The car's engine can be on without the
     * car moving — a parked-and-idling session must never surface as a 0 km home-to-home trip.
     */
    fun moved(fixes: List<Fix>): Boolean {
        val stats = compute(fixes)
        if (stats.maxSpeedMps >= MIN_DIRECTION_MPS) return true
        val usable = fixes.sortedBy { it.t }
            .filter { it.accuracyM <= ACCURACY_LIMIT_M }
            .takeIf { it.size >= 2 } ?: return false
        val origin = usable.first()
        return usable.maxOf { Geo.haversineMetres(origin.lat, origin.lon, it.lat, it.lon) } >= MIN_DRIVE_M
    }

    fun compute(fixes: List<Fix>): Stats {
        if (fixes.size < 2) return Stats(0.0, 0, 0, 0f, 0.0, 0.0)
        val sorted = fixes.sortedBy { it.t }

        // Drop inaccurate fixes first, then measure between consecutive survivors. Rejecting a
        // whole segment because one endpoint was bad would destroy the two legs either side of a
        // single glitch and silently under-report distance; bridging over it keeps the real total.
        // Distance is measured from an anchor rather than between consecutive fixes.
        //
        // A fix is only a position plus an uncertainty, and comparing two consecutive ones
        // measures noise as often as movement: a network fix is accurate to about 20 m, while a
        // car at 50 km/h covers only 14 m in a second. Per-sample differencing therefore invents
        // distance while parked and cannot see real movement while driving.
        //
        // Holding an anchor and only counting once displacement clearly exceeds the uncertainty
        // solves both: noise never clears the bar, and real movement clears it within a second or
        // two and is then counted in full.
        val usable = sorted.filter { it.accuracyM <= ACCURACY_LIMIT_M }
        var distance = 0.0
        var anchor: Fix? = null
        for (fix in usable) {
            val from = anchor
            if (from == null) {
                anchor = fix
                continue
            }
            val moved = Geo.haversineMetres(from.lat, from.lon, fix.lat, fix.lon)
            val noiseFloor = maxOf(from.accuracyM, fix.accuracyM) * NOISE_FACTOR
            if (moved > noiseFloor) {
                distance += moved
                anchor = fix
            }
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
