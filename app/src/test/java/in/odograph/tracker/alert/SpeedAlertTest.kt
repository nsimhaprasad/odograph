package `in`.odograph.tracker.alert

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SpeedAlertTest {

    private fun alert(limit: Float = 80f) = SpeedAlert(
        AlertConfig(limitKmh = limit, marginKmh = 3f, sustainMs = 3_000, repeatMs = 25_000)
    )

    @Test
    fun `a limit of zero disables the whole feature`() {
        val a = SpeedAlert(AlertConfig(limitKmh = 0f))
        val out = a.update(140f, 1_000)
        assertThat(out.overLimit).isFalse()
        assertThat(out.sound).isFalse()
    }

    @Test
    fun `cruising under the limit never alerts`() {
        val a = alert()
        var t = 0L
        repeat(60) {
            t += 1_000
            assertThat(a.update(72f, t).sound).isFalse()
        }
    }

    @Test
    fun `the visual warning fires immediately on crossing`() {
        val a = alert()
        val out = a.update(85f, 1_000)
        assertThat(out.overLimit).isTrue()
        // but the audible one waits out the sustain window
        assertThat(out.sound).isFalse()
    }

    @Test
    fun `a brief overtake does not trigger the chime`() {
        val a = alert()
        assertThat(a.update(86f, 1_000).sound).isFalse()
        assertThat(a.update(86f, 2_000).sound).isFalse()
        // dropped back before the 3s sustain elapsed
        assertThat(a.update(74f, 3_000).sound).isFalse()
        assertThat(a.update(74f, 9_000).sound).isFalse()
    }

    @Test
    fun `sustained overspeed does trigger the chime`() {
        val a = alert()
        a.update(86f, 1_000)
        a.update(86f, 2_500)
        assertThat(a.update(86f, 4_100).sound).isTrue()
    }

    @Test
    fun `it does not chime on every fix while still over`() {
        val a = alert()
        a.update(86f, 1_000)
        val fired = (2..30).count { a.update(86f, it * 1_000L).sound }
        // one at the sustain point, one more after the repeat interval
        assertThat(fired).isEqualTo(2)
    }

    @Test
    fun `it re-alerts once the repeat interval has elapsed`() {
        val a = alert()
        a.update(86f, 1_000)
        assertThat(a.update(86f, 4_100).sound).isTrue()
        assertThat(a.update(86f, 20_000).sound).isFalse()
        assertThat(a.update(86f, 29_200).sound).isTrue()
    }

    @Test
    fun `hovering inside the hysteresis band does not flap`() {
        val a = alert()
        a.update(86f, 1_000)
        a.update(86f, 4_100)              // alerting
        // 79-81 sits inside limit +/- 3, so state must hold rather than reset
        listOf(81f, 79f, 80.5f, 79.5f).forEachIndexed { i, s ->
            assertThat(a.update(s, 5_000L + i * 1_000).overLimit).isTrue()
        }
    }

    @Test
    fun `dropping clearly below the limit clears the alert`() {
        val a = alert()
        a.update(86f, 1_000)
        a.update(86f, 4_100)
        assertThat(a.update(70f, 5_000).overLimit).isFalse()
    }

    @Test
    fun `after clearing it can alert again on a fresh sustained overspeed`() {
        val a = alert()
        a.update(86f, 1_000)
        assertThat(a.update(86f, 4_100).sound).isTrue()
        a.update(70f, 5_000)
        a.update(90f, 6_000)
        assertThat(a.update(90f, 9_100).sound).isTrue()
    }

    @Test
    fun `changing the limit takes effect without restarting`() {
        val a = alert(limit = 80f)
        a.update(86f, 1_000)
        a.update(86f, 4_100)
        a.reconfigure(AlertConfig(limitKmh = 100f, marginKmh = 3f))
        assertThat(a.update(86f, 5_000).overLimit).isFalse()
    }
}
