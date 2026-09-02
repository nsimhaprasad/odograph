package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class EnergyModelTest {

    private val vehicle = Vehicle()

    /** A straight run north at a constant speed, optionally climbing. */
    private fun run(
        samples: Int,
        speedMps: Float,
        metresPerSample: Double,
        altitudeStep: Double = 0.0,
        startAltitude: Double = 900.0
    ): List<Fix> = (0 until samples).map { i ->
        Fix(
            t = i * 1000L,
            lat = 12.9700 + i * (metresPerSample / 111_132.0),
            lon = 77.5900,
            speedMps = speedMps,
            accuracyM = 6f,
            altitudeM = startAltitude + i * altitudeStep
        )
    }

    @Test
    fun `an empty or single-fix trip estimates nothing`() {
        assertThat(EnergyModel.estimate(emptyList()).usedKwh).isEqualTo(0.0)
    }

    @Test
    fun `climbing 100 metres costs roughly the potential energy divided by efficiency`() {
        // m g h = 1650 * 9.807 * 100 = 1.62 MJ = 0.449 kWh, / 0.88 drivetrain = 0.51 kWh
        val flat = EnergyModel.estimate(run(100, 15f, 15.0), vehicle)
        val climb = EnergyModel.estimate(run(100, 15f, 15.0, altitudeStep = 1.0), vehicle)

        val attributableToClimb = climb.usedKwh - flat.usedKwh
        assertThat(attributableToClimb)
            .`as`("energy attributable to 100 m of climb")
            .isCloseTo(0.51, within(0.06))
    }

    @Test
    fun `descending returns energy through regen but not all of it`() {
        val descent = EnergyModel.estimate(run(100, 15f, 15.0, altitudeStep = -1.0), vehicle)
        // 0.449 kWh of potential energy, recovered at 60 percent
        assertThat(descent.regeneratedKwh).isCloseTo(0.27, within(0.05))
        assertThat(descent.regeneratedKwh)
            .`as`("regen never returns the full potential energy")
            .isLessThan(0.449)
    }

    @Test
    fun `a climb costs more than the same descent returns`() {
        val climb = EnergyModel.estimate(run(100, 15f, 15.0, altitudeStep = 1.0), vehicle)
        val descent = EnergyModel.estimate(run(100, 15f, 15.0, altitudeStep = -1.0), vehicle)
        assertThat(climb.netKwh).isGreaterThan(descent.netKwh)
    }

    @Test
    fun `drag makes fast driving less efficient than slow driving`() {
        // Same distance covered, once at 15 m/s and once at 33 m/s.
        val slow = EnergyModel.estimate(run(100, 15f, 15.0), vehicle)
        val fast = EnergyModel.estimate(run(100, 33f, 15.0), vehicle)
        assertThat(fast.whPerKm)
            .`as`("aerodynamic drag rises with the square of speed")
            .isGreaterThan(slow.whPerKm)
    }

    @Test
    fun `efficiency lands in a plausible range for a real EV`() {
        val trip = EnergyModel.estimate(run(400, 17f, 17.0), vehicle)
        // Real small EVs manage roughly 5 to 9 km per kWh in mixed conditions.
        assertThat(trip.kmPerKwh).isBetween(3.0, 12.0)
        assertThat(trip.whPerKm).isBetween(80.0, 330.0)
    }

    @Test
    fun `projected range is efficiency times the usable pack`() {
        val trip = EnergyModel.estimate(run(400, 17f, 17.0), vehicle)
        assertThat(trip.projectedRangeKm(vehicle))
            .isCloseTo(trip.kmPerKwh * vehicle.usableBatteryKwh, within(0.001))
    }

    @Test
    fun `inaccurate fixes are excluded from the estimate`() {
        val noisy = run(50, 15f, 15.0).map { it.copy(accuracyM = 200f) }
        assertThat(EnergyModel.estimate(noisy, vehicle).distanceM).isEqualTo(0.0)
    }
}
