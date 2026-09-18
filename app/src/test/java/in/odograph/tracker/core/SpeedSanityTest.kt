package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Keeping a GNSS spike out of a trip's permanent record.
 *
 * The asymmetry that sets every threshold here: rejecting a real reading costs one under-reported
 * maximum on one drive, while accepting a false one corrupts a stored figure for good — the trip's
 * top speed, and every ranking built on it, stays wrong forever. So the limits are generous, and
 * where the two are in tension this keeps honest data and tolerates the occasional spike that
 * genuinely looks like driving.
 */
class SpeedSanityTest {

    private fun kmh(v: Float) = v / 3.6f

    private fun fix(i: Int, speedKmh: Float) = Fix(
        t = 1_000L * i,
        lat = 12.9716, lon = 77.5946,
        speedMps = kmh(speedKmh),
        accuracyM = 5f,
        interpolated = false,
        altitudeM = 900.0
    )

    // ---------------------------------------------------------------- the reported case

    /**
     * From the car: a 38 km drive recorded 195 km/h in a Windsor, which is limited to about 140.
     * The spike sat between ordinary readings and carried a perfectly good accuracy estimate, so
     * nothing in the accuracy filter could have stopped it.
     */
    @Test
    fun `the reported 195 kmh spike is rejected`() {
        val drive = listOf(
            fix(0, 60f), fix(1, 62f), fix(2, 195f), fix(3, 63f), fix(4, 61f)
        )
        val max = SpeedSanity.plausibleMaxSpeedMps(drive)
        assertThat(max * 3.6f)
            .`as`("the spike must not become the trip's top speed")
            .isEqualTo(63f, org.assertj.core.api.Assertions.within(0.5f))
    }

    @Test
    fun `a spike is rejected even when it is the very first reading`() {
        val drive = listOf(fix(0, 195f), fix(1, 40f), fix(2, 45f))
        assertThat(SpeedSanity.plausibleMaxSpeedMps(drive) * 3.6f)
            .isEqualTo(45f, org.assertj.core.api.Assertions.within(0.5f))
    }

    /**
     * The reason a rejected reading must not become the reference: otherwise one bad fix drags the
     * threshold up behind it and waves through everything that follows.
     */
    @Test
    fun `a rejected spike does not licence the readings after it`() {
        val drive = listOf(fix(0, 50f), fix(1, 400f), fix(2, 190f), fix(3, 52f))
        assertThat(SpeedSanity.plausibleMaxSpeedMps(drive) * 3.6f)
            .`as`("190 is only plausible from 400, which never happened")
            .isEqualTo(52f, org.assertj.core.api.Assertions.within(0.5f))
    }

    // ---------------------------------------------------------------- real driving survives

    @Test
    fun `ordinary highway driving is kept in full`() {
        val drive = (0..20).map { fix(it, 40f + it * 3f) }   // 40 up to 100 km/h, gently
        assertThat(SpeedSanity.plausibleMaxSpeedMps(drive) * 3.6f)
            .isEqualTo(100f, org.assertj.core.api.Assertions.within(1f))
    }

    @Test
    fun `a brisk but real launch is not mistaken for noise`() {
        // Nought to sixty in about four seconds is quick, and quite possible.
        val drive = listOf(fix(0, 0f), fix(1, 15f), fix(2, 32f), fix(3, 48f), fix(4, 60f))
        assertThat(SpeedSanity.plausibleMaxSpeedMps(drive) * 3.6f)
            .isEqualTo(60f, org.assertj.core.api.Assertions.within(1f))
    }

    @Test
    fun `hard braking is believed, because brakes beat motors`() {
        val drive = listOf(fix(0, 90f), fix(1, 60f), fix(2, 25f), fix(3, 0f))
        assertThat(SpeedSanity.plausibleMaxSpeedMps(drive) * 3.6f)
            .isEqualTo(90f, org.assertj.core.api.Assertions.within(1f))
    }

    @Test
    fun `a parked car reports nothing`() {
        assertThat(SpeedSanity.plausibleMaxSpeedMps(List(10) { fix(it, 0f) })).isEqualTo(0f)
        assertThat(SpeedSanity.plausibleMaxSpeedMps(emptyList())).isEqualTo(0f)
    }

    // ---------------------------------------------------------------- the two tests, separately

    @Test
    fun `nothing above the ceiling is believed whatever preceded it`() {
        assertThat(SpeedSanity.isPlausible(previousMps = kmh(155f), candidateMps = kmh(200f), dtSeconds = 60f))
            .isFalse()
    }

    @Test
    fun `the ceiling sits above what the car can actually do`() {
        assertThat(SpeedSanity.CEILING_MPS * 3.6f)
            .`as`("headroom over a car limited to about 140")
            .isGreaterThan(145f)
    }

    @Test
    fun `an impossible jump is rejected even well under the ceiling`() {
        assertThat(SpeedSanity.isPlausible(previousMps = 0f, candidateMps = kmh(120f), dtSeconds = 1f))
            .`as`("nought to 120 in one second")
            .isFalse()
    }

    /**
     * Across a long gap the acceleration test permits almost anything and stops discriminating, so
     * beyond the window only the ceiling applies. A tunnel must not licence an impossible speed,
     * but neither should it discard a legitimately fast one on the far side.
     */
    @Test
    fun `a long gap falls back to the ceiling alone`() {
        assertThat(SpeedSanity.isPlausible(0f, kmh(110f), dtSeconds = 600f)).isTrue()
        assertThat(SpeedSanity.isPlausible(0f, kmh(400f), dtSeconds = 600f)).isFalse()
    }

    @Test
    fun `a first reading has nothing to be judged against but the ceiling`() {
        assertThat(SpeedSanity.isPlausible(null, kmh(90f), 0f)).isTrue()
        assertThat(SpeedSanity.isPlausible(null, kmh(300f), 0f)).isFalse()
    }

    @Test
    fun `nonsense is refused rather than propagated`() {
        assertThat(SpeedSanity.isPlausible(10f, Float.NaN, 1f)).isFalse()
        assertThat(SpeedSanity.isPlausible(10f, Float.POSITIVE_INFINITY, 1f)).isFalse()
        assertThat(SpeedSanity.isPlausible(10f, -5f, 1f)).isFalse()
    }

    // ---------------------------------------------------------------- what the trip ends up storing

    /** The guard has to hold through TripStats, which is what actually writes the stored figure. */
    @Test
    fun `a trip's stored top speed excludes the spike`() {
        val drive = listOf(
            fix(0, 55f), fix(1, 58f), fix(2, 195f), fix(3, 60f), fix(4, 57f)
        )
        assertThat(TripStats.compute(drive).maxSpeedMps * 3.6f)
            .isEqualTo(60f, org.assertj.core.api.Assertions.within(1f))
    }
}
