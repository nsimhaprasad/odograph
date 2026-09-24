package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The fixes held back before a drive is known to have started.
 *
 * The first test is the bug: the system's cached "last known" position, stamped by a clock that
 * had been sitting at the image's build date, arriving first and becoming the origin of the next
 * drive — a trip timer that read 7508:59, to a date before the car was bought.
 */
class FixWindowTest {

    private fun fix(t: Long) = Fix(t, 12.97, 77.59, 0f, 5f, false, 900.0)

    private val nov2025 = 1_763_164_800_000L        // 15 Nov 2025
    private val sep2026 = 1_790_208_000_000L        // 24 Sep 2026

    @Test
    fun `a stale cached position is dropped the moment a real fix arrives`() {
        val held = listOf(fix(nov2025))

        val window = FixWindow.admit(held, fix(sep2026))

        assertThat(window.map { it.t }).containsExactly(sep2026)
    }

    /** The stale fix is first and alone; nothing newer exists yet to measure it against. */
    @Test
    fun `alone, a fix is kept whatever its age`() {
        assertThat(FixWindow.admit(emptyList(), fix(nov2025))).hasSize(1)
    }

    @Test
    fun `a stale fix arriving after real ones is dropped too`() {
        val held = listOf(fix(sep2026), fix(sep2026 + 1_000))

        val window = FixWindow.admit(held, fix(nov2025))

        assertThat(window.map { it.t }).containsExactly(sep2026, sep2026 + 1_000)
    }

    @Test
    fun `fixes within the age limit are all kept, in time order`() {
        val held = listOf(fix(sep2026 + 2_000), fix(sep2026))

        val window = FixWindow.admit(held, fix(sep2026 + 1_000))

        assertThat(window.map { it.t }).containsExactly(sep2026, sep2026 + 1_000, sep2026 + 2_000)
    }

    /** A queue that has lasted longer than the limit keeps only its recent end. */
    @Test
    fun `the window slides as time passes`() {
        val held = (0 until 10).map { fix(sep2026 + it * 60_000L) }      // ten minutes, one a minute

        val window = FixWindow.admit(held, fix(sep2026 + 10 * 60_000L))

        // Newest is t+10min; anything older than t+5min goes.
        assertThat(window.first().t).isEqualTo(sep2026 + 5 * 60_000L)
        assertThat(window.last().t).isEqualTo(sep2026 + 10 * 60_000L)
    }

    @Test
    fun `the count bound still applies to a fast receiver`() {
        val held = (0 until 100).map { fix(sep2026 + it * 200L) }       // 5 Hz for 20 s

        val window = FixWindow.admit(held, fix(sep2026 + 100 * 200L))

        assertThat(window).hasSize(FixWindow.MAX_COUNT)
        assertThat(window.last().t).isEqualTo(sep2026 + 100 * 200L)
    }
}
