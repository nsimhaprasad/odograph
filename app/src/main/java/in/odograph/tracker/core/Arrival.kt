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
     * Far shorter than [STILL_MS] because a quiet bus is real evidence, but not zero: a single
     * frame reporting the bus asleep while the car is at a signal must not end a live drive.
     */
    const val BUS_ASLEEP_STILL_MS = 2 * 60_000L

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
    data class CarState(val canBusActive: Boolean? = null)

    /**
     * Whether the drive that is currently open has ended.
     *
     * [stillForMs] is how long the car has been below [STILL_SPEED_MPS]. A moving car has not
     * arrived, whatever else is true — no telematics reading may override what the wheels are
     * doing, which is the lesson the `locked` signal taught.
     */
    fun arrived(stillForMs: Long, car: CarState = CarState()): Boolean {
        if (stillForMs <= 0L) return false
        if (car.canBusActive == false && stillForMs >= BUS_ASLEEP_STILL_MS) return true
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
