package `in`.odograph.tracker.core

import kotlin.math.abs

/**
 * Working out what a drive cost when the telematics link was down for it.
 *
 * The box is powered by the car and the car is driven through basements, tunnels and forest, so a
 * drive with no MG frames inside it is not an edge case — it is a Tuesday. Until now such a drive
 * kept its nulls: no energy, no cost, no mileage, permanently, because the only source consulted
 * was the frames captured between its start and its end.
 *
 * There are two ways to do better, and they are not equally good, so they are kept apart.
 *
 * **Backfill is measurement.** The car's `powerUsageSinceLastChargeKwh` counter runs continuously
 * and resets only at a charge. A frame from before the drive and a frame from after it therefore
 * bracket the drive's real consumption, even if nothing was captured during it. This is the same
 * counter the app already trusts when it is sampled during a drive — the only difference is that
 * the two readings are further apart in time. It belongs in `energyKwh` with everything else
 * measured.
 *
 * **Estimation is a guess.** With no bracketing frames at all, the remaining move is to multiply
 * the distance by what the car has historically used. That is indicative, and it must never be
 * stored in `energyKwh`, because `energyKwh` is what the efficiency model learns from: a model fed
 * its own predictions agrees with itself ever more closely while getting no better, and the error
 * it reports shrinks as it goes. That failure is invisible from the inside, which is why the two
 * live in different columns rather than one column with a flag.
 */
object EnergyRecovery {

    /**
     * How far the car's own distance counter may disagree with the drive's measured distance
     * before the bracket is rejected, as a fraction.
     *
     * The check that makes backfill safe. Two frames either side of a drive only bracket *that*
     * drive if nothing else happened in between — no second trip the recorder missed, no charge
     * resetting the counters. The car's `distanceSinceLastChargeKm` moves by the distance the car
     * covered, so comparing its delta against the drive's own GNSS distance catches exactly that:
     * a bracket spanning two drives shows roughly twice the distance and is thrown away.
     *
     * A quarter, because the two distances are measured differently — the car counts wheel
     * rotations, the app integrates GNSS fixes, and they disagree by a few percent at the best of
     * times and by more when the sky is poor, which is precisely when this path is taken.
     */
    const val DISTANCE_TOLERANCE = 0.25

    /** The least distance worth bracketing, km. Below this the tolerance means nothing. */
    const val MIN_DISTANCE_KM = 1.0

    /** Readings taken either side of a drive, from frames outside it. */
    data class Bracket(
        /** The energy counter before the drive, kWh since the last charge. */
        val beforeKwh: Double?,
        /** The same counter after the drive. */
        val afterKwh: Double?,
        /** The distance counter before the drive, km since the last charge. */
        val beforeKm: Double?,
        /** The same counter after the drive. */
        val afterKm: Double?
    )

    /**
     * The energy a drive used, recovered from counters read either side of it.
     *
     * Null whenever the bracket cannot be trusted, which is most of the reasons it might exist:
     * a missing reading, a counter that went backwards because the car charged in between, or a
     * distance delta that does not match the drive and therefore spans more than it.
     *
     * [distanceKm] is the drive's own measured distance, the thing the car's counter is checked
     * against.
     */
    fun backfilledKwh(bracket: Bracket, distanceKm: Double): Double? {
        val energyDelta = counterDelta(bracket.beforeKwh, bracket.afterKwh) ?: return null
        if (energyDelta <= 0.0) return null

        // Without a distance to check against there is no way to know the bracket covers this
        // drive and nothing else, and an unchecked bracket is not measurement.
        val distanceDelta = counterDelta(bracket.beforeKm, bracket.afterKm) ?: return null
        if (distanceKm < MIN_DISTANCE_KM || distanceDelta <= 0.0) return null
        if (abs(distanceDelta - distanceKm) / distanceKm > DISTANCE_TOLERANCE) return null

        return BatteryMath.round2(energyDelta)
    }

    /**
     * A rising counter's delta, or null when it fell.
     *
     * A fall means the counter reset, which on this car means a charge happened between the two
     * readings — so the pair no longer describes one stretch of driving.
     */
    private fun counterDelta(before: Double?, after: Double?): Double? {
        if (before == null || after == null) return null
        if (!before.isFinite() || !after.isFinite()) return null
        val delta = after - before
        return if (delta < 0.0) null else delta
    }

    /**
     * What a drive of this length would be expected to cost, from what the car has been doing.
     *
     * Indicative, and labelled as such everywhere it surfaces. [kwhPer100Km] is the app's learned
     * figure — the same rolling, bias-corrected number the drive screen quotes — so this estimate
     * improves as the history grows, and is refused outright while there is no history to base it
     * on.
     */
    fun estimatedKwh(distanceKm: Double, kwhPer100Km: Double?): Double? {
        if (kwhPer100Km == null || !kwhPer100Km.isFinite() || kwhPer100Km <= 0.0) return null
        if (distanceKm < MIN_DISTANCE_KM || !distanceKm.isFinite()) return null
        return BatteryMath.round2(distanceKm * kwhPer100Km / 100.0)
    }
}
