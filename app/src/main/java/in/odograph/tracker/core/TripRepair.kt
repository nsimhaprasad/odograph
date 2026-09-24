package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.TripEntity

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
     * The longest a row can last and still be a fragment rather than a drive, seconds.
     *
     * A minute. Nothing that took a car anywhere is over in less, and the fragments this stitches
     * back together were two and three seconds long.
     */
    const val FRAGMENT_MAX_S = 60L

    /**
     * The furthest a row can reach and still be a fragment, metres.
     *
     * A hundred. Not because a hundred metres of driving is nothing, but because a row that short
     * cannot have measured what it did: distance accumulates between fixes, and a trip lasting two
     * seconds holds one or two of them.
     */
    const val FRAGMENT_MAX_M = 100.0

    /**
     * The longest silence between two fragments that still makes them the same drive, ms.
     *
     * The fragments arrive two or three seconds apart because that is how fast the recorder was
     * restarting. A minute is far longer than that and far shorter than any real gap between
     * journeys, and the run has to break somewhere or every stub the car ever produced would be
     * stitched into one absurd drive spanning months.
     */
    const val STITCH_GAP_MS = 60_000L

    /** Fewer fragments than this in a row is not a shredded drive, just a short stop. */
    const val MIN_RUN = 3

    data class Stitched(val drivesRecovered: Int, val fragmentsAbsorbed: Int, val metresRecovered: Double)

    /**
     * Puts back together the drives a restarting recorder tore apart.
     *
     * A recorder that restarts opens a fresh trip when the car is next seen to move, and one that
     * restarts every two seconds opens one every two seconds. That happened: a single evening
     * produced three hundred rows, most of them zero kilometres, each lasting about as long as it
     * takes to read this sentence.
     *
     * The first attempt at this deleted them, on the reasoning that nothing under a hundred metres
     * and a minute can be a drive. The reasoning was wrong and the data said so plainly: those
     * three hundred rows ran from 17:12 to 17:29 and stepped through fourteen distinct places in
     * strict order, and the next surviving trip carried on from the fourteenth. The car was not
     * sitting still producing noise — it was being driven, and the recorder was shredding the
     * journey as it went. Each piece read nearly zero because distance accumulates *between*
     * fixes and a two-second trip holds one or two; the kilometres were destroyed by the
     * fragmentation, not absent from the world. Deleting the pieces would have erased the only
     * remaining evidence that the drive ever happened.
     *
     * So they are stitched instead. A run of fragments following each other within
     * [STITCH_GAP_MS] becomes the one drive it always was: the earliest row survives, every
     * other row's points and frames are handed to it, and the totals are recomputed from the
     * combined track — which is where the lost distance comes back, because the fixes either side
     * of each seam were always there, just filed under different trips.
     */
    fun stitchShreddedDrives(dao: OdographDao): Stitched {
        val fragments = dao.driveFragments(FRAGMENT_MAX_M, FRAGMENT_MAX_S)
        var drives = 0
        var absorbed = 0
        var metres = 0.0

        runs(fragments).forEach { run ->
            val keeper = run.first()
            val before = run.sumOf { it.distanceM }

            run.drop(1).forEach { fragment ->
                dao.movePointsTo(fragment.id, keeper.id)
                dao.moveBatteryTo(fragment.id, keeper.id)
                dao.deleteTrip(fragment.id)
            }

            val fixes = dao.pointsFor(keeper.id).map {
                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
            }
            if (fixes.isNotEmpty()) {
                val stats = TripStats.compute(fixes)
                dao.finishTrip(
                    keeper.id, fixes.last().t, stats.distanceM, stats.durationS,
                    stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps,
                    stats.elevGainM, stats.elevLossM
                )
                dao.setOrigin(keeper.id, fixes.first().lat, fixes.first().lon)
                dao.setDestination(keeper.id, fixes.last().lat, fixes.last().lon)
                metres += stats.distanceM - before
            }
            // The places were already resolved on the fragments; the drive ran from where the
            // first one began to where the last one ended, which is the sequence they recorded.
            dao.setTripPlaces(keeper.id, run.first().startPlaceId, run.last().endPlaceId)
            drives += 1
            absorbed += run.size - 1
        }
        return Stitched(drives, absorbed, metres)
    }

    /** Fragments grouped into the drives they were torn from, by the silence between them. */
    private fun runs(fragments: List<TripEntity>): List<List<TripEntity>> {
        val out = mutableListOf<List<TripEntity>>()
        var current = mutableListOf<TripEntity>()
        fragments.forEach { f ->
            val previous = current.lastOrNull()
            val continues = previous != null &&
                f.startedAt - (previous.endedAt ?: previous.startedAt) <= STITCH_GAP_MS
            if (!continues) {
                if (current.size >= MIN_RUN) out += current.toList()
                current = mutableListOf()
            }
            current += f
        }
        if (current.size >= MIN_RUN) out += current.toList()
        return out
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

    /** What clearing impossible energies did. */
    data class Cleared(val examined: Int, val cleared: Int)

    /**
     * Forgets every stored energy the car cannot physically have produced.
     *
     * The ceiling in [BatteryMath.plausible] arrived after these drives were written. Trip 435
     * sits in the history at 138 km/kWh — a counter that ticked once between two sparse frames —
     * and trip 426 at 14, one whole percent of charge across a short errand. Both feed the rolling
     * mean the driving screen quotes. Nothing that runs at close can reach them, and the sweep
     * only looks at drives with no figure at all, so this makes them drives with no figure: the
     * sweep then reckons them from the distance, which is a better answer than the one stored.
     *
     * Never invents. A drive with no energy is left alone; only a figure that fails the same test
     * a closing drive now faces is removed, and the charge levels it was derived from stay.
     */
    fun repairImplausibleEnergies(dao: OdographDao): Cleared {
        var examined = 0
        var cleared = 0
        for (trip in dao.closedTrips()) {
            val energy = trip.energyKwh ?: continue
            examined++
            if (!BatteryMath.plausible(energy, trip.distanceM)) {
                dao.clearEnergy(trip.id)
                cleared++
            }
        }
        return Cleared(examined, cleared)
    }

    /** What re-anchoring drives that began from a stale fix did. */
    data class Reanchored(val examined: Int, val reanchored: Int, val worstDays: Double)

    /**
     * A leading gap this long or longer means the first point was never part of the drive.
     *
     * An hour. A car does not sit at its origin for an hour with the recorder running and then
     * set off as the same drive — the arrival rule would have closed it long before. So a first
     * point that far ahead of the second is the location source's cached position, and the drive
     * actually began at the second.
     */
    const val STALE_START_GAP_MS = 60 * 60_000L

    /**
     * Re-derives drives whose start is a stale cached fix.
     *
     * The location source used to hand over the system's "last known" position as a real fix.
     * A network fix is stamped with the system clock, and on a box with no SIM that clock can be
     * sitting at the Android image's build date at boot — so the cached fix carried a date from
     * before the car existed. It became the origin of the next drive, and the drive was stored
     * with a duration of three hundred and thirteen days — which also made its average speed
     * nothing and its start date a lie in every list it appears in.
     *
     * The stale points are removed, the drive's start moves to its first real fix, and every
     * figure derived from the track is worked out again from what remains. Nothing else about
     * the drive changes: energy, cost and places were never a function of the stale point.
     */
    fun repairStaleStarts(dao: OdographDao): Reanchored {
        var examined = 0
        var reanchored = 0
        var worstDays = 0.0
        for (trip in dao.closedTrips()) {
            val points = dao.pointsFor(trip.id)
            if (points.size < 2) continue
            examined++

            // Walk forward past every point that is followed by a gap of an hour or more.
            var firstReal = 0
            while (firstReal < points.lastIndex &&
                points[firstReal + 1].t - points[firstReal].t >= STALE_START_GAP_MS
            ) firstReal++
            if (firstReal == 0) continue

            val stale = points[firstReal].t - points[0].t
            if (stale / 86_400_000.0 > worstDays) worstDays = stale / 86_400_000.0

            val kept = points.drop(firstReal)
            val fixes = kept.map {
                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
            }
            val stats = TripStats.compute(fixes)
            dao.deletePointsBefore(trip.id, kept.first().t)
            dao.setStartedAt(trip.id, kept.first().t)
            dao.finishTrip(
                trip.id, kept.last().t, stats.distanceM, stats.durationS,
                stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps,
                stats.elevGainM, stats.elevLossM
            )
            dao.setOrigin(trip.id, kept.first().lat, kept.first().lon)
            reanchored++
        }
        return Reanchored(examined, reanchored, worstDays)
    }
}
