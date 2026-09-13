package `in`.odograph.tracker.core

import `in`.odograph.tracker.core.BatteryMath.ChargeKind
import `in`.odograph.tracker.data.BatteryEntity
import org.assertj.core.api.Assertions.assertThat
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
        assertThat(BatteryMath.chargedKwh(samples)).isCloseTo(0.6667, within(1e-9))
        assertThat(BatteryMath.consumedKwh(samples, capacity)).isCloseTo(4.92, within(1e-9))
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
        assertThat(BatteryMath.rangeAtFullKwh(49.2, 11.5)).isCloseTo(427.8, within(1e-9))
    }

    @Test
    fun `range at a soc is clamped into 0 to 100`() {
        assertThat(BatteryMath.rangeAtSocKwh(49.2, 50.0, 11.5)).isCloseTo(213.9, within(1e-9))
        assertThat(BatteryMath.rangeAtSocKwh(49.2, -5.0, 11.5)).isEqualTo(0.0)
        assertThat(BatteryMath.rangeAtSocKwh(49.2, 140.0, 11.5)).isCloseTo(427.8, within(1e-9))
    }

    @Test
    fun `rolling efficiency averages the newest trips only`() {
        assertThat(BatteryMath.rollingKwhPer100Km(listOf(10.0, 20.0, 30.0))).isCloseTo(20.0, within(1e-9))
        assertThat(BatteryMath.rollingKwhPer100Km(emptyList())).isNull()
        // A window larger than the dataset just averages what exists.
        assertThat(BatteryMath.rollingKwhPer100Km(listOf(12.0), window = 10)).isCloseTo(12.0, within(1e-9))
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

    private fun within(tolerance: Double) = org.assertj.core.data.Offset.offset(tolerance)
}