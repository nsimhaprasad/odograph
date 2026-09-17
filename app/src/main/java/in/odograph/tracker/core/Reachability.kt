package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.RouteTripEff
import kotlin.math.roundToInt

/**
 * "Can I get there, and what will be left when I do?"
 *
 * The car already answers a nearby question — remaining range — and answers it badly for this
 * purpose, because a single kilometre figure cannot know whether the next drive is the motorway
 * run or the stop-start crawl across town. This one does, when the route has been driven before:
 * the recorder has the distance that drive actually takes and the energy it actually costs, which
 * beats a straight line and a fleet average on both terms.
 *
 * Deliberately conservative where it is uncertain. An estimate that sends someone home on a
 * confident 4% is worse than one that admits it does not know, so every fallback widens the
 * distance and none of them flatters the efficiency.
 */
object Reachability {

    /**
     * Charge held back from the answer, percentage points.
     *
     * Arriving at zero is not arriving. This is the buffer for the detour, the cold morning and
     * the charger that turns out to be occupied — subtracted before the verdict, never hidden from
     * the number itself, so the screen can say "12%, which is tight" rather than silently lying by
     * five points.
     */
    const val RESERVE_PERCENT = 10.0

    /**
     * Below this the trip is worth planning around rather than simply making.
     *
     * Above [RESERVE_PERCENT] on purpose: a drive that lands exactly on the reserve has consumed
     * the entire margin that existed for it going wrong.
     */
    const val TIGHT_PERCENT = 20.0

    /**
     * How much further a road is than the straight line between its ends.
     *
     * Only used when the route has never been driven, and 1.3 is the usual figure for real road
     * networks. A guess, and marked as one by [Estimate.measured] being false, because the honest
     * thing on an unfamiliar route is to say the answer is softer.
     */
    const val DETOUR_FACTOR = 1.3

    enum class Verdict {
        /** Comfortably within range, with the reserve intact. */
        COMFORTABLE,

        /** It reaches, but with little spare. Worth knowing before setting off rather than after. */
        TIGHT,

        /** Not without charging on the way. */
        UNREACHABLE
    }

    /**
     * What is left on arrival, and how much to trust it.
     *
     * [arrivalPercent] is the raw projection, reserve not deducted — the screen shows the real
     * number and lets [verdict] carry the judgement. [measured] says whether this came from a
     * route the car has actually driven or from a straight line and an average.
     */
    data class Estimate(
        val distanceKm: Double,
        val arrivalPercent: Double,
        val verdict: Verdict,
        val measured: Boolean
    ) {
        /** The projection as the screen shows it: whole points, never below empty. */
        val shownPercent: Int get() = arrivalPercent.coerceAtLeast(0.0).roundToInt()
    }

    /**
     * Projects the charge left on arrival.
     *
     * [kwhPer100Km] is the consumption to bill the drive at — the route's own measured figure
     * where there is one, the rolling average otherwise. Returns null when there is nothing honest
     * to say: no charge reading, no usable efficiency, or a pack size that makes no sense.
     */
    fun estimate(
        distanceKm: Double,
        socPercent: Double,
        capacityKwh: Double,
        kwhPer100Km: Double,
        measured: Boolean
    ): Estimate? {
        if (distanceKm < 0.0 || socPercent < 0.0) return null
        if (capacityKwh <= 0.0 || kwhPer100Km <= 0.0) return null

        val available = capacityKwh * socPercent / 100.0
        val needed = kwhPer100Km * distanceKm / 100.0
        val leftKwh = available - needed
        val arrival = leftKwh / capacityKwh * 100.0

        return Estimate(
            distanceKm = distanceKm,
            arrivalPercent = arrival,
            verdict = verdictFor(arrival),
            measured = measured
        )
    }

    private fun verdictFor(arrivalPercent: Double): Verdict = when {
        arrivalPercent < RESERVE_PERCENT -> Verdict.UNREACHABLE
        arrivalPercent < TIGHT_PERCENT -> Verdict.TIGHT
        else -> Verdict.COMFORTABLE
    }

    /**
     * How far the drive really is.
     *
     * A route the car has driven knows its own distance, detours and all. Everything else gets the
     * straight line stretched by [DETOUR_FACTOR], which is a guess and is reported as one.
     */
    fun distanceKm(
        measuredRouteM: Double?,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Pair<Double, Boolean> {
        val measured = measuredRouteM?.takeIf { it > 0.0 }
        if (measured != null) return measured / 1000.0 to true
        val straight = Geo.haversineMetres(fromLat, fromLon, toLat, toLon) / 1000.0
        return straight * DETOUR_FACTOR to false
    }

    /**
     * Which consumption figure to bill the drive at.
     *
     * The route's own measured efficiency wins: the same journey twice a day for a month is a far
     * better predictor of the next one than everything the car has ever done averaged together.
     * The rolling figure stands in otherwise, and null means there is not yet enough to say.
     */
    fun efficiencyKwhPer100Km(routeEfficiency: Double?, rollingEfficiency: Double?): Double? =
        routeEfficiency?.takeIf { it > 0.0 } ?: rollingEfficiency?.takeIf { it > 0.0 }

    /**
     * What a route has actually cost, the times it has been driven.
     *
     * [kwhPer100Km] is the median rather than the mean on purpose: one crawl home through a
     * thunderstorm should not permanently re-price a commute driven fifty times. Distance is the
     * mean, where the same reasoning does not apply — a route's length barely varies, and what
     * variation there is comes from where exactly the car was parked at either end.
     */
    data class RouteProfile(
        val distanceM: Double,
        val kwhPer100Km: Double,
        val drives: Int
    )

    /**
     * Per-route consumption and distance, keyed origin-to-destination.
     *
     * Direction matters and the key keeps it: the climb out to the office and the roll back down
     * are different drives with different costs, and averaging them together loses exactly the
     * information this is for.
     *
     * A route needs [minDrives] before it is allowed to speak for itself. One drive is an anecdote
     * — it might have been the day of the diversion — and the rolling average is the better answer
     * until there is enough to beat it.
     */
    fun routeProfiles(
        trips: List<RouteTripEff>,
        minDrives: Int = BatteryMath.MIN_ROUTE_DRIVES
    ): Map<Pair<Long, Long>, RouteProfile> =
        trips
            .filter { it.distanceM >= BatteryMath.MIN_EFFICIENCY_DISTANCE_M && it.energyKwh > 0.0 }
            .groupBy { it.startId to it.endId }
            .mapNotNull { (route, legs) ->
                if (legs.size < minDrives) return@mapNotNull null
                val efficiencies = legs.mapNotNull {
                    BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM)
                }
                if (efficiencies.isEmpty()) return@mapNotNull null
                route to RouteProfile(
                    distanceM = legs.sumOf { it.distanceM } / legs.size,
                    kwhPer100Km = median(efficiencies),
                    drives = legs.size
                )
            }
            .toMap()

    /** Middle value, averaging the two middles on an even count. */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
