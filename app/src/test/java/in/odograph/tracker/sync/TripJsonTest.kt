package `in`.odograph.tracker.sync

import `in`.odograph.tracker.data.TripEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TripJsonTest {

    private val trip = TripEntity(
        id = 7, startedAt = 1_700_000_000_000L, endedAt = 1_700_000_600_000L,
        distanceM = 18_432.0, durationS = 600, movingS = 540,
        maxSpeedMps = 21.6f, avgSpeedMps = 34.1, slowestKmMps = 3.2,
        startLat = 12.97, startLon = 77.59
    )

    @Test
    fun `a trip encodes its identity and totals`() {
        val json = TripJson.encode(trip, "windsor")
        assertThat(json).contains(""""id":7""")
        assertThat(json).contains(""""distanceM":18432.0""")
        assertThat(json).contains(""""device":"windsor"""")
    }

    @Test
    fun `absent coordinates encode as json null not the string null`() {
        val json = TripJson.encode(trip.copy(endLat = null, endLon = null), "d")
        assertThat(json).contains(""""endLat":null""")
        assertThat(json).doesNotContain(""""endLat":"null"""")
    }

    @Test
    fun `a device id containing quotes cannot break the payload`() {
        assertThat(TripJson.encode(trip, """my "car"""")).contains("""my \"car\"""")
    }

    @Test
    fun `a batch is a json array`() {
        val json = TripJson.encodeBatch(listOf(trip, trip.copy(id = 8)), "d")
        assertThat(json).startsWith("[").endsWith("]")
        assertThat(json).contains(""""id":8""")
    }

    @Test
    fun `an empty batch is still valid json`() {
        assertThat(TripJson.encodeBatch(emptyList(), "d")).isEqualTo("[]")
    }
}
