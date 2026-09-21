package `in`.odograph.tracker.core

/**
 * The two range questions a driver actually asks, answered from two different populations.
 *
 * The drive screen has always shown one number, built from the last ten drives. That is the right
 * default — recent driving predicts the next hour better than last spring does — but it answers
 * neither of the questions people ask out loud, which are "what does this car really do" and "at
 * the rate I am going *now*, how far can I get".
 *
 * Those want opposite things from the data. The first wants every drive ever recorded, because the
 * whole point is to stop being swayed by a fortnight of unusual weather. The second wants only the
 * drive in progress, because the whole point is to be swayed by exactly that. Neither replaces the
 * rolling figure; all three are shown, and where they disagree is itself information — a live
 * number far above the lifetime one is a gentle hour, and far below it is a hint to ease off.
 */
object RangeModel {

    /**
     * Drives needed before a lifetime figure means anything.
     *
     * Higher than the rolling window's floor, and deliberately: a "lifetime" number computed from
     * three drives is not a lifetime number, it is a rolling one wearing a grander label, and the
     * grander label is what makes it misleading.
     */
    const val MIN_LIFETIME_DRIVES = 10

    /**
     * Distance this drive must cover before it can speak about its own consumption, metres.
     *
     * Two kilometres, matching [BatteryMath.MIN_EFFICIENCY_DISTANCE_M]. Below that the figure is
     * mostly quantisation: the car reports whole percent, so one percent of the pack is the
     * smallest energy it can express, and across a short hop that single step is the entire
     * measurement.
     */
    const val MIN_LIVE_DISTANCE_M = BatteryMath.MIN_EFFICIENCY_DISTANCE_M

    /**
     * What the car has really cost across every drive it has recorded.
     *
     * The median rather than the mean, for the same reason the buckets use one: consumption has a
     * long tail on one side and a floor on the other — a drive can cost far more than usual for a
     * hundred reasons and can never cost much less than physics allows — so a mean is pulled up by
     * precisely the drives that were unrepresentative. Over a whole history that tail is long.
     */
    fun lifetimeKwhPer100Km(drives: List<Pair<Double, Double?>>): Double? {
        val per100 = drives.mapNotNull { (distanceM, energyKwh) ->
            BatteryMath.kwhPer100Km(energyKwh, distanceM)
        }
        if (per100.size < MIN_LIFETIME_DRIVES) return null
        val sorted = per100.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * What the drive in progress is costing, from its own energy and its own distance.
     *
     * No history, no correction, no smoothing. This is the one number on the screen that is
     * allowed to be volatile, because a driver asking it has just changed something — joined a
     * motorway, switched the air conditioning on, started up a hill — and wants to see the
     * consequence rather than have it averaged away.
     */
    fun liveKwhPer100Km(energyKwh: Double?, distanceM: Double): Double? {
        if (distanceM < MIN_LIVE_DISTANCE_M) return null
        return BatteryMath.kwhPer100Km(energyKwh, distanceM)
    }

    /**
     * How far the charge in the pack goes at a given consumption.
     *
     * Null rather than infinity when the consumption is zero or unknown: a range of "no limit" is
     * the single most dangerous number this app could put in front of a driver.
     */
    fun remainingKm(capacityKwh: Double, socPercent: Double?, kwhPer100Km: Double?): Double? {
        if (socPercent == null || kwhPer100Km == null || kwhPer100Km <= 0.0) return null
        if (capacityKwh <= 0.0) return null
        val km = BatteryMath.rangeAtSocKwh(capacityKwh, socPercent, kwhPer100Km)
        return km.takeIf { it.isFinite() && it >= 0.0 }
    }
}
