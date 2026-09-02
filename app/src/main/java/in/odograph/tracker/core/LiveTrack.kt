package `in`.odograph.tracker.core

/**
 * Running distance and speed for the drive in progress.
 *
 * Mirrors the anchor rule in [TripStats] so the live readout and the stored totals cannot
 * disagree, and derives speed when the provider does not supply it. The network provider returns
 * position with no velocity at all, because triangulating from WiFi and cell towers gives it no
 * Doppler measurement to work from — so without derivation the speedometer would sit at zero for
 * an entire drive while the map quietly filled in.
 */
class LiveTrack(
    private val accuracyLimitM: Float = 25f,
    private val noiseFactor: Float = 1.5f,
    /** Anything faster is a bad fix, not a car. */
    private val maxPlausibleMps: Float = 70f
) {
    private var anchor: Fix? = null

    var distanceM: Double = 0.0
        private set

    /** Provider speed when there is one, otherwise derived from displacement over time. */
    var speedMps: Float = 0f
        private set

    fun add(fix: Fix) {
        if (fix.accuracyM > accuracyLimitM) return

        val from = anchor
        if (from == null) {
            anchor = fix
            if (fix.speedMps > 0f) speedMps = fix.speedMps
            return
        }

        val moved = Geo.haversineMetres(from.lat, from.lon, fix.lat, fix.lon)
        val noiseFloor = maxOf(from.accuracyM, fix.accuracyM) * noiseFactor
        val seconds = (fix.t - from.t) / 1000.0

        if (moved > noiseFloor) {
            distanceM += moved
            val derived = if (seconds > 0) (moved / seconds).toFloat() else 0f
            speedMps = when {
                fix.speedMps > 0f -> fix.speedMps
                derived in 0f..maxPlausibleMps -> derived
                else -> 0f
            }
            anchor = fix
        } else if (fix.speedMps > 0f) {
            // GNSS reports speed directly even when the position has barely moved.
            speedMps = fix.speedMps
        } else if (seconds > STALE_AFTER_S) {
            // Nothing has cleared the noise floor for a while: the vehicle is stopped.
            speedMps = 0f
        }
    }

    private companion object {
        const val STALE_AFTER_S = 6.0
    }
}
