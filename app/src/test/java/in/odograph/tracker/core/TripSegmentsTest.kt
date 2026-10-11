package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.BatteryEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Cutting a drive into stretches and reading each one's consumption.
 *
 * The cases that matter are the ones that mislead: a stretch too short, or one where the car's
 * energy counter barely ticked, reads an efficiency that means nothing, and it must never become
 * the drive's "best" or stretch the colour scale. The happy path — a long drive that was frugal
 * early and thirsty late — is here too, because that is the whole point of the view.
 */
class TripSegmentsTest {

    private val cap = 51.6

    private fun frame(
        t: Long, odo: Double, power: Double,
        speed: Double = 50.0, charging: Boolean = false, soc: Double? = null
    ) = BatteryEntity(
        tripId = 1, t = t, socPercent = soc, charging = charging,
        odometerKm = odo, powerUsageSinceLastChargeKwh = power, carSpeedKmh = speed
    )

    /** Frames at the given odometer/counter readings, one every two minutes. */
    private fun drive(points: List<Pair<Double, Double>>, charging: List<Boolean>? = null) =
        points.mapIndexed { i, (odo, power) ->
            frame(1_000_000L + i * 120_000L, odo, power, charging = charging?.get(i) ?: false)
        }

    @Test
    fun `a frugal-then-thirsty drive splits into stretches with the right best and worst`() {
        // 60 km: first 30 at 10 km/kWh (0→3 kWh), next 30 at 5 km/kWh (3→9 kWh).
        val frames = drive(listOf(
            1000.0 to 0.0, 1010.0 to 1.0, 1020.0 to 2.0, 1030.0 to 3.0,
            1040.0 to 5.0, 1050.0 to 7.0, 1060.0 to 9.0
        ))
        val b = TripSegments.of(frames, cap)!!

        assertThat(b.segments).hasSize(2)
        assertThat(b.segments[0].kmPerKwh).isEqualTo(10.0)
        assertThat(b.segments[1].kmPerKwh).isEqualTo(5.0)
        assertThat(b.bestIndex).isEqualTo(0)
        assertThat(b.worstIndex).isEqualTo(1)
        // 60 km on 9 kWh.
        assertThat(b.overallKmPerKwh).isEqualTo(6.67)
        assertThat(b.segments[0].distanceKm).isEqualTo(30.0)
    }

    @Test
    fun `a stretch whose counter barely ticked gets no figure and never wins`() {
        // First 30 km real (8 km/kWh); over the next 30 the counter moves 0.15 kWh — 200 km/kWh,
        // which is not frugality, it is a counter that missed the stretch.
        val frames = drive(listOf(
            1000.0 to 0.0, 1010.0 to 1.25, 1020.0 to 2.5, 1030.0 to 3.75,
            1040.0 to 3.80, 1050.0 to 3.85, 1060.0 to 3.90
        ))
        val b = TripSegments.of(frames, cap)!!

        assertThat(b.segments).hasSize(2)
        assertThat(b.segments[0].kmPerKwh).isEqualTo(8.0)
        assertThat(b.segments[1].kmPerKwh).isNull()        // the outlier is withheld
        assertThat(b.segments[1].distanceKm).isEqualTo(30.0) // but the stretch still shows
        assertThat(b.bestIndex).isEqualTo(0)
        assertThat(b.worstIndex).isEqualTo(0)               // only the measured stretch counts
    }

    @Test
    fun `a short drive stays one segment`() {
        val frames = drive(listOf(
            1000.0 to 0.0, 1002.0 to 0.25, 1005.0 to 0.625, 1010.0 to 1.25
        ))
        val b = TripSegments.of(frames, cap)!!
        assertThat(b.segments).hasSize(1)
        assertThat(b.segments[0].kmPerKwh).isEqualTo(8.0) // 10 km / 1.25 kWh
    }

    @Test
    fun `a two-kilometre hop shows distance but no efficiency`() {
        val frames = drive(listOf(1000.0 to 0.0, 1001.0 to 0.1, 1002.0 to 0.2))
        val b = TripSegments.of(frames, cap)!!
        assertThat(b.segments).hasSize(1)
        assertThat(b.segments[0].distanceKm).isEqualTo(2.0)
        assertThat(b.segments[0].kmPerKwh).isNull()        // too short for the ratio to mean anything
        assertThat(b.bestIndex).isEqualTo(-1)
    }

    @Test
    fun `charging at the ends is not read as part of the drive`() {
        val frames = drive(
            listOf(
                999.0 to 0.0,                                   // plugged in, parked
                1000.0 to 0.0, 1010.0 to 1.0, 1020.0 to 2.0, 1030.0 to 3.0,
                1040.0 to 5.0, 1050.0 to 7.0, 1060.0 to 9.0,
                1060.0 to 9.0                                   // plugged in on arrival
            ),
            charging = listOf(true, false, false, false, false, false, false, false, true)
        )
        val b = TripSegments.of(frames, cap)!!
        assertThat(b.segments).hasSize(2)
        assertThat(b.segments.first().fromKm).isEqualTo(0.0) // measured from the first driving frame
        assertThat(b.segments.last().toKm).isEqualTo(60.0)
    }

    @Test
    fun `no frames, or no movement, is nothing to show`() {
        assertThat(TripSegments.of(emptyList(), cap)).isNull()
        assertThat(TripSegments.of(drive(listOf(1000.0 to 0.0, 1000.0 to 0.0)), cap)).isNull()
    }
}
