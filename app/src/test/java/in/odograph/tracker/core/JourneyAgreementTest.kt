package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Whether the app's inferred trip boundaries match the ones the car drew for itself.
 *
 * Every boundary this app draws is a guess from stillness, and the guesses have been expensive:
 * drives ended at red lights, an evening shredded into fragments, journeys split by a selector
 * moved to park while waiting. The car does not guess — it numbers its journeys, and that number
 * rides on every frame.
 *
 * These tests pin the reading of that evidence, not an action taken on it. Nothing acts on the
 * verdict yet, and the reason is [Telematics]-shaped: replacing a working inference with an
 * unstudied signal is exactly how the `locked` and `canBusActive` mistakes happened, twice.
 */
class JourneyAgreementTest {

    @Test
    fun `one identifier across a drive means both drew the same boundaries`() {
        assertThat(JourneyAgreement.verdict(listOf(41, 41, 41)))
            .isEqualTo(JourneyAgreement.Verdict.AGREED)
    }

    @Test
    fun `a second identifier means the car ended a journey the app ran through`() {
        assertThat(JourneyAgreement.verdict(listOf(41, 42)))
            .isEqualTo(JourneyAgreement.Verdict.CAR_SPLIT_IT)
    }

    /**
     * The common case, and the reason the verdict has three values rather than two. The telematics
     * link is down for most of most drives — a basement, a tunnel, a dead SIM — and a drive the
     * car never commented on is not a drive the car disagreed with.
     */
    @Test
    fun `no identifiers at all is silence, not disagreement`() {
        assertThat(JourneyAgreement.verdict(emptyList()))
            .isEqualTo(JourneyAgreement.Verdict.UNKNOWN)
    }

    @Test
    fun `a single identifier seen once still counts as agreement`() {
        assertThat(JourneyAgreement.verdict(listOf(7)))
            .isEqualTo(JourneyAgreement.Verdict.AGREED)
    }

    // ------------------------------------------------------------------ tally

    @Test
    fun `the tally counts each drive once, by verdict`() {
        val t = JourneyAgreement.tally(
            listOf(
                listOf(1, 1),        // agreed
                listOf(2, 3),        // car split it
                emptyList(),         // unknown
                listOf(4),           // agreed
                emptyList()          // unknown
            )
        )

        assertThat(t.agreed).isEqualTo(2)
        assertThat(t.carSplit).isEqualTo(1)
        assertThat(t.unknown).isEqualTo(2)
    }

    /**
     * Silence must not be scored. Counting the drives the car said nothing about as agreement
     * would make the figure climb towards 100% precisely as the telematics link got worse, which
     * is the opposite of what it is for.
     */
    @Test
    fun `drives the car never commented on are left out of the percentage`() {
        val t = JourneyAgreement.tally(
            listOf(listOf(1, 1), listOf(2, 3), emptyList(), emptyList(), emptyList())
        )

        assertThat(t.answered).isEqualTo(2)
        assertThat(t.agreementPercent).isEqualTo(50.0)
    }

    @Test
    fun `nothing to go on gives no percentage rather than a flattering zero`() {
        val t = JourneyAgreement.tally(listOf(emptyList(), emptyList()))

        assertThat(t.answered).isZero()
        assertThat(t.agreementPercent).isNull()
    }

    @Test
    fun `no drives at all is not an error`() {
        val t = JourneyAgreement.tally(emptyList())

        assertThat(t.agreed).isZero()
        assertThat(t.carSplit).isZero()
        assertThat(t.unknown).isZero()
        assertThat(t.agreementPercent).isNull()
    }

    // ------------------------------------------------- the same rule, from counts

    @Test
    fun `a count says the same thing the list does`() {
        assertThat(JourneyAgreement.verdictOf(0)).isEqualTo(JourneyAgreement.Verdict.UNKNOWN)
        assertThat(JourneyAgreement.verdictOf(1)).isEqualTo(JourneyAgreement.Verdict.AGREED)
        assertThat(JourneyAgreement.verdictOf(2)).isEqualTo(JourneyAgreement.Verdict.CAR_SPLIT_IT)
        assertThat(JourneyAgreement.verdictOf(9)).isEqualTo(JourneyAgreement.Verdict.CAR_SPLIT_IT)
    }

    /**
     * A grouped query returns no row for a drive that carried no identifier, so those drives have
     * to be counted separately. If they were dropped instead, the agreement figure would climb
     * towards 100% exactly as the telematics link got worse.
     */
    @Test
    fun `drives that produced no row are still counted as silence`() {
        val t = JourneyAgreement.tallyOfCounts(countsPerDrive = listOf(1, 1, 3), silentDrives = 7)

        assertThat(t.agreed).isEqualTo(2)
        assertThat(t.carSplit).isEqualTo(1)
        assertThat(t.unknown).isEqualTo(7)
        assertThat(t.answered).isEqualTo(3)
        assertThat(t.agreementPercent).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `counts and lists reach the same tally for the same history`() {
        val fromLists = JourneyAgreement.tally(
            listOf(listOf(1, 1), listOf(2, 3), emptyList(), listOf(4), emptyList())
        )
        val fromCounts = JourneyAgreement.tallyOfCounts(listOf(1, 2, 1), silentDrives = 2)

        assertThat(fromCounts).isEqualTo(fromLists)
    }

    /** A negative can only come from a miscount upstream; it must not subtract from the total. */
    @Test
    fun `a nonsensical silent count cannot drag the tally below zero`() {
        val t = JourneyAgreement.tallyOfCounts(listOf(1), silentDrives = -5)

        assertThat(t.unknown).isZero()
        assertThat(t.agreed).isEqualTo(1)
    }

    /**
     * Repeats do not inflate the count. A drive that sat in traffic under good signal contributes
     * a hundred frames carrying the same identifier, and a drive through a basement contributes
     * two — weighting by frames would let signal quality decide the verdict.
     */
    @Test
    fun `how many frames carried an identifier does not change the verdict`() {
        val many = JourneyAgreement.verdict(List(200) { 9 })
        val few = JourneyAgreement.verdict(listOf(9, 9))

        assertThat(many).isEqualTo(few).isEqualTo(JourneyAgreement.Verdict.AGREED)
    }
}
