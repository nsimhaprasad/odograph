package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripRecoveryTest {

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
    fun `an orphaned trip is closed with totals computed from its points`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)

        val recovered = dao.tripById(old)!!
        // Closed at the last point it managed to write, not at "now".
        assertThat(recovered.endedAt).isEqualTo(1_060_000L)
        assertThat(recovered.distanceM).isGreaterThan(1000.0)
        assertThat(recovered.durationS).isEqualTo(60L)
    }

    @Test
    fun `the new trip inherits its origin from the previous trip's last point`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 0f, null, 905.0, 5f, false))

        val newId = TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)

        val fresh = dao.tripById(newId)!!
        assertThat(fresh.startLat).isEqualTo(12.9800)
        assertThat(fresh.startLon).isEqualTo(77.5900)
    }

    @Test
    fun `an orphan that never got a fix is discarded rather than closed with garbage`() {
        val dao = db.dao()
        val empty = dao.startTrip(1_000_000L)

        TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)

        assertThat(dao.tripById(empty)).isNull()
    }

    @Test
    fun `recovery always leaves exactly one open trip`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.97, 77.59, 0f, null, null, 5f, false))

        TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)
        assertThat(dao.allTrips().count { it.endedAt == null }).isEqualTo(1)

        TripRecovery.recoverAndStart(dao, nowFromGnss = 3_000_000L)
        assertThat(dao.allTrips().count { it.endedAt == null }).isEqualTo(1)
    }

    @Test
    fun `a first ever boot with no history simply starts a trip`() {
        val dao = db.dao()
        val id = TripRecovery.recoverAndStart(dao, nowFromGnss = 5_000L)

        assertThat(dao.openTrip()?.id).isEqualTo(id)
        assertThat(dao.tripById(id)!!.startLat).isNull()
    }
}
