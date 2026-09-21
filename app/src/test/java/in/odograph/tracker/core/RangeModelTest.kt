package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

/**
 * The two range questions a driver asks out loud, and why they need different histories.
 *
 * "What does this car really do" wants every drive ever recorded, because the point is to stop
 * being swayed by a fortnight of unusual weather. "At the rate I am going now, how far can I get"
 * wants only the drive in progress, because the point is to be swayed by exactly that. A single
 * number cannot answer both, which is why the screen stopped trying.
 */
class RangeModelTest {

    private fun drive(km: Double, kwhPer100: Double) = km * 1000.0 to (kwhPer100 * km / 100.0)

    // ---------------------------------------------------------------- the whole history

    @Test
    fun `the lifetime figure is the middle of every drive ever recorded`() {
        val history = List(15) { drive(20.0, 15.0) }

        assertThat(RangeModel.lifetimeKwhPer100Km(history)!!).isCloseTo(15.0, within(0.01))
    }

    /**
     * The median, not the mean. Consumption has a long tail on one side and a floor on the other —
     * a drive can cost far more than usual for a hundred reasons and can never cost much less than
     * physics allows — so a mean is dragged up by precisely the drives that were unrepresentative,
     * and over a whole history that tail is long.
     */
    @Test
    fun `one dreadful drive does not move the lifetime figure`() {
        val ordinary = List(14) { drive(20.0, 15.0) }
        val crawl = drive(20.0, 60.0)

        val lifetime = RangeModel.lifetimeKwhPer100Km(ordinary + crawl)!!

        assertThat(lifetime).`as`("a mean would read about 18").isCloseTo(15.0, within(0.5))
    }

    /**
     * A "lifetime" number from three drives is a rolling one wearing a grander label, and the
     * label is what makes it misleading.
     */
    @Test
    fun `too little history has no lifetime figure to give`() {
        assertThat(RangeModel.lifetimeKwhPer100Km(List(3) { drive(20.0, 15.0) })).isNull()
        assertThat(RangeModel.lifetimeKwhPer100Km(emptyList())).isNull()
    }

    /** Drives with no energy reading are not drives with zero consumption. */
    @Test
    fun `uninstrumented drives are left out rather than counted as free`() {
        val instrumented = List(12) { drive(20.0, 15.0) }
        val dark = List(20) { 20_000.0 to null }

        assertThat(RangeModel.lifetimeKwhPer100Km(instrumented + dark)!!)
            .isCloseTo(15.0, within(0.01))
    }

    // ---------------------------------------------------------------- the drive in progress

    @Test
    fun `the live figure is this drive and nothing else`() {
        assertThat(RangeModel.liveKwhPer100Km(energyKwh = 3.0, distanceM = 20_000.0)!!)
            .isCloseTo(15.0, within(0.01))
    }

    /**
     * The car reports whole percent, so one percent of the pack is the smallest energy it can
     * express. Across a short hop that single step is the entire measurement, and a range built
     * on it would swing by half at every tick.
     */
    @Test
    fun `a drive too short to have measured anything says nothing`() {
        assertThat(RangeModel.liveKwhPer100Km(0.53, 800.0)).isNull()
    }

    // ---------------------------------------------------------------- turning it into kilometres

    @Test
    fun `range is the charge in the pack at the given consumption`() {
        // Half of 52.9 kWh at 15 kWh/100km.
        assertThat(RangeModel.remainingKm(52.9, 50.0, 15.0)!!).isCloseTo(176.3, within(0.5))
    }

    /**
     * A range of "no limit" is the single most dangerous number this app could put in front of a
     * driver, so an unknown or impossible consumption produces nothing at all.
     */
    @Test
    fun `an impossible consumption gives no range rather than an infinite one`() {
        assertThat(RangeModel.remainingKm(52.9, 50.0, 0.0)).isNull()
        assertThat(RangeModel.remainingKm(52.9, 50.0, null)).isNull()
        assertThat(RangeModel.remainingKm(52.9, null, 15.0)).isNull()
        assertThat(RangeModel.remainingKm(0.0, 50.0, 15.0)).isNull()
    }

    @Test
    fun `an empty pack has no range left`() {
        assertThat(RangeModel.remainingKm(52.9, 0.0, 15.0)!!).isZero()
    }
}
