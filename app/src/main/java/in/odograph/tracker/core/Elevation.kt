package `in`.odograph.tracker.core

import kotlin.math.abs

data class ElevationProfile(
    val currentM: Double?,
    val gainM: Double,
    val lossM: Double,
    val minM: Double?,
    val maxM: Double?,
    /** Signed percent grade over the recent window: positive climbing, negative descending. */
    val gradePercent: Double
) {
    val netM: Double get() = gainM - lossM
}

/**
 * Cumulative ascent and descent from GNSS altitude.
 *
 * GNSS altitude is far noisier than its horizontal position, typically two to three times worse,
 * so summing every sample-to-sample difference would report hundreds of metres of climb on a flat
 * road. The standard remedy is a hysteresis threshold: only commit a change once the altitude has
 * moved clearly away from the last committed level, which is what surveyors and cycle computers
 * do for exactly this reason.
 */
object Elevation {

    /** Altitude must move this far from the last committed level before it counts. */
    private const val THRESHOLD_M = 4.0

    fun profile(fixes: List<Fix>): ElevationProfile {
        val withAltitude = fixes.filter { it.altitudeM != null }
        if (withAltitude.isEmpty()) {
            return ElevationProfile(null, 0.0, 0.0, null, null, 0.0)
        }

        var gain = 0.0
        var loss = 0.0
        var committed = withAltitude.first().altitudeM!!

        withAltitude.forEach { fix ->
            val altitude = fix.altitudeM!!
            val delta = altitude - committed
            if (abs(delta) >= THRESHOLD_M) {
                if (delta > 0) gain += delta else loss += -delta
                committed = altitude
            }
        }

        val altitudes = withAltitude.mapNotNull { it.altitudeM }
        return ElevationProfile(
            currentM = altitudes.last(),
            gainM = gain,
            lossM = loss,
            minM = altitudes.min(),
            maxM = altitudes.max(),
            gradePercent = recentGrade(withAltitude)
        )
    }

    /** Grade over the last stretch of road, not the last sample: one sample is mostly noise. */
    private fun recentGrade(fixes: List<Fix>, overMetres: Double = 300.0): Double {
        if (fixes.size < 2) return 0.0
        val last = fixes.last()
        var run = 0.0
        for (i in fixes.size - 1 downTo 1) {
            val a = fixes[i - 1]
            val b = fixes[i]
            run += Geo.haversineMetres(a.lat, a.lon, b.lat, b.lon)
            if (run >= overMetres) {
                val rise = (last.altitudeM ?: return 0.0) - (a.altitudeM ?: return 0.0)
                return if (run > 0) rise / run * 100.0 else 0.0
            }
        }
        return 0.0
    }
}
