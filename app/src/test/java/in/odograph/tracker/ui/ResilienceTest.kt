package `in`.odograph.tracker.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The wrapper every screen loads its data through.
 *
 * This is the whole of the containment story and it is deliberately tiny, because the alternative
 * turned out not to exist. Compose forbids `try` around a composable call — it is a compile error,
 * not an omission — so a feature cannot be wrapped in a catch-all after the fact. Containment has
 * to happen before composition, at the load, which is where the exceptions actually are anyway:
 * a database that has just migrated, a row written by an older build, an arithmetic edge nobody
 * has driven into yet.
 *
 * A note on what is *not* tested here, because it matters more than what is. There is no
 * end-to-end test rendering a screen against a failing database, and not for want of trying: a
 * closed Room database silently reopens, and a database file overwritten with garbage is quietly
 * recreated. Both were attempted, both injected nothing, and both produced a confident green test
 * that asserted the absence of a failure that had never happened. That test was deleted rather
 * than kept. The screens' use of [loaded] is verified by reading them.
 */
class ResilienceTest {

    @Test
    fun `a load that works gives back its value`() {
        val result = loaded { 42 }

        assertThat(result).isInstanceOf(Loaded.Ready::class.java)
        assertThat((result as Loaded.Ready).value).isEqualTo(42)
    }

    /**
     * The point of the whole thing: the throw stops here. In a screen this call sits inside a
     * coroutine launched from composition, and an exception escaping it does not fail that screen
     * — it takes down the process, and with it the recording of the drive in progress.
     */
    @Test
    fun `a load that throws is contained rather than propagated`() {
        val result = loaded { error("the database is having a bad day") }

        assertThat(result).isInstanceOf(Loaded.Failed::class.java)
        assertThat((result as Loaded.Failed).error).hasMessageContaining("bad day")
    }

    /**
     * Errors, not just exceptions. An OutOfMemoryError or a StackOverflowError from a runaway
     * statistic is exactly as fatal to a moving car's display as an IllegalStateException, and a
     * net that catches only the polite half of Throwable is not a net.
     */
    @Test
    fun `a load that fails hard is contained too`() {
        val result = loaded { throw StackOverflowError("a recursion that ran away") }

        assertThat(result).isInstanceOf(Loaded.Failed::class.java)
    }

    /**
     * The failure is kept rather than swallowed. A panel that is empty because there is nothing to
     * show and a panel that is empty because it broke look identical to a driver, and only one of
     * them is worth telling anybody about.
     */
    @Test
    fun `a contained failure keeps the reason`() {
        val result = loaded { throw IllegalArgumentException("soc was 250%") } as Loaded.Failed

        assertThat(result.error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(result.error.message).isEqualTo("soc was 250%")
    }
}
