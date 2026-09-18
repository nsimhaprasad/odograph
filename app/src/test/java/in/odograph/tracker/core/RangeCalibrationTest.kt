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
     */
    @Test
    fun `a wild disagreement is clamped rather than swallowed`() {
        val early = (0..9).map { drive(it, 20.0, 8.0) }
        val later = (10..29).map { drive(it, 20.0, 40.0) }

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
}
