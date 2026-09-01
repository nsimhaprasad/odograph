package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class GeoTest {
    @Test
    fun `same point is zero metres`() {
        assertThat(Geo.haversineMetres(12.97, 77.59, 12.97, 77.59))
            .isCloseTo(0.0, within(0.01))
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertThat(Geo.haversineMetres(12.0, 77.0, 13.0, 77.0))
            .isCloseTo(111_195.0, within(500.0))
    }

    @Test
    fun `a degree of longitude shrinks with latitude`() {
        val atEquator = Geo.haversineMetres(0.0, 77.0, 0.0, 78.0)
        val atBengaluru = Geo.haversineMetres(13.0, 77.0, 13.0, 78.0)
        assertThat(atBengaluru).isLessThan(atEquator)
        assertThat(atBengaluru).isCloseTo(108_400.0, within(1_000.0))
    }
}
