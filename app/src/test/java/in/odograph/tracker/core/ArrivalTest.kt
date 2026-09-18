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
     * The regression this whole signal nearly caused, pinned so it cannot return.
     *
     * `locked` was treated as certain proof of parking. It is the opposite of certain: the Windsor
     * locks its own doors above walking pace, so a car at speed reports locked=true and "arrived"
     * on the very next telematics poll. The open drive was closed, the next fix opened another,
     * and the screen sat at zero distance and zero moving time for a whole journey while the
     * speedometer read perfectly normally. A moving car has not arrived, whatever else is true.
     */
    @Test
    fun `no car reading can end a drive while the wheels are turning`() {
        val everySignal = Arrival.CarState(canBusActive = false)
        assertThat(Arrival.arrived(stillForMs = 0L, car = everySignal))
            .`as`("a moving car has not arrived")
            .isFalse()
    }

    @Test
    fun `a sleeping CAN bus ends the drive once the car has also stopped`() {
        val stopped = 1_000_000L
        // Read after the wheels stopped, which is what makes it evidence about a parked car.
        val asleep = Arrival.CarState(canBusActive = false, observedAt = stopped + 10_000L)

        assertThat(
            Arrival.arrived(Arrival.BUS_ASLEEP_STILL_MS, asleep, lastMovedAt = stopped)
        ).isTrue()
    }

    /** One odd frame at a signal must not end a live drive either. */
    @Test
    fun `a sleeping bus at a brief halt is not yet an arrival`() {
        val stopped = 1_000_000L
        val asleep = Arrival.CarState(canBusActive = false, observedAt = stopped + 5_000L)

        assertThat(Arrival.arrived(30_000L, asleep, lastMovedAt = stopped)).isFalse()
    }

    @Test
    fun `a live bus is still driving`() {
        assertThat(
            Arrival.arrived(minute, Arrival.CarState(canBusActive = true, observedAt = 1L))
        ).isFalse()
    }

    // ------------------------------------------------- a reading has to be about the car right now

    /**
     * The drive that reset itself halfway, with the box untouched and the app never killed.
     *
     * The car sits in the drive with its bus asleep and the poller reads that once. Hours later it
     * is driven into town, where the traffic holds it for two minutes. Nothing has polled since —
     * the heartbeat is five minutes and the hotspot is unreliable — so the reading from the drive
     * is still the latest one there is, and it ended the journey at the first red light. The next
     * fix opened a fresh trip, so the drive came back as two with a route between them that was
     * never driven.
     *
     * A reading taken before the wheels stopped describes a moving car, and a moving car has not
     * parked whatever its bus was doing.
     */
    @Test
    fun `a bus reading taken before the car stopped cannot end the drive`() {
        val readInTheDriveway = 1_000_000L
        val stoppedInTrafficLater = readInTheDriveway + 3 * 60 * 60_000L
        val stale = Arrival.CarState(canBusActive = false, observedAt = readInTheDriveway)

        assertThat(
            Arrival.arrived(
                stillForMs = Arrival.BUS_ASLEEP_STILL_MS,
                car = stale,
                lastMovedAt = stoppedInTrafficLater
            )
        ).`as`("two minutes at a signal is not an arrival on hours-old evidence").isFalse()
    }

    /** With the stale reading refused, the plain timer still ends the drive when it should. */
    @Test
    fun `a stale reading falls back to the full stillness timer rather than to nothing`() {
        val stopped = 5_000_000L
        val stale = Arrival.CarState(canBusActive = false, observedAt = stopped - 60_000L)

        assertThat(Arrival.arrived(Arrival.STILL_MS, stale, lastMovedAt = stopped)).isTrue()
    }

    /**
     * A reading with no timestamp cannot be checked for staleness, and an unstaleable reading is
     * exactly the one that goes on ending drives forever. Absent means absent.
     */
    @Test
    fun `a bus reading with no time attached is not evidence`() {
        val undated = Arrival.CarState(canBusActive = false, observedAt = 0L)

        assertThat(Arrival.arrived(Arrival.BUS_ASLEEP_STILL_MS, undated, lastMovedAt = 1_000L))
            .isFalse()
    }

    /**
     * Telematics is optional — switched off, unconfigured, or simply unreachable — so an absent
     * answer must never be read as "parked". The timer has to carry it alone.
     */
    @Test
    fun `an unknown car state falls back to the timer`() {
        val unknown = Arrival.CarState(canBusActive = null)
        assertThat(Arrival.arrived(minute, unknown)).isFalse()
        assertThat(Arrival.arrived(Arrival.STILL_MS, unknown)).isTrue()
    }

    @Test
    fun `the bus shortcut is much shorter than the plain timer but not instant`() {
        assertThat(Arrival.BUS_ASLEEP_STILL_MS).isLessThan(Arrival.STILL_MS)
        assertThat(Arrival.BUS_ASLEEP_STILL_MS).isGreaterThan(0L)
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
