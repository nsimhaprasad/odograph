package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * When the box is allowed to call the MG servers.
 *
 * The first test is the one that matters most, and it is the regression test for a bug that cost
 * months of battery data: before it, the box connected to MG only when somebody opened the app,
 * so every drive nobody watched was recorded with no charge level, no energy and no cost.
 */
class TelematicsScheduleTest {

    private val heartbeat = 5 * 60_000L
    private val floor = 30_000L

    private fun attempt(
        sinceCall: Long? = null,
        sinceAttempt: Long? = null,
        visible: Boolean = false,
        refresh: Boolean = false
    ) = TelematicsSchedule.shouldAttempt(
        sinceLastCallMs = sinceCall,
        sinceLastAttemptMs = sinceAttempt,
        screenVisible = visible,
        refreshRequested = refresh,
        heartbeatMs = heartbeat,
        minIntervalMs = floor
    )

    /**
     * The whole point. A box that has just booted in a car nobody is looking at must connect by
     * itself — that is the only way a drive gets a charge level, and without one it has no energy
     * figure and no cost, permanently.
     */
    @Test
    fun `a box that has just booted connects without anyone opening the app`() {
        assertThat(attempt(sinceCall = null, sinceAttempt = null, visible = false)).isTrue()
    }

    @Test
    fun `having never connected, it keeps trying at the floor's cadence`() {
        assertThat(attempt(sinceCall = null, sinceAttempt = floor)).isTrue()
        assertThat(attempt(sinceCall = null, sinceAttempt = floor * 3)).isTrue()
    }

    /**
     * The other half of that. A box with no signal abandons the attempt before it reaches the
     * network, so "attempted" and "called" diverge — and if the retry were governed by the last
     * successful *call*, a box that had never connected would retry on every wake-up of the loop,
     * once a second, posting an outage notification each time.
     */
    @Test
    fun `a box with no signal retries on a timer rather than spinning`() {
        assertThat(attempt(sinceCall = null, sinceAttempt = 1_000L)).isFalse()
        assertThat(attempt(sinceCall = null, sinceAttempt = floor - 1)).isFalse()
    }

    // ------------------------------------------------------- protecting the account

    /**
     * These servers belong to someone else. A box that hammers them looks like an attack and the
     * account gets blocked, which costs every number in the app — so the floor outranks every
     * trigger, including a human tapping refresh.
     */
    @Test
    fun `nothing gets past the rate floor, not even an explicit refresh`() {
        assertThat(attempt(sinceCall = 5_000L, sinceAttempt = 5_000L, refresh = true)).isFalse()
        assertThat(attempt(sinceCall = 5_000L, sinceAttempt = 5_000L, visible = true)).isFalse()
    }

    @Test
    fun `an open MG screen polls at the floor, not faster`() {
        assertThat(attempt(sinceCall = floor - 1, sinceAttempt = floor - 1, visible = true))
            .isFalse()
        assertThat(attempt(sinceCall = floor, sinceAttempt = floor, visible = true)).isTrue()
    }

    // ------------------------------------------------------- the background heartbeat

    @Test
    fun `in the background it waits for the heartbeat rather than the floor`() {
        assertThat(attempt(sinceCall = floor, sinceAttempt = floor)).isFalse()
        assertThat(attempt(sinceCall = heartbeat - 1, sinceAttempt = heartbeat - 1)).isFalse()
        assertThat(attempt(sinceCall = heartbeat, sinceAttempt = heartbeat)).isTrue()
    }

    @Test
    fun `an explicit refresh does not wait for the heartbeat`() {
        assertThat(attempt(sinceCall = floor, sinceAttempt = floor, refresh = true)).isTrue()
    }

    /**
     * The arithmetic that broke the original. A sentinel standing in for "never" was subtracted
     * from the clock and overflowed, making every elapsed comparison false forever. Null cannot be
     * subtracted from anything, so the shape of that mistake is unavailable here — this pins it.
     */
    @Test
    fun `never is absence, not a number that can be subtracted`() {
        // Once the floor has passed, "never called" means connect — however long the box has been
        // up. The old sentinel made this false at every uptime, which is the bug.
        listOf(floor, 3_600_000L, Long.MAX_VALUE).forEach { sinceAttempt ->
            assertThat(attempt(sinceCall = null, sinceAttempt = sinceAttempt))
                .`as`("since attempt %d", sinceAttempt)
                .isTrue()
        }
        // And with nothing attempted yet, immediately.
        assertThat(attempt(sinceCall = null, sinceAttempt = null)).isTrue()
    }
}
