package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

/**
 * What the recorder reads out of an MG frame, checked against frames the car really sent.
 *
 * These are the readings the poll loop used to compute inline, where the only way to exercise them
 * was to run a suspending function that wanted a database and a network — so a scale factor
 * applied twice could sit in the charging-power line unnoticed until a 30 kW charger showed up on
 * the glass as SLOW.
 */
class TelematicsTest {

    // ---------------------------------------------------------------- charging power

    @Test
    fun `a slow home charger reads about 1 and a half kilowatts`() {
        // 382.0 V x 4.2 A, from the CHARGING_70 capture.
        assertThat(Telematics.chargePowerKw(MgFixtures.CHARGING_70))
            .isEqualTo(1.6044, within(0.0001))
    }

    @Test
    fun `an eleven amp wallbox reads about four kilowatts`() {
        assertThat(Telematics.chargePowerKw(MgFixtures.CHARGING_11A))
            .isEqualTo(4.1256, within(0.0001))
    }

    /**
     * The regression this function exists to pin down. Re-applying the decoder's scale factors
     * here divided the reading far enough that every fast charger classified as SLOW; the value
     * below must stay a plain V x I.
     */
    @Test
    fun `a seventeen amp charger is not quietly scaled down`() {
        val kw = Telematics.chargePowerKw(MgFixtures.CHARGING_17A_SOC45)
        assertThat(kw).isEqualTo(6.5322, within(0.0001))
        assertThat(kw).`as`("must not be re-scaled into the slow band").isGreaterThan(3.3)
    }

    @Test
    fun `a full car drawing nothing reads zero`() {
        assertThat(Telematics.chargePowerKw(MgFixtures.IDLE_100)).isEqualTo(0.0)
    }

    @Test
    fun `a frame with no charging block draws nothing rather than throwing`() {
        assertThat(Telematics.chargePowerKw(null)).isEqualTo(0.0)
    }

    // ---------------------------------------------------------------- the odometer

    @Test
    fun `the status frame's odometer is preferred`() {
        val f = MgFixtures.status(odometerKm = 23_500.0, charge = MgFixtures.CHARGING_70)
        assertThat(Telematics.carOdometerKm(f)).isEqualTo(23_500.0)
    }

    @Test
    fun `the charging frame's odometer stands in when the status frame has none`() {
        val f = MgFixtures.status(odometerKm = null, charge = MgFixtures.CHARGING_70)
        assertThat(Telematics.carOdometerKm(f)).isEqualTo(MgFixtures.ODO_KM)
    }

    @Test
    fun `a frame quoting no odometer at all reports none`() {
        val f = MgFixtures.status(odometerKm = null, charge = null)
        assertThat(Telematics.carOdometerKm(f)).isNull()
    }

    /**
     * The anchor model depends on this: a normal MG frame always carries an odometer somewhere,
     * which is what makes the car's dash usable as ground truth instead of a calibration line.
     */
    @Test
    fun `every captured frame carries an odometer somewhere`() {
        listOf(
            MgFixtures.CHARGING_70, MgFixtures.CHARGING_74, MgFixtures.CHARGING_11A,
            MgFixtures.CHARGING_17A_SOC45, MgFixtures.IDLE_100
        ).forEach {
            assertThat(Telematics.carOdometerKm(MgFixtures.status(odometerKm = null, charge = it)))
                .isNotNull()
        }
    }

    // ---------------------------------------------------------------- is there a battery reading

    @Test
    fun `a frame with a SOC carries a battery reading`() {
        assertThat(Telematics.hasBatteryReading(MgFixtures.CHARGING_70)).isTrue()
    }

    @Test
    fun `a charging block without a SOC is not a battery reading`() {
        assertThat(Telematics.hasBatteryReading(MgFixtures.NO_SOC)).isFalse()
    }

    @Test
    fun `no charging block is not a battery reading`() {
        assertThat(Telematics.hasBatteryReading(null)).isFalse()
    }

    /** Zero is a reading. Treating it as absent would hide exactly the state that matters most. */
    @Test
    fun `a flat battery is still a battery reading`() {
        assertThat(Telematics.hasBatteryReading(MgFixtures.EMPTY)).isTrue()
    }

    // ---------------------------------------------------------------- range

    @Test
    fun `the car's range comes straight off the frame`() {
        assertThat(Telematics.carRangeKm(MgFixtures.CHARGING_70)).isEqualTo(223.0)
        assertThat(Telematics.carRangeKm(MgFixtures.IDLE_100)).isEqualTo(320.0)
    }

    @Test
    fun `no charging block means no range`() {
        assertThat(Telematics.carRangeKm(null)).isNull()
    }

    @Test
    fun `range at full extrapolates from the car's own quote`() {
        // 223 km at 70% implies about 319 km at 100%, against the 320 km the full car quotes.
        assertThat(Telematics.carRangeAtFullKm(MgFixtures.CHARGING_70))
            .isEqualTo(318.57, within(0.01))
    }

    @Test
    fun `range at full agrees with the car when it is already full`() {
        assertThat(Telematics.carRangeAtFullKm(MgFixtures.IDLE_100)).isEqualTo(320.0)
    }

    /** The extrapolation is a division by SOC, so a flat battery must not produce an infinity. */
    @Test
    fun `range at full is unknown on a flat battery rather than infinite`() {
        assertThat(Telematics.carRangeAtFullKm(MgFixtures.EMPTY)).isNull()
    }

    @Test
    fun `range at full is unknown without a SOC`() {
        assertThat(Telematics.carRangeAtFullKm(MgFixtures.NO_SOC)).isNull()
        assertThat(Telematics.carRangeAtFullKm(null)).isNull()
    }

    // ---------------------------------------------------------------- implied pack size

    /**
     * Every captured frame should size the pack the same way whatever its state of charge. This is
     * the cheapest available check that the SOC and energy scales are still being decoded
     * consistently — if one drifts, the implied capacity stops agreeing across frames.
     */
    @Test
    fun `every captured frame implies the same pack size`() {
        listOf(
            MgFixtures.CHARGING_70, MgFixtures.CHARGING_74, MgFixtures.CHARGING_11A,
            MgFixtures.CHARGING_17A_SOC45, MgFixtures.IDLE_100
        ).forEach {
            assertThat(Telematics.impliedCapacityKwh(it))
                .`as`("pack implied by a frame at ${it.soc}%")
                .isEqualTo(MgFixtures.PACK_KWH, within(1.0))
        }
    }

    @Test
    fun `a flat battery implies nothing about the pack rather than dividing by zero`() {
        assertThat(Telematics.impliedCapacityKwh(MgFixtures.EMPTY)).isNull()
        assertThat(Telematics.impliedCapacityKwh(MgFixtures.NO_SOC)).isNull()
        assertThat(Telematics.impliedCapacityKwh(null)).isNull()
    }

    /**
     * This car is not the one the default assumes, and the gap is not cosmetic.
     *
     * [BatteryMath.DEFAULT_CAPACITY_KWH] is 52.9 kWh, the Windsor EV Pro's pack. Every frame this
     * car has ever sent implies about 37.3 kWh — the standard Windsor — so unless the capacity has
     * been overridden on /config, every figure derived from a SOC swing is overstated by the ratio
     * asserted here: trip energy, trip cost, charge loss and the range estimates alike.
     *
     * Pinned rather than silently corrected, because changing the default changes what every
     * future drive costs and cannot be undone for the drives already recorded. The car reports its
     * own pack size on every frame, so [Telematics.impliedCapacityKwh] is the honest fix.
     */
    @Test
    fun `the configured default is the Pro pack, which this car does not have`() {
        val implied = Telematics.impliedCapacityKwh(MgFixtures.IDLE_100)!!
        assertThat(implied).isEqualTo(MgFixtures.PACK_KWH, within(0.5))
        assertThat(BatteryMath.DEFAULT_CAPACITY_KWH)
            .`as`("the default is the Pro pack and does not match this car")
            .isNotEqualTo(MgFixtures.PACK_KWH)

        val overstatement = BatteryMath.DEFAULT_CAPACITY_KWH / implied
        assertThat(overstatement)
            .`as`("energy and cost are overstated by this factor while the default stands")
            .isEqualTo(1.42, within(0.02))
    }
}
