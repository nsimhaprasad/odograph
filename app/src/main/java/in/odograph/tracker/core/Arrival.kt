package `in`.odograph.tracker.core

/**
 * When a drive has ended.
 *
 * The other half of [Departure], and until now the half nobody wrote. Nothing closed a trip while
 * the recorder was running: the box is powered by the car, so ignition-off ended a drive by
 * cutting power and the *next* boot tidied up the row it left open. That works only for as long as
 * the recorder never outlives the ignition, and even then it is wrong about what a drive is — an
 * outing with a stop at the shops is recorded as one trip that begins and ends at home, which
 * quietly ruins per-trip efficiency, cost, and every "most visited route" answer built on it.
 *
 * The hard part is that a stationary car is not necessarily a parked one. A signal, a level
 * crossing, a jam: all look identical to a car park from the accelerometer's point of view. So the
 * rule waits longer than traffic plausibly does, and takes a shortcut whenever the car itself
 * says it has been shut down.
 */
object Arrival {

    /**
     * How long a car must sit still before the drive is over, milliseconds.
     *
     * Twelve minutes, chosen against traffic rather than against convenience. A signal cycle is
     * two, a bad junction five, a level crossing perhaps eight; a genuine stop is almost always
     * longer. Erring short is the expensive direction — it splits one drive into two and invents a
     * route that was never driven — while erring long merely delays the row being written.
     */
    const val STILL_MS = 12 * 60_000L

    /**
     * Speed below which the car counts as stationary, m/s.
     *
     * Matched to [Departure.SPEED_MPS] so a car cannot be simultaneously too slow to have arrived
     * and fast enough to have departed.
     */
    const val STILL_SPEED_MPS = Departure.SPEED_MPS

    /**
     * Whether the drive that is currently open has ended.
     *
     * Time, and nothing else. The car's own word was tried here twice and withdrawn twice, which
     * is worth recording because the temptation to reach for it again is strong.
     *
     * First `locked`, which sounds like the most certain parking signal there is and is not: the
     * Windsor locks its own doors above walking pace, so a car in motion reported locked and
     * "arrived" on the very next poll. Then `canBusActive`, on the sounder-looking reasoning that
     * a sleeping bus means a car that has been shut down and left. On a vehicle that sleeps its
     * bus only when the driver walks away, that holds. This one sleeps it whenever the selector
     * reaches P — and in the traffic this car is actually driven in, P is where the selector goes
     * at every long halt. A signal, a jam, a level crossing: to the bus all three are
     * indistinguishable from a car park, and each one ended the journey and opened a new one
     * behind it.
     *
     * The failure is the same both times. A reading that correlates with parking on some cars is
     * not a reading that means parking on this one, and each turned out to be a worse proxy for
     * "how long has the car been still" than the answer measured directly — which needs no
     * telematics link, no poll, and cannot be wrong about a car it was never calibrated against.
     *
     * So the rule is the one the driver would state: carry on within a few minutes and it is the
     * same drive; leave it longer than that and the next movement begins a new one.
     */
    fun arrived(stillForMs: Long): Boolean {
        if (stillForMs <= 0L) return false
        return stillForMs >= STILL_MS
    }

    /**
     * A gap between fixes longer than this is the receiver losing the car, not the car sitting
     * still, milliseconds.
     *
     * Fixes arrive about once a second. Well over a minute without one means a tunnel, a
     * multi-storey, an urban canyon or a receiver that has stalled — and in none of those does
     * anybody know what the car was doing.
     */
    const val BLIND_GAP_MS = 90_000L

    /**
     * How long the car has been stationary, given when it last moved and how much of that was
     * spent unable to see it.
     *
     * Returns zero rather than something enormous when the car has not yet been seen to move, so a
     * drive cannot be closed before it has produced a single moving fix.
     *
     * [blindMs] is subtracted because "the car was still" and "we could not see the car" are
     * different facts, and only the first ends a drive. Stillness is inferred from the absence of
     * moving fixes, which makes a long GNSS outage indistinguishable from a car park unless the
     * outage is counted separately. Drive into a tunnel under a city for a quarter of an hour and
     * the first fix on the far side used to arrive with a stillness of fifteen minutes attached,
     * closing a drive that had never stopped — at motorway speed, in the middle of the road, with
     * the next fix opening a fresh trip. The journey came back as two, joined by a straight line
     * through the hill.
     */
    fun stillForMs(lastMovedAt: Long, now: Long, blindMs: Long = 0L): Long {
        if (lastMovedAt <= 0L || lastMovedAt == Long.MIN_VALUE) return 0L
        return (now - lastMovedAt - blindMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
