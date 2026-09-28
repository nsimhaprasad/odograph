package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.BatteryEntity
import kotlin.math.abs

/**
 * What the pack actually holds, measured rather than configured.
 *
 * The car never states its capacity — the library's own BatteryCapacityTest confirms the
 * `totalBatteryCapacityKwh` field's presence bit is clear in every captured frame — but it does
 * report the energy currently in the pack alongside the percentage that energy represents. Near a
 * full charge those two together are a direct measurement of usable capacity, and a measurement
 * repeated over months is state of health.
 *
 * This is the one legitimate use of that arithmetic, and it is worth being precise about why,
 * because the same sum in the wrong place caused a wrong conclusion once already. Reading it from
 * *another car's* captured frames and treating the answer as this car's capacity was the mistake.
 * Reading it from this car's own frames, and reporting it rather than silently configuring
 * anything, is the measurement the mistake was mistaken for.
 */
object BatteryHealth {

    /**
     * How full the pack must be before a reading counts, percent.
     *
     * The arithmetic divides by the state of charge, so its error is magnified as that figure
     * falls: a percentage point of rounding is a 1% error at 95% charge and a 5% error at 20%.
     * Near the top the reading is both precise and directly meaningful — this is, almost exactly,
     * what a full pack holds.
     */
    const val MIN_SOC_PERCENT = 90.0

    /** Readings needed before a figure is offered. One is a sample; a handful is a measurement. */
    const val MIN_READINGS = 3

    /**
     * How far apart two capacity readings may sit before the spread is worth flagging, kWh.
     *
     * Consistent readings mean the fields are decoding correctly and the pack is behaving.
     * Scattered ones mean something else is going on and the number should not be leaned on.
     */
    const val CONSISTENCY_KWH = 2.0

    data class Reading(val at: Long, val capacityKwh: Double, val socPercent: Double)

    /**
     * A measurement of the pack, and how much of it there is to believe.
     *
     * [sohPercent] is against the configured nominal capacity, so it answers "how much of the
     * battery I paid for is still there". Null when nothing sensible is configured to compare with.
     */
    data class Health(
        val capacityKwh: Double,
        val readings: Int,
        val spreadKwh: Double,
        val newestAt: Long,
        val sohPercent: Double?
    ) {
        /** Whether the readings agree well enough to be quoted without hedging. */
        val consistent: Boolean get() = spreadKwh <= CONSISTENCY_KWH
    }

    /**
     * Every near-full sample turned into a capacity reading.
     *
     * Samples taken while charging are kept. A pack being charged still reports what it holds, and
     * excluding them would throw away most of the readings near full — a car is rarely at 95% for
     * long except on its way to 100%.
     */
    fun readings(samples: List<BatteryEntity>): List<Reading> =
        samples.mapNotNull { sample ->
            val soc = sample.socPercent ?: return@mapNotNull null
            val energy = sample.batteryEnergyKwh ?: return@mapNotNull null
            if (soc < MIN_SOC_PERCENT || soc <= 0.0 || energy <= 0.0) return@mapNotNull null
            Reading(sample.t, energy / soc * 100.0, soc)
        }

    /**
     * The pack's measured capacity, from the most recent readings, and its state of health.
     *
     * Two passes, one for robustness and one for precision. First a median finds the middle and
     * anything more than [CONSISTENCY_KWH] from it — a frame decoded oddly, or caught mid-balance
     * — is dropped, so one bad reading cannot move a figure meant to change over years. Then the
     * survivors are combined by a mean weighted by the square of the charge level.
     *
     * The weighting is the whole reason the figure is precise to a decimal rather than a band. The
     * reading is `energy ÷ charge`, and the charge arrives in whole percent, so a rounding of half
     * a percent is a 0.5% error at 100% and a 0.6% error at 90% — the error grows as 1/charge, and
     * inverse-variance weighting is therefore charge². A reading taken at a true 100% has no
     * division error at all and is worth far more than one at 90%; the weighting says exactly that.
     *
     * [sohPercent] is against [nameplateKwh] — the capacity the car was sold with (52.9 kWh),
     * *not* whatever usable figure is configured for range — so it answers "how much of the
     * battery I paid for is still there", and reads at or below 100% as a state of health should.
     */
    fun measure(
        samples: List<BatteryEntity>,
        nameplateKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        window: Int = 40
    ): Health? {
        val recent = readings(samples).sortedByDescending { it.at }.take(window)
        if (recent.size < MIN_READINGS) return null

        val med = median(recent.map { it.capacityKwh })
        val clean = recent.filter { abs(it.capacityKwh - med) <= CONSISTENCY_KWH }.ifEmpty { recent }
        val measured = weightedMean(
            clean.map { it.capacityKwh },
            clean.map { it.socPercent * it.socPercent }
        )
        return Health(
            capacityKwh = measured,
            readings = clean.size,
            spreadKwh = abs(clean.maxOf { it.capacityKwh } - clean.minOf { it.capacityKwh }),
            newestAt = recent.first().at,
            sohPercent = if (nameplateKwh > 0.0) measured / nameplateKwh * 100.0 else null
        )
    }

    private fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        val total = weights.sum()
        return if (total <= 0.0) values.average()
        else values.zip(weights).sumOf { it.first * it.second } / total
    }

    /**
     * How the measurement has moved between two periods, as a percentage of the older one.
     *
     * Degradation is slow and noise is not, so a trend only means anything across a long gap. A
     * negative figure is capacity lost.
     */
    fun trendPercent(older: Health?, newer: Health?): Double? {
        if (older == null || newer == null) return null
        if (older.capacityKwh <= 0.0) return null
        return (newer.capacityKwh - older.capacityKwh) / older.capacityKwh * 100.0
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
