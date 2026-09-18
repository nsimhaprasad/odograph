package `in`.odograph.tracker.core

/**
 * Rejecting speeds the car cannot have been doing.
 *
 * A trip's top speed was whatever the highest single fix claimed, filtered only on the receiver's
 * own accuracy estimate — and a GNSS spike routinely arrives with a confident accuracy attached.
 * One bad sample on a 38 km drive reported 195 km/h in a car that will not do 140, and because the
 * figure is stored with the trip it stays wrong for good: every "top speed" and every best-of
 * ranking built on that drive inherits it.
 *
 * Two independent objections, because each catches what the other misses. A ceiling catches the
 * absurd value that happens to arrive between two equally absurd ones. An acceleration limit
 * catches the plausible-looking value that a car could not possibly have reached from the speed it
 * was doing a second earlier — 0 to 195 in one second is not a fast car, it is a bad fix.
 *
 * Deliberately generous. The cost of rejecting a real reading is one under-reported maximum; the
 * cost of accepting a false one is a permanently corrupted record, so where the two are in tension
 * this errs towards keeping honest data and admits the occasional spike that looks like driving.
 */
object SpeedSanity {

    /**
     * Above this, no reading is believed, m/s.
     *
     * About 160 km/h against a car limited to roughly 140. The gap is deliberate headroom: a
     * ceiling set at the manufacturer's figure would clip genuine readings on a downhill stretch
     * or with a following wind, and the purpose here is to catch the impossible, not to police the
     * driver.
     */
    const val CEILING_MPS = 160f / 3.6f

    /**
     * The most a car can gain in a second, m/s².
     *
     * An EV of this class does nought to a hundred in about seven seconds, which is near 4 m/s².
     * Six leaves room for a keen launch downhill and still rejects a jump of tens of metres per
     * second between consecutive fixes.
     */
    const val MAX_ACCEL_MPS2 = 6.0f

    /**
     * The most it can lose in a second, m/s².
     *
     * Higher than the acceleration limit because brakes are stronger than motors, and a sharp stop
     * is a real thing a car does. Being lenient here costs nothing: a spuriously *low* reading
     * cannot inflate a maximum.
     */
    const val MAX_DECEL_MPS2 = 12.0f

    /**
     * The longest gap over which the acceleration test still means anything, seconds.
     *
     * Across a long gap — a tunnel, a dropped fix, a sleeping receiver — the limit permits almost
     * any change and stops discriminating, so beyond this only the ceiling applies. Without the
     * cap a ten-minute hole would licence any speed at all.
     */
    const val MAX_STEP_S = 10f

    /**
     * Whether a car doing [previousMps] could be doing [candidateMps] after [dtSeconds].
     *
     * A first reading has no predecessor to be judged against, so pass [previousMps] as null and
     * only the ceiling applies.
     */
    fun isPlausible(previousMps: Float?, candidateMps: Float, dtSeconds: Float): Boolean {
        if (!candidateMps.isFinite() || candidateMps < 0f) return false
        if (candidateMps > CEILING_MPS) return false
        if (previousMps == null || !previousMps.isFinite()) return true
        if (dtSeconds <= 0f) return candidateMps <= previousMps

        val window = minOf(dtSeconds, MAX_STEP_S)
        val change = candidateMps - previousMps
        return if (change >= 0f) change <= MAX_ACCEL_MPS2 * window
        else -change <= MAX_DECEL_MPS2 * window
    }

    /**
     * The fastest this run of fixes can be believed to have gone, m/s.
     *
     * Walks them in order holding the last speed that survived, so a rejected spike never becomes
     * the reference for judging the next reading — otherwise one bad fix would drag the threshold
     * up behind it and wave through everything that followed.
     */
    fun plausibleMaxSpeedMps(fixes: List<Fix>): Float {
        var previous: Float? = null
        var previousT: Long? = null
        var max = 0f

        for (fix in fixes.sortedBy { it.t }) {
            val dt = previousT?.let { (fix.t - it) / 1000f } ?: 0f
            if (isPlausible(previous, fix.speedMps, dt)) {
                if (fix.speedMps > max) max = fix.speedMps
                previous = fix.speedMps
                previousT = fix.t
            }
            // A rejected fix updates nothing: it is noise, and noise is not where the car was.
        }
        return max
    }
}
