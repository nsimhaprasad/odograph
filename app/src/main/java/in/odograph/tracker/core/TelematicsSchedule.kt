package `in`.odograph.tracker.core

/**
 * When the box is allowed to call the MG servers.
 *
 * Two pressures pull against each other. The servers belong to someone else, so calling them too
 * often makes the box look like an attack and gets the account blocked — a blocked box has no
 * range, no energy and no cost figures at all. But calling them too rarely is just as bad in a
 * quieter way: the charge level is the only source of a drive's energy, so a drive that ran
 * without a single poll has no kWh and no cost, and never will.
 *
 * This used to live inline in the recorder as four booleans over a `Long.MIN_VALUE` sentinel, and
 * it was wrong for months. `elapsedRealtime() - Long.MIN_VALUE` overflows to about -9.2e18, so the
 * "has the heartbeat elapsed" test read `-9.2e18 >= 300000` and was false forever. The first poll
 * after a boot could therefore only be triggered by the MG screen becoming visible — the box
 * connected when, and only when, somebody opened the app. Every drive where nobody did was
 * recorded with no battery data at all.
 *
 * So there is no sentinel here. "Never" is null, null is not a number, and the arithmetic that
 * broke this cannot be written.
 */
object TelematicsSchedule {

    /**
     * Whether to make an MG call now.
     *
     * [sinceLastCallMs] is the time since the last call that actually went out, null if none ever
     * has. [sinceLastAttemptMs] is the time since the last time this said yes — which is not the
     * same thing, because an attempt can be abandoned before it reaches the network when the link
     * is down. Separating them is what stops a box with no signal from spinning: without it, a
     * box that has never connected would retry on every wake-up of the loop, once a second,
     * posting a notification each time.
     *
     * The order matters. The rate limit is checked first and applies to everything, including the
     * first attempt after a reconnect, because it is the rule that protects the account.
     */
    fun shouldAttempt(
        sinceLastCallMs: Long?,
        sinceLastAttemptMs: Long?,
        screenVisible: Boolean,
        refreshRequested: Boolean,
        heartbeatMs: Long,
        minIntervalMs: Long
    ): Boolean {
        // Nothing may go out faster than the floor, whatever is asking for it.
        if (sinceLastAttemptMs != null && sinceLastAttemptMs < minIntervalMs) return false

        // Never connected. Keep trying at the floor's cadence — this is the boot case, and the
        // whole point is that it does not wait for a human to open a screen.
        if (sinceLastCallMs == null) return true

        return screenVisible || refreshRequested || sinceLastCallMs >= heartbeatMs
    }
}
