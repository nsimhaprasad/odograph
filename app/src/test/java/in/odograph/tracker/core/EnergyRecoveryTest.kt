package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

/**
 * Recovering a drive's energy when the telematics link was down for the whole of it.
 *
 * The distinction these tests defend is that backfill is measurement and estimation is a guess.
 * The first reads counters the car kept running while nobody was listening; the second multiplies
 * distance by a historical average. They are stored in different columns for that reason, and the
 * estimate must never reach the model that produced it.
 */
class EnergyRecoveryTest {

    private fun bracket(
        beforeKwh: Double? = 10.0,
        afterKwh: Double? = 14.0,
        beforeKm: Double? = 50.0,
        afterKm: Double? = 75.0
    ) = EnergyRecovery.Bracket(beforeKwh, afterKwh, beforeKm, afterKm)

    // ------------------------------------------------------------------ backfill

    /**
     * The case this exists for: a drive through a basement or a forest with no frames inside it,
     * bracketed by a reading before and a reading after. The counters ran the whole time.
     */
    @Test
    fun `counters either side of a drive recover the energy it used`() {
        // 4 kWh over 25 km, and the drive's own distance agrees.
        val kwh = EnergyRecovery.backfilledKwh(bracket(), distanceKm = 25.0)

        assertThat(kwh!!).isCloseTo(4.0, within(0.001))
    }

    /**
     * The check that makes it safe. Two readings far enough apart may span more than one drive,
     * and the car's own distance counter is what gives that away — it moved 25 km while this
     * drive covered 8, so most of that energy belongs to something else.
     */
    @Test
    fun `a bracket that spans more than the drive is refused`() {
        assertThat(EnergyRecovery.backfilledKwh(bracket(), distanceKm = 8.0)).isNull()
    }

    @Test
    fun `a bracket straddling a charge is refused, because the counters reset`() {
        // After < before: the car charged in between and the counters went back to zero.
        val reset = bracket(beforeKwh = 30.0, afterKwh = 2.0, beforeKm = 180.0, afterKm = 12.0)

        assertThat(EnergyRecovery.backfilledKwh(reset, distanceKm = 12.0)).isNull()
    }

    @Test
    fun `a missing reading on either side gives nothing`() {
        assertThat(EnergyRecovery.backfilledKwh(bracket(beforeKwh = null), 25.0)).isNull()
        assertThat(EnergyRecovery.backfilledKwh(bracket(afterKwh = null), 25.0)).isNull()
    }

    /**
     * Without a distance to check against, the bracket cannot be shown to cover this drive and
     * nothing else — and an unchecked bracket is not a measurement, it is a hope.
     */
    @Test
    fun `no distance counter means no way to verify, so no backfill`() {
        assertThat(EnergyRecovery.backfilledKwh(bracket(beforeKm = null), 25.0)).isNull()
        assertThat(EnergyRecovery.backfilledKwh(bracket(afterKm = null), 25.0)).isNull()
    }

    @Test
    fun `a counter that did not move is not a drive that used nothing`() {
        val still = bracket(beforeKwh = 10.0, afterKwh = 10.0)

        assertThat(EnergyRecovery.backfilledKwh(still, distanceKm = 25.0)).isNull()
    }

    /**
     * The two distances are measured differently — wheel rotations against integrated GNSS — and
     * disagree by a few percent even in good conditions. A drive that had no signal is exactly
     * where the sky was poor, so the tolerance has to survive that.
     */
    @Test
    fun `ordinary disagreement between the two odometers is tolerated`() {
        // Car says 25 km, the app's GNSS made it 22.5 — 10% out, well within reason.
        assertThat(EnergyRecovery.backfilledKwh(bracket(), distanceKm = 22.5)).isNotNull()
    }

    @Test
    fun `a drive too short to check is not backfilled`() {
        assertThat(EnergyRecovery.backfilledKwh(bracket(), distanceKm = 0.4)).isNull()
    }

    // ---------------------------------------------------------------- estimation

    @Test
    fun `an estimate is distance times what the car has been using`() {
        // 40 km at 16 kWh/100km.
        assertThat(EnergyRecovery.estimatedKwh(40.0, 16.0)!!).isCloseTo(6.4, within(0.001))
    }

    /**
     * With no history there is no estimate. A default plucked from somewhere would be indexed,
     * charted and averaged exactly like a measurement, and nothing downstream would know.
     */
    @Test
    fun `with nothing learned yet there is no estimate to give`() {
        assertThat(EnergyRecovery.estimatedKwh(40.0, null)).isNull()
        assertThat(EnergyRecovery.estimatedKwh(40.0, 0.0)).isNull()
        assertThat(EnergyRecovery.estimatedKwh(40.0, Double.NaN)).isNull()
    }

    @Test
    fun `a drive too short to estimate is left alone`() {
        assertThat(EnergyRecovery.estimatedKwh(0.3, 16.0)).isNull()
    }

    /**
     * The estimate tracks whatever the model currently believes, which is the point: it improves
     * as the history grows rather than being frozen at whatever was true when it was written.
     */
    @Test
    fun `the estimate follows the learned figure`() {
        val thirsty = EnergyRecovery.estimatedKwh(100.0, 18.0)!!
        val frugal = EnergyRecovery.estimatedKwh(100.0, 14.0)!!

        assertThat(thirsty).isGreaterThan(frugal)
        assertThat(thirsty).isCloseTo(18.0, within(0.001))
        assertThat(frugal).isCloseTo(14.0, within(0.001))
    }
}
