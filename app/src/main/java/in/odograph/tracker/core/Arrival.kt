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
     * What the car itself says about being shut down, when telematics is reachable.
     *
     * [locked] and [canBusActive] come straight off the MG status frame. A locked car has been
     * walked away from, which is as certain as parking gets; a quiet CAN bus means the car is
     * asleep. Either ends the drive immediately, without waiting out [STILL_MS].
     *
     * All fields are nullable because the link is optional: telematics may be switched off, the
     * credentials unset, or the servers unreachable, and the recorder has to work regardless.
     */
    data class CarState(val locked: Boolean? = null, val canBusActive: Boolean? = null)

    /**
     * Whether the drive that is currently open has ended.
     *
     * [stillForMs] is how long the car has been below [STILL_SPEED_MPS]. Negative or zero means it
     * is still moving.
     */
    fun arrived(stillForMs: Long, car: CarState = CarState()): Boolean {
        if (car.locked == true) return true
        if (car.canBusActive == false) return true
        return stillForMs >= STILL_MS
    }

    /**
     * How long the car has been stationary, given when it last moved.
     *
     * Returns zero rather than something enormous when the car has not yet been seen to move, so a
     * drive cannot be closed before it has produced a single moving fix.
     */
    fun stillForMs(lastMovedAt: Long, now: Long): Long =
        if (lastMovedAt <= 0L || lastMovedAt == Long.MIN_VALUE) 0L
        else (now - lastMovedAt).coerceAtLeast(0L)
}
