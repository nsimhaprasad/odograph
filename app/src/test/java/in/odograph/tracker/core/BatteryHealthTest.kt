package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.BatteryEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

/**
 * Measuring what the pack actually holds.
 *
 * The car never states its capacity, but it does report the energy in the pack and the percentage
 * that represents. Near full those two are a direct measurement, and repeated over months they are
 * state of health.
 */
class BatteryHealthTest {

    private val day = 86_400_000L

    private fun sample(atDay: Int, soc: Double?, energyKwh: Double?) = BatteryEntity(
        tripId = -1, t = 1_600_000_000_000L + atDay * day,
        socPercent = soc, charging = false, batteryEnergyKwh = energyKwh
    )

    /** A healthy 52.9 kWh pack reading near full. */
    private fun healthy(atDay: Int, soc: Double) = sample(atDay, soc, 52.9 * soc / 100.0)

    @Test
    fun `a full pack measures its own capacity`() {
        val health = BatteryHealth.measure(List(6) { healthy(it, 96.0) })!!
        assertThat(health.capacityKwh).isEqualTo(52.9, within(0.2))
        assertThat(health.sohPercent).isEqualTo(100.0, within(1.0))
        assertThat(health.consistent).isTrue()
    }

    /**
     * The reason for the floor: the arithmetic divides by the state of charge, so its error grows
     * as that falls. A reading at 30% is not a measurement of a full pack.
     */
    @Test
    fun `only readings near full are used`() {
        val low = List(8) { healthy(it, 30.0) }
        assertThat(BatteryHealth.measure(low)).isNull()
    }

    @Test
    fun `a degraded pack reports its loss`() {
        // 47 kWh where 52.9 is nominal: about 89% of health.
        val worn = List(6) { i -> sample(i, 95.0, 47.0 * 95.0 / 100.0) }
        val health = BatteryHealth.measure(worn)!!
        assertThat(health.capacityKwh).isEqualTo(47.0, within(0.3))
        assertThat(health.sohPercent!!).isEqualTo(88.8, within(1.0))
    }

    @Test
    fun `one odd frame does not move a figure meant to change over years`() {
        val mostly = List(9) { healthy(it, 95.0) }
        val odd = listOf(sample(9, 95.0, 20.0))
        assertThat(BatteryHealth.measure(mostly + odd)!!.capacityKwh)
            .isEqualTo(52.9, within(0.5))
    }

    @Test
    fun `scattered readings are reported as such rather than quoted confidently`() {
        val scattered = listOf(
            sample(0, 95.0, 52.9 * 0.95), sample(1, 95.0, 44.0 * 0.95),
            sample(2, 95.0, 58.0 * 0.95), sample(3, 95.0, 49.0 * 0.95)
        )
        val health = BatteryHealth.measure(scattered)!!
        assertThat(health.consistent).isFalse()
        assertThat(health.spreadKwh).isGreaterThan(BatteryHealth.CONSISTENCY_KWH)
    }

    @Test
    fun `too few readings produce no figure rather than a guess`() {
        assertThat(BatteryHealth.measure(listOf(healthy(0, 95.0)))).isNull()
        assertThat(BatteryHealth.measure(emptyList())).isNull()
    }

    @Test
    fun `frames missing either half of the sum are skipped`() {
        val partial = listOf(
            sample(0, 95.0, null), sample(1, null, 50.0), sample(2, 0.0, 50.0)
        )
        assertThat(BatteryHealth.readings(partial)).isEmpty()
    }

    /** Charging is when a pack is near full, so excluding those frames would discard most of them. */
    @Test
    fun `a pack being charged still reports what it holds`() {
        val charging = List(5) { i ->
            BatteryEntity(
                tripId = -1, t = 1_600_000_000_000L + i * 60_000L,
                socPercent = 97.0, charging = true, batteryEnergyKwh = 52.9 * 0.97
            )
        }
        assertThat(BatteryHealth.measure(charging)!!.capacityKwh).isEqualTo(52.9, within(0.3))
    }

    @Test
    fun `degradation between two periods is reported as a trend`() {
        val newer = BatteryHealth.measure(List(5) { sample(it, 95.0, 50.0 * 0.95) })
        val older = BatteryHealth.measure(List(5) { sample(it, 95.0, 52.9 * 0.95) })
        val trend = BatteryHealth.trendPercent(older, newer)!!
        assertThat(trend).`as`("capacity lost is negative").isLessThan(0.0)
        assertThat(trend).isEqualTo(-5.5, within(1.0))
    }

    @Test
    fun `a trend needs both ends`() {
        assertThat(BatteryHealth.trendPercent(null, null)).isNull()
    }
}
