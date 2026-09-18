package `in`.odograph.tracker.core

import java.util.TimeZone
import kotlin.math.abs

/**
 * Measuring how wrong the range estimate has been, and correcting for it.
 *
 * Everything else here estimates parameters: it observes consumption and averages it. That is not
 * the same as calibration, and the difference is why a flat average can be wrong by a sixth in the
 * same direction for months without anything noticing. Nothing ever asked the only question that
 * catches a systematic bias — what did I predict last time, and what actually happened?
 *
 * The trap this has to avoid is circularity. Scoring the model against the same drives it was
 * built from is not a test of anything: the median of a set predicts that set perfectly and the
 * error comes out at zero however wrong the model is in the world. So each drive is scored with a
 * model built only from the drives *before* it — the estimate as it actually stood at the time,
 * predicting something it had not yet seen — and the correction is the middle of those honest
 * misses.
 */
object RangeCalibration {

    /**
     * Drives that must be scored before a correction is applied.
     *
     * A correction from two misses is itself noise, and applying it would add error rather than
     * remove it — the estimate would chase whatever the last drive happened to do.
     */
    const val MIN_SCORED = 5

    /** How many recent scores the correction is taken from. */
    const val WINDOW = 20

    /**
     * The furthest the correction may move the estimate, either way.
     *
     * A model that is out by more than a third is not biased, it is broken — a capacity set wrong,
     * telematics dropping mid-drive, a pack behaving strangely — and quietly multiplying the error
     * away would hide the fault while making the number look healthy. Clamping keeps the
     * correction to what it is for, and leaves a real problem visible.
     */
    const val MAX_CORRECTION = 1.35
    const val MIN_CORRECTION = 0.74

    /** One honest miss: what the estimate said before the drive, against what the drive cost. */
    data class Score(val at: Long, val predicted: Double, val actual: Double) {
        /** Above one means the drive cost more than predicted — the estimate was optimistic. */
        val ratio: Double get() = if (predicted > 0.0) actual / predicted else 1.0
    }

    /**
     * How the estimate has been performing, and the factor that corrects it.
     *
     * [medianErrorPercent] is signed: positive means drives have been costing more than predicted,
     * so the range shown has been optimistic and the car will not get as far as it claims.
     */
    data class Accuracy(
        val scored: Int,
        val medianErrorPercent: Double,
        val correction: Double,
        val typicalMissPercent: Double
    )

    /**
     * Replays the history, scoring each drive against what the estimate knew before it.
     *
     * Out of sample by construction: the model for drive *i* is built from drives before *i* only.
     * That is the whole point — it reproduces what the app would genuinely have told the driver
     * that morning, which is the only thing worth measuring the error of.
     */
    fun backtest(
        samples: List<EfficiencyStats.Sample>,
        zone: TimeZone,
        window: Int = WINDOW
    ): List<Score> {
        val ordered = samples.sortedBy { it.startedAt }
        val scores = mutableListOf<Score>()

        for (i in ordered.indices) {
            val drive = ordered[i]
            val actual = BatteryMath.kwhPer100Km(drive.energyKwh, drive.distanceM) ?: continue
            if (drive.distanceM < BatteryMath.MIN_EFFICIENCY_DISTANCE_M) continue

            val before = ordered.subList(0, i)
            val predicted = EfficiencyStats.expectedKwhPer100Km(
                samples = before,
                character = DriveContext.character(drive.distanceM, drive.movingS),
                timeOfDay = DriveContext.timeOfDay(drive.startedAt, zone),
                zone = zone
            ) ?: continue

            scores += Score(drive.startedAt, predicted, actual)
        }
        return scores.takeLast(window)
    }

    /** The correction implied by a set of scores, or null when there are too few to mean anything. */
    fun accuracy(scores: List<Score>): Accuracy? {
        if (scores.size < MIN_SCORED) return null
        val ratios = scores.map { it.ratio }.filter { it.isFinite() && it > 0.0 }
        if (ratios.size < MIN_SCORED) return null

        val middle = median(ratios)
        return Accuracy(
            scored = ratios.size,
            medianErrorPercent = (middle - 1.0) * 100.0,
            correction = middle.coerceIn(MIN_CORRECTION, MAX_CORRECTION),
            // How far a typical drive lands from the estimate regardless of direction, which is
            // what says whether the number is trustworthy at all — a correction can remove a bias
            // and leave the scatter exactly as wide as it was.
            typicalMissPercent = median(ratios.map { abs(it - 1.0) * 100.0 })
        )
    }

    /**
     * The estimate, corrected by what the estimate has been getting wrong.
     *
     * With too little history to have measured anything, the raw figure is returned untouched:
     * an uncorrected estimate is honest, while one adjusted by a factor nothing supports is not.
     */
    fun calibrate(kwhPer100Km: Double, accuracy: Accuracy?): Double =
        if (accuracy == null) kwhPer100Km else kwhPer100Km * accuracy.correction

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
