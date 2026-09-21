package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test
import java.util.TimeZone

/**
 * The loop that turns averaging into self-correction.
 *
 * The trap it exists to avoid: scoring a model against the drives it was built from is not a test.
 * The median of a set predicts that set perfectly, so the error comes out at zero however wrong
 * the model is about the world. Every score here is out of sample by construction.
 */
class RangeCalibrationTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val hour = 3_600_000L
    private val base = 1_600_000_000_000L

    /** A drive of [km] at [kwhPer100] consumption, [i] hours after the start of the history. */
    private fun drive(i: Int, km: Double, kwhPer100: Double, movingS: Long = 1_800) =
        EfficiencyStats.Sample(
            startedAt = base + i * hour,
            distanceM = km * 1_000.0,
            movingS = movingS,
            energyKwh = kwhPer100 * km / 100.0,
            tempC = 28.0
        )

    // ---------------------------------------------------------------- an unbiased estimate

    @Test
    fun `a model that is right reports no bias worth correcting`() {
        val consistent = (0..30).map { drive(it, 20.0, 16.0) }

        val accuracy = RangeCalibration.accuracy(RangeCalibration.backtest(consistent, ist))!!

        assertThat(accuracy.medianErrorPercent).isEqualTo(0.0, within(1.0))
        assertThat(accuracy.correction).isEqualTo(1.0, within(0.02))
    }

    // ---------------------------------------------------------------- a biased one

    /**
     * The failure this is for. Consumption steps up and stays up — a hotter season, a heavier
     * right foot — and a flat average trails it for as long as the window is deep. The estimate
     * stays optimistic and the correction is what notices.
     */
    @Test
    fun `an estimate that runs optimistic is detected and corrected upward`() {
        val early = (0..14).map { drive(it, 20.0, 14.0) }
        val later = (15..34).map { drive(it, 20.0, 19.0) }

        val accuracy = RangeCalibration.accuracy(RangeCalibration.backtest(early + later, ist))!!

        assertThat(accuracy.medianErrorPercent)
            .`as`("drives cost more than predicted")
            .isGreaterThan(0.0)
        assertThat(accuracy.correction)
            .`as`("the estimate is pushed up towards what the drives actually cost")
            .isGreaterThan(1.0)
    }

    @Test
    fun `a correction raises the predicted consumption and so shortens the range`() {
        val accuracy = RangeCalibration.Accuracy(
            scored = 10, medianErrorPercent = 12.0, correction = 1.12, typicalMissPercent = 12.0
        )
        val raw = 16.0
        val calibrated = RangeCalibration.calibrate(raw, accuracy)

        assertThat(calibrated).isGreaterThan(raw)
        assertThat(BatteryMath.rangeAtFullKwh(52.9, calibrated))
            .`as`("a hungrier car goes less far")
            .isLessThan(BatteryMath.rangeAtFullKwh(52.9, raw))
    }

    // ---------------------------------------------------------------- refusing to guess

    @Test
    fun `too little history produces no correction rather than a fabricated one`() {
        val barely = (0..3).map { drive(it, 20.0, 16.0) }
        assertThat(RangeCalibration.accuracy(RangeCalibration.backtest(barely, ist))).isNull()
    }

    @Test
    fun `without a correction the estimate is returned untouched`() {
        assertThat(RangeCalibration.calibrate(16.0, null)).isEqualTo(16.0)
    }

    /**
     * A model out by more than a third is not biased, it is broken — a capacity set wrong, a pack
     * misbehaving — and multiplying that away would hide the fault while making the number look
     * healthy.
     *
     * A car that gets steadily worse, rather than one that steps once. The step version of this
     * scenario stopped disagreeing wildly the day the estimate became the rolling mean of the last
     * ten drives, because that mean catches a step up within ten drives and the error it leaves is
     * a transient. Only a sustained trend keeps the estimate permanently behind, which is the
     * shape a real fault takes anyway: a pack losing capacity does not do it in one afternoon.
     */
    @Test
    fun `a wild disagreement is clamped rather than swallowed`() {
        val early = (0..29).map { drive(it, 20.0, 8.0 * Math.pow(1.15, it.toDouble())) }
        val later = emptyList<EfficiencyStats.Sample>()

        val accuracy = RangeCalibration.accuracy(RangeCalibration.backtest(early + later, ist))!!

        assertThat(accuracy.correction).isLessThanOrEqualTo(RangeCalibration.MAX_CORRECTION)
        assertThat(accuracy.medianErrorPercent)
            .`as`("the raw disagreement is still reported in full, so the fault stays visible")
            .isGreaterThan(50.0)
    }

    // ---------------------------------------------------------------- scoring honestly

    /**
     * Every score must come from a model that had not seen the drive it is predicting. Scoring in
     * sample would report a perfect estimate for any model at all.
     */
    @Test
    fun `each drive is scored by what was known before it`() {
        val drives = (0..20).map { drive(it, 20.0, 16.0) }
        val scores = RangeCalibration.backtest(drives, ist)

        assertThat(scores).isNotEmpty
        assertThat(scores.size)
            .`as`("the earliest drives cannot be scored — nothing preceded them")
            .isLessThan(drives.size)
    }

    @Test
    fun `scatter is reported separately from bias`() {
        // Alternating either side of the truth: no net bias, but every drive misses.
        val noisy = (0..30).map { drive(it, 20.0, if (it % 2 == 0) 12.0 else 20.0) }

        val accuracy = RangeCalibration.accuracy(RangeCalibration.backtest(noisy, ist))!!

        assertThat(accuracy.typicalMissPercent)
            .`as`("a correction can remove a bias and leave the scatter exactly as wide")
            .isGreaterThan(10.0)
    }

    @Test
    fun `drives too short to measure are not scored`() {
        val tiny = (0..20).map { drive(it, 0.5, 16.0) }
        assertThat(RangeCalibration.backtest(tiny, ist)).isEmpty()
    }

    // ------------------------------------------------- how did it do on this one drive

    /**
     * The question a driver arrives with. The aggregate answers "is the estimate biased", which is
     * what the correction needs and not what anybody wants to know after a journey — a bias of
     * zero is perfectly compatible with every individual drive being wildly out.
     */
    @Test
    fun `a drive that cost more than predicted reads as an optimistic estimate`() {
        val before = drives(count = 12, kwhPer100 = 15.0, from = 1_000_000L)
        val drive = driveCosting(kwhPer100 = 18.0, at = 9_000_000L)

        val v = RangeCalibration.verdict(drive, before, utc)!!

        assertThat(v.errorPercent).`as`("18 against 15 is a fifth over").isCloseTo(20.0, within(1.0))
        assertThat(v.actualKwhPer100Km).isGreaterThan(v.predictedKwhPer100Km)
    }

    @Test
    fun `a drive that cost less than predicted reads as a cautious estimate`() {
        val before = drives(count = 12, kwhPer100 = 18.0, from = 1_000_000L)
        val drive = driveCosting(kwhPer100 = 15.0, at = 9_000_000L)

        val v = RangeCalibration.verdict(drive, before, utc)!!

        assertThat(v.errorPercent).isLessThan(0.0)
    }

    /**
     * The trap the whole file exists to avoid, applied to one row instead of the history. A model
     * that contains the drive it is predicting predicts it perfectly, however wrong it is about
     * everything else, so the verdict would report a flawless estimate for a broken one.
     */
    @Test
    fun `the drive being scored is never part of its own prediction`() {
        val before = drives(count = 12, kwhPer100 = 15.0, from = 1_000_000L)
        val drive = driveCosting(kwhPer100 = 30.0, at = 9_000_000L)

        // Handed the drive itself among the history, exactly as a careless caller would.
        val v = RangeCalibration.verdict(drive, listOf(drive) + before, utc)!!

        assertThat(v.errorPercent)
            .`as`("a drive twice the usual cost cannot come back as a perfect prediction")
            .isGreaterThan(50.0)
    }

    /**
     * Below the threshold the app quotes the car's own range rather than its own, and marking the
     * car's guess right or wrong would be scoring somebody else's work.
     */
    @Test
    fun `a drive made before the app had an estimate of its own is not scored`() {
        val tooFew = drives(count = 2, kwhPer100 = 15.0, from = 1_000_000L)

        assertThat(RangeCalibration.verdict(driveCosting(16.0, 9_000_000L), tooFew, utc)).isNull()
        assertThat(RangeCalibration.verdict(driveCosting(16.0, 9_000_000L), emptyList(), utc)).isNull()
    }

    /** A drive too short to measure consumption on has no verdict to give, only a wrong one. */
    @Test
    fun `a drive too short to measure is not scored`() {
        val before = drives(count = 12, kwhPer100 = 15.0, from = 1_000_000L)
        val hop = EfficiencyStats.Sample(
            startedAt = 9_000_000L,
            distanceM = BatteryMath.MIN_EFFICIENCY_DISTANCE_M - 1.0,
            movingS = 60L, energyKwh = 0.3, tempC = 28.0
        )

        assertThat(RangeCalibration.verdict(hop, before, utc)).isNull()
    }

    /** The range figures are the same fact in the units a driver thinks in. */
    @Test
    fun `the verdict states itself in kilometres as well as consumption`() {
        val before = drives(count = 12, kwhPer100 = 15.0, from = 1_000_000L)
        val v = RangeCalibration.verdict(driveCosting(18.0, 9_000_000L), before, utc)!!

        assertThat(v.predictedRangeAtFullKm(52.9))
            .`as`("a worse drive must not promise more range")
            .isGreaterThan(v.actualRangeAtFullKm(52.9))
    }

    @Test
    fun `a drive that landed on the estimate counts as close enough`() {
        val before = drives(count = 12, kwhPer100 = 15.0, from = 1_000_000L)
        val v = RangeCalibration.verdict(driveCosting(15.3, 9_000_000L), before, utc)!!

        assertThat(kotlin.math.abs(v.errorPercent))
            .isLessThan(RangeCalibration.CLOSE_ENOUGH_PERCENT)
    }

    // ------------------------------------ the correction must fit the estimate it corrects

    /**
     * A bias measured on one estimator is not a bias another one has.
     *
     * This scored the conditioned model — the median of prior drives in the same character bucket
     * — while the driving screen has always shown the rolling mean of the last ten. On a real
     * history the conditioned model came out 15% pessimistic while the rolling mean it was
     * correcting was only 4% out, so the correction pushed a nearly honest number 17% optimistic:
     * the car said 328 km and the app said 374.
     */
    @Test
    fun `a drive is scored against the rolling mean the screen actually shows`() {
        val history = drives(count = 12, kwhPer100 = 15.0, from = 1_000_000L)
        val scores = RangeCalibration.backtest(history, utc)

        val expected = BatteryMath.rollingKwhPer100Km(List(10) { 15.0 })!!
        assertThat(scores.last().predicted)
            .`as`("the prediction is the screen's own rolling mean, not a bucketed median")
            .isCloseTo(expected, within(0.01))
    }

    /**
     * The estimator is unbiased against a steady history, so the correction must leave it alone.
     * Anything else is the calibration inventing an error to fix.
     */
    @Test
    fun `a steady history needs no correction`() {
        val history = drives(count = 20, kwhPer100 = 15.0, from = 1_000_000L)

        val accuracy = RangeCalibration.accuracy(RangeCalibration.backtest(history, utc))!!

        assertThat(accuracy.correction).isCloseTo(1.0, within(0.02))
        assertThat(accuracy.medianErrorPercent).isCloseTo(0.0, within(2.0))
    }

    /**
     * And when the estimate genuinely is optimistic, the correction still says so. Fixing the
     * estimator mismatch must not cost the calibration its actual job.
     */
    @Test
    fun `a history that keeps costing more than predicted still raises a correction`() {
        // Ten cheap drives, then a run of expensive ones the rolling mean lags behind.
        val cheap = (0 until 10).map { driveCosting(12.0, 1_000_000L + it * 3_600_000L) }
        val dear = (0 until 12).map { driveCosting(18.0, 1_000_000L + (10 + it) * 3_600_000L) }

        val accuracy = RangeCalibration.accuracy(
            RangeCalibration.backtest(cheap + dear, utc)
        )!!

        assertThat(accuracy.medianErrorPercent)
            .`as`("drives cost more than was predicted, so the estimate was optimistic")
            .isGreaterThan(0.0)
        assertThat(accuracy.correction).isGreaterThan(1.0)
    }

    private val utc: java.util.TimeZone get() = java.util.TimeZone.getTimeZone("UTC")

    /** A run of identical drives, newest first, as the caller supplies them. */
    private fun drives(count: Int, kwhPer100: Double, from: Long) =
        (0 until count).map { driveCosting(kwhPer100, from + it * 3_600_000L) }.reversed()

    private fun driveCosting(kwhPer100: Double, at: Long): EfficiencyStats.Sample {
        val distanceM = 20_000.0
        return EfficiencyStats.Sample(
            startedAt = at,
            distanceM = distanceM,
            movingS = 1_800L,
            energyKwh = kwhPer100 * distanceM / 1000.0 / 100.0,
            tempC = 28.0
        )
    }
}
