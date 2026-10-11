package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.BatteryEntity

/**
 * Breaking a single drive into stretches and reading the consumption of each.
 *
 * One number for a whole journey hides the thing the driver most wants to see: the same outing can
 * run at ten km/kW·h crawling out of town in ECO+ and five flat out on the highway, and the average
 * — seven — describes neither. The car stamps every frame with its odometer and its own energy
 * counter, so the drive can be cut into equal distances and each stretch read on its own: how far,
 * how much energy, the km/kW·h that follows, the speed it was driven at, and how hard the climate
 * was working. The best and worst stretches fall straight out; the list in order is the trend.
 *
 * Energy comes from the same two sources, in the same order, as the whole-drive figure
 * ([BatteryMath.driveEnergyKwh]) — the car's own kW·h counter first, the state of charge as a
 * fallback — so a segment and the trip it belongs to can never disagree about where the energy went.
 */
object TripSegments {

    /** The distance each segment aims for; a drive splits into whole segments nearest this, km. */
    const val TARGET_SEGMENT_KM = 25.0

    /** The most segments a drive is ever cut into, however long — beyond this it is noise, not a trend. */
    const val MAX_SEGMENTS = 8

    /** Below this a drive is a single segment: too short to tell one stretch from another. */
    const val MIN_SPLIT_KM = 15.0

    /**
     * The shortest stretch that gets a km/kW·h figure, km.
     *
     * Three. The energy counter steps in tenths of a kW·h and the charge level in whole percent, so
     * over a kilometre or two the ratio is mostly quantisation — a stretch where the counter
     * happened to tick once reads twenty km/kW·h, one where it did not reads nothing. Neither means
     * anything. A stretch this short still shows its distance and speed, but no efficiency, and it
     * never sets the best, the worst, or the colour scale. No segment is ever cut shorter than this.
     */
    const val MIN_SEGMENT_KM = 3.0

    data class Segment(
        /** Distance into the drive at this stretch's start and end, km (odometer-relative). */
        val fromKm: Double,
        val toKm: Double,
        val distanceKm: Double,
        /** Energy this stretch drew, kW·h. Can be ~0 when regeneration cancelled it out. */
        val energyKwh: Double,
        /** Kilometres per kW·h over the stretch, or null when no energy could be measured. */
        val kmPerKwh: Double?,
        /** Average speed driven over the stretch, km/h, stops included. */
        val avgSpeedKmh: Double,
        /** Mean outside air temperature across the stretch, °C, when the car reported it. */
        val exteriorTempC: Double?,
        /** Fraction of the stretch the climate control was running, 0..1, or null when unknown. */
        val climateShare: Double?
    )

    data class Breakdown(
        val segments: List<Segment>,
        /** The whole drive's km per kW·h — the average the segments are read against. */
        val overallKmPerKwh: Double?,
        /** Index of the most and least efficient measurable segments; -1 when none can be measured. */
        val bestIndex: Int,
        val worstIndex: Int
    )

    /**
     * Cuts one drive's battery [frames] (any order) into consumption segments.
     *
     * The frames are first trimmed of charging at either end — the same trim the whole-drive energy
     * uses, so a fill while parked is never read as part of the journey — and the surviving driving
     * frames are split into equal odometer distances. A drive shorter than [MIN_SPLIT_KM] comes back
     * as a single segment so the caller always has the drive's own figure to show; a drive with no
     * odometer movement at all, or too few frames to difference, comes back null.
     */
    fun of(frames: List<BatteryEntity>, capacityKwh: Double): Breakdown? {
        val driving = frames
            .sortedBy { it.t }
            .dropWhile { it.charging == true }
            .dropLastWhile { it.charging == true }
            .filter { it.odometerKm != null }
        if (driving.size < 2) return null

        val startOdo = driving.first().odometerKm!!
        val totalKm = driving.last().odometerKm!! - startOdo
        if (totalKm <= 0.0) return null

        // Never cut more segments than the drive has [MIN_SEGMENT_KM] stretches to fill, so a short
        // drive is not sliced into meaningless slivers just because the target asked for more.
        val roomFor = (totalKm / MIN_SEGMENT_KM).toInt().coerceAtLeast(1)
        val count = if (totalKm < MIN_SPLIT_KM) 1
            else Math.round(totalKm / TARGET_SEGMENT_KM).toInt().coerceIn(2, minOf(MAX_SEGMENTS, roomFor))
        val binKm = totalKm / count

        // The frame index at which each distance boundary is first reached, 0..count. Segments share
        // their boundary frames, so the stretches' distances and energies sum back to the drive's.
        val bounds = IntArray(count + 1)
        bounds[count] = driving.lastIndex
        for (k in 1 until count) {
            val target = startOdo + k * binKm
            var i = bounds[k - 1]
            while (i < driving.lastIndex && driving[i].odometerKm!! < target) i++
            bounds[k] = i
        }

        val segments = (0 until count).mapNotNull { k ->
            val a = driving[bounds[k]]
            val b = driving[bounds[k + 1]]
            val dist = b.odometerKm!! - a.odometerKm!!
            if (dist <= 0.0) return@mapNotNull null
            val span = driving.subList(bounds[k], bounds[k + 1] + 1)
            val energy = BatteryMath.counterDelta(a.powerUsageSinceLastChargeKwh, b.powerUsageSinceLastChargeKwh)
                ?.takeIf { it > 0.0 }
                ?: BatteryMath.consumedFromSoc(a.socPercent, b.socPercent, capacityKwh)
                ?: 0.0
            val hours = (b.t - a.t) / 3_600_000.0
            val avgSpeed = span.mapNotNull { it.carSpeedKmh }.takeIf { it.isNotEmpty() }?.average()
                ?: if (hours > 0.0) dist / hours else 0.0
            val temps = span.mapNotNull { it.exteriorTempC }
            val climate = span.mapNotNull { it.climateRunning }
            // A figure only when the stretch is long enough for the ratio to mean something and the
            // energy it drew is physically possible for the distance — otherwise the stretch stands
            // with its distance and speed but no efficiency, excluded from best, worst and colour.
            val kmPerKwh = if (energy > 0.0 && dist >= MIN_SEGMENT_KM)
                (dist / energy).takeIf { it <= BatteryMath.MAX_PLAUSIBLE_KM_PER_KWH }?.let { BatteryMath.round2(it) }
            else null
            Segment(
                fromKm = BatteryMath.round2(a.odometerKm - startOdo),
                toKm = BatteryMath.round2(b.odometerKm - startOdo),
                distanceKm = BatteryMath.round2(dist),
                energyKwh = BatteryMath.round2(energy),
                kmPerKwh = kmPerKwh,
                avgSpeedKmh = BatteryMath.round2(avgSpeed),
                exteriorTempC = temps.takeIf { it.isNotEmpty() }?.average()?.let { BatteryMath.round2(it) },
                climateShare = climate.takeIf { it.isNotEmpty() }?.let { c -> c.count { it }.toDouble() / c.size }
            )
        }
        if (segments.isEmpty()) return null

        val measured = segments.withIndex().filter { it.value.kmPerKwh != null }
        val best = measured.maxByOrNull { it.value.kmPerKwh!! }?.index ?: -1
        val worst = measured.minByOrNull { it.value.kmPerKwh!! }?.index ?: -1
        val overall = BatteryMath.driveEnergyKwh(driving, capacityKwh, totalKm * 1000.0)
            ?.let { if (it > 0.0) BatteryMath.round2(totalKm / it) else null }
        return Breakdown(segments, overall, best, worst)
    }
}
