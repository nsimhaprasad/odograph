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
     * The longest a single drive can plausibly last, ms.
     *
     * Two days — the same ceiling the recorder puts on an open trip. A point sitting more than this
     * before a drive's end cannot belong to that drive: nobody drives for two days without the
     * arrival rule closing it. So such a point was stamped by a clock that had not yet corrected,
     * not recorded two days into a journey, and this is the horizon that tells the two apart.
     */
    const val MAX_PLAUSIBLE_DRIVE_MS = 48 * 3_600_000L

    /**
     * The earliest wall-clock time the box could truthfully report, ms since the epoch.
     *
     * The head-unit has no SIM and so no NTP; at boot its clock sits at the firmware image's build
     * date — observed at 15 Nov 2025 — until GNSS corrects it, seconds into the first drive. Any
     * reading before this floor is that uncorrected boot clock, not a real moment, and true time
     * only ever climbs further past it.
     */
    const val MIN_PLAUSIBLE_WALL_MS = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    /** Whether [wallMs] is on a corrected clock rather than the firmware's boot-time default. */
    fun clockSynced(wallMs: Long): Boolean = wallMs >= MIN_PLAUSIBLE_WALL_MS

    /** Where a drive's stale opening ends and its real clock begins, and the shift between them. */
    data class ClockSkew(
        /** Index of the first point already on the corrected clock; everything before it is stale. */
        val firstTrueIndex: Int,
        /** Milliseconds to add to each stale point so its wrong time becomes its real one. */
        val offsetMs: Long,
        /** The last stale timestamp, so a shift can be scoped to exactly the stale block. */
        val lastStaleT: Long
    )

    /**
     * Detects a drive whose opening points were stamped by an uncorrected boot clock.
     *
     * The box wakes with the firmware's build date for a clock — no SIM, so no NTP — and corrects
     * it the moment GNSS or NTP lands, often minutes into the first drive. That leaves the points
     * in two blocks: an opening stretch on the stale clock (ticking normally, a second or two
     * apart) and then one jump of weeks or months to the first corrected point and the rest of the
     * drive. The jump is the only discontinuity; inside each block time runs true.
     *
     * [anchor] is a timestamp known to be on the corrected clock — a closed drive's end, or the
     * live clock during one. Every stale point sits more than [MAX_PLAUSIBLE_DRIVE_MS] before it,
     * so the first point within that horizon opens the corrected block. The shift that carries the
     * stale block up to meet it is the jump itself, less one ordinary gap between fixes, so the two
     * blocks join seamlessly and the pre-correction driving keeps the distance and time it took.
     *
     * Returns null when nothing before the anchor is stale — including a drive run entirely before
     * the clock ever corrected (its end is stale too, so there is no corrected point to measure the
     * jump against, and its own track cannot place it in time at all).
     */
    fun detectClockSkew(pointTimes: List<Long>, anchor: Long): ClockSkew? {
        if (pointTimes.size < 2) return null
        var firstTrue = 0
        while (firstTrue < pointTimes.lastIndex &&
            anchor - pointTimes[firstTrue] > MAX_PLAUSIBLE_DRIVE_MS
        ) firstTrue++
        if (firstTrue == 0) return null

        val jump = pointTimes[firstTrue] - pointTimes[firstTrue - 1]
        val spacing = medianSpacing(pointTimes, firstTrue)
        val offset = (jump - spacing).coerceAtLeast(0L)
        if (offset == 0L) return null
        return ClockSkew(firstTrue, offset, pointTimes[firstTrue - 1])
    }

    /** The median gap between the stale opening points, or one second when there is only one. */
    private fun medianSpacing(times: List<Long>, firstTrue: Int): Long {
        if (firstTrue < 2) return 1_000L
        val gaps = (1 until firstTrue).map { times[it] - times[it - 1] }.sorted()
        return gaps[gaps.size / 2].coerceAtLeast(1L)
    }

    /** A drive's corrected start, and how far its clock had drifted, after [correctStaleOpening]. */
    data class CorrectedStart(val startedAt: Long, val offsetMs: Long)

    /**
     * Corrects, in place, a drive whose opening was stamped by an uncorrected boot clock.
     *
     * One lone stale point is a cached "last known" position the drive was wrongly begun from — not
     * part of this journey — and is dropped, exactly as the first version of this repair did. A
     * stale *block* is real driving at the right place and the wrong time, and is carried bodily
     * onto the corrected clock so none of its kilometres are lost. Either way the drive's true
     * start is its first surviving point, returned for the caller to store.
     *
     * Shared by the live re-anchor and the closed-trip sweep so the glass and the archive fix the
     * same corruption the same way. Returns null when there is nothing stale to correct.
     */
    fun correctStaleOpening(dao: OdographDao, tripId: Long, anchor: Long): CorrectedStart? {
        val points = dao.pointsFor(tripId)
        if (points.size < 2) return null
        val skew = detectClockSkew(points.map { it.t }, anchor) ?: return null
        if (skew.firstTrueIndex == 1) {
            dao.deletePointsBefore(tripId, points[1].t)
        } else {
            dao.shiftPointTimes(tripId, skew.offsetMs, skew.lastStaleT)
            // Any battery frames telematics wrote during the stale opening carry the same wrong
            // clock; move them by the same jump so a drive's energy readings line up with its track.
            dao.shiftBatteryTimes(tripId, skew.offsetMs, skew.lastStaleT)
        }
        val start = dao.pointsFor(tripId).firstOrNull()?.t ?: return null
        return CorrectedStart(start, skew.offsetMs)
    }

    /**
     * Re-derives every closed drive whose start was stamped by an uncorrected boot clock.
     *
     * The box has no SIM and so no NTP; at boot its clock sits at the Android image's build date
     * and corrects only once GNSS or NTP lands, minutes into the first drive. A drive that opened
     * before that correction was stored with a start from before the car existed and a duration of
     * weeks — a lie in every list it appears in — and, when the whole opening was recorded stale,
     * the earlier gap-based repair never caught it because its stale points were seconds apart, not
     * an hour. [correctStaleOpening] fixes both shapes against the drive's own trustworthy end, and
     * every figure derived from the track is worked out again from the corrected points. Nothing
     * else changes: energy, cost and places were never a function of when the opening was stamped.
     */
    fun repairStaleStarts(dao: OdographDao): Reanchored {
        var examined = 0
        var reanchored = 0
        var worstDays = 0.0
        for (trip in dao.closedTrips()) {
            val points = dao.pointsFor(trip.id)
            if (points.size < 2) continue
            examined++

            val anchor = trip.endedAt ?: points.last().t
            val corrected = correctStaleOpening(dao, trip.id, anchor) ?: continue
            if (corrected.offsetMs / 86_400_000.0 > worstDays) worstDays = corrected.offsetMs / 86_400_000.0

            val fixes = dao.pointsFor(trip.id).map {
                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
            }
            if (fixes.isEmpty()) continue
            val stats = TripStats.compute(fixes)
            dao.setStartedAt(trip.id, fixes.first().t)
            dao.finishTrip(
                trip.id, fixes.last().t, stats.distanceM, stats.durationS,
                stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps,
                stats.elevGainM, stats.elevLossM
            )
            dao.setOrigin(trip.id, fixes.first().lat, fixes.first().lon)
            reanchored++
        }
        return Reanchored(examined, reanchored, worstDays)
    }

    /** What removing double-booked fills did. */
    data class Deduped(val examined: Int, val removed: Int)

    /** How close two sessions' ends must be, in time, to be the same fill. */
    const val SAME_FILL_END_MS = 60_000L

    /**
     * Removes reconstructed fills that duplicate a watched one.
     *
     * The ledger closed a watched session on a frame, and the reconciler then ran on the same
     * frame, found a session that ended at 100 against a last-known reading of 65, and booked the
     * rise again "from charge level". Two rows for one overnight charge, both 65→100, ending at
     * the same instant. The reconciler no longer does this; these are the rows it left behind.
     *
     * Only a *reconstructed* row is ever removed, and only when a *watched* one ends within a
     * minute of it at the same level. A watched session is evidence the box saw with its own
     * eyes; a reconstruction is an inference, and the inference loses.
     */
    fun repairDuplicateReconstructions(dao: OdographDao): Deduped {
        val all = dao.allChargeEvents()
        val watched = all.filter { !it.reconstructed && it.endTime != null && it.endSoc != null }
        var examined = 0
        var removed = 0
        for (r in all) {
            if (!r.reconstructed || r.endTime == null || r.endSoc == null) continue
            examined++
            val twin = watched.any { w ->
                kotlin.math.abs(w.endTime!! - r.endTime) <= SAME_FILL_END_MS &&
                    kotlin.math.abs(w.endSoc!! - r.endSoc) <= ChargeReconciler.NOISE_PERCENT
            }
            if (twin) {
                dao.deleteChargeEvent(r.id)
                removed++
            }
        }
        return Deduped(examined, removed)
    }

    /** What thinning the stored tracks did. */
    data class Thinned(val drives: Int, val pointsRemoved: Int)

    /**
     * Thins every closed drive's stored track to the spacing a closing drive now keeps.
     *
     * Runs once on the revision gate for the history, and again whenever the driver asks from
     * Setup. Both are safe: a track already at the spacing loses nothing, so the second run and
     * the tenth do exactly what the first did to a drive that closed since — nothing, or only
     * what is due. The totals on each drive were measured from the full track when it closed and
     * are not touched here.
     */
    fun thinAllTracks(dao: OdographDao): Thinned {
        var drives = 0
        var removed = 0
        for (trip in dao.closedTrips()) {
            val n = `in`.odograph.tracker.record.TripRecovery.thinStoredTrack(dao, trip.id)
            if (n > 0) { drives++; removed += n }
        }
        return Thinned(drives, removed)
    }
}
