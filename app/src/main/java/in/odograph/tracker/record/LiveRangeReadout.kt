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
 */
object LiveRangeReadout {

    data class Readout(
        /** This drive's mileage so far, km per kW·h. Null until a usable energy figure exists. */
        val kmPerKwh: Double?,
        /** Range at a full charge, from the corrected rolling estimate — or the car's own figure. */
        val rangeAtFullKm: Double?,
        /** Remaining range at the current charge, from the app's own estimate. Null before enough drives. */
        val smartRangeKm: Double?,
        /** Remaining range at the lifetime average. */
        val lifetimeRangeKm: Double?,
        /** Remaining range at this drive's own rate. */
        val liveRangeKm: Double?,
        /** What this drive's energy has cost, billed as the closing trip will be. */
        val tripCostInr: Double?,
        /** Lifetime energy across every instrumented drive, kW·h. */
        val totalKwh: Double
    )

    /**
     * [energy] is what this drive has used so far, [distanceM] how far it has gone, [soc] the
     * charge level from the frame, [accuracy] the measured bias of the estimate.
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
        val effs = dao.tripEnergies()
            .mapNotNull { BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM) }

        // Feed this drive's live efficiency into the rolling window too, so RANGE@100 and
        // MILEAGE move on every poll instead of freezing until more trips close, and a long single
        // drive keeps correcting the estimate on the drive screen.
        val liveEff = kmPerKwh?.let { 100.0 / it }
        val rollingEffs = if (liveEff != null) listOf(liveEff) + effs else effs

        // Corrected by how wrong the estimate has actually been. The rolling figure observes what
        // drives cost; it never asks whether its own predictions came true, so a bias in the same
        // direction can sit there for months unnoticed. The correction is measured out of sample
        // — each past drive scored against a model built only from the drives before it.
        val rolling = BatteryMath.rollingKwhPer100Km(rollingEffs)?.let { raw ->
            RangeCalibration.calibrate(raw, accuracy)
        }
        val enough = rollingEffs.size >= BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE && rolling != null

        // Below the gate the car's own number is the only honest one. Same gate for both range
        // figures: only real measured efficiency gets to quote a range.
        val rangeAtFull = if (enough) BatteryMath.rangeAtFullKwh(capacityKwh, rolling!!)
        else Telematics.carRangeAtFullKm(ch)
        val smartRange = if (enough) BatteryMath.rangeAtSocKwh(capacityKwh, soc, rolling!!) else null

        // "The total for this ride" is billed exactly like the closing trip will be — the same
        // blended fill rate, so what the screen quotes is what shows up next week in the archive.
        val tripCost = TripRecovery.driveCost(dao, energy, dao.tripById(tripId)?.startedAt ?: 0L)

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
