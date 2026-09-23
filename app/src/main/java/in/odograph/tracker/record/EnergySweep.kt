package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.EfficiencyStats
import `in`.odograph.tracker.core.EnergyRecovery
import `in`.odograph.tracker.core.RangeCalibration
import `in`.odograph.tracker.data.OdographDao
import java.util.TimeZone

/**
 * Revisiting drives that finished with no energy figure.
 *
 * A drive only gets a measured energy if the telematics link was up during it, and on this box it
 * frequently is not — the car is driven into basements, through tunnels, and out of coverage
 * altogether. Those drives used to keep their nulls permanently: no kWh, no cost, no mileage, no
 * contribution to anything, however much was learned afterwards.
 *
 * Two things can be done about that later, and the order matters because one is measurement and
 * the other is not.
 *
 * First the counters are tried. The car keeps running totals that reset only at a charge, so a
 * frame from before the drive and one from after it bracket what the drive actually used — real
 * consumption, recovered after the fact, checked against the car's own distance counter to make
 * sure the bracket covers that drive and nothing else. This is why the sweep is worth re-running:
 * a drive that had no bracket this morning may have one this evening, because the frame that
 * closes it had not been captured yet.
 *
 * Only when that fails is the drive given an indicative figure from its distance and the app's
 * learned consumption. That figure is written somewhere the efficiency model cannot read, and it
 * is recomputed on every sweep so it tracks the model as the model improves.
 */
object EnergySweep {

    /** How many drives one sweep will look at. Bounded so a long history cannot stall a poll. */
    const val BATCH = 50

    /** What one sweep did, for the log. */
    data class Result(val backfilled: Int, val estimated: Int, val untouched: Int)

    /**
     * Fills in what can be filled in, newest drives first.
     *
     * Safe to call repeatedly. A drive that gets a measured figure leaves the candidate list for
     * good; a drive that only gets an estimate stays, and will be upgraded to a real measurement
     * the moment a usable bracket exists for it.
     */
    fun run(dao: OdographDao, zone: TimeZone): Result {
        val candidates = dao.tripsMissingEnergy(BATCH)
        if (candidates.isEmpty()) return Result(0, 0, 0)

        // The same figure the drive screen quotes: the rolling mean of recent drives, corrected by
        // the bias the app has measured in itself. Computed once for the whole sweep rather than
        // per drive — it is the same number for all of them, and it is not cheap.
        val learned = learnedKwhPer100Km(dao, zone)

        var backfilled = 0
        var estimated = 0
        var untouched = 0

        candidates.forEach { trip ->
            val endedAt = trip.endedAt ?: return@forEach
            val distanceKm = trip.distanceM / 1000.0

            val before = dao.counterAtOrBefore(trip.startedAt)
            val after = dao.counterAtOrAfter(endedAt)
            val bracket = EnergyRecovery.Bracket(
                beforeKwh = before?.powerUsageSinceLastChargeKwh,
                afterKwh = after?.powerUsageSinceLastChargeKwh,
                beforeKm = before?.distanceSinceLastChargeKm,
                afterKm = after?.distanceSinceLastChargeKm
            )

            val recovered = EnergyRecovery.backfilledKwh(bracket, distanceKm)
            if (recovered != null) {
                // Measurement. It goes in the same column as every other measured figure, prices
                // the same way, and from here on this drive is indistinguishable from one that was
                // watched the whole way — because it was measured just as truly, only later.
                dao.setBackfilledEnergy(
                    trip.id,
                    recovered,
                    TripRecovery.driveCost(dao, recovered, trip.startedAt)
                        ?.let { BatteryMath.round2(it) }
                )
                backfilled++
                return@forEach
            }

            val guess = EnergyRecovery.estimatedKwh(distanceKm, learned)
            if (guess != null) {
                dao.setEstimatedEnergy(
                    trip.id,
                    guess,
                    TripRecovery.driveCost(dao, guess, trip.startedAt)
                        ?.let { BatteryMath.round2(it) }
                )
                estimated++
            } else {
                untouched++
            }
        }
        return Result(backfilled, estimated, untouched)
    }

    /**
     * What the app currently believes a hundred kilometres costs, or null with too little history.
     *
     * Built only from drives with a *measured* energy, which is what selecting on `energyKwh`
     * gives — estimates live in another column precisely so that they cannot reach this.
     */
    fun learnedKwhPer100Km(dao: OdographDao, zone: TimeZone): Double? {
        val effs = dao.tripEnergies()
            .mapNotNull { BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM) }
        if (effs.size < BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE) return null
        val raw = BatteryMath.rollingKwhPer100Km(effs) ?: return null

        val samples = dao.efficiencySamples().map {
            EfficiencyStats.Sample(
                it.startedAt, it.distanceM, it.movingS, it.energyKwh, it.avgTempC, it.climateShare
            )
        }
        return RangeCalibration.calibrate(
            raw,
            RangeCalibration.accuracy(RangeCalibration.backtest(samples, zone))
        )
    }
}
