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
     * The longest a row can last and still be certainly not a drive, seconds.
     *
     * A minute. Nothing that moved a car anywhere is over in less, and the rows this removes were
     * two and three seconds long.
     */
    const val NOT_A_DRIVE_S = 60L

    /**
     * The furthest a row can reach and still be certainly not a drive, metres.
     *
     * A hundred, which is parked GNSS wander on a bad fix rather than travel. The rows this
     * removes are mostly zero and the largest is ninety-two.
     */
    const val NOT_A_DRIVE_M = 100.0

    data class Swept(val removed: Int, val metresReclaimed: Double)

    /**
     * Removes rows that were never drives.
     *
     * A recorder that restarts opens a fresh trip when the car is next seen to move, and a
     * recorder that restarts every two seconds opens one every two seconds. That happened: one
     * evening produced three hundred rows, two hundred and twenty-three of them zero kilometres
     * and most of the rest under a hundred metres, each lasting about as long as it takes to
     * read this sentence. The cause is fixed — an interrupted drive is resumed now rather than
     * abandoned, and the recording pump no longer races itself — but the wreckage stays until
     * something clears it, and on that box it is three quarters of the history.
     *
     * Closing a trip already discards one that never moved; these survived only because parked
     * GNSS wander cleared the noise floor and made them look like travel. The test here is
     * deliberately cruder and much harder to argue with: under a hundred metres, under a minute,
     * and never instrumented. Nothing that took a car anywhere fits all three.
     *
     * Energy is the third condition rather than a nicety. A row the telematics ever attached a
     * reading to is a row something is known about, and known things are not swept up quietly.
     */
    fun removeNonDrives(dao: OdographDao): Swept {
        val doomed = dao.nonDrives(NOT_A_DRIVE_M, NOT_A_DRIVE_S)
        var metres = 0.0
        doomed.forEach { trip ->
            metres += trip.distanceM
            // The same three tables a discarded trip has always taken with it. A points row left
            // behind belongs to a trip id that no longer exists, which is worse than either.
            dao.deletePointsFor(trip.id)
            dao.deleteBatteryFor(trip.id)
            dao.deleteTrip(trip.id)
        }
        return Swept(doomed.size, metres)
    }

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
