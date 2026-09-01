package `in`.odograph.tracker.ui

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class FormatTest {

    @Test
    fun `metres per second convert to kilometres per hour`() {
        assertThat(mpsToKmh(10f)).isCloseTo(36f, within(0.01f))
    }

    @Test
    fun `durations format as zero padded hours and minutes`() {
        assertThat(formatHhMm(0)).isEqualTo("00:00")
        assertThat(formatHhMm(3_600)).isEqualTo("01:00")
        assertThat(formatHhMm(3_661)).isEqualTo("01:01")
        assertThat(formatHhMm(86_399)).isEqualTo("23:59")
    }

    @Test
    fun `a drive longer than a day keeps counting hours rather than wrapping`() {
        assertThat(formatHhMm(90_000)).isEqualTo("25:00")
    }

    @Test
    fun `distance shows one decimal place`() {
        assertThat(formatKm(18_432.0)).isEqualTo("18.4")
        assertThat(formatKm(0.0)).isEqualTo("0.0")
    }
}
