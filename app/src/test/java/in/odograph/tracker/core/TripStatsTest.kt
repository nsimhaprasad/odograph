package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class TripStatsTest {

    private fun fix(t: Long, lat: Double, lon: Double, s: Float, acc: Float = 5f, alt: Double? = null) =
        Fix(t, lat, lon, s, acc, altitudeM = alt)

    @Test
    fun `empty input yields zeroed stats`() {
        val s = TripStats.compute(emptyList())
        assertThat(s.distanceM).isEqualTo(0.0)
        assertThat(s.durationS).isEqualTo(0L)
    }

    @Test
    fun `a low accuracy fix is bridged over rather than losing the legs either side`() {
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 10f, acc = 5f),
            fix(1000, 12.9800, 77.5900, 10f, acc = 80f),   // glitch, dropped
            fix(2000, 12.9900, 77.5900, 10f, acc = 5f)
        )
        // 0.02 degrees of latitude, measured straight across the dropped fix.
        assertThat(TripStats.compute(fixes).distanceM).isCloseTo(2224.0, within(50.0))
    }

    @Test
    fun `a trip of only inaccurate fixes reports no distance`() {
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 10f, acc = 90f),
            fix(1000, 12.9800, 77.5900, 10f, acc = 90f)
        )
        assertThat(TripStats.compute(fixes).distanceM).isEqualTo(0.0)
    }

    @Test
    fun `moving time excludes stationary fixes below the threshold`() {
        val fixes = listOf(
            fix(0, 12.97, 77.59, 0.1f),
            fix(1000, 12.97, 77.59, 0.2f),
            fix(2000, 12.97, 77.59, 12.0f),
            fix(3000, 12.97, 77.59, 12.0f)
        )
        assertThat(TripStats.compute(fixes).movingS).isEqualTo(2L)
    }

    @Test
    fun `max speed ignores low accuracy outliers`() {
        val fixes = listOf(
            fix(0, 12.97, 77.59, 20f, acc = 5f),
            fix(1000, 12.97, 77.59, 90f, acc = 40f),
            fix(2000, 12.97, 77.59, 25f, acc = 5f)
        )
        assertThat(TripStats.compute(fixes).maxSpeedMps).isEqualTo(25f)
    }

    @Test
    fun `average speed is distance over moving time not wall clock`() {
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 0f),
            fix(60_000, 12.9700, 77.5900, 0f),
            fix(61_000, 12.9700, 77.5900, 20f),
            fix(62_000, 12.9800, 77.5900, 20f)
        )
        val s = TripStats.compute(fixes)
        assertThat(s.durationS).isEqualTo(62L)
        assertThat(s.movingS).isEqualTo(2L)
        assertThat(s.avgSpeedMps).isGreaterThan(100.0)
    }

    @Test
    fun `fixes arriving out of order are sorted before computing`() {
        val ordered = listOf(
            fix(0, 12.9700, 77.5900, 10f),
            fix(1000, 12.9710, 77.5900, 10f),
            fix(2000, 12.9720, 77.5900, 10f)
        )
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])
        assertThat(TripStats.compute(shuffled).distanceM)
            .isCloseTo(TripStats.compute(ordered).distanceM, within(0.01))
    }

    @Test
    fun `a stationary vehicle accrues no distance from position noise`() {
        // A network fix wanders a few metres per sample even when nothing moves. At 1 Hz that
        // would otherwise invent kilometres over an hour parked with the ignition on.
        val jitter = listOf(0.0, 0.00003, -0.00002, 0.00004, -0.00003, 0.00002)
        val fixes = jitter.mapIndexed { i, d ->
            Fix(i * 1000L, 12.9716 + d, 77.5946 - d, speedMps = 0f, accuracyM = 16f)
        }
        assertThat(TripStats.compute(fixes).distanceM)
            .`as`("parked with a wandering network fix")
            .isEqualTo(0.0)
    }

    @Test
    fun `a single fix is never movement`() {
        assertThat(TripStats.moved(listOf(fix(0, 12.97, 77.59, 0f)))).isFalse()
    }

    @Test
    fun `parked engine-on jitter is not movement`() {
        // The exact scenario that used to record a 0 km Home-to-Home "trip": the ignition on,
        // the car still, a few GPS samples drifting tens of metres around the driveway but no
        // direction of travel. It must not count as a drive.
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 0f),
            fix(60_000, 12.9703, 77.5903, 0f),
            fix(120_000, 12.9699, 77.5902, 0f)
        )
        assertThat(TripStats.moved(fixes))
            .`as`("jitter never leaves the origin")
            .isFalse()
    }

    @Test
    fun `leaving the origin counts as movement even without a speed reading`() {
        // A network-only session reports no velocity at all, so the displacement fallback has
        // to catch it: the car did travel, and the drive must be kept.
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 0f),
            fix(60_000, 12.9900, 77.5900, 0f)   // ~2.2 km away
        )
        assertThat(TripStats.moved(fixes)).isTrue()
    }

    @Test
    fun `a direction-of-travel reading of a real drive counts as movement`() {
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 12f),
            fix(1000, 12.9710, 77.5900, 12f)
        )
        assertThat(TripStats.moved(fixes)).isTrue()
    }

    @Test
    fun `distance still accrues once the vehicle is actually moving`() {
        val fixes = listOf(
            Fix(0, 12.9700, 77.5900, speedMps = 12f, accuracyM = 8f),
            Fix(1000, 12.9710, 77.5900, speedMps = 12f, accuracyM = 8f),
            Fix(2000, 12.9720, 77.5900, speedMps = 12f, accuracyM = 8f)
        )
        assertThat(TripStats.compute(fixes).distanceM).isCloseTo(222.0, within(20.0))
    }

    @Test
    fun `a stop in the middle of a drive does not lose the distance either side`() {
        val fixes = listOf(
            Fix(0, 12.9700, 77.5900, speedMps = 12f, accuracyM = 8f),
            Fix(1000, 12.9710, 77.5900, speedMps = 0f, accuracyM = 8f),   // red light
            Fix(2000, 12.9710, 77.5900, speedMps = 0f, accuracyM = 8f),
            Fix(3000, 12.9720, 77.5900, speedMps = 12f, accuracyM = 8f)
        )
        // Both moving legs count; the stationary pair contributes nothing.
        assertThat(TripStats.compute(fixes).distanceM).isCloseTo(222.0, within(25.0))
    }

    // ---- elevation: every up and every down count, never the net ----

    private fun climb(latStep: Double, vararg alts: Double): List<Fix> = alts.mapIndexed { i, alt ->
        Fix(
            (i * 1000).toLong() + 1_000_000,       // 1 Hz cadence
            12.9700 + latStep * i, 77.5900,
            speedMps = 12f, accuracyM = 5f,        // each step is ~latStep km, far past the noise floor
            altitudeM = alt
        )
    }

    @Test
    fun `a hill climbed and descended counts both the up and the down, not the net`() {
        // 900 -> 1100 climbing, then back down to 950. Start/end differ by only 50 m, but the
        // drive really burned energy on two climbs and reclaimed one descent: gain must sum 200,
        // loss must sum 150 — net would hide the regen story entirely.
        val s = TripStats.compute(climb(0.01, 900.0, 1000.0, 1100.0, 950.0))
        assertThat(s.elevGainM).isCloseTo(200.0, within(0.01))
        assertThat(s.elevLossM).isCloseTo(150.0, within(0.01))
    }

    @Test
    fun `altitude jitter below the deadband never manufactures a climb`() {
        // Each step changes altitude by less than the 3 m deadband even though the car moved km.
        val s = TripStats.compute(climb(0.01, 900.0, 901.5, 902.0, 900.5, 899.0))
        assertThat(s.elevGainM).isZero()
        assertThat(s.elevLossM).isZero()
    }

    @Test
    fun `parked altitude wobble does not count because nothing moved`() {
        // Stationary fixes whose altitude swings wildly: no displacement, no elevation.
        val s = TripStats.compute(
            listOf(
                Fix(0, 12.9700, 77.5900, speedMps = 0f, accuracyM = 5f, altitudeM = 900.0),
                Fix(1000, 12.9700, 77.5900, speedMps = 0f, accuracyM = 5f, altitudeM = 950.0),
                Fix(2000, 12.9700, 77.5900, speedMps = 0f, accuracyM = 5f, altitudeM = 880.0)
            )
        )
        assertThat(s.elevGainM).isZero()
        assertThat(s.elevLossM).isZero()
    }

    @Test
    fun `network fixes with no altitude skip cleanly and the climb continues`() {
        // The middle fix carries no altitude (network provider / interpolated bridge); the
        // neighbouring real fixes still form the climb.
        val fixes = listOf(
            Fix(0, 12.9700, 77.5900, speedMps = 12f, accuracyM = 5f, altitudeM = 900.0),
            Fix(1000, 12.9710, 77.5900, speedMps = 12f, accuracyM = 5f, altitudeM = 940.0),
            Fix(2000, 12.9720, 77.5900, speedMps = 12f, accuracyM = 5f, altitudeM = null),
            Fix(3000, 12.9730, 77.5900, speedMps = 12f, accuracyM = 5f, altitudeM = 1000.0)
        )
        val s = TripStats.compute(fixes)
        assertThat(s.elevGainM).isCloseTo(100.0, within(0.01))
        assertThat(s.elevLossM).isZero()
    }

    @Test
    fun `live track elevation agrees with the archived totals`() {
        // The live readout and the stored total come from different classes but must never
        // disagree about what the same fixes climbed: 900->1000, 1000->1100, 1100->950,
        // 950->1050 is 300 m gained and 150 m descended.
        val fixes = climb(0.01, 900.0, 1000.0, 1100.0, 950.0, 1050.0)
        val archived = TripStats.compute(fixes)
        val live = LiveTrack()
        fixes.forEach { live.add(it) }
        assertThat(live.elevGainM).isCloseTo(archived.elevGainM, within(0.01))
        assertThat(live.elevLossM).isCloseTo(archived.elevLossM, within(0.01))
        assertThat(archived.elevGainM).isCloseTo(300.0, within(0.01))
        assertThat(archived.elevLossM).isCloseTo(150.0, within(0.01))
    }
}
