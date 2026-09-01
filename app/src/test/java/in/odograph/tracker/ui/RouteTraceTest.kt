package `in`.odograph.tracker.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RouteTraceTest {

    @Test
    fun `an empty route normalises to an empty list`() {
        assertThat(normaliseRoute(emptyList())).isEmpty()
    }

    @Test
    fun `all normalised points fall inside the unit box`() {
        val pts = listOf(12.90 to 77.50, 12.99 to 77.62, 13.05 to 77.55)
        normaliseRoute(pts).forEach { (x, y) ->
            assertThat(x).isBetween(0f, 1f)
            assertThat(y).isBetween(0f, 1f)
        }
    }

    @Test
    fun `a single point maps to the centre rather than dividing by zero`() {
        assertThat(normaliseRoute(listOf(12.97 to 77.59))).containsExactly(0.5f to 0.5f)
    }

    @Test
    fun `identical points do not blow up the projection`() {
        val same = List(5) { 12.97 to 77.59 }
        assertThat(normaliseRoute(same)).hasSize(5)
    }

    @Test
    fun `aspect ratio is preserved so a route is not stretched`() {
        // A route twice as wide as it is tall should stay twice as wide once normalised.
        val pts = listOf(12.970 to 77.590, 12.975 to 77.600)
        val n = normaliseRoute(pts)
        val dx = kotlin.math.abs(n[1].first - n[0].first)
        val dy = kotlin.math.abs(n[1].second - n[0].second)
        assertThat(dx / dy).isCloseTo(2.0f, org.assertj.core.api.Assertions.within(0.05f))
    }
}
