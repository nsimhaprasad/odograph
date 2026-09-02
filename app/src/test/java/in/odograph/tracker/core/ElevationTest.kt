package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class ElevationTest {

    private fun fix(i: Int, altitude: Double?, lat: Double = 12.97 + i * 0.001) =
        Fix(i * 1000L, lat, 77.59, 15f, 6f, altitudeM = altitude)

    @Test
    fun `no altitude data yields an empty profile rather than zeros pretending to be data`() {
        val p = Elevation.profile(listOf(fix(0, null), fix(1, null)))
        assertThat(p.currentM).isNull()
        assertThat(p.gainM).isEqualTo(0.0)
    }

    @Test
    fun `a steady climb is counted once, not per sample`() {
        val fixes = (0..20).map { fix(it, 900.0 + it * 5.0) }   // 100 m of climb
        val p = Elevation.profile(fixes)
        assertThat(p.gainM).isCloseTo(100.0, within(6.0))
        assertThat(p.lossM).isEqualTo(0.0)
    }

    @Test
    fun `noise on a flat road does not accumulate into phantom climb`() {
        // GNSS altitude wanders by a couple of metres; summing raw deltas would report hundreds.
        val wobble = listOf(0.0, 1.8, -1.5, 2.0, -2.1, 1.2, -0.9, 1.7, -1.4, 0.6)
        val fixes = wobble.mapIndexed { i, d -> fix(i, 900.0 + d) }
        val p = Elevation.profile(fixes)
        assertThat(p.gainM).`as`("flat road").isEqualTo(0.0)
        assertThat(p.lossM).`as`("flat road").isEqualTo(0.0)
    }

    @Test
    fun `a climb followed by a descent reports both separately`() {
        val up = (0..10).map { fix(it, 900.0 + it * 10.0) }
        val down = (11..20).map { fix(it, 1000.0 - (it - 10) * 10.0) }
        val p = Elevation.profile(up + down)
        assertThat(p.gainM).isCloseTo(100.0, within(10.0))
        assertThat(p.lossM).isCloseTo(100.0, within(10.0))
        assertThat(p.netM).isCloseTo(0.0, within(15.0))
    }

    @Test
    fun `min max and current altitude are reported`() {
        val fixes = listOf(fix(0, 900.0), fix(1, 950.0), fix(2, 880.0), fix(3, 920.0))
        val p = Elevation.profile(fixes)
        assertThat(p.minM).isEqualTo(880.0)
        assertThat(p.maxM).isEqualTo(950.0)
        assertThat(p.currentM).isEqualTo(920.0)
    }
}
