package `in`.odograph.tracker.core

/**
 * Keeping the charge ledger honest against the state of charge itself.
 *
 * The ledger is built entirely from live polling frames: a session opens on the first frame that
 * says "charging" and closes on the first that says otherwise. That works only while somebody is
 * watching, and on this car nobody usually is. The box is powered by the vehicle, so the moment
 * the ignition goes off the recorder goes with it — which is precisely when the car is plugged in.
 * A fill that runs from 60% to 100% overnight in a dark garage produces no frames at all, and the
 * ledger, having seen nothing, records nothing. The pack is fuller in the morning and the app
 * cannot say why.
 *
 * So the state of charge is treated as a ledger in its own right, because it is one. Every rise
 * has to be paid for by a charge and every fall by a drive, and a rise nobody watched is still a
 * charge that happened. When the car comes back reading higher than the last thing the app knew
 * about can explain, that difference is energy, and it is booked rather than shrugged at: a
 * session that ended at 60% cannot be followed by a car sitting at 80% with no charge in between.
 *
 * Nothing here invents a number. The SOC swing is the same evidence a watched session uses for its
 * energy — the only difference is that the endpoints are further apart, and that the app is honest
 * about not having seen the middle.
 */
object ChargeReconciler {

    /**
     * SOC movement below this is the meter, not energy, percent.
     *
     * The car reports whole percents and the reading wanders by one either side of a true value,
     * particularly just after a drive when the pack is still settling. Booking a 1% rise as a
     * charge would fill the history with sessions that never happened, each one carrying half a
     * kilowatt-hour of fictional energy and a price to match.
     */
    const val NOISE_PERCENT = 1.5

    /** One thing the car said about its charge, and when it said it. */
    data class Reading(val socPercent: Double, val at: Long)

    /** The most recent session in the ledger, whether or not it was ever closed. */
    data class Session(
        val id: Long,
        val endSoc: Double?,
        val endedAt: Long,
        /** Open sessions are still being watched and are handled by the ledger, not by this. */
        val open: Boolean
    )

    sealed interface Action {
        /** The ledger already explains the pack. */
        data object Nothing : Action

        /**
         * The session that was being watched is the one that filled the pack; carry it to [toSoc].
         *
         * The plug was never pulled — the box simply stopped watching. Extending is right rather
         * than opening a second row, because a second row would claim the car was unplugged and
         * plugged back in, which is a thing that did not happen.
         */
        data class Extend(val sessionId: Long, val toSoc: Double, val at: Long) : Action

        /**
         * Energy arrived with nothing to attach it to, so the charge is reconstructed whole.
         */
        data class Record(
            val fromSoc: Double,
            val toSoc: Double,
            val from: Long,
            val to: Long
        ) : Action
    }

    /**
     * Compares what the car now says against what the ledger can account for.
     *
     * [lastKnown] is the most recent state of charge the app has on record, from any source — the
     * last frame of a drive, an overnight parked reading, the close of a session. [latest] is what
     * the car is saying now.
     *
     * [drivenSinceM] is how far the car travelled between the two readings. Driving consumes, so
     * any rise across a window that contains a drive is a *lower bound* on what went in rather
     * than the whole of it — the reconstruction says so by booking only what it can prove.
     */
    fun reconcile(
        lastKnown: Reading?,
        latest: Reading,
        lastSession: Session?,
        drivenSinceM: Double = 0.0
    ): Action {
        // No baseline is not the same as a baseline of zero. A box with no history at all must not
        // decide that the pack went from empty to wherever it is now and book the difference.
        if (lastKnown == null) return Action.Nothing
        if (latest.at <= lastKnown.at) return Action.Nothing

        val rise = latest.socPercent - lastKnown.socPercent
        if (rise <= NOISE_PERCENT) return Action.Nothing

        // An open session is the ledger's own business: frames are arriving, and advancing it is
        // exactly what ChargeLedger does with them. Stepping in would double-book the same energy.
        if (lastSession != null && lastSession.open) return Action.Nothing

        // The pack picked up where the last session left off, and nothing was driven in between:
        // the plug was never pulled, so this is that same fill continuing past the last frame.
        val continues = lastSession != null &&
            lastSession.endSoc != null &&
            drivenSinceM <= 0.0 &&
            kotlin.math.abs(lastSession.endSoc - lastKnown.socPercent) <= NOISE_PERCENT

        return if (continues) {
            Action.Extend(lastSession!!.id, toSoc = latest.socPercent, at = latest.at)
        } else {
            Action.Record(
                fromSoc = lastKnown.socPercent,
                toSoc = latest.socPercent,
                from = lastKnown.at,
                to = latest.at
            )
        }
    }
}
