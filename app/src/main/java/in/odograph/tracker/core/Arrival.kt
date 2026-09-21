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
     * How long a stationary car with a sleeping CAN bus waits before the drive is called over.
     *
     * Ten minutes, raised from two, because two was calibrated against the wrong car.
     *
     * A quiet bus was taken to mean "shut down and left", which is what it means on a vehicle that
     * only sleeps its bus when the driver walks away. The Windsor sleeps it whenever the selector
     * goes to P — at a drop-off, at a gate, waiting outside a shop, pulling over to take a call —
     * and two minutes of that was enough to declare the journey finished. The drive was closed
     * mid-outing, the next movement opened another, and the driver watched the trip reset itself
     * for the crime of putting the car in park for three minutes.
     *
     * The asymmetry that governs every threshold in this file applies here too, and more sharply:
     * ending a drive early splits one journey into two and invents a route that was never driven,
     * while ending it late merely delays a row nobody is waiting for. Ten minutes is past every
     * stop a driver would describe as a pause and short of every one they would call parking.
     *
     * It stays below [STILL_MS] rather than being deleted because the signal is still real for a
     * box that outlives the ignition. On this box, which dies with it, the shortcut earns almost
     * nothing — the next boot closes the orphan correctly either way — so it is not worth one
     * split drive.
     */
    const val BUS_ASLEEP_STILL_MS = 10 * 60_000L

    /**
     * What the car itself says about being shut down, when telematics is reachable.
     *
     * Only [canBusActive] is here, and `locked` deliberately is not. A locked car sounds like the
     * most certain parking signal there is, and it is not one: the Windsor locks its own doors
     * above walking pace, so a car in motion reports locked=true and would "arrive" on the very
     * next poll. That shipped once — every telematics frame closed the open drive, the next fix
     * opened another, and the screen sat at zero distance and zero moving time for an entire
     * journey while the speedometer read perfectly normally.
     *
     * Nullable because the link is optional: telematics may be switched off, the credentials
     * unset, or the servers unreachable, and the recorder has to work regardless.
     */
    data class CarState(
        val canBusActive: Boolean? = null,
        /**
         * When this reading was taken, on the same clock as the fixes.
         *
         * Zero means "no reading", which is not the same as a reading of false and must never be
         * treated as one. A reading with no time attached cannot be checked for staleness, and an
         * unstaleable reading is how a frame from the car park goes on ending drives all day.
         */
        val observedAt: Long = 0L
    ) {
        /**
         * Whether this reading is evidence that the car in front of us, right now, is parked.
         *
         * The reading must have been taken *after* the car last moved. A bus-asleep frame from
         * before the wheels stopped is a statement about a moving car, and a moving car has not
         * parked whatever its bus was doing; carrying it forward turns one stale frame into a
         * verdict on every traffic stop that follows it.
         */
        fun saysParked(lastMovedAt: Long): Boolean =
            canBusActive == false && observedAt > 0L && observedAt >= lastMovedAt
    }

    /**
     * Whether the drive that is currently open has ended.
     *
     * [stillForMs] is how long the car has been below [STILL_SPEED_MPS]. A moving car has not
     * arrived, whatever else is true — no telematics reading may override what the wheels are
     * doing, which is the lesson the `locked` signal taught.
     *
     * That lesson was learned too narrowly the first time. `locked` was removed and
     * [CarState.canBusActive] was left with the same shape of power over a live drive and none of
     * the same suspicion. A poll only happens every few minutes, the last reading was kept
     * indefinitely — including after the link failed or was switched off — and nothing checked
     * whether it described the car as it is now. So a single frame taken while the car sat in the
     * drive, hours before setting off, was enough to end the drive at the first two-minute traffic
     * stop, and at every one after it. The car was driven, the app was untouched, and the trip
     * reset itself halfway. [CarState.saysParked] is what makes the reading answerable.
     */
    fun arrived(stillForMs: Long, car: CarState = CarState(), lastMovedAt: Long = 0L): Boolean {
        if (stillForMs <= 0L) return false
        if (car.saysParked(lastMovedAt) && stillForMs >= BUS_ASLEEP_STILL_MS) return true
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
