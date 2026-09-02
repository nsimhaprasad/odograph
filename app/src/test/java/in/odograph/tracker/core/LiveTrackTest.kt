package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class LiveTrackTest {

    private fun fix(t: Long, lat: Double, lon: Double, speed: Float = 0f, acc: Float = 20f) =
        Fix(t, lat, lon, speed, acc)

    @Test
    fun `speed is derived when the provider supplies none`() {
        val track = LiveTrack()
        track.add(fix(0, 12.9700, 77.5900))
        // ~111 m north in 10 s = 11.1 m/s
        track.add(fix(10_000, 12.9710, 77.5900))

        assertThat(track.speedMps).isCloseTo(11.1f, within(1.5f))
        assertThat(track.distanceM).isCloseTo(111.0, within(15.0))
    }

    @Test
    fun `provider speed wins over derivation when GNSS supplies it`() {
        val track = LiveTrack()
        track.add(fix(0, 12.9700, 77.5900, speed = 20f, acc = 5f))
        track.add(fix(1000, 12.9701, 77.5900, speed = 20f, acc = 5f))
        assertThat(track.speedMps).isEqualTo(20f)
    }

    @Test
    fun `a parked vehicle on a wandering network fix stays at zero`() {
        val track = LiveTrack()
        listOf(0.0, 0.00003, -0.00002, 0.00004, -0.00003, 0.00002, 0.0, 0.00001)
            .forEachIndexed { i, d ->
                track.add(fix(i * 1000L, 12.9716 + d, 77.5946 - d))
            }
        assertThat(track.distanceM).isEqualTo(0.0)
        assertThat(track.speedMps).isEqualTo(0f)
    }

    @Test
    fun `an implausible jump is not reported as speed`() {
        val track = LiveTrack()
        track.add(fix(0, 12.9700, 77.5900))
        track.add(fix(1000, 13.5000, 77.5900))   // 59 km in one second
        assertThat(track.speedMps).isEqualTo(0f)
    }

    @Test
    fun `inaccurate fixes are ignored entirely`() {
        val track = LiveTrack()
        track.add(fix(0, 12.9700, 77.5900, acc = 200f))
        track.add(fix(1000, 12.9800, 77.5900, acc = 200f))
        assertThat(track.distanceM).isEqualTo(0.0)
    }

    @Test
    fun `distance accumulates across a real drive`() {
        val track = LiveTrack()
        repeat(20) { i ->
            // ~111 m per step, one step every 5 s ~= 80 km/h
            track.add(fix(i * 5_000L, 12.9700 + i * 0.001, 77.5900))
        }
        assertThat(track.distanceM).isCloseTo(2110.0, within(200.0))
        assertThat(track.speedMps).isCloseTo(22.2f, within(3f))
    }

    @Test
    fun `speed falls back to zero after the vehicle stops moving`() {
        val track = LiveTrack()
        track.add(fix(0, 12.9700, 77.5900))
        track.add(fix(10_000, 12.9710, 77.5900))
        assertThat(track.speedMps).isGreaterThan(5f)

        // Parked: subsequent fixes never clear the noise floor.
        track.add(fix(20_000, 12.97101, 77.5900))
        assertThat(track.speedMps).isEqualTo(0f)
    }
}
