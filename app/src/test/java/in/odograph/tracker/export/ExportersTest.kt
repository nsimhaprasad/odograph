package `in`.odograph.tracker.export

import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ExportersTest {

    private val trip = TripEntity(
        id = 7, startedAt = 1_700_000_000_000L, endedAt = 1_700_000_600_000L,
        distanceM = 18_432.0, durationS = 600, movingS = 540,
        maxSpeedMps = 21.6f, avgSpeedMps = 34.1, slowestKmMps = 3.2
    )
    private val point =
        PointEntity(1, 7, 1_700_000_000_000L, 12.97, 77.59, 12.5f, 90f, 900.0, 4f, false)

    @Test
    fun `trips csv has a header row and one row per trip`() {
        val lines = Exporters.tripsCsv(listOf(trip)).trim().lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("id,started_at,ended_at,distance_m")
        assertThat(lines[1]).contains("18432.0")
    }

    @Test
    fun `every csv row has the same column count as the header`() {
        val lines = Exporters.tripsCsv(listOf(trip, trip.copy(id = 8, endedAt = null))).trim().lines()
        val cols = lines[0].split(",").size
        lines.drop(1).forEach { assertThat(it.split(",")).hasSize(cols) }
    }

    @Test
    fun `an empty trip list still emits the header`() {
        assertThat(Exporters.tripsCsv(emptyList()).trim().lines()).hasSize(1)
    }

    @Test
    fun `gpx is well formed and contains a trackpoint`() {
        val gpx = Exporters.gpx("Trip 7", listOf(point))
        assertThat(gpx).startsWith("<?xml")
        assertThat(gpx).contains("""<trkpt lat="12.97" lon="77.59">""")
        assertThat(gpx).endsWith("</gpx>")
    }

    @Test
    fun `gpx escapes xml metacharacters in the trip name`() {
        assertThat(Exporters.gpx("Home & <Office>", listOf(point)))
            .contains("Home &amp; &lt;Office&gt;")
    }

    @Test
    fun `points csv renders nulls as empty fields not the string null`() {
        val csv = Exporters.pointsCsv(
            listOf(point.copy(bearingDeg = null, altitudeM = null))
        )
        assertThat(csv).doesNotContain("null")
    }

    @Test
    fun `trips csv carries the battery and cost columns`() {
        val csv = Exporters.tripsCsv(
            listOf(trip.copy(socStart = 90.0, socEnd = 64.2, energyKwh = 12.7, costInr = 101.6))
        )
        val line = csv.trim().lines()[1]
        assertThat(line).contains(",90.0,64.2,12.7,101.6")
    }

    @Test
    fun `trips csv carries the per-trip climb and descent after the speed stats`() {
        val line = Exporters.tripsCsv(
            listOf(trip.copy(elevGainM = 120.0, elevLossM = 35.0))
        ).trim().lines()[1]
        // The elevation values sit right after the speed stats (slowest_km_mps).
        assertThat(line).contains(",120.0,35.0,")
    }

    @Test
    fun `header includes the elevation columns`() {
        assertThat(Exporters.tripsCsv(emptyList()).trim().lines()[0])
            .contains("elev_gain_m,elev_loss_m")
    }

    @Test
    fun `trips csv leaves battery blanks when a trip was not instrumented`() {
        val line = Exporters.tripsCsv(listOf(trip)).trim().lines()[1]
        assertThat(line).endsWith(",,,,")
    }
}
