package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.OdographDao

/**
 * Correcting top speeds that a GNSS spike wrote into the history.
 *
 * [SpeedSanity] stops new ones, but every drive recorded before it keeps whatever the worst single
 * fix claimed — a Windsor limited to about 140 km/h with 195 km/h against a drive, permanently,
 * because the figure is stored rather than derived. The points those trips were built from are
 * still on disk, so the honest maximum can simply be worked out again.
 *
 * Only the top speed is touched. Recomputing a finished trip wholesale would also move its
 * distance, its energy and its cost, and a repair that quietly rewrites what a drive cost is not a
 * repair — it is a second, larger bug wearing the first one's clothes.
 */
object TripRepair {

    /**
     * How far above the plausible figure a stored one must sit before it is rewritten, m/s.
     *
     * About 2 km/h. Recomputation is not bit-identical to what the original pass stored — fixes
     * may have been filtered slightly differently — and rewriting every trip by a rounding error
     * would churn the whole history to no purpose and make the report meaningless.
     */
    const val TOLERANCE_MPS = 0.6f

    data class Outcome(val examined: Int, val corrected: Int, val worstBeforeMps: Float)

    /**
     * Walks every closed drive and lowers any top speed its own points cannot support.
     *
     * Never raises one. A stored figure below what the points imply is not evidence of a spike,
     * and might be a deliberately conservative value from an older rule; the purpose here is to
     * remove impossible readings, not to re-derive history.
     *
     * Trips whose points have gone are left exactly as they are: with no evidence there is no
     * basis to change anything, and a repair pass must never invent one.
     */
    fun repairMaxSpeeds(dao: OdographDao): Outcome {
        var examined = 0
        var corrected = 0
        var worst = 0f

        for (trip in dao.closedTrips()) {
            val points = dao.pointsFor(trip.id)
            if (points.isEmpty()) continue
            examined++

            val fixes = points.map {
                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
            }
            val plausible = SpeedSanity.plausibleMaxSpeedMps(fixes)
            if (trip.maxSpeedMps > plausible + TOLERANCE_MPS) {
                if (trip.maxSpeedMps > worst) worst = trip.maxSpeedMps
                dao.setMaxSpeed(trip.id, plausible)
                corrected++
            }
        }
        return Outcome(examined, corrected, worst)
    }
}
