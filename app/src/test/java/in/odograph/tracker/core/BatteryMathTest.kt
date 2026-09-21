package `in`.odograph.tracker.core

import `in`.odograph.tracker.core.BatteryMath.ChargeKind
import `in`.odograph.tracker.data.BatteryEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class BatteryMathTest {

    private val capacity = 49.2

    // ---- consumed energy ----

    @Test
    fun `a plain drive draws the SOC swing times capacity`() {
        val samples = listOf(
            BatteryEntity(0, 1, 1_000_000, socPercent = 80.0, charging = false),
            BatteryEntity(0, 2, 1_100_000, socPercent = 66.2, charging = false)
        )
        assertThat(BatteryMath.consumedKwh(samples, capacity)).isCloseTo(6.7896, within(1e-9))
    }

    @Test
    fun `full to empty draws the whole battery`() {
        val samples = listOf(
            BatteryEntity(0, 1, 0, socPercent = 100.0, charging = false),
            BatteryEntity(0, 2, 10_000, socPercent = 0.0, charging = false)
        )
        assertThat(BatteryMath.consumedKwh(samples, capacity)).isEqualTo(capacity)
    }

    @Test
    fun `a charging phase mid trip is added back so the drive is billed for what it used`() {
        // 10% drawn (4.92 kWh), while 40 kW flowed in for one minute (0.667 kWh) mid trip:
        // the drive is billed for 4.92 used, with the charge added back over the swing.
        val samples = listOf(
            BatteryEntity(0, 1, 0, socPercent = 90.0, charging = false),
            BatteryEntity(0, 2, 60_000, socPercent = 85.0, charging = true, chargingPowerKw = 40.0),
            BatteryEntity(0, 3, 120_000, socPercent = 80.0, charging = false)
        )
        assertThat(BatteryMath.chargedKwh(samples)).isCloseTo(40.0 * 60.0 / 3600.0, within(1e-9))
        assertThat(BatteryMath.consumedKwh(samples, capacity))
            .isCloseTo(4.92 + 40.0 * 60.0 / 3600.0, within(1e-9))
    }

    @Test
    fun `a paused charge interval contributes nothing`() {
        // The second interval ends off-charge at 40 kW "residual" power; pauses bill no life.
        val samples = listOf(
            BatteryEntity(0, 1, 0, socPercent = 80.0, charging = true, chargingPowerKw = 40.0),
            BatteryEntity(0, 2, 60_000, socPercent = 80.0, charging = false, chargingPowerKw = 40.0)
        )
        assertThat(BatteryMath.chargedKwh(samples)).isZero()
    }

    @Test
    fun `soc outside the physical range is clamped so noise cannot manufacture energy`() {
        // Sensor burp: 130 reads as the 100 it physically means, so the swing is the honest 5
        // points (a net gain), not a hallucinated 35-point refill.
        val samples = listOf(
            BatteryEntity(0, 1, 0, socPercent = 95.0, charging = false),
            BatteryEntity(0, 2, 60_000, socPercent = 130.0, charging = false)
        )
        assertThat(BatteryMath.consumedKwh(samples, capacity)).isEqualTo(capacity * -5.0 / 100.0)
    }

    @Test
    fun `fewer than two usable readings or a bad capacity leave the trip un-instrumented`() {
        assertThat(BatteryMath.consumedKwh(emptyList(), capacity)).isNull()
        assertThat(
            BatteryMath.consumedKwh(
                listOf(BatteryEntity(0, 1, 0, socPercent = 50.0, charging = false)),
                capacity
            )
        ).isNull()
        val pair = listOf(
            BatteryEntity(0, 1, 0, socPercent = 90.0, charging = false),
            BatteryEntity(0, 2, 1_000, socPercent = 89.0, charging = false)
        )
        assertThat(BatteryMath.consumedKwh(pair, 0.0)).isNull()
        assertThat(BatteryMath.consumedKwh(pair, -3.0)).isNull()
    }

    @Test
    fun `a net -charging drive reports negative energy, never a fake positive`() {
        val samples = listOf(
            BatteryEntity(0, 1, 0, socPercent = 40.0, charging = false),
            BatteryEntity(0, 2, 120_000, socPercent = 42.0, charging = false)
        )
        assertThat(BatteryMath.consumedKwh(samples, capacity)).isLessThan(0.0)
    }

    // ---- recharge energy ----

    @Test
    fun `recharge energy is bounded by capacity and by zero`() {
        assertThat(BatteryMath.rechargeEnergyKwh(20.0, 50.0, capacity)).isCloseTo(14.76, within(1e-9))
        // An alleged 120-point gain clamps to the 90 real points the battery admits, never more.
        assertThat(BatteryMath.rechargeEnergyKwh(10.0, 130.0, capacity)).isCloseTo(44.28, within(1e-9))
        assertThat(BatteryMath.rechargeEnergyKwh(-50.0, 100.0, capacity)).isCloseTo(capacity, within(1e-9))
        // SOC fell during a "charge" - a sensor oddity that must not bill anything.
        assertThat(BatteryMath.rechargeEnergyKwh(50.0, 40.0, capacity)).isEqualTo(0.0)
        assertThat(BatteryMath.rechargeEnergyKwh(null, 40.0, capacity)).isEqualTo(0.0)
        assertThat(BatteryMath.rechargeEnergyKwh(10.0, 40.0, 0.0)).isEqualTo(0.0)
    }

    // ---- efficiency and range ----

    @Test
    fun `mileage needs a real drive length and real energy, not 2 km of noise`() {
        assertThat(BatteryMath.kmPerKwh(4.0, 40_000.0)).isCloseTo(10.0, within(1e-9))
        assertThat(BatteryMath.kwhPer100Km(4.0, 40_000.0)).isCloseTo(10.0, within(1e-9))
        assertThat(BatteryMath.kmPerKwh(null, 40_000.0)).isNull()
        assertThat(BatteryMath.kmPerKwh(0.2, 40_000.0)).isNull()   // below the 0.5 kWh floor
        assertThat(BatteryMath.kmPerKwh(4.0, 500.0)).isNull()      // below the 2 km floor
    }

    @Test
    fun `range at full scales with efficiency and capacity`() {
        assertThat(BatteryMath.rangeAtFullKwh(49.2, 11.5)).isCloseTo(427.83, within(0.05))
    }

    @Test
    fun `range at a soc is clamped into 0 to 100`() {
        assertThat(BatteryMath.rangeAtSocKwh(49.2, 50.0, 11.5)).isCloseTo(213.91, within(0.05))
        assertThat(BatteryMath.rangeAtSocKwh(49.2, -5.0, 11.5)).isEqualTo(0.0)
        assertThat(BatteryMath.rangeAtSocKwh(49.2, 140.0, 11.5)).isCloseTo(427.83, within(0.05))
    }

    @Test
    fun `rolling efficiency averages the newest trips only`() {
        assertThat(BatteryMath.rollingKwhPer100Km(listOf(10.0, 20.0, 30.0))).isCloseTo(20.0, within(1e-9))
        assertThat(BatteryMath.rollingKwhPer100Km(emptyList())).isNull()
        // A window larger than the dataset just averages what exists.
        assertThat(BatteryMath.rollingKwhPer100Km(listOf(12.0), window = 10)).isCloseTo(12.0, within(1e-9))
    }

    // ---- two-decimal precision ----

    @Test
    fun `round2 keeps money and energy to two decimals`() {
        assertThat(BatteryMath.round2(123.456)).isEqualTo(123.46)
        assertThat(BatteryMath.round2(7.5)).isEqualTo(7.5)
        assertThat(BatteryMath.round2(0.004)).isEqualTo(0.0)
        assertThat(BatteryMath.round2(-1.476)).isEqualTo(-1.48)
        assertThat(BatteryMath.round2(49.2 * 30.0 / 100.0)).isEqualTo(14.76)
    }

    @Test
    fun `a nan reading is evidence of nothing, not a wild swing`() {
        val nan = Double.NaN
        // A corrupt SOC drops out of the swing entirely: one usable reading is not a trip.
        assertThat(
            BatteryMath.consumedKwh(
                listOf(
                    BatteryEntity(0, 1, 0, socPercent = 50.0, charging = false),
                    BatteryEntity(0, 2, 60_000, socPercent = nan, charging = false)
                ),
                capacity
            )
        ).isNull()
        // A corrupt power spike never books energy into the road bill.
        assertThat(
            BatteryMath.chargedKwh(
                listOf(
                    BatteryEntity(0, 1, 0, socPercent = 50.0, charging = true, chargingPowerKw = nan),
                    BatteryEntity(0, 2, 60_000, socPercent = 51.0, charging = true, chargingPowerKw = nan)
                )
            )
        ).isZero()
        assertThat(BatteryMath.rechargeEnergyKwh(50.0, nan, capacity)).isEqualTo(0.0)
        assertThat(BatteryMath.rechargeEnergyKwh(nan, 60.0, capacity)).isEqualTo(0.0)
    }

    // ---- charge kind ----

    @Test
    fun `a charge peaking at or above 10 kw is fast, everything below is slow`() {
        assertThat(BatteryMath.chargeKind(9.9, 3.0, 0, 3_600_000)).isEqualTo(ChargeKind.SLOW)
        assertThat(BatteryMath.chargeKind(10.0, 3.0, 0, 3_600_000)).isEqualTo(ChargeKind.FAST)
        assertThat(BatteryMath.chargeKind(42.0, 12.0, 0, 3_600_000)).isEqualTo(ChargeKind.FAST)
    }

    @Test
    fun `a slept-though night is classified fair by the charger's peak not its fake duration`() {
        // 10 kWh over 8 reported wall-clock hours reads as 1.25 kW average; the peak tells the truth.
        assertThat(BatteryMath.chargeKind(50.0, 10.0, 0, 8 * 3_600_000L)).isEqualTo(ChargeKind.FAST)
    }

    @Test
    fun `no power readings fall back to the session average`() {
        // 12 kWh in exactly one hour: a fast session even though power was never reported.
        assertThat(BatteryMath.chargeKind(null, 12.0, 0, 3_600_000)).isEqualTo(ChargeKind.FAST)
        assertThat(BatteryMath.chargeKind(null, 6.0, 0, 3_600_000)).isEqualTo(ChargeKind.SLOW)
        // A zero-energy session of unknown provenance is not fast.
        assertThat(BatteryMath.chargeKind(null, 0.0, 0, 3_600_000)).isEqualTo(ChargeKind.SLOW)
    }

    @Test
    fun `a lone power spike cannot make a home charge a fast charge`() {
        // 6 readings at grid power, one reading briefly over 10 kW: not a fast charger. The ~7 kW
        // majority wins over the blip's peak.
        assertThat(
            BatteryMath.chargeKind(
                45.0, 7.0, 0, 90 * 60_000L, samplesTotal = 7, samplesAbove = 1
            )
        ).isEqualTo(ChargeKind.SLOW)
    }

    @Test
    fun `occasional dips never demote a real fast charger`() {
        // 30 readings at full fast-charger power with two momentary dips below 10 kW: still fast.
        assertThat(
            BatteryMath.chargeKind(
                40.0, 16.0, 0, 40 * 60_000L, samplesTotal = 32, samplesAbove = 30
            )
        ).isEqualTo(ChargeKind.FAST)
    }

    @Test
    fun `a session exactly half-fast stays slow`() {
        // A tie on the 10 kW line is not worth the fast rate. 6 readings and 3 above the line
        // still reach the majority rule (6 >= FAST_EVIDENCE_MIN_SAMPLES), where 2*3 > 6 is false.
        assertThat(
            BatteryMath.chargeKind(
                30.0, 12.0, 0, 60 * 60_000L, samplesTotal = 6, samplesAbove = 3
            )
        ).isEqualTo(ChargeKind.SLOW)
    }

    @Test
    fun `too few readings to judge consistency fall back to the peak`() {
        // 3 readings can be a blip or the start of a fast charge; the capability peek decides.
        assertThat(
            BatteryMath.chargeKind(
                38.0, 6.0, 0, 2 * 60 * 60_000L, samplesTotal = 3, samplesAbove = 3
            )
        ).isEqualTo(ChargeKind.FAST)
        assertThat(
            BatteryMath.chargeKind(
                9.0, 6.0, 0, 2 * 60 * 60_000L, samplesTotal = 3, samplesAbove = 0
            )
        ).isEqualTo(ChargeKind.SLOW)
    }

    @Test
    fun `a total bill is the final price, a tariff gets gst added on top`() {
        assertThat(BatteryMath.sessionCostInr(10.0, null, null, 250.0, 18.0)).isEqualTo(250.0)
        assertThat(BatteryMath.sessionCostInr(10.0, null, 20.0, null, 18.0))
            .isCloseTo(10.0 * 20.0 * 1.18, within(1e-9))
        assertThat(BatteryMath.sessionCostInr(10.0, null, null, null, 18.0)).isNull()
    }

    @Test
    fun `a tariff bills the wall meter when the driver entered one`() {
        // 10 kWh absorbed but 12 kWh delivered at the wall: you pay for the 12.
        assertThat(BatteryMath.sessionCostInr(10.0, 12.0, 20.0, null, 18.0))
            .isCloseTo(12.0 * 20.0 * 1.18, within(1e-9))
    }

    @Test
    fun `loss pct compares the battery reading to the wall meter`() {
        assertThat(BatteryMath.lossPct(10.0, 12.0)).isCloseTo(16.6666667, within(1e-6))
        assertThat(BatteryMath.lossPct(12.0, 12.0)).isCloseTo(0.0, within(1e-9))
        assertThat(BatteryMath.lossPct(10.0, null)).isNull()
        assertThat(BatteryMath.lossPct(10.0, 0.0)).isNull()
    }

    private fun within(tolerance: Double) = org.assertj.core.data.Offset.offset(tolerance)

    // ------------------------------------------- the car's own running counters

    /**
     * The car keeps its own tally of energy and distance since the last charge, and the difference
     * across a drive is what it thinks that drive cost. Worth having because our own figure is
     * built from whole-percent state of charge and is quantised to 0.53 kW·h a step on this pack,
     * which on a short errand is the entire measurement.
     */
    @Test
    fun `the counter delta is what the car counted across the drive`() {
        assertThat(BatteryMath.counterDelta(8.8, 11.3)!!).isCloseTo(2.5, within(0.001))
    }

    /**
     * The counters reset to zero at every charge, so a drive straddling one reads backwards. That
     * is not a small error to clamp away — it means the window contains a reset and the counter
     * cannot answer for it — so the answer is nothing rather than a number.
     */
    @Test
    fun `a drive that straddles a charge gets no answer rather than a wrong one`() {
        assertThat(BatteryMath.counterDelta(18.4, 0.6)).isNull()
    }

    /** A counter read once is a reading, not a difference. */
    @Test
    fun `one end missing means no difference`() {
        assertThat(BatteryMath.counterDelta(null, 11.3)).isNull()
        assertThat(BatteryMath.counterDelta(8.8, null)).isNull()
        assertThat(BatteryMath.counterDelta(null, null)).isNull()
    }

    @Test
    fun `a drive the counter did not move on counted nothing`() {
        assertThat(BatteryMath.counterDelta(8.8, 8.8)!!).isZero()
    }

    // ------------------------------------------- which source a drive's energy comes from

    private fun frame(t: Long, soc: Double?, counter: Double?) =
        `in`.odograph.tracker.data.BatteryEntity(
            tripId = 1, t = t, socPercent = soc, powerUsageSinceLastChargeKwh = counter
        )

    /**
     * The car's counter wins when it can answer. Whole-percent state of charge can only express
     * energy in steps of 0.53 kW·h on this pack, so a drive that really used 0.9 records as 0.53
     * and the history believes the car went seventy percent further than it did.
     */
    @Test
    fun `the car's own counter is preferred over the charge level`() {
        val frames = listOf(frame(0, 80.0, 10.0), frame(1000, 79.0, 10.9))

        // Charge level would say 1% of 52.9 = 0.53; the counter says 0.9.
        assertThat(BatteryMath.driveEnergyKwh(frames, 52.9)!!).isCloseTo(0.9, within(0.001))
    }

    /**
     * A drive that straddles a charge sees the counter reset and read backwards, so it cannot
     * answer and the charge level has to.
     */
    @Test
    fun `a counter that reset falls back to the charge level`() {
        val frames = listOf(frame(0, 40.0, 18.4), frame(1000, 38.0, 0.6))

        assertThat(BatteryMath.driveEnergyKwh(frames, 52.9)!!)
            .`as`("2% of 52.9")
            .isCloseTo(1.06, within(0.01))
    }

    /**
     * Over a drive that went somewhere, a zero delta means the frames were too sparse to catch
     * the movement rather than that the car used nothing — and a confident nought is worse than
     * the coarse figure.
     */
    @Test
    fun `a counter that did not move falls back rather than claiming nothing was used`() {
        val frames = listOf(frame(0, 80.0, 10.0), frame(1000, 77.0, 10.0))

        assertThat(BatteryMath.driveEnergyKwh(frames, 52.9)!!)
            .`as`("3% of 52.9")
            .isCloseTo(1.59, within(0.01))
    }

    /** Most drives on this box happen with the link down and have no counter at all. */
    @Test
    fun `a drive the car never reported a counter for still gets a figure`() {
        val frames = listOf(frame(0, 80.0, null), frame(1000, 76.0, null))

        assertThat(BatteryMath.driveEnergyKwh(frames, 52.9)!!).isCloseTo(2.12, within(0.01))
    }

    /** And a drive with neither source gets nothing rather than a zero. */
    @Test
    fun `a drive with no evidence at all reports no energy`() {
        val frames = listOf(frame(0, null, null), frame(1000, null, null))

        assertThat(BatteryMath.driveEnergyKwh(frames, 52.9)).isNull()
    }

    /**
     * The charge-level figure is kept separately so a drive can show what each source made of it.
     * A comparison where one number is derived from the other tells nobody anything.
     */
    @Test
    fun `the charge-level figure stands on its own for comparison`() {
        assertThat(BatteryMath.consumedFromSoc(80.0, 76.0, 52.9)!!).isCloseTo(2.12, within(0.01))
        assertThat(BatteryMath.consumedFromSoc(80.0, 80.0, 52.9)).isNull()
        assertThat(BatteryMath.consumedFromSoc(null, 76.0, 52.9)).isNull()
    }
}