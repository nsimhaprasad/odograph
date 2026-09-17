package `in`.odograph.tracker.core

/**
 * When a parked car has become a drive.
 *
 * The recorder used to open a trip the moment it booted, on the theory that whatever followed
 * could be thrown away later if the car turned out not to have moved — and it was, but only by the
 * *next* boot. In between, a car that started and sat there had an open 0 km row at the top of its
 * history. Opening the row when the car actually moves means it is never wrong to begin with, and
 * that turns "has it moved?" into a decision worth stating plainly and testing.
 *
 * Deliberately agrees with [TripStats.moved], which decides whether a finished trip was real. Two
 * different answers to the same question is how a drive gets recorded and then discarded.
 */
object Departure {

    /** Sustained speed that means the car is under way rather than drifting on GPS noise. */
    const val SPEED_MPS = 1.0f

    /** Consecutive fixes that must agree, so one bad fix cannot open a trip at a parked car. */
    const val CONFIRM_FIXES = 3

    /**
     * Displacement that counts as a departure whatever the speed says, metres.
     *
     * A crawl out of a car park may never sustain [SPEED_MPS], and a car that has ended up fifty
     * metres away has plainly gone somewhere. Matches [TripStats]'s own threshold.
     */
    const val DISPLACEMENT_M = 50.0

    /**
     * Whether [fixes] — the window held since the car was last still — shows a departure.
     *
     * [derivedSpeedMps] is the recorder's own anchor-based speed, which exists because some
     * providers report no speed at all; when it is available it stands in for the per-fix speeds,
     * which would otherwise all read zero and never open a trip on such a device.
     */
    fun departed(fixes: List<Fix>, derivedSpeedMps: Float = 0f): Boolean {
        if (fixes.size < CONFIRM_FIXES) return false
        if (derivedSpeedMps >= SPEED_MPS) return true
        if (fixes.takeLast(CONFIRM_FIXES).all { it.speedMps >= SPEED_MPS }) return true

        val origin = fixes.first()
        return fixes.any {
            Geo.haversineMetres(origin.lat, origin.lon, it.lat, it.lon) >= DISPLACEMENT_M
        }
    }
}
