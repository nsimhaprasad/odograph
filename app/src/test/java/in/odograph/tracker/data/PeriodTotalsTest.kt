package `in`.odograph.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeriodTotalsTest {

    private lateinit var db: OdographDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun finishedTrip(startedAt: Long, metres: Double, seconds: Long): Long {
        val dao = db.dao()
        val id = dao.startTrip(startedAt)
        dao.finishTrip(id, startedAt + seconds * 1000, metres, seconds, seconds, 25f, 20.0, 3.0, 0.0, 0.0)
        return id
    }

    @Test
    fun `totals count every finished trip inside the range`() {
        finishedTrip(1_000L, 5_000.0, 600)
        finishedTrip(2_000L, 7_000.0, 900)

        val t = db.dao().periodTotals(0L, 10_000L)

        assertThat(t.drives).isEqualTo(2)
        assertThat(t.distanceM).isCloseTo(12_000.0, within(0.1))
        assertThat(t.durationS).isEqualTo(1_500L)
    }

    @Test
    fun `totals agree with the route list over the same range`() {
        val dao = db.dao()
        val a = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59, visits = 1))
        val b = dao.insertPlace(PlaceEntity(lat = 12.96, lon = 77.75, visits = 1))
        repeat(3) { n ->
            val id = finishedTrip(1_000L + n, 5_000.0, 600)
            dao.setTripPlaces(id, a, b)
        }

        val totals = dao.periodTotals(0L, 10_000L)
        val routes = dao.routeSummariesBetween(0L, 10_000L)

        assertThat(routes.sumOf { it.drives })
            .`as`("route drives must equal the headline drive count")
            .isEqualTo(totals.drives)
    }

    @Test
    fun `an unbounded upper limit does not overflow`() {
        finishedTrip(1_000L, 5_000.0, 600)
        val t = db.dao().periodTotals(0L, Long.MAX_VALUE)
        assertThat(t.drives).isEqualTo(1)
    }

    @Test
    fun `trips outside the range are excluded`() {
        finishedTrip(1_000L, 5_000.0, 600)
        assertThat(db.dao().periodTotals(5_000L, 10_000L).drives).isEqualTo(0)
    }

    @Test
    fun `an open trip is not counted until it has ended`() {
        db.dao().startTrip(1_000L)
        assertThat(db.dao().periodTotals(0L, 10_000L).drives).isEqualTo(0)
    }
}
