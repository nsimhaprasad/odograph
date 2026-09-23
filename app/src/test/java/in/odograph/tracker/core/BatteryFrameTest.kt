package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * One telematics frame, written down.
 *
 * The mapping used to be a sixty-line constructor inside the poll loop and could not be tested
 * without the service. Now the real captured frames go straight through it, so "do we record X"
 * is answered here rather than by reading the loop.
 */
class BatteryFrameTest {

    private val frameTime = 1_786_729_051_000L

    @Test
    fun `a captured charging frame is written down field for field`() {
        val ch = MgFixtures.CHARGING_70
        val row = BatteryFrame.from(MgFixtures.status(charge = ch), ch, tripId = 7, t = frameTime, powerKw = 1.6)

        assertThat(row.tripId).isEqualTo(7)
        assertThat(row.t).isEqualTo(frameTime)
        assertThat(row.socPercent).isEqualTo(70.0)
        assertThat(row.charging).isTrue()
        assertThat(row.pluggedIn).isTrue()
        assertThat(row.rangeKm).isEqualTo(223.0)
        assertThat(row.chargingPowerKw).isEqualTo(1.6)
        assertThat(row.batteryEnergyKwh).isEqualTo(26.0)
        assertThat(row.odometerKm).isEqualTo(MgFixtures.ODO_KM)
        assertThat(row.chargeTimeRemainingMin).isEqualTo(276)
        assertThat(row.chargingType).isEqualTo(2)
        assertThat(row.workingVoltage).isEqualTo(382.0)
        assertThat(row.workingCurrent).isEqualTo(4.2)
    }

    /**
     * The counters that make backfill possible. If either of these stopped being recorded, a
     * drive with no link during it would silently lose its only route to a measured figure.
     */
    @Test
    fun `the since-last-charge counters are kept`() {
        val ch = MgFixtures.CHARGING_70
        val row = BatteryFrame.from(MgFixtures.status(charge = ch), ch, tripId = 1, t = frameTime, powerKw = 0.0)

        assertThat(row.distanceSinceLastChargeKm).isEqualTo(138.5)
        assertThat(row.powerUsageSinceLastChargeKwh).isEqualTo(8.8)
    }

    /**
     * The trip is the caller's decision, not the frame's. The same frame filed under the parked
     * bucket must come out identical apart from that one field.
     */
    @Test
    fun `which trip a frame belongs to is decided by the caller`() {
        val ch = MgFixtures.IDLE_100
        val status = MgFixtures.status(charge = ch)
        val onTrip = BatteryFrame.from(status, ch, tripId = 42, t = frameTime, powerKw = 0.0)
        val parked = BatteryFrame.from(status, ch, tripId = -1, t = frameTime, powerKw = 0.0)

        assertThat(onTrip.tripId).isEqualTo(42)
        assertThat(parked.tripId).isEqualTo(-1)
        assertThat(parked.copy(tripId = 42)).isEqualTo(onTrip)
    }

    /**
     * The car never sends its capacity — the presence bit is clear in every captured frame — and
     * the row must say so with a null, not invent a zero that downstream code would divide by.
     */
    @Test
    fun `a reading the car did not send is null, never zero`() {
        val ch = MgFixtures.CHARGING_74
        val row = BatteryFrame.from(MgFixtures.status(charge = ch), ch, tripId = 1, t = frameTime, powerKw = 0.0)

        assertThat(row.carCapacityKwh).isNull()
        assertThat(row.chargeTimeRemainingMin).isNull()
    }

    @Test
    fun `a full idle frame reads as not charging with the cable in`() {
        val ch = MgFixtures.IDLE_100
        val row = BatteryFrame.from(MgFixtures.status(charge = ch), ch, tripId = 1, t = frameTime, powerKw = 0.0)

        assertThat(row.socPercent).isEqualTo(100.0)
        assertThat(row.charging).isFalse()
        assertThat(row.pluggedIn).isTrue()
        assertThat(row.powerUsageSinceLastChargeKwh).isEqualTo(0.0)
    }
}
