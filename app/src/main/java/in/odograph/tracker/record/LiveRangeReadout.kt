package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.RangeCalibration
import `in`.odograph.tracker.core.RangeModel
import `in`.odograph.tracker.core.Telematics
import `in`.odograph.tracker.data.OdographDao
import io.windsor.telematics.ChargeStatus

/**
 * The range and efficiency figures the driving screen shows, worked out from one frame.
 *
 * This was the middle sixty lines of the telematics poll, and the only way to check any of it was
 * to run the service. It is arithmetic over a handful of history queries, and the history queries
 * are the only reason it lives in `record` rather than `core`.
 *
 * Two of its figures are predictions and must hold still; one is a live rate and must not. The
 * range at the current charge and the range at full are what the driver plans on, and they moved
 * between 200 and 400 km in one ride: the drive's own first kilometre was being averaged in as a
 * full sample, and between trips the figure was dropped and the car's own quote shown instead.
 * The range at this drive's rate is the one that is meant to move — it is how the driver sees the
 * hill they are on — and it stays exactly as volatile as it was.
 */
object LiveRangeReadout {

    /**
     * How far a drive must have gone before its own figure joins the prediction, metres.
     *
     * Five kilometres. Below that the energy is one or two quantisation steps and the rate can be
     * out by a factor of five either way. Even past it the drive enters weighted by its distance,
     * so it cannot outvote a week of history on its first afternoon.
     */
    const val MIN_LIVE_SAMPLE_M = 5_000.0

    /** And this much energy — two charge-level steps, or a counter that has plainly been moving. */
    const val MIN_LIVE_SAMPLE_KWH = 1.0

    data class Readout(
        /** This drive's mileage so far, km per kW·h. Null until a usable energy figure exists. */
        val kmPerKwh: Double?,
        /** Range at a full charge, from the corrected rolling estimate — or the car's own figure. */
        val rangeAtFullKm: Double?,
        /** Remaining range at the current charge, from the app's own estimate. Null before enough drives. */
        val smartRangeKm: Double?,
        /** Remaining range at the lifetime average. */
        val lifetimeRangeKm: Double?,
        /** Remaining range at this drive's own rate. Volatile on purpose. */
        val liveRangeKm: Double?,
        /** What this drive's energy has cost, billed as the closing trip will be. */
        val tripCostInr: Double?,
        /** Lifetime energy across every instrumented drive, kW·h. */
        val totalKwh: Double
    )

    /**
     * [tripId] is the open drive or [TripRecorderService.NO_TRIP]; the prediction does not need
     * one, only the drive-specific figures do. [energy] is what this drive has used so far,
     * [distanceM] how far it has gone, [soc] the charge level from the frame, [accuracy] the
     * measured bias of the estimate.
     */
    fun compute(
        dao: OdographDao,
        tripId: Long,
        distanceM: Double,
        soc: Double,
        ch: ChargeStatus,
        capacityKwh: Double,
        accuracy: RangeCalibration.Accuracy?,
        energy: Double?
    ): Readout {
        val kmPerKwh = BatteryMath.kmPerKwh(energy, distanceM)

        // Newest first, as the window wants it. The drive in progress joins only once it is a
        // sample in its own right, and then weighted by its distance like any other.
        val history = dao.tripEnergies().map { it.distanceM to it.energyKwh }
        val live = if (energy != null && energy >= MIN_LIVE_SAMPLE_KWH && distanceM >= MIN_LIVE_SAMPLE_M) {
            listOf(distanceM to energy)
        } else emptyList()
        val window = live + history

        // Corrected by how wrong the estimate has actually been. The rolling figure observes what
        // drives cost; it never asks whether its own predictions came true, so a bias in the same
        // direction can sit there for months unnoticed. The correction is measured out of sample
        // — each past drive scored against a model built only from the drives before it.
        val rolling = BatteryMath.rollingKwhPer100KmWeighted(window)?.let { raw ->
            RangeCalibration.calibrate(raw, accuracy)
        }
        val enough = window.size >= BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE && rolling != null

        // Below the gate the car's own number is the only honest one. Same gate for both range
        // figures: only real measured efficiency gets to quote a range.
        val rangeAtFull = if (enough) BatteryMath.rangeAtFullKwh(capacityKwh, rolling!!)
        else Telematics.carRangeAtFullKm(ch)
        val smartRange = if (enough) BatteryMath.rangeAtSocKwh(capacityKwh, soc, rolling!!) else null

        // "The total for this ride" is billed exactly like the closing trip will be — the same
        // blended fill rate, so what the screen quotes is what shows up next week in the archive.
        val tripCost = if (tripId >= 0) {
            TripRecovery.driveCost(dao, energy, dao.tripById(tripId)?.startedAt ?: 0L)
        } else null

        // The same charge read two other ways: across every drive ever recorded, and across the
        // one happening right now. See RangeModel for why those want opposite things.
        val lifetimeEff = RangeModel.lifetimeKwhPer100Km(
            dao.allTripEnergies().map { it.distanceM to it.energyKwh }
        )
        val liveEffNow = RangeModel.liveKwhPer100Km(energy, distanceM)

        return Readout(
            kmPerKwh = kmPerKwh,
            rangeAtFullKm = rangeAtFull,
            smartRangeKm = smartRange,
            lifetimeRangeKm = RangeModel.remainingKm(capacityKwh, soc, lifetimeEff),
            liveRangeKm = RangeModel.remainingKm(capacityKwh, soc, liveEffNow),
            tripCostInr = tripCost,
            totalKwh = dao.totalEnergyKwh()
        )
    }
}
