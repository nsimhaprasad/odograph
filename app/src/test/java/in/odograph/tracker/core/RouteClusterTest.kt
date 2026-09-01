package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RouteClusterTest {

    private val home = Endpoint(12.9716, 77.5946)
    private val office = Endpoint(12.9698, 77.7500)

    @Test
    fun `endpoints within the radius resolve to the same place`() {
        val places = PlaceIndex()
        val nearby = Endpoint(home.lat + 0.0005, home.lon + 0.0005)  // ~74 m away
        assertThat(places.assign(nearby)).isEqualTo(places.assign(home))
        assertThat(places.size).isEqualTo(1)
    }

    @Test
    fun `endpoints straddling what would be a grid boundary still match`() {
        // This exact pair failed under grid snapping: a cell edge ran between them.
        val places = PlaceIndex()
        assertThat(places.assign(Endpoint(12.97155, 77.59455)))
            .isEqualTo(places.assign(Endpoint(12.97165, 77.59465)))
    }

    @Test
    fun `distant endpoints become different places`() {
        val places = PlaceIndex()
        assertThat(places.assign(office)).isNotEqualTo(places.assign(home))
        assertThat(places.size).isEqualTo(2)
    }

    @Test
    fun `repeated commutes are counted together`() {
        val trips = listOf(
            home to office,
            home to office,
            office to home,
            Endpoint(home.lat + 0.0004, home.lon) to office
        )
        assertThat(RouteCluster.group(trips).values.max()).isEqualTo(3)
    }

    @Test
    fun `direction matters so the return leg is a different route`() {
        assertThat(RouteCluster.group(listOf(home to office, office to home))).hasSize(2)
    }
}
