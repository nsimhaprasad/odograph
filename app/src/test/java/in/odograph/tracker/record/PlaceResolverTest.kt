package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaceResolverTest {

    private lateinit var db: OdographDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the first visit creates a place`() {
        val id = PlaceResolver(db.dao()).resolve(12.9716, 77.5946)
        assertThat(id).isGreaterThan(0L)
        assertThat(db.dao().allPlaces()).hasSize(1)
    }

    @Test
    fun `a nearby visit reuses the same place`() {
        val r = PlaceResolver(db.dao())
        val first = r.resolve(12.9716, 77.5946)
        val again = r.resolve(12.9716 + 0.0005, 77.5946 + 0.0005)   // ~74 m
        assertThat(again).isEqualTo(first)
        assertThat(db.dao().allPlaces()).hasSize(1)
    }

    @Test
    fun `a distant visit creates a second place`() {
        val r = PlaceResolver(db.dao())
        r.resolve(12.9716, 77.5946)
        r.resolve(12.9698, 77.7500)
        assertThat(db.dao().allPlaces()).hasSize(2)
    }

    @Test
    fun `repeat visits are counted and pull the centroid`() {
        val r = PlaceResolver(db.dao())
        r.resolve(12.9716, 77.5946)
        r.resolve(12.9720, 77.5946)
        r.resolve(12.9720, 77.5946)

        val place = db.dao().allPlaces().single()
        assertThat(place.visits).isEqualTo(3)
        assertThat(place.lat).isCloseTo(12.9719, within(0.0002))
    }

    @Test
    fun `recovery attaches a finished trip to its start and end places`() {
        val dao = db.dao()
        val trip = dao.startTrip(1_000L)
        dao.appendPoint(PointEntity(0, trip, 1_000L, 12.9716, 77.5946, 0f, null, null, 5f, false))
        dao.appendPoint(PointEntity(0, trip, 60_000L, 12.9698, 77.7500, 0f, null, null, 5f, false))

        TripRecovery.recoverAndStart(dao, nowFromGnss = 90_000L)

        val done = dao.tripById(trip)!!
        assertThat(done.startPlaceId).isNotNull()
        assertThat(done.endPlaceId).isNotNull()
        assertThat(done.startPlaceId).isNotEqualTo(done.endPlaceId)
    }

    @Test
    fun `the same commute driven repeatedly groups into one route`() {
        val dao = db.dao()
        repeat(3) { n ->
            val t = dao.startTrip(n * 100_000L)
            dao.appendPoint(PointEntity(0, t, n * 100_000L, 12.9716, 77.5946, 0f, null, null, 5f, false))
            dao.appendPoint(
                PointEntity(0, t, n * 100_000L + 60_000, 12.9698, 77.7500, 0f, null, null, 5f, false)
            )
            TripRecovery.recoverAndStart(dao, nowFromGnss = n * 100_000L + 90_000)
        }

        val routes = dao.routeSummaries()
        assertThat(routes).hasSize(1)
        assertThat(routes.first().drives).isEqualTo(3)
    }

    @Test
    fun `the return leg is a separate route`() {
        val dao = db.dao()
        fun drive(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, at: Long) {
            val t = dao.startTrip(at)
            dao.appendPoint(PointEntity(0, t, at, fromLat, fromLon, 0f, null, null, 5f, false))
            dao.appendPoint(PointEntity(0, t, at + 60_000, toLat, toLon, 0f, null, null, 5f, false))
            TripRecovery.recoverAndStart(dao, nowFromGnss = at + 90_000)
        }
        drive(12.9716, 77.5946, 12.9698, 77.7500, 0)
        drive(12.9698, 77.7500, 12.9716, 77.5946, 200_000)

        assertThat(dao.routeSummaries()).hasSize(2)
    }
}
