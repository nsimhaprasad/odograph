package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * What separates a car that has set off from a car that is sitting still.
 *
 * The cost of getting this wrong runs both ways: too eager and every power-up writes a 0 km drive
 * to the top of the history, too reluctant and the first minutes of a real drive are lost. Both
 * used to be possible, because the trip was opened at boot and only judged a whole reboot later.
 */
class DepartureTest {

    /** Bangalore, near where the fixtures' Home sits. */
    private val lat0 = 12.9716
    private val lon0 = 77.5946

    private fun fix(
        i: Int,
        speedMps: Float = 0f,
        metresNorth: Double = 0.0
    ) = Fix(
        t = 1_000L * i,
        lat = lat0 + metresNorth / 111_320.0,
        lon = lon0,
        speedMps = speedMps,
        accuracyM = 5f,
        interpolated = false,
        altitudeM = 900.0
    )

    private fun parked(n: Int) = List(n) { fix(it) }

    // ---------------------------------------------------------------- staying put

    @Test
    fun `a car that has not moved has not departed`() {
        assertThat(Departure.departed(parked(10))).isFalse()
    }

    @Test
    fun `nothing seen yet is not a departure`() {
        assertThat(Departure.departed(emptyList())).isFalse()
    }

    /** The confirmation window exists precisely so one bad fix cannot open a drive. */
    @Test
    fun `a single blip does not open a trip`() {
        val fixes = parked(5) + fix(5, speedMps = 12f)
        assertThat(Departure.departed(fixes)).isFalse()
    }

    @Test
    fun `two moving fixes are still not enough`() {
        val fixes = parked(4) + fix(4, speedMps = 5f) + fix(5, speedMps = 5f)
        assertThat(Departure.departed(fixes)).isFalse()
    }

    @Test
    fun `fewer fixes than the confirmation window can never depart`() {
        val racing = List(2) { fix(it, speedMps = 30f, metresNorth = it * 200.0) }
        assertThat(Departure.departed(racing)).isFalse()
    }

    /**
     * GPS wander while parked is the case that would otherwise write a drive every night: the
     * readings jitter a few metres each way and never go anywhere.
     */
    @Test
    fun `parked jitter is not a departure`() {
        val jitter = List(30) { i ->
            fix(i, speedMps = 0.3f, metresNorth = if (i % 2 == 0) 4.0 else -4.0)
        }
        assertThat(Departure.departed(jitter)).isFalse()
    }

    // ---------------------------------------------------------------- setting off

    @Test
    fun `three sustained moving fixes open a trip`() {
        val fixes = parked(3) + List(3) { fix(3 + it, speedMps = 4f, metresNorth = 4.0 * it) }
        assertThat(Departure.departed(fixes)).isTrue()
    }

    @Test
    fun `a speed exactly at the threshold counts`() {
        val fixes = parked(2) + List(3) { fix(2 + it, speedMps = Departure.SPEED_MPS) }
        assertThat(Departure.departed(fixes)).isTrue()
    }

    /**
     * Some providers report no per-fix speed at all, which is why the recorder derives its own.
     * Without this branch such a device would never open a trip until it had crawled fifty metres.
     */
    @Test
    fun `the recorder's derived speed can open a trip on its own`() {
        assertThat(Departure.departed(parked(5), derivedSpeedMps = 8f)).isTrue()
    }

    @Test
    fun `a derived speed below the threshold does not`() {
        assertThat(Departure.departed(parked(5), derivedSpeedMps = 0.4f)).isFalse()
    }

    /** A crawl out of a car park may never sustain the speed threshold, but it has still gone. */
    @Test
    fun `going far enough counts even without ever moving fast`() {
        val crawl = List(20) { fix(it, speedMps = 0.2f, metresNorth = it * 5.0) }
        assertThat(Departure.departed(crawl)).isTrue()
    }

    // The helper converts metres to degrees with a mean meridian length, which at this latitude
    // lands a nominal 50 m at about 49.7 m of real haversine distance. The cases either side are
    // therefore stated with a margin rather than pretending to sit exactly on the threshold.

    @Test
    fun `a displacement past the threshold counts`() {
        val fixes = parked(2) + fix(2, metresNorth = Departure.DISPLACEMENT_M + 5.0)
        assertThat(Departure.departed(fixes)).isTrue()
    }

    @Test
    fun `stopping short of the threshold does not`() {
        val fixes = parked(2) + fix(2, metresNorth = Departure.DISPLACEMENT_M - 5.0)
        assertThat(Departure.departed(fixes)).isFalse()
    }

    @Test
    fun `coming back to where it started still counts as having gone`() {
        val roundTrip = parked(2) +
            List(4) { fix(2 + it, metresNorth = 60.0) } +
            List(4) { fix(6 + it, metresNorth = 0.0) }
        assertThat(Departure.departed(roundTrip)).isTrue()
    }

    // ---------------------------------------------------------------- agreeing with TripStats

    /**
     * What opens a trip and what keeps one have to agree. If departure were the looser of the two,
     * a drive would be recorded and then discarded as "never moved" at the next boot — which is
     * the dummy entry this whole change exists to remove, arriving by a different route.
     */
    @Test
    fun `anything that opens a trip would also survive as one`() {
        val cases = listOf(
            "sustained speed" to (parked(2) + List(3) { fix(2 + it, speedMps = 6f, metresNorth = 6.0 * it) }),
            "slow crawl" to List(20) { fix(it, speedMps = 0.2f, metresNorth = it * 5.0) },
            "threshold displacement" to (parked(2) + fix(2, metresNorth = Departure.DISPLACEMENT_M + 5.0))
        )
        cases.forEach { (name, fixes) ->
            assertThat(Departure.departed(fixes)).`as`("$name departs").isTrue()
            assertThat(TripStats.moved(fixes)).`as`("$name survives as a trip").isTrue()
        }
    }

    @Test
    fun `the two thresholds are the same number`() {
        assertThat(Departure.DISPLACEMENT_M).isEqualTo(50.0)
        assertThat(Departure.SPEED_MPS).isEqualTo(1.0f)
    }
}
