package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.BatteryEntity

/**
 * Energy accounting for EV battery traces.
 *
 * A trip's used energy comes from its SOC snapshots and is corrected for any energy put back in
 * mid-trip: the SOC drawn, times capacity, plus what the charging frames measured going back in.
 * Efficiency and range estimates then turn that into the numbers a driver actually cares about.
 *
 * The model is deliberately conservative about garbage in, garbage out: every estimate carries its
 * own guards (see the per-function contracts), and readouts prefer an honest "unknown" over a
 * fabricated zero.
 */
object BatteryMath {

    /**
     * Default usable capacity for the MG Windsor EV Pro, kWh — the 52.9 kWh nominal prismatic
     * pack. Overridable on /config. Older (non-Pro) Windsors use a 38 kWh pack; that needs its
     * own /config override because it shares this default.
     */
    const val DEFAULT_CAPACITY_KWH = 52.9

    /** Trips up to this distance are "city"; anything beyond is a long/outstation drive. */
    const val CITY_MAX_DISTANCE_M = 50_000.0

    /** Instrumented trips needed before real-world efficiency beats the car's own estimate. */
    const val MIN_TRIPS_FOR_REAL_ESTIMATE = 3

    /** Longest gap between charging frames still treated as one session. */
    const val CHARGE_SESSION_GAP_MS = 20 * 60_000L

    /**
     * Charging at or above this average/peak power counts as a fast (public) charge; below it is
     * a slow (home) charge. The user's rule: under 10 kWh per hour is slow, at or over is fast.
     */
    const val FAST_CHARGE_KW = 10.0

    /**
     * Power readings below this many are not evidence of a sustained {FAST,SLOW} profile — a
     * 3-sample session cannot distinguish a lone spike from real fast charging, so classification
     * falls back to the peak/average rule instead.
     */
    const val FAST_EVIDENCE_MIN_SAMPLES = 5

    /**
     * A session is fast when this share of its power readings sat at or above [FAST_CHARGE_KW].
     * Majority, not any single reading: one brief grid spike never makes a home charge a "fast
     * charge", and one momentary dip never demotes a real fastcharger.
     */
    const val FAST_EVIDENCE_FRACTION = 0.5

    /** Tracks shorter than this are measurement noise, not a drive worth quoting efficiency for. */
    const val MIN_EFFICIENCY_DISTANCE_M = 2_000.0

    /** Consumptions this small are SOC-quantisation noise; quoting efficiency from them is nonsense. */
    const val MIN_EFFICIENCY_ENERGY_KWH = 0.5

    /**
     * A route needs this much total distance (metres — the spec name says KM, the value is 30 km)
     * and this many drives before it can be ranked.
     */
    const val MIN_ROUTE_KM = 30_000.0

    /** Drives this many times minimum before a route earns a spot on the best/worst board. */
    const val MIN_ROUTE_DRIVES = 3

    /** Parked windows shorter than this are a nap, not an overnight vampire-drain observation. */
    const val MIN_DRAIN_WINDOW_MS = 12 * 3_600_000L

    /** Where the energy for a charge session came from, which picks which electricity rate applies. */
    enum class ChargeKind { SLOW, FAST }

    /** Rounds to 0.01 (paise, or 0.01 kW·h) so stored money and energy never carry float noise. */
    fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    /**
     * kW·h drained from the battery over a trip, from its charge snapshots. Charge-adjusted: any
     * energy the charging frames measured going back in is added back, so a trip that charged on
     * the way counts the fuel it actually used.
     *
     * Guards covered: fewer than two usable SOC readings or an unknown capacity -> null; SOC is
     * clamped to the physical [0,100] range so sensor noise cannot manufacture kWh, and a NaN
     * reading is evidence of nothing so it is dropped like a missing one; the result is signed — a
     * strongly negative value means the trip net-charged (regen or a plugged-in pause), and
     * callers that bill money clamp it at zero rather than inventing a refund.
     */
    fun consumedKwh(samples: List<BatteryEntity>, capacityKwh: Double): Double? {
        if (capacityKwh <= 0) return null
        val socs = samples.filter { it.socPercent != null && it.socPercent.isFinite() }
            .map { it.socPercent!!.coerceIn(0.0, 100.0) }
        if (socs.size < 2) return null
        val drawn = capacityKwh * (socs.first() - socs.last()) / 100.0
        return drawn + chargedKwh(samples)
    }

    /**
     * kW·h the charging frames put back in, integrated from reported power over poll intervals.
     * Only intervals that end on a charging frame with a positive power reading count; a paused
     * or non-charging interval contributes nothing even if the previous frame was charging.
     */
    fun chargedKwh(samples: List<BatteryEntity>): Double {
        var charged = 0.0
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val cur = samples[i]
            val power = cur.chargingPowerKw ?: 0.0
            if (cur.charging == true && power.isFinite() && power > 0 && cur.t > prev.t) {
                charged += power * (cur.t - prev.t) / 3_600_000.0
            }
        }
        return charged
    }

    /** Energy a recharge added between two SOC readings, clamped to the physical battery size. */
    fun rechargeEnergyKwh(startSoc: Double?, endSoc: Double, capacityKwh: Double): Double {
        if (capacityKwh <= 0) return 0.0
        if (startSoc == null || !startSoc.isFinite() || !endSoc.isFinite()) return 0.0
        val drawn = (endSoc.coerceIn(0.0, 100.0) - startSoc.coerceIn(0.0, 100.0)) / 100.0
        return (drawn * capacityKwh).coerceIn(0.0, capacityKwh)
    }

    /** kW·h consumed per 100 km for one trip. Null until the trip is worth quoting. */
    fun kwhPer100Km(energyKwh: Double?, distanceM: Double): Double? {
        if (energyKwh == null || energyKwh < MIN_EFFICIENCY_ENERGY_KWH) return null
        if (distanceM < MIN_EFFICIENCY_DISTANCE_M) return null
        return energyKwh / (distanceM / 100_000.0)
    }

    /**
     * What the state of charge says a drive used, from its two endpoints.
     *
     * The fallback figure, and the one every drive recorded before the car's counter was read was
     * built on. Kept as its own function so a drive can show what each source made of it side by
     * side: a comparison where one number is derived from the other tells nobody anything.
     */
    fun consumedFromSoc(startSoc: Double?, endSoc: Double?, capacityKwh: Double): Double? {
        if (startSoc == null || endSoc == null) return null
        val used = (startSoc - endSoc) / 100.0 * capacityKwh
        return round2(used).takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * What a drive cost, from the best evidence it left behind.
     *
     * Two sources, and they are not equals. The state of charge is reported in whole percent, so
     * on a 52.9 kW·h pack it can only express energy in steps of 0.53 kW·h — a drive that really
     * used 0.9 records as 0.53, and the history then believes the car went seventy percent
     * further on that charge than it did. Every short errand is mostly quantisation, and the bias
     * runs one way often enough to show up as the app reading better mileage than the car does.
     *
     * The car's own counter has a tenth of a kilowatt-hour of resolution — five times finer — and
     * a live reading from this vehicle put it at 0.6 kW·h across 3.0 km, which is 20 kW·h/100km
     * and entirely ordinary city driving. (The figure that made this look untrustworthy, 8.8
     * kW·h across 138.5 km, came from a different car in the captured frames — a 38 kW·h Windsor,
     * not this one.)
     *
     * So the counter is preferred and the state of charge is the fallback, for the several
     * situations where the counter cannot answer: a drive that straddles a charge, where the
     * counter resets and reads backwards; a drive with too few frames to difference; and any
     * drive made while the telematics link was down, which on this box is most of them.
     *
     * A zero delta falls back too. Over a drive that went anywhere it means the frames were too
     * sparse to catch the movement rather than that the car used nothing, and the state of charge
     * is no worse a guess than a confident nought.
     */
    fun driveEnergyKwh(
        frames: List<`in`.odograph.tracker.data.BatteryEntity>,
        capacityKwh: Double,
        distanceM: Double? = null
    ): Double? {
        // No frames is no measurement, and the answer is "unknown" rather than an exception. One
        // caller guards this and the live-drive caller guards the line after the call instead, so
        // a drive whose frames all landed in the parked bucket threw from here and abandoned the
        // rest of that poll. Refusing empty input at the source covers every caller at once.
        if (frames.isEmpty()) return null
        // A counter that barely moved over a real distance did not measure the drive; it missed
        // it. 0.1 kWh across 13.8 km was accepted here because 0.1 is more than zero, and the
        // drive went into the history at 138 km/kWh. The charge level is tried next, and if that
        // is just as impossible the drive is unknown — which the sweep will estimate honestly.
        val fromCar = counterDelta(
            frames.first().powerUsageSinceLastChargeKwh,
            frames.last().powerUsageSinceLastChargeKwh
        )?.takeIf { it > 0.0 && plausible(it, distanceM) }
        val energy = (fromCar ?: consumedKwh(frames, capacityKwh))?.takeIf { plausible(it, distanceM) }
        return energy?.let { round2(it) }
    }

    /**
     * The most kilometres this car can physically get from a kilowatt-hour over a real distance.
     *
     * Twelve. Measured drives sit at five to eight; a long downhill with regeneration can touch
     * ten. Anything past this over more than [MIN_EFFICIENCY_DISTANCE_M] is not a frugal drive,
     * it is an energy figure that missed most of the drive — a counter that did not tick, or a
     * charge level that did not move — and it must not reach the rolling mean the screen shows.
     */
    const val MAX_PLAUSIBLE_KM_PER_KWH = 12.0

    /**
     * The most the charge level may rise between two frames while the car is not charging.
     *
     * Five percent. Regeneration on a long descent puts a percent or two back over several
     * minutes, and frames arrive at least thirty seconds apart, so anything more is not the pack
     * — it is the frame. One such frame said 100% in the middle of a drive at 63%, and for as
     * long as it stood the range at the current charge equalled the range at full.
     */
    const val MAX_SOC_RISE_WHILE_DRIVING = 5.0

    /**
     * The charge level to show live: [reported], unless it has jumped up while driving.
     *
     * A frame that says the battery filled itself is kept on disk — it is evidence — but the
     * readout carries on from [previous] instead. Charging passes untouched: a rising level is
     * the whole point of it. With nothing to compare against, [reported] stands.
     */
    fun plausibleLiveSoc(reported: Double?, previous: Double?, charging: Boolean?): Double? {
        if (reported == null || previous == null || charging == true) return reported
        return if (reported - previous > MAX_SOC_RISE_WHILE_DRIVING) previous else reported
    }

    /**
     * Whether [energyKwh] could really have carried the car [distanceM]. Unknown distance passes.
     *
     * Negative passes too: a long descent can put more back than it takes, and that is a real
     * measurement — it is what stops such a drive being billed. Exactly zero over a real distance
     * is the opposite case: the car cannot cover kilometres on nothing, so a zero means the
     * counter did not tick and the charge level did not move, and nothing was measured at all.
     */
    fun plausible(energyKwh: Double, distanceM: Double?): Boolean {
        if (distanceM == null || distanceM < MIN_EFFICIENCY_DISTANCE_M) return true
        if (energyKwh < 0.0) return true
        if (energyKwh == 0.0) return false
        return distanceM / 1000.0 / energyKwh <= MAX_PLAUSIBLE_KM_PER_KWH
    }

    /**
     * What the car's own running counter says a drive used, from the frames either side of it.
     *
     * The counters reset to zero at every charge, so a drive that straddles one reads backwards.
     * That is not a small error to be clamped away — it means the window contains a reset and the
     * counter simply cannot answer for it — so the answer is nothing rather than a number.
     *
     * Null is also the answer when either end is missing. A counter read once is a reading, not a
     * difference, and there is no honest way to turn one into the other.
     */
    fun counterDelta(first: Double?, last: Double?): Double? {
        if (first == null || last == null) return null
        val delta = last - first
        return delta.takeIf { it.isFinite() && it >= 0.0 }
    }

    /** km per kW·h for one trip — the number drivers call "mileage". */
    fun kmPerKwh(energyKwh: Double?, distanceM: Double): Double? {
        val eff = kwhPer100Km(energyKwh, distanceM) ?: return null
        return 100.0 / eff
    }

    /** Mean efficiency over the most recent [window] trips, given newest-first efficiencies. */
    fun rollingKwhPer100Km(efficiencies: List<Double>, window: Int = 10): Double? {
        val recent = efficiencies.take(window)
        if (recent.isEmpty()) return null
        return recent.sum() / recent.size
    }

    /** Real-world range at 100% charge given an efficiency, km. */
    fun rangeAtFullKwh(capacityKwh: Double, efficiencyKwhPer100Km: Double): Double =
        capacityKwh / efficiencyKwhPer100Km * 100.0

    /** Range remaining at a given SOC (clamped to 0..100), km. */
    fun rangeAtSocKwh(capacityKwh: Double, socPercent: Double, efficiencyKwhPer100Km: Double): Double =
        capacityKwh * socPercent.coerceIn(0.0, 100.0) / 100.0 / efficiencyKwhPer100Km * 100.0

    /**
     * Classifies a charge session from its reported power readings. The caller keeps a running
     * count of readings at or above [FAST_CHARGE_KW] versus total readings; when enough readings
     * exist the consistent majority decides, which is immune to a single transient spike (never
     * makes a home charge "fast") and to a single momentary dip (never demotes a fastcharger).
     * With too few readings to judge consistency it falls back to the peak (the charger's
     * capability, immune to a slept-through night shrinking the measured duration) and the
     * session-average as a last resort when power was never reported.
     */
    fun chargeKind(
        peakPowerKw: Double?,
        energyKwh: Double,
        startTime: Long,
        endTime: Long,
        samplesTotal: Int = 0,
        samplesAbove: Int = 0
    ): ChargeKind {
        if (samplesTotal >= FAST_EVIDENCE_MIN_SAMPLES) {
            // Majority without floats: above*2 > total is strictly more than half. A tie stays
            // silent-and-slow — a session right on the 10 kW line is not worth the fast rate.
            return if (samplesAbove * 2 > samplesTotal) ChargeKind.FAST else ChargeKind.SLOW
        }
        val peak = peakPowerKw ?: 0.0
        val hours = (endTime - startTime).coerceAtLeast(1L) / 3_600_000.0
        val average = if (hours > 0) energyKwh / hours else 0.0
        return if (maxOf(peak, average) >= FAST_CHARGE_KW) ChargeKind.FAST else ChargeKind.SLOW
    }

    /**
     * The driver-entered price of a charge session, from whichever way they entered it:
     * a total bill already includes GST and is final; a per-kWh tariff gets GST added on top.
     * The tariff is applied to the wall-meter reading when present (you pay for what the wall
     * delivered), falling back to the battery's own SOC-swing kWh. Null when neither was entered,
     * meaning the configured default rate applies.
     */
    fun sessionCostInr(
        energyKwh: Double,
        deliveredKwh: Double?,
        enteredRateInr: Double?,
        enteredBillInr: Double?,
        gstRatePct: Double?
    ): Double? {
        enteredBillInr?.let { return it.coerceIn(0.0, Double.MAX_VALUE) }
        if (enteredRateInr != null && gstRatePct != null) {
            val basis = deliveredKwh ?: energyKwh
            return basis * enteredRateInr.coerceAtLeast(0.0) * (1.0 + gstRatePct / 100.0)
        }
        return null
    }

    /**
     * Charging loss percentage from the wall-meter versus the battery-side SOC-swing reading.
     * Positive when the wall delivered more than the battery absorbed — the normal state.
     * Null when either number is missing or zero (loss is meaningless for a home plug reading
     * that entered the same kWh on both sides).
     */
    fun lossPct(carKwh: Double, deliveredKwh: Double?): Double? {
        if (deliveredKwh == null || deliveredKwh <= 0.0 || carKwh <= 0.0) return null
        return (1.0 - carKwh / deliveredKwh) * 100.0
    }
}