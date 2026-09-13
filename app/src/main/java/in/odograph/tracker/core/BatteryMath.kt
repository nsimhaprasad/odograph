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

    /** Default usable capacity for the Windsor EV, kWh. Overridable on /config. */
    const val DEFAULT_CAPACITY_KWH = 49.2

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

    /** Tracks shorter than this are measurement noise, not a drive worth quoting efficiency for. */
    const val MIN_EFFICIENCY_DISTANCE_M = 2_000.0

    /** Consumptions this small are SOC-quantisation noise; quoting efficiency from them is nonsense. */
    const val MIN_EFFICIENCY_ENERGY_KWH = 0.5

    /** Where the energy for a charge session came from, which picks which electricity rate applies. */
    enum class ChargeKind { SLOW, FAST }

    /**
     * kW·h drained from the battery over a trip, from its charge snapshots. Charge-adjusted: any
     * energy the charging frames measured going back in is added back, so a trip that charged on
     * the way counts the fuel it actually used.
     *
     * Guards covered: fewer than two usable SOC readings or an unknown capacity -> null; SOC is
     * clamped to the physical [0,100] range so sensor noise cannot manufacture kWh; the result is
     * signed — a strongly negative value means the trip net-charged (regen or a plugged-in pause),
     * and callers that bill money clamp it at zero rather than inventing a refund.
     */
    fun consumedKwh(samples: List<BatteryEntity>, capacityKwh: Double): Double? {
        if (capacityKwh <= 0) return null
        val socs = samples.filter { it.socPercent != null }.map { it.socPercent!!.coerceIn(0.0, 100.0) }
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
            if (cur.charging == true && power > 0 && cur.t > prev.t) {
                charged += power * (cur.t - prev.t) / 3_600_000.0
            }
        }
        return charged
    }

    /** Energy a recharge added between two SOC readings, clamped to the physical battery size. */
    fun rechargeEnergyKwh(startSoc: Double?, endSoc: Double, capacityKwh: Double): Double {
        if (capacityKwh <= 0) return 0.0
        if (startSoc == null) return 0.0
        val drawn = (endSoc.coerceIn(0.0, 100.0) - startSoc.coerceIn(0.0, 100.0)) / 100.0
        return (drawn * capacityKwh).coerceIn(0.0, capacityKwh)
    }

    /** kW·h consumed per 100 km for one trip. Null until the trip is worth quoting. */
    fun kwhPer100Km(energyKwh: Double?, distanceM: Double): Double? {
        if (energyKwh == null || energyKwh < MIN_EFFICIENCY_ENERGY_KWH) return null
        if (distanceM < MIN_EFFICIENCY_DISTANCE_M) return null
        return energyKwh / (distanceM / 100_000.0)
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
     * Classifies a charge session. Defaults to the peak reported power (the charger's capability,
     * immune to a slept-though night shrinking the measured duration); if no power was ever
     * reported, falls back to the session-average power drawn from energy and wall-clock time.
     */
    fun chargeKind(peakPowerKw: Double?, energyKwh: Double, startTime: Long, endTime: Long): ChargeKind {
        val peak = peakPowerKw ?: 0.0
        val hours = (endTime - startTime).coerceAtLeast(1L) / 3_600_000.0
        val average = if (hours > 0) energyKwh / hours else 0.0
        return if (maxOf(peak, average) >= FAST_CHARGE_KW) ChargeKind.FAST else ChargeKind.SLOW
    }
}