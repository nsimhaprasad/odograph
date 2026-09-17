package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import `in`.odograph.tracker.data.RouteTripEff
import org.junit.Test

/**
 * "Can I get there?" — and the asymmetry that shapes every threshold in it.
 *
 * Being told a drive is tight when it turns out fine costs a moment's thought. Being told it is
 * fine when it is not strands a car. So the reserve is held back, the unfamiliar route is stretched
 * rather than trimmed, and nothing here rounds in the driver's favour.
 *
 * Figures are this car's: a 52.9 kWh pack, and consumption around 16 kWh/100km.
 */
class ReachabilityTest {

    private val pack = BatteryMath.DEFAULT_CAPACITY_KWH   // 52.9
    private val normal = 16.0                             // kWh per 100 km

    // ---------------------------------------------------------------- the projection

    @Test
    fun `a short hop off a full battery barely registers`() {
        val e = Reachability.estimate(18.4, socPercent = 100.0, pack, normal, measured = true)!!
        // 18.4 km at 16 kWh/100km is 2.94 kWh of 52.9 — about 5.6 points.
        assertThat(e.arrivalPercent).isEqualTo(94.4, within(0.5))
        assertThat(e.verdict).isEqualTo(Reachability.Verdict.COMFORTABLE)
    }

    @Test
    fun `the airport run off a half charge is comfortable`() {
        val e = Reachability.estimate(41.2, socPercent = 50.0, pack, normal, measured = true)!!
        assertThat(e.arrivalPercent).isEqualTo(37.5, within(0.5))
        assertThat(e.verdict).isEqualTo(Reachability.Verdict.COMFORTABLE)
    }

    @Test
    fun `a long drive on a low charge does not reach`() {
        val e = Reachability.estimate(180.0, socPercent = 40.0, pack, normal, measured = true)!!
        assertThat(e.arrivalPercent).isLessThan(Reachability.RESERVE_PERCENT)
        assertThat(e.verdict).isEqualTo(Reachability.Verdict.UNREACHABLE)
    }

    @Test
    fun `a drive that lands between the reserve and the tight mark is flagged as tight`() {
        // Solve for an arrival around 15%: 85% of 52.9 kWh spent at 16 kWh/100km.
        val km = (100.0 - 15.0) / 100.0 * pack / normal * 100.0
        val e = Reachability.estimate(km, socPercent = 100.0, pack, normal, measured = true)!!
        assertThat(e.arrivalPercent).isEqualTo(15.0, within(1.0))
        assertThat(e.verdict).isEqualTo(Reachability.Verdict.TIGHT)
    }

    /** Arriving on the reserve is not arriving comfortably; the boundary belongs to UNREACHABLE. */
    @Test
    fun `landing exactly on the reserve is not called reachable`() {
        val km = (100.0 - Reachability.RESERVE_PERCENT) / 100.0 * pack / normal * 100.0
        val e = Reachability.estimate(km, socPercent = 100.0, pack, normal, measured = true)!!
        assertThat(e.verdict).isNotEqualTo(Reachability.Verdict.COMFORTABLE)
    }

    @Test
    fun `a heavier right foot shortens the reach`() {
        val gentle = Reachability.estimate(120.0, 60.0, pack, 14.0, measured = true)!!
        val heavy = Reachability.estimate(120.0, 60.0, pack, 22.0, measured = true)!!
        assertThat(heavy.arrivalPercent).isLessThan(gentle.arrivalPercent)
    }

    // ---------------------------------------------------------------- what it refuses to answer

    @Test
    fun `nothing is claimed without a usable efficiency`() {
        assertThat(Reachability.estimate(40.0, 80.0, pack, 0.0, measured = false)).isNull()
        assertThat(Reachability.estimate(40.0, 80.0, pack, -3.0, measured = false)).isNull()
    }

    @Test
    fun `nothing is claimed with a nonsensical pack`() {
        assertThat(Reachability.estimate(40.0, 80.0, 0.0, normal, measured = true)).isNull()
    }

    @Test
    fun `a flat battery reaches nowhere rather than reporting a negative`() {
        val e = Reachability.estimate(40.0, socPercent = 0.0, pack, normal, measured = true)!!
        assertThat(e.verdict).isEqualTo(Reachability.Verdict.UNREACHABLE)
        assertThat(e.shownPercent).`as`("never shown below empty").isEqualTo(0)
    }

    @Test
    fun `going nowhere costs nothing`() {
        val e = Reachability.estimate(0.0, socPercent = 55.0, pack, normal, measured = true)!!
        assertThat(e.arrivalPercent).isEqualTo(55.0, within(0.01))
    }

    // ---------------------------------------------------------------- how far it is

    /** A route the car has driven knows its own distance, detours and all. */
    @Test
    fun `a driven route uses the distance it actually takes`() {
        val (km, measured) = Reachability.distanceKm(
            measuredRouteM = 18_432.0,
            fromLat = 12.9716, fromLon = 77.5946,
            toLat = 12.9698, toLon = 77.7500
        )
        assertThat(km).isEqualTo(18.432, within(0.001))
        assertThat(measured).isTrue()
    }

    /**
     * An unfamiliar destination only has a straight line, and roads are never straight. Stretching
     * it is the conservative direction; the alternative is promising a drive that is really 30%
     * longer than the map suggests.
     */
    @Test
    fun `an unknown route stretches the straight line rather than trusting it`() {
        val (km, measured) = Reachability.distanceKm(
            measuredRouteM = null,
            fromLat = 12.9716, fromLon = 77.5946,
            toLat = 12.9698, toLon = 77.7500
        )
        val straight = Geo.haversineMetres(12.9716, 77.5946, 12.9698, 77.7500) / 1000.0
        assertThat(km).isEqualTo(straight * Reachability.DETOUR_FACTOR, within(0.001))
        assertThat(km).isGreaterThan(straight)
        assertThat(measured).isFalse()
    }

    @Test
    fun `a route with no recorded distance falls back rather than claiming zero km`() {
        val (km, measured) = Reachability.distanceKm(0.0, 12.97, 77.59, 12.98, 77.75)
        assertThat(km).isGreaterThan(0.0)
        assertThat(measured).isFalse()
    }

    // ---------------------------------------------------------------- which efficiency to bill at

    /**
     * The same commute twice a day for a month predicts the next one far better than everything
     * the car has ever done averaged together.
     */
    @Test
    fun `the route's own consumption wins when it has one`() {
        assertThat(Reachability.efficiencyKwhPer100Km(routeEfficiency = 14.2, rollingEfficiency = 18.0))
            .isEqualTo(14.2)
    }

    @Test
    fun `the rolling average stands in for an undriven route`() {
        assertThat(Reachability.efficiencyKwhPer100Km(null, 18.0)).isEqualTo(18.0)
    }

    @Test
    fun `no history means no answer rather than a guessed one`() {
        assertThat(Reachability.efficiencyKwhPer100Km(null, null)).isNull()
        assertThat(Reachability.efficiencyKwhPer100Km(0.0, 0.0)).isNull()
    }

    // ---------------------------------------------------------------- the thresholds themselves

    @Test
    fun `tight sits above the reserve, so a drive can be reachable and still worth noting`() {
        assertThat(Reachability.TIGHT_PERCENT).isGreaterThan(Reachability.RESERVE_PERCENT)
    }

    @Test
    fun `the detour factor lengthens a drive and never shortens one`() {
        assertThat(Reachability.DETOUR_FACTOR).isGreaterThan(1.0)
    }

    // ---------------------------------------------------------------- what a route has really cost

    private fun leg(from: Long, to: Long, km: Double, kwh: Double) =
        RouteTripEff(startId = from, endId = to, distanceM = km * 1000.0, energyKwh = kwh)

    @Test
    fun `a route driven enough times speaks for itself`() {
        val trips = List(5) { leg(1, 2, 18.4, 2.9) }
        val profile = Reachability.routeProfiles(trips)[1L to 2L]!!
        assertThat(profile.drives).isEqualTo(5)
        assertThat(profile.distanceM).isEqualTo(18_400.0, within(1.0))
        // 2.9 kWh over 18.4 km is about 15.8 kWh/100km.
        assertThat(profile.kwhPer100Km).isEqualTo(15.76, within(0.1))
    }

    /** One drive is an anecdote — it might have been the day of the diversion. */
    @Test
    fun `a route driven once does not get to speak for itself`() {
        assertThat(Reachability.routeProfiles(listOf(leg(1, 2, 18.4, 2.9)))).isEmpty()
    }

    @Test
    fun `direction matters, because the climb out is not the roll back`() {
        val trips = List(4) { leg(1, 2, 18.4, 3.6) } + List(4) { leg(2, 1, 18.4, 2.2) }
        val profiles = Reachability.routeProfiles(trips)
        val out = profiles[1L to 2L]!!.kwhPer100Km
        val back = profiles[2L to 1L]!!.kwhPer100Km
        assertThat(out).`as`("uphill costs more").isGreaterThan(back)
    }

    /**
     * The reason consumption is a median. One crawl home through a thunderstorm must not
     * permanently re-price a commute driven fifty times.
     */
    @Test
    fun `one terrible drive does not re-price the whole commute`() {
        val normal = List(9) { leg(1, 2, 18.4, 2.9) }
        val awful = listOf(leg(1, 2, 18.4, 9.0))
        val withOutlier = Reachability.routeProfiles(normal + awful)[1L to 2L]!!
        val without = Reachability.routeProfiles(normal)[1L to 2L]!!
        assertThat(withOutlier.kwhPer100Km).isEqualTo(without.kwhPer100Km, within(0.5))
    }

    @Test
    fun `a trip too short to measure efficiency from is left out`() {
        val tiny = List(5) { leg(1, 2, 0.4, 0.2) }
        assertThat(Reachability.routeProfiles(tiny)).isEmpty()
    }

    @Test
    fun `a drive with no energy recorded is left out`() {
        val trips = List(5) { leg(1, 2, 18.4, 0.0) }
        assertThat(Reachability.routeProfiles(trips)).isEmpty()
    }

    @Test
    fun `no history produces no profiles rather than throwing`() {
        assertThat(Reachability.routeProfiles(emptyList())).isEmpty()
    }

    /**
     * The whole point: a known route is billed at its own measured cost and its own measured
     * length, so the answer stops being an estimate and the tilde comes off.
     */
    @Test
    fun `a known route gives a measured answer where a straight line would guess`() {
        val profile = Reachability.routeProfiles(List(6) { leg(1, 2, 18.4, 2.9) })[1L to 2L]!!

        val (km, measured) = Reachability.distanceKm(
            measuredRouteM = profile.distanceM,
            fromLat = 12.9716, fromLon = 77.5946, toLat = 12.9698, toLon = 77.7500
        )
        assertThat(measured).isTrue()

        val e = Reachability.estimate(
            km, socPercent = 60.0, capacityKwh = pack,
            kwhPer100Km = Reachability.efficiencyKwhPer100Km(profile.kwhPer100Km, 22.0)!!,
            measured = measured
        )!!
        assertThat(e.measured).isTrue()
        assertThat(e.distanceKm).isEqualTo(18.4, within(0.1))
    }
}
