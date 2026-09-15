package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.DailyEffRow
import `in`.odograph.tracker.data.ParkedBatteryRow
import `in`.odograph.tracker.data.RouteTripEff

/**
 * The pure-math side of the INSIGHTS tab: events from the DB go in, rankings and drain windows
 * come out, and every number carries the same hygiene rules as [BatteryMath] — no fabricated
 * values, ranked data needs a minimum sample, and a window that cannot be trusted is left out.
 */
object Analytics {

    /** One daily point for the RANGE@100 line. */
    data class RangePoint(val day: Int, val distanceKm: Double, val rangeAtFullKm: Double)

    /** One qualifying overnight park, where measured SOC fell far enough to prove the drain. */
    data class DrainWindow(val startMs: Long, val hours: Double, val dropPercent: Double)

    /** One route that earned its spot on the efficiency board. */
    data class RouteRank(
        val from: Long, val to: Long, val drives: Int, val km: Double, val kwhPer100Km: Double
    )

    data class Rankings(val best: List<RouteRank>, val worst: List<RouteRank>)

    /**
     * The range the car would have right now if it were 100% charged, one point per qualifying day.
     * Days that travelled too little, or used too little energy, are noise and are skipped; days
     * with no closeable energy are skipped too. Points are ascending in time.
     */
    fun dailyRangeSeries(days: List<DailyEffRow>, capacityKwh: Double): List<RangePoint> {
        if (capacityKwh <= 0) return emptyList()
        return days
            .asSequence()
            .filter { it.distanceM >= BatteryMath.MIN_EFFICIENCY_DISTANCE_M }
            .filter { it.energyKwh >= BatteryMath.MIN_EFFICIENCY_ENERGY_KWH }
            .map { d ->
                val effKwhPer100 = BatteryMath.kwhPer100Km(d.energyKwh, d.distanceM) ?: return@map null
                val range = BatteryMath.rangeAtFullKwh(capacityKwh, effKwhPer100)
                if (!range.isFinite()) null else RangePoint(d.day, d.distanceM / 1000.0, range)
            }
            .filterNotNull()
            .toList()
    }

    /**
     * Overnight vampire-drain observations from parked battery frames. Consecutive frames are
     * grouped (a charge session in the middle splits one window in two), and only groups long
     * enough to look like an overnight park with two trustworthy SOCs are quoted. A window that
     * shows the battery gaining is evidence of a charging session the group logic missed, and is
     * discarded rather than reported as negative drain.
     */
    fun drainWindows(frames: List<ParkedBatteryRow>, capacityKwh: Double): List<DrainWindow> {
        if (frames.isEmpty() || capacityKwh <= 0) return emptyList()
        val samples = frames.filter { it.socPercent != null }
            .map { it to it.socPercent!!.coerceIn(0.0, 100.0) }
        if (samples.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Pair<ParkedBatteryRow, Double>>>()
        var group = mutableListOf<Pair<ParkedBatteryRow, Double>>()
        group.add(samples.first())
        for (i in 1 until samples.size) {
            if (samples[i].first.t - samples[i - 1].first.t <= BatteryMath.CHARGE_SESSION_GAP_MS) {
                group.add(samples[i])
            } else {
                groups.add(group)
                group = mutableListOf(samples[i])
            }
        }
        groups.add(group)

        return groups.mapNotNull { g ->
            val first = g.first().second
            val last = g.last().second
            val elapsedMs = g.last().first.t - g.first().first.t
            val drop = first - last
            if (elapsedMs < BatteryMath.MIN_DRAIN_WINDOW_MS) null
            else if (drop <= 0) null
            else DrainWindow(g.first().first.t, elapsedMs / 3_600_000.0, drop)
        }
    }

    /**
     * Best and worst routes by average kWh/100 km. Only routes with enough km and enough drives
     * are ranked — a two-km hop to the shop is not evidence about the car's efficiency.
     * Routes are keyed by their end-place pairs; ties resolve by how often the route was driven.
     */
    fun routeRankings(trips: List<RouteTripEff>): Rankings {
        val perRoute = trips
            .groupBy { it.startId to it.endId }
            .mapNotNull { (pair, group) ->
                val distanceM = group.sumOf { it.distanceM }
                val energy = group.sumOf { it.energyKwh }
                val drives = group.size
                if (distanceM < BatteryMath.MIN_ROUTE_KM || drives < BatteryMath.MIN_ROUTE_DRIVES) null
                else {
                    val eff = BatteryMath.kwhPer100Km(energy, distanceM)
                    if (eff == null || !eff.isFinite()) null
                    else RouteRank(pair.first, pair.second, drives, distanceM / 1000.0, eff)
                }
            }

        val byEfficiency = perRoute.sortedWith(
            compareBy({ it.kwhPer100Km }, { -it.km }, { -it.drives })
        )
        return Rankings(
            best = byEfficiency.take(3).sortedBy { it.kwhPer100Km },
            worst = byEfficiency.takeLast(3).sortedByDescending { it.kwhPer100Km }
        )
    }
}