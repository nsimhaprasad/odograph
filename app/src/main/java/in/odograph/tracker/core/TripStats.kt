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
    val slowestKmSpeedMps: Double,
    /** Metres climbed this trip, deadbanded against GNSS altitude noise. Always >= 0. */
    val elevGainM: Double,
    /** Metres descended this trip. Always >= 0, so a descending drive reads as gain-bad, loss-big. */
    val elevLossM: Double
)

object TripStats {
    private const val ACCURACY_LIMIT_M = 25f
    private const val SPEED_ACCURACY_LIMIT_M = 15f
    private const val MOVING_THRESHOLD_MPS = 0.5f

    /** A non-finite number is a corrupt fix; it must neither disturb nor poison the totals. */
    private fun Fix.sane(): Boolean = lat.isFinite() && lon.isFinite() && accuracyM.isFinite()

    /**
     * A GNSS altitude is only good to a few metres, so a raw per-sample difference would
     * manufacture hundreds of metres of "climb" out of parked jitter. A delta only counts once it
     * moves past this far from the last one that cleared the bar — and it is then counted in full,
     * the same anchor philosophy the distance loop uses. Shared with [LiveTrack] so the live
     * readout and stored totals cannot disagree.
     */
    internal const val ELEV_DEADBAND = 3.0

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
            .filter { it.sane() && it.accuracyM <= ACCURACY_LIMIT_M }
            .takeIf { it.size >= 2 } ?: return false
        val origin = usable.first()
        return usable.maxOf { Geo.haversineMetres(origin.lat, origin.lon, it.lat, it.lon) } >= MIN_DRIVE_M
    }

    fun compute(fixes: List<Fix>): Stats {
        if (fixes.size < 2) return Stats(0.0, 0, 0, 0f, 0.0, 0.0, 0.0, 0.0)
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
        val usable = sorted.filter { it.sane() && it.accuracyM <= ACCURACY_LIMIT_M }
        var distance = 0.0
        var elevGain = 0.0
        var elevLoss = 0.0
        var anchor: Fix? = null
        // A fix without altitude (network provider, interpolated bridge) must not become the
        // elevation reference — otherwise the climb either side of it would be lost. So elevation
        // keeps its own anchor that only advances when the moving fix carries an altitude.
        var elevAnchor: Fix? = null
        for (fix in usable) {
            val from = anchor
            if (from == null) {
                anchor = fix
                if (fix.altitudeM != null) elevAnchor = fix
                continue
            }
            val moved = Geo.haversineMetres(from.lat, from.lon, fix.lat, fix.lon)
            val noiseFloor = maxOf(from.accuracyM, fix.accuracyM) * NOISE_FACTOR
            if (moved > noiseFloor) {
                distance += moved
                // A GNSS altitude is only good to a few metres, so only count a delta once it
                // clears the deadband; anything smaller is jitter. Moving fixes only, so parked
                // GPS drift can never manufacture a climb. This mirrors LiveTrack exactly, so the
                // live readout and the stored total agree.
                if (fix.altitudeM != null) {
                    val fromAlt = elevAnchor?.altitudeM
                    if (fromAlt != null) {
                        val delta = fix.altitudeM - fromAlt
                        if (delta > ELEV_DEADBAND) elevGain += delta
                        else if (delta < -ELEV_DEADBAND) elevLoss += -delta
                    }
                    elevAnchor = fix
                }
                anchor = fix
            }
        }

        var movingMs = 0L
        for (i in 1 until sorted.size) {
            if (sorted[i].speedMps > MOVING_THRESHOLD_MPS) movingMs += (sorted[i].t - sorted[i - 1].t)
        }

        val durationS = (sorted.last().t - sorted.first().t) / 1000
        val movingS = movingMs / 1000
        // Not simply the highest reading. The receiver's own accuracy estimate is no defence
        // against a spike, because a spike routinely arrives with a confident accuracy attached —
        // which is how a 38 km drive came to record 195 km/h in a car that will not do 140, and
        // keep it, because the figure is stored with the trip.
        val maxSpeed = SpeedSanity.plausibleMaxSpeedMps(
            sorted.filter { it.sane() && it.accuracyM <= SPEED_ACCURACY_LIMIT_M }
        )
        val avg = if (movingS > 0) distance / movingS else 0.0
        return Stats(
            distance, durationS, movingS, maxSpeed, avg, slowestKm(sorted), elevGain, elevLoss
        )
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
