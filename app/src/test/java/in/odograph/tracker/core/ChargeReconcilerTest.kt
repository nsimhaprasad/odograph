package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Energy that arrived while nobody was watching.
 *
 * The ledger is built from live polling frames, and on this car there usually are none: the box is
 * powered by the vehicle, so it switches off at the moment the vehicle is plugged in. A fill that
 * runs to 100% overnight is therefore invisible to everything that builds sessions, and the pack
 * is simply fuller in the morning with nothing in the history to explain it.
 *
 * The two failure directions are not equally expensive. Missing a charge leaves the history short
 * of energy the car really took, which quietly corrupts every cost and efficiency figure built on
 * it. Inventing one puts a fictional fill and a fictional bill in front of the driver. So the bar
 * is evidence: a rise in the state of charge that the sessions cannot account for.
 */
class ChargeReconcilerTest {

    private val hour = 3_600_000L

    private fun reading(soc: Double, at: Long) = ChargeReconciler.Reading(soc, at)

    private fun session(
        id: Long = 1L,
        endSoc: Double?,
        endedAt: Long,
        open: Boolean = false
    ) = ChargeReconciler.Session(id, endSoc, endedAt, open)

    // ---------------------------------------------------------------- the case this exists for

    /**
     * The driver's own example: a session recorded 25→60, and the car is now sitting at 80 with
     * nothing driven in between. Those forty percent went somewhere, and the only thing that puts
     * charge into a pack is charging.
     */
    @Test
    fun `a car fuller than the last session left it has been charged`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(60.0, at = 10 * hour),
            latest = reading(80.0, at = 18 * hour),
            lastSession = session(endSoc = 60.0, endedAt = 10 * hour)
        )

        assertThat(action)
            .`as`("the pack cannot gain twenty percent on its own")
            .isInstanceOf(ChargeReconciler.Action.Extend::class.java)
    }

    /**
     * The plug was never pulled — the box just stopped watching. Extending is right and opening a
     * second row is not: a second row would claim the car was unplugged and plugged back in, which
     * is a thing that did not happen.
     */
    @Test
    fun `a charge that continued past the last frame extends that session`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(60.0, at = 2 * hour),
            latest = reading(100.0, at = 9 * hour),
            lastSession = session(id = 7L, endSoc = 60.0, endedAt = 2 * hour)
        ) as ChargeReconciler.Action.Extend

        assertThat(action.sessionId).isEqualTo(7L)
        assertThat(action.toSoc).isEqualTo(100.0)
    }

    /** A fill with no session anywhere near it is reconstructed whole. */
    @Test
    fun `a charge with nothing to attach it to is recorded as its own session`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(30.0, at = 2 * hour),
            latest = reading(90.0, at = 9 * hour),
            lastSession = null
        ) as ChargeReconciler.Action.Record

        assertThat(action.fromSoc).isEqualTo(30.0)
        assertThat(action.toSoc).isEqualTo(90.0)
        assertThat(action.from).isEqualTo(2 * hour)
        assertThat(action.to).isEqualTo(9 * hour)
    }

    /**
     * Driving between the two readings means the pack was being emptied at the same time, so the
     * session cannot be a continuation of the last one — the car went away and came back.
     */
    @Test
    fun `a charge after a drive is a new session rather than a continuation`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(60.0, at = 2 * hour),
            latest = reading(80.0, at = 9 * hour),
            lastSession = session(endSoc = 60.0, endedAt = 2 * hour),
            drivenSinceM = 40_000.0
        )

        assertThat(action).isInstanceOf(ChargeReconciler.Action.Record::class.java)
    }

    // ---------------------------------------------------------------- not inventing charges

    @Test
    fun `a pack that is emptier than it was has been driven, not charged`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(80.0, at = 2 * hour),
            latest = reading(45.0, at = 9 * hour),
            lastSession = session(endSoc = 80.0, endedAt = 2 * hour)
        )

        assertThat(action).isEqualTo(ChargeReconciler.Action.Nothing)
    }

    /**
     * The car reports whole percents and the reading wanders, particularly just after a drive
     * while the pack settles. Booking single points as charges would fill the history with
     * sessions that never happened, each carrying fictional energy and a price to match.
     */
    @Test
    fun `a percent of meter wander is not a charge`() {
        listOf(0.0, 0.5, 1.0, ChargeReconciler.NOISE_PERCENT).forEach { drift ->
            val action = ChargeReconciler.reconcile(
                lastKnown = reading(60.0, at = 2 * hour),
                latest = reading(60.0 + drift, at = 9 * hour),
                lastSession = session(endSoc = 60.0, endedAt = 2 * hour)
            )
            assertThat(action).`as`("a rise of $drift").isEqualTo(ChargeReconciler.Action.Nothing)
        }
    }

    /**
     * No baseline is not a baseline of zero. A box with no history must not decide the pack went
     * from empty to wherever it is now and bill the difference.
     */
    @Test
    fun `a first ever reading is not a charge from empty`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = null,
            latest = reading(72.0, at = 9 * hour),
            lastSession = null
        )

        assertThat(action).isEqualTo(ChargeReconciler.Action.Nothing)
    }

    /**
     * While a session is open the frames are arriving and the ledger is already advancing it on
     * them. Stepping in would book the same kilowatt-hours a second time.
     */
    @Test
    fun `a session still being watched is left to the ledger`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(40.0, at = 2 * hour),
            latest = reading(70.0, at = 3 * hour),
            lastSession = session(endSoc = 40.0, endedAt = 2 * hour, open = true)
        )

        assertThat(action).isEqualTo(ChargeReconciler.Action.Nothing)
    }

    /** A reading that is not newer than the baseline says nothing about what happened between. */
    @Test
    fun `a reading older than the baseline is ignored`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(40.0, at = 9 * hour),
            latest = reading(90.0, at = 2 * hour),
            lastSession = null
        )

        assertThat(action).isEqualTo(ChargeReconciler.Action.Nothing)
    }

    /**
     * A session whose end level was never captured cannot be shown to join up with anything, so
     * the rise becomes its own reconstructed session rather than silently extending a row whose
     * endpoint is unknown.
     */
    @Test
    fun `a session with no closing level is not extended on faith`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(60.0, at = 2 * hour),
            latest = reading(90.0, at = 9 * hour),
            lastSession = session(endSoc = null, endedAt = 2 * hour)
        )

        assertThat(action).isInstanceOf(ChargeReconciler.Action.Record::class.java)
    }

    /**
     * The last session ended somewhere else entirely — the car has been driven and charged since,
     * unwatched. Extending would backdate this fill onto a session it has nothing to do with.
     */
    @Test
    fun `a session that ended at a different level is not extended`() {
        val action = ChargeReconciler.reconcile(
            lastKnown = reading(35.0, at = 8 * hour),
            latest = reading(90.0, at = 9 * hour),
            lastSession = session(endSoc = 60.0, endedAt = 2 * hour)
        )

        assertThat(action).isInstanceOf(ChargeReconciler.Action.Record::class.java)
    }
}
