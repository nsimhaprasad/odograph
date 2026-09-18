package `in`.odograph.tracker.core

/**
 * What the car costs under particular conditions, learned from the drives it has actually made.
 *
 * One number for the whole history answers "what does this car cost" and no useful question after
 * that. These split the same drives by when they happened, how they were driven and how hot it
 * was, which is what turns a range estimate from a fleet average into a statement about the drive
 * in front of you.
 */
object EfficiencyStats {

    /**
     * Drives needed before a bucket is allowed to speak.
     *
     * Two drives can differ by half for reasons that have nothing to do with the bucket — one
     * diversion, one passenger, one afternoon stuck behind a lorry. Three is the same floor
     * [BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE] uses before real-world efficiency is trusted over
     * the car's own guess, and the reasoning is identical.
     */
    const val MIN_DRIVES = 3

    /** One drive, reduced to what any of these questions need of it. */
    data class Sample(
        val startedAt: Long,
        val distanceM: Double,
        val movingS: Long,
        val energyKwh: Double,
        val tempC: Double?
    )

    /** What a group of drives cost, and how much of it there is to believe. */
    data class Bucket(val kwhPer100Km: Double, val drives: Int, val distanceKm: Double) {
        /** Range from a full pack at this consumption, km. */
        fun rangeAtFullKm(capacityKwh: Double): Double =
            BatteryMath.rangeAtFullKwh(capacityKwh, kwhPer100Km)
    }

    /**
     * Consumption for a set of drives, or null when there are too few to mean anything.
     *
     * The median, not the mean. Efficiency has a long tail on one side — a drive can cost far more
     * than usual for a hundred reasons and can never cost much less than physics allows — so a
     * mean is dragged upward by exactly the drives that were unrepresentative.
     */
    fun bucket(samples: List<Sample>): Bucket? {
        val usable = samples.filter {
            it.distanceM >= BatteryMath.MIN_EFFICIENCY_DISTANCE_M &&
                it.energyKwh >= BatteryMath.MIN_EFFICIENCY_ENERGY_KWH
        }
        if (usable.size < MIN_DRIVES) return null
        val per100 = usable.mapNotNull { BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM) }
        if (per100.size < MIN_DRIVES) return null
        return Bucket(
            kwhPer100Km = median(per100),
            drives = usable.size,
            distanceKm = usable.sumOf { it.distanceM } / 1000.0
        )
    }

    /** Day against night, which in this climate is mostly a question about heat and traffic. */
    fun byTimeOfDay(
        samples: List<Sample>,
        zone: java.util.TimeZone
    ): Map<DriveContext.TimeOfDay, Bucket> =
        samples.groupBy { DriveContext.timeOfDay(it.startedAt, zone) }
            .mapNotNull { (k, v) -> bucket(v)?.let { k to it } }
            .toMap()

    /** Stop-start against sustained running. */
    fun byCharacter(samples: List<Sample>): Map<DriveContext.Character, Bucket> =
        samples.groupBy { DriveContext.character(it.distanceM, it.movingS) }
            .mapNotNull { (k, v) -> if (k == null) null else bucket(v)?.let { k to it } }
            .toMap()

    /** By how hot it was outside, for the drives that recorded it. */
    fun byTemperature(samples: List<Sample>): Map<DriveContext.TempBand, Bucket> =
        samples.groupBy { DriveContext.tempBand(it.tempC) }
            .mapNotNull { (k, v) -> if (k == null) null else bucket(v)?.let { k to it } }
            .toMap()

    /**
     * The consumption to expect for a drive being made now, under these conditions.
     *
     * Prefers the most specific bucket that has enough drives behind it and falls back towards the
     * general, because a precise answer from two drives is worse than a vague one from fifty. The
     * order — character, then time of day, then everything — is by how much each actually moves
     * the number: how the car is driven dominates, when it was driven is mostly a proxy for that
     * plus heat, and the overall figure is the last resort.
     */
    fun expectedKwhPer100Km(
        samples: List<Sample>,
        character: DriveContext.Character?,
        timeOfDay: DriveContext.TimeOfDay?,
        zone: java.util.TimeZone
    ): Double? {
        character?.let { c -> byCharacter(samples)[c]?.let { return it.kwhPer100Km } }
        timeOfDay?.let { t -> byTimeOfDay(samples, zone)[t]?.let { return it.kwhPer100Km } }
        return bucket(samples)?.kwhPer100Km
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
