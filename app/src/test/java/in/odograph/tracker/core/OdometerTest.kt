package `in`.odograph.tracker.core

import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.ui.theme.Settings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OdometerTest {

    private val settings = Settings(ApplicationProvider.getApplicationContext())

    @Test
    fun `seeding a bare box sets the baseline`() {
        val r = Odometer.calibrate(settings, typedKm = 20000.0, measuredKm = 0.0)
        assertThat(r.applied).isTrue()
        assertThat(settings.odoBaselineKm).isEqualTo(20000.0, within(0.001))
        assertThat(Odometer.appOdoKm(settings, 0.0)).isEqualTo(20000.0, within(0.001))
        assertThat(Odometer.appOdoKm(settings, 50.0)).isEqualTo(20050.0, within(0.001))
    }

    @Test
    fun `an early re-seed is still a seed, not a calibration`() {
        Odometer.calibrate(settings, 20000.0, 0.0)
        // Corrected the seed while only 3 km on record: no drift yet to share, just re-base.
        val r = Odometer.calibrate(settings, 20000.0, 3.0)
        assertThat(r.applied).isTrue()
        assertThat(Odometer.factor(settings)).isEqualTo(1.0)
        assertThat(Odometer.appOdoKm(settings, 10.0)).isEqualTo(20007.0, within(0.001))
    }

    @Test
    fun `a real reading spawns a segment that corrects since-since trips only`() {
        Odometer.calibrate(settings, 20000.0, 0.0)
        // 500 km tracked, dash says 504 → GPS under-measured by 0.8%.
        val r = Odometer.calibrate(settings, 20504.0, 500.0)
        assertThat(r.applied).isTrue()

        // The whole first window carries the 504/500 factor…
        val expectedFactor = 504.0 / 500.0
        assertThat(Odometer.factor(settings)).isEqualTo(expectedFactor, within(1e-9))

        // …so at the calibration moment we are exactly on the dash.
        assertThat(Odometer.appOdoKm(settings, 500.0)).isEqualTo(20504.0, within(0.001))
        // …and future tracking keeps the same interface ratio.
        assertThat(Odometer.appOdoKm(settings, 520.0)).isEqualTo(20504 + 20 * expectedFactor, within(0.01))
    }

    @Test
    fun `a second reading only re-brackets the later window`() {
        Odometer.calibrate(settings, 20000.0, 0.0)
        Odometer.calibrate(settings, 20504.0, 500.0)
        // Next window: 100 more GPS km, dash claims 105 → that segment only is more optimistic.
        Odometer.calibrate(settings, 20609.0, 600.0)

        assertThat(Odometer.appOdoKm(settings, 600.0)).isEqualTo(20609.0, within(0.001))
        // Late travel shares the factor of the newest bracket.
        val late = Odometer.appOdoKm(settings, 620.0)
        assertThat(late).isGreaterThan(20609.0)
    }

    @Test
    fun `a reading below the previous is rejected once trips exist`() {
        Odometer.calibrate(settings, 20000.0, 0.0)
        Odometer.calibrate(settings, 20504.0, 500.0)
        val r = Odometer.calibrate(settings, 20400.0, 600.0)
        assertThat(r.applied).isFalse()
        assertThat(r.reason).contains("below")
    }

    @Test
    fun `a far-off reading is flagged as suspect`() {
        Odometer.calibrate(settings, 20000.0, 0.0)
        // 500 km tracked from a 20,000 seed → expected is 20,500; typing 20,700 is wildly off.
        val p = Odometer.preview(settings, 20700.0, 500.0)
        assertThat(p.suspect).isTrue()
        assertThat(p.offByKm).isEqualTo(200.0, within(0.001))
    }

    @Test
    fun `an on-track reading is not suspect`() {
        Odometer.calibrate(settings, 20000.0, 0.0)
        val p = Odometer.preview(settings, 20095.0, 100.0)
        assertThat(p.suspect).isFalse()
    }

    @Test
    fun `record-due appears only after a long unrecorded stretch`() {
        assertThat(Odometer.recordDueAt(settings, 0.0)).isNull()
        Odometer.calibrate(settings, 20000.0, 0.0)
        assertThat(Odometer.recordDueAt(settings, 300.0)).isNull()
        assertThat(Odometer.recordDueAt(settings, 500.0)).isEqualTo(500.0, within(0.001))
    }

    @Test
    fun `drift is the car's reading minus ours`() {
        assertThat(Odometer.drift(20400.0, 20412.5)).isEqualTo(-12.5, within(0.001))
        assertThat(Odometer.drift(20504.0, 20500.0)).isEqualTo(4.0, within(0.001))
    }
}