package `in`.odograph.tracker.core

/**
 * The fixes held back before a drive is known to have started.
 *
 * Until the car is judged to be moving there is no trip for a fix to belong to, so the recent
 * ones are kept in memory and written all at once when a departure is confirmed. The window was
 * bounded by count, which is the wrong dimension: the harm is not how many fixes are held but how
 * old the oldest one is, because the oldest becomes the drive's start.
 *
 * And one fix in particular is old. The location source hands over the system's cached "last
 * known" position at start-up so the screen can stop saying ACQUIRING. A network-provider fix is
 * stamped with the system clock, and a box with no SIM has no NTP — so a fix computed while the
 * clock still sat at the Android image's build date carries that date, and the cache serves it
 * up months later as if it were real. It arrived first, became the origin of the next drive, and
 * the driver watched the trip timer read 7508:59 — three hundred and thirteen days, to a date
 * before the car was even bought.
 */
object FixWindow {

    /**
     * The most a held fix may predate the newest one, milliseconds.
     *
     * Five minutes. A car standing at a light or in a queue is judged within seconds of moving
     * off, so nothing older than this can be part of the departure — it is either a stale cache
     * or a previous stop, and either way it is not where this drive began.
     */
    const val MAX_AGE_MS = 5 * 60_000L

    /** The most fixes held at all, so a car parked with a live receiver never hoards a night's worth. */
    const val MAX_COUNT = 60

    /**
     * The window after [incoming] joins it: newest-anchored, time-bounded, count-bounded, in time
     * order. A fix older than the newest by more than [maxAgeMs] is dropped whichever end it
     * came in at — including [incoming] itself, when it is the stale one.
     */
    fun admit(
        held: List<Fix>,
        incoming: Fix,
        maxAgeMs: Long = MAX_AGE_MS,
        maxCount: Int = MAX_COUNT
    ): List<Fix> {
        val all = (held + incoming).sortedBy { it.t }
        val newest = all.last().t
        val fresh = all.filter { newest - it.t <= maxAgeMs }
        return if (fresh.size > maxCount) fresh.takeLast(maxCount) else fresh
    }
}
