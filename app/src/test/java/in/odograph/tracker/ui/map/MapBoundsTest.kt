package `in`.odograph.tracker.ui.map

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class MapBoundsTest {

    private val route = listOf(
        12.9716 to 77.5946,
        12.9800 to 77.6100,
        12.9698 to 77.7500
    )

    @Test
    fun `an empty route has no bounds`() {
        assertThat(MapBounds.of(emptyList())).isNull()
    }

    @Test
    fun `bounds span the extremes of the route`() {
        val b = MapBounds.of(route)!!
        assertThat(b.minLat).isEqualTo(12.9698)
        assertThat(b.maxLat).isEqualTo(12.9800)
        assertThat(b.minLon).isEqualTo(77.5946)
        assertThat(b.maxLon).isEqualTo(77.7500)
    }

    @Test
    fun `padding grows the box on every side`() {
        val b = MapBounds.padded(MapBounds.of(route)!!, 0.10)
        assertThat(b.minLat).isLessThan(12.9698)
        assertThat(b.maxLat).isGreaterThan(12.9800)
        assertThat(b.minLon).isLessThan(77.5946)
        assertThat(b.maxLon).isGreaterThan(77.7500)
    }

    @Test
    fun `a stationary trip still yields a non degenerate padded box`() {
        val b = MapBounds.padded(MapBounds.of(listOf(12.97 to 77.59))!!)
        assertThat(b.maxLat).isGreaterThan(b.minLat)
        assertThat(b.maxLon).isGreaterThan(b.minLon)
    }
}
