package `in`.odograph.tracker.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The resolution a projected CarPlay session negotiates is not knowable in advance, so the sizing
 * policy is checked against every viewport the head unit might plausibly hand us.
 */
class MetricsTest {

    /** width x height in dp, spanning tiny 800x480 units through wide 1920x720 ones. */
    private val viewports = listOf(
        "extreme small" to (427f to 240f),
        "small 800x480" to (533f to 300f),
        "common 1280x720" to (640f to 360f),
        "wide short" to (960f to 360f),
        "large" to (800f to 480f),
        "very large" to (1280f to 800f)
    )

    @Test
    fun `the driver column fits inside every candidate viewport`() {
        viewports.forEach { (name, size) ->
            val (w, h) = size
            val m = metricsFor(w, h)
            val available = h - m.padDp * 2 - m.topBarHeightDp
            assertThat(m.driverColumnHeightDp)
                .`as`("driver column at $name (${w}x$h)")
                .isLessThanOrEqualTo(available)
        }
    }

    @Test
    fun `the detailed stat strip leaves room for the map above it`() {
        viewports.forEach { (name, size) ->
            val (w, h) = size
            val m = metricsFor(w, h)
            val used = m.padDp * 2 + m.topBarHeightDp + m.statStripHeightDp + m.gapDp
            assertThat(used)
                .`as`("detail chrome at $name (${w}x$h) must leave over half the height for the map")
                .isLessThan(h * 0.5f)
        }
    }

    @Test
    fun `type never falls below a legible floor`() {
        viewports.forEach { (name, size) ->
            val m = metricsFor(size.first, size.second)
            assertThat(m.labelSp).`as`("label at $name").isGreaterThanOrEqualTo(8f)
            assertThat(m.bodySp).`as`("body at $name").isGreaterThanOrEqualTo(10f)
            assertThat(m.heroSp).`as`("hero at $name").isGreaterThanOrEqualTo(20f)
        }
    }

    @Test
    fun `touch targets stay large enough for an imprecise single touch`() {
        viewports.forEach { (name, size) ->
            val m = metricsFor(size.first, size.second)
            val chipHeight = m.chipTextSp * 1.25f + m.chipPadVDp * 2
            assertThat(chipHeight).`as`("chip height at $name").isGreaterThanOrEqualTo(40f)
        }
    }

    @Test
    fun `sizes grow monotonically with available height`() {
        val small = metricsFor(533f, 300f)
        val large = metricsFor(1280f, 800f)
        assertThat(large.heroSp).isGreaterThan(small.heroSp)
        assertThat(large.padDp).isGreaterThanOrEqualTo(small.padDp)
    }

    @Test
    fun `hero type is capped so it cannot dominate a very tall screen`() {
        assertThat(metricsFor(2000f, 2000f).heroSp).isLessThanOrEqualTo(74f)
    }

    @Test
    fun `compact flags trip at the expected thresholds`() {
        assertThat(metricsFor(533f, 300f).veryCompact).isTrue()
        assertThat(metricsFor(1280f, 800f).compact).isFalse()
        assertThat(metricsFor(960f, 360f).compact).isTrue()
    }
}
