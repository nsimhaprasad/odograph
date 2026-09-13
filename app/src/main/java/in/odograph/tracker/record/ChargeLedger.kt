package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.BatteryMath.ChargeKind
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.OdographDao

/**
 * Turns a stream of polling frames into completed, priced charge sessions.
 *
 * Each frame is a livestream of "charging?" plus SOC plus power. Everything else falls out of
 * two rules:
 *
 *  - A row opens on the first charging frame and advances with every charging frame, recording
 *    energy from the SOC swing (never an integration of slept-through hours). The car saying it
 *    is charging is the truth that matters: even if the box slept for the night between frames,
 *    the SOC swing still captures exactly what went in, so a frame after a gap extends the
 *    session rather than ending it.
 *  - The plug pulled is the authoritative end: the first frame where the car reports "not
 *    charging" closes the session, visible end-time and closing SOC set from that frame. A frame
 *    that is merely stale — the box died and woke to find the charge already over — closes the
 *    session too, with the closing frame's SOC so the final kW·h the box never saw is still
 *    booked.
 *
 * Closing classifies by the peak reported power (the charger's capability, immune to wrong
 * durations), with the session-average as a fallback, then prices it with whichever rate that
 * kind buys, snapshot at close so later rate changes never rewrite history. A session with no
 * energy costs zero, not nothing.
 *
 * The cost of the honesty: a session that slept across two genuinely separate charges is merged,
 * and its kWh attributed from the endpoint swing. The capture-coverage display shows those gaps,
 * so the mistake is visible rather than silently folded in.
 *
 * A parked charge that belongs to no drive still gets a session: the box only ever writes battery
 * frames under whichever trip is open, and a culled trip deletes its frames, so the session row
 * is the survivor that stays.
 */
class ChargeLedger(
    private val dao: OdographDao,
    private val capacityKwh: Double,
    private val homeRateInr: Double,
    private val outsideRateInr: Double
) {

    fun observe(charging: Boolean?, socPercent: Double?, powerKw: Double?, now: Long) {
        var open = dao.openChargeEvent()

        // A stale row is only worth closing when the car is not (or no longer) charging: the box
        // died and woke to find the charge over. If the car still says it is charging, the gap is
        // just missing frames and the session lives on — the SOC swing will book the sleep.
        if (open != null && charging != true && isStale(open, now)) {
            close(open, now, socPercent)
            open = null
        }

        if (charging == true) {
            if (socPercent == null) {
                // A charging frame without SOC is worthless as energy evidence; we keep the
                // session alive but cannot extend the numbers fairly.
                return
            }
            if (open == null) {
                dao.insertChargeEvent(
                    ChargeEventEntity(
                        startTime = now,
                        startSoc = socPercent,
                        endTime = now,
                        endSoc = socPercent,
                        energyKwh = 0.0,
                        peakPowerKw = powerKw?.coerceAtLeast(0.0)
                    )
                )
            } else {
                val energy = BatteryMath.rechargeEnergyKwh(open.startSoc, socPercent, capacityKwh)
                val peak = maxOf(
                    open.peakPowerKw ?: 0.0,
                    powerKw?.coerceAtLeast(0.0) ?: 0.0
                )
                dao.advanceChargeEvent(
                    open.id, endTime = now, endSoc = socPercent,
                    energyKwh = energy, peakPowerKw = peak
                )
            }
            return
        }

        // The car now says it is not charging. That is the authoritative end of the session; a
        // brief AC pause reads as a split session, which is harmless (both splits are slow). If
        // there was no open session it is a nop.
        if (open != null) close(open, now, socPercent)
    }

    private fun isStale(open: ChargeEventEntity, now: Long): Boolean =
        now - (open.endTime ?: open.startTime) > BatteryMath.CHARGE_SESSION_GAP_MS

    /**
     * Closes an open session, taking the closing frame's SOC so a charge whose final frames were
     * slept through still books its real energy. Classifies it and prices it, so the row becomes
     * a billed fill that later drives can draw their rate from.
     */
    private fun close(open: ChargeEventEntity, now: Long, closingSoc: Double?) {
        val endSoc = closingSoc ?: open.endSoc
        val energy = if (open.startSoc != null && endSoc != null) {
            BatteryMath.rechargeEnergyKwh(open.startSoc, endSoc, capacityKwh)
        } else {
            open.energyKwh
        }
        val peak = open.peakPowerKw ?: 0.0
        dao.advanceChargeEvent(
            open.id, endTime = now, endSoc = endSoc, energyKwh = energy, peakPowerKw = peak
        )
        val kind = BatteryMath.chargeKind(peak, energy, open.startTime, now)
        val rate = if (kind == ChargeKind.FAST) outsideRateInr else homeRateInr
        dao.closeChargeEvent(open.id, kind.ordinal, energy * rate)
    }
}