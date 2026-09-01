package `in`.odograph.tracker.ui.gauge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test
import kotlin.math.abs

class SpeedSpringTest {

    @Test
    fun `it converges on a steady target`() {
        val s = SpeedSpring()
        repeat(200) { s.update(20f, 0.1f) }
        assertThat(s.update(20f, 0.1f)).isCloseTo(20f, within(0.1f))
    }

    @Test
    fun `it attenuates jitter rather than following it`() {
        val s = SpeedSpring()
        repeat(100) { s.update(20f, 0.1f) }
        var maxDeviation = 0f
        repeat(40) { i ->
            val noisy = if (i % 2 == 0) 23f else 17f
            maxDeviation = maxOf(maxDeviation, abs(s.update(noisy, 0.1f) - 20f))
        }
        assertThat(maxDeviation).isLessThan(1.5f)
    }

    @Test
    fun `it starts at zero`() {
        assertThat(SpeedSpring().update(0f, 0.1f)).isEqualTo(0f)
    }

    @Test
    fun `a long frame gap does not overshoot the target`() {
        val s = SpeedSpring()
        assertThat(s.update(50f, 10f)).isEqualTo(50f)
    }
}
