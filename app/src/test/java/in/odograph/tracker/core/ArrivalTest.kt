package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * When a drive is over.
 *
 * The failure modes point opposite ways and are not equally expensive. Ending a drive too eagerly
 * splits one journey into two and invents a route that was never driven — a "Home to Traffic
 * Light" that then pollutes every efficiency and cost figure derived from it. Ending it too late
 * merely delays a row. So the timer is set against how long traffic actually holds a car, and the
 * car's own word is taken whenever it is available.
 */
class ArrivalTest {

    private val minute = 60_000L

    // ---------------------------------------------------------------- still moving

    @Test
    fun `a moving car has not arrived`() {
        assertThat(Arrival.arrived(stillForMs = 0L)).isFalse()
        assertThat(Arrival.arrived(stillForMs = -1L)).isFalse()
    }

    @Test
    fun `a brief stop is not an arrival`() {
        assertThat(Arrival.arrived(stillForMs = 30_000L)).isFalse()
    }

    /** The cases the timer exists to survive. Each is a stationary car that has not arrived. */
    @Test
    fun `traffic does not end a drive`() {
        listOf(
            "a signal cycle" to 2 * minute,
            "a bad junction" to 5 * minute,
            "a level crossing" to 8 * minute,
            "a jam" to 11 * minute
        ).forEach { (what, still) ->
            assertThat(Arrival.arrived(still)).`as`(what).isFalse()
        }
    }

    // ---------------------------------------------------------------- arrived

    @Test
    fun `sitting still for long enough ends the drive`() {
        assertThat(Arrival.arrived(Arrival.STILL_MS)).isTrue()
        assertThat(Arrival.arrived(30 * minute)).isTrue()
    }

    /**
     * The car's own word was tried here twice and withdrawn twice; this pins why, so nobody
     * reaches for it a third time.
     *
     * `locked` first, which sounds like certain proof of parking and is not: the Windsor locks its
     * doors above walking pace, so a car at speed reported locked and "arrived" on the very next
     * poll. Then `canBusActive`, on the sounder-looking reasoning that a sleeping bus means a car
     * shut down and left. This car sleeps its bus whenever the selector reaches P, and in the
     * traffic it is actually driven in, P is where the selector goes at every long halt.
     *
     * Both were a worse proxy for "how long has the car been still" than measuring that directly.
     */
    @Test
    fun `the decision is time alone and takes no reading from the car`() {
        val signature = Arrival::class.java.methods.first { it.name == "arrived" }

        assertThat(signature.parameterTypes.toList())
            .`as`("a car reading has no business ending a drive on this vehicle")
            .containsExactly(Long::class.java)
    }

    // ----------------------------------------------- park is a resting gear, not an arrival

    /**
     * The driver's own rule: carry on within a few minutes and it is the same drive.
     *
     * In this city the selector goes to P at every long halt — a jam, a signal, a level crossing,
     * dropping someone at a gate. None of it is arriving, and none of it may end the journey.
     */
    @Test
    fun `stopping in park during a drive does not end it`() {
        listOf(
            "a signal long enough to select P" to 2 * minute,
            "a jam with the engine idling" to 5 * minute,
            "a level crossing" to 8 * minute,
            "the far side of a bad junction" to 11 * minute
        ).forEach { (what, still) ->
            assertThat(Arrival.arrived(still)).`as`(what).isFalse()
        }
    }

    /**
     * The other half of the rule: leave it longer than that and the next movement is a new drive.
     *
     * Repeated stops cannot add up to one, because the stillness is measured from the last time
     * the car moved — every crawl forward starts the clock again, which is exactly what a car
     * inching through traffic does.
     */
    @Test
    fun `a stop long enough to be parking does end the drive`() {
        assertThat(Arrival.arrived(Arrival.STILL_MS)).isTrue()
        assertThat(Arrival.arrived(30 * minute)).isTrue()
    }

    @Test
    fun `stillness is measured from the last movement, so repeated halts never accumulate`() {
        val setOff = 1_000_000L
        // Three eight-minute halts in a jam, each ended by the car creeping forward a car length.
        val crawledAt = listOf(setOff + 8 * minute, setOff + 17 * minute, setOff + 26 * minute)
        crawledAt.forEach { moved ->
            assertThat(Arrival.arrived(Arrival.stillForMs(moved, moved + 8 * minute)))
                .`as`("an eight-minute halt after creeping forward at $moved")
                .isFalse()
        }

        val halfAnHourOut = setOff + 34 * minute
        assertThat(Arrival.stillForMs(crawledAt.last(), halfAnHourOut))
            .`as`("the clock runs from the last crawl, not from setting off")
            .isEqualTo(8 * minute)
        assertThat(Arrival.arrived(Arrival.stillForMs(crawledAt.last(), halfAnHourOut)))
            .`as`("half an hour of stop-start traffic is still one drive")
            .isFalse()
    }

    // ---------------------------------------------------------------- how long it has been still

    @Test
    fun `stillness is measured from the last movement`() {
        assertThat(Arrival.stillForMs(lastMovedAt = 1_000L, now = 61_000L)).isEqualTo(60_000L)
    }

    /**
     * A car that has not yet moved must not be counted as having sat still since the epoch, or the
     * first drive of a session would be closed before it produced a single moving fix.
     */
    @Test
    fun `a car that has never moved has not been sitting still`() {
        assertThat(Arrival.stillForMs(lastMovedAt = Long.MIN_VALUE, now = 9_999_999L)).isEqualTo(0L)
        assertThat(Arrival.stillForMs(lastMovedAt = 0L, now = 9_999_999L)).isEqualTo(0L)
    }

    // -------------------------------------------- losing the car is not the car standing still

    /**
     * The other way a drive used to end itself with nobody touching anything.
     *
     * Stillness is inferred from the absence of moving fixes, which makes a tunnel look exactly
     * like a car park. Fifteen minutes under a hill and the first fix on the far side arrived
     * carrying fifteen minutes of "stillness" — enough to close the drive at motorway speed, in
     * the middle of the road, with the next fix opening a fresh one behind it.
     */
    @Test
    fun `a long gap with no fixes does not count as the car standing still`() {
        val stopped = 1_000_000L
        val blind = 15 * minute
        val now = stopped + blind

        assertThat(Arrival.stillForMs(stopped, now, blindMs = blind))
            .`as`("none of that was the car being seen to stand still")
            .isEqualTo(0L)
        assertThat(Arrival.arrived(Arrival.stillForMs(stopped, now, blindMs = blind)))
            .isFalse()
    }

    /** Only the unseen part is discounted; a car that then genuinely sits still still arrives. */
    @Test
    fun `stillness either side of a blind stretch still counts`() {
        val stopped = 1_000_000L
        val blind = 10 * minute
        val now = stopped + blind + Arrival.STILL_MS

        assertThat(Arrival.stillForMs(stopped, now, blindMs = blind))
            .isEqualTo(Arrival.STILL_MS)
        assertThat(Arrival.arrived(Arrival.stillForMs(stopped, now, blindMs = blind))).isTrue()
    }

    /** A blind stretch longer than the whole wait cannot push the answer below zero. */
    @Test
    fun `more blind time than elapsed time is not negative stillness`() {
        assertThat(Arrival.stillForMs(1_000L, 2_000L, blindMs = 60_000L)).isEqualTo(0L)
    }

    @Test
    fun `the blind gap is far longer than the gap between ordinary fixes`() {
        assertThat(Arrival.BLIND_GAP_MS).isGreaterThan(30_000L)
        assertThat(Arrival.BLIND_GAP_MS).isLessThan(Arrival.STILL_MS)
    }

    @Test
    fun `a clock that went backwards does not end a drive`() {
        assertThat(Arrival.stillForMs(lastMovedAt = 10_000L, now = 5_000L)).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- agreeing with Departure

    /**
     * A car cannot be simultaneously too slow to have arrived and fast enough to have departed, or
     * a drive would end and restart on alternate fixes at the same speed.
     */
    @Test
    fun `the arrival and departure speed thresholds are the same`() {
        assertThat(Arrival.STILL_SPEED_MPS).isEqualTo(Departure.SPEED_MPS)
    }

    /** Long enough to outlast traffic, short enough that a stop is its own trip. */
    @Test
    fun `the stationary window is longer than traffic and shorter than an errand`() {
        assertThat(Arrival.STILL_MS).isGreaterThan(8 * minute)
        assertThat(Arrival.STILL_MS).isLessThan(20 * minute)
    }
}
