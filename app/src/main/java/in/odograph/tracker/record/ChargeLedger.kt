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
 * Closing classifies by the consistent speed, not a lone spike: every power reading is tallied,
 * and once enough readings exist the majority at or above the fast threshold decides. A single
 * gration spike never makes a home charge a "fast charge", and a momentary dip never demotes a
 * real fastcharger; only with too few readings to judge does the peak swing back in. The session
 * is then priced with whichever rate that kind buys, unless the driver already entered a real
 * price (a tariff with GST, or a total bill) into the still-open session — that wins, because
 * what you actually paid beats any rate.
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

    /** What a batch of frames did to the ledger, so the caller can prompt the driver. */
    sealed interface Change {
        data object None : Change
        /** A fresh session opened from this frame; the driver may pre-price a fast one. */
        data class Opened(val event: ChargeEventEntity) : Change
        /** A session closed and was priced; a fast one may be worth correcting with the real bill. */
        data class Closed(val event: ChargeEventEntity) : Change
    }

    fun observe(charging: Boolean?, socPercent: Double?, powerKw: Double?, now: Long): Change {
        var open = dao.openChargeEvent()
        val sampleAbove = powerKw != null && powerKw >= BatteryMath.FAST_CHARGE_KW

        // A stale row is only worth closing when the car is not (or no longer) charging: the box
        // died and woke to find the charge over. If the car still says it is charging, the gap is
        // just missing frames and the session lives on — the SOC swing will book the sleep.
        if (open != null && charging != true && isStale(open, now)) {
            close(open, now, socPercent)
            open = null
        }

        if (charging == true) {
            if (open == null) {
                val event = ChargeEventEntity(
                    startTime = now,
                    startSoc = socPercent,
                    endTime = now,
                    endSoc = socPercent,
                    energyKwh = 0.0,
                    peakPowerKw = powerKw?.coerceAtLeast(0.0),
                    samplesTotal = if (powerKw != null) 1 else 0,
                    samplesAbove = if (sampleAbove) 1 else 0
                )
                val id = dao.insertChargeEvent(event)
                return Change.Opened(event.copy(id = id))
            }
            // The SOC may be missing (a frame without SOC is worthless as energy evidence) but the
            // power reading is still evidence of the charger's speed, so it is tallied regardless.
            val energy = if (socPercent == null) {
                open.energyKwh
            } else {
                BatteryMath.round2(BatteryMath.rechargeEnergyKwh(open.startSoc, socPercent, capacityKwh))
            }
            val peak = maxOf(
                open.peakPowerKw ?: 0.0,
                powerKw?.coerceAtLeast(0.0) ?: 0.0
            )
            val samplesTotal = open.samplesTotal + if (powerKw != null) 1 else 0
            val samplesAbove = open.samplesAbove + if (sampleAbove) 1 else 0
            dao.advanceChargeEvent(
                open.id, endTime = now, endSoc = socPercent,
                energyKwh = energy, peakPowerKw = peak,
                samplesTotal = samplesTotal, samplesAbove = samplesAbove
            )
            return Change.None
        }

        // The car now says it is not charging. That is the authoritative end of the session; a
        // brief AC pause reads as a split session, which is harmless (both splits are slow). If
        // there was no open session it is a nop.
        if (open != null) return close(open, now, socPercent)
        return Change.None
    }

    private fun isStale(open: ChargeEventEntity, now: Long): Boolean =
        now - (open.endTime ?: open.startTime) > BatteryMath.CHARGE_SESSION_GAP_MS

    /**
     * Closes an open session, taking the closing frame's SOC so a charge whose final frames were
     * slept through still books its real energy. Classifies it from the consistent power evidence
     * and prices it, so the row becomes a billed fill that later drives can draw their rate from.
     * A rate or bill the driver already entered into the open session wins over the default rate.
     */
    private fun close(open: ChargeEventEntity, now: Long, closingSoc: Double?): Change.Closed {
        val endSoc = closingSoc ?: open.endSoc
        val energy = if (open.startSoc != null && endSoc != null) {
            BatteryMath.round2(BatteryMath.rechargeEnergyKwh(open.startSoc, endSoc, capacityKwh))
        } else {
            open.energyKwh
        }
        val peak = open.peakPowerKw ?: 0.0
        val kind = BatteryMath.chargeKind(
            peak, energy, open.startTime, now,
            open.samplesTotal, open.samplesAbove
        )
        val entered = BatteryMath.sessionCostInr(energy, open.enteredRateInr, open.enteredBillInr, open.gstRatePct)
        val cost = BatteryMath.round2(
            if (kind == ChargeKind.FAST) {
                entered ?: energy * outsideRateInr
            } else {
                // A rate entered expecting a fast charge means nothing on a slow one; the grid rate
                // the driver configured is the truth for home charging.
                energy * homeRateInr
            }
        )
        val closed = open.copy(
            endTime = now, endSoc = endSoc, energyKwh = energy, kind = kind.ordinal, costInr = cost
        )
        dao.advanceChargeEvent(
            open.id, endTime = now, endSoc = endSoc, energyKwh = energy, peakPowerKw = peak,
            samplesTotal = open.samplesTotal, samplesAbove = open.samplesAbove
        )
        dao.closeChargeEvent(open.id, kind.ordinal, cost)
        return Change.Closed(closed)
    }
}