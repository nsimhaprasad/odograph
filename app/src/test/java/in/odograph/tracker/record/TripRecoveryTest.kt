package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.ChargeEventEntity
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
        // Two fixes that clear the anchor noise floor, so the orphan is a real drive that keeps
        // enough of itself to seed the next trip's origin.
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 905.0, 5f, false))
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

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
    fun `an orphan whose engine ran but never moved is discarded not recorded as a 0 km trip`() {
        val dao = db.dao()
        val parked = dao.startTrip(1_000_000L)
        // The car sat with the engine on: cell-tower jitter around one spot, no direction of
        // travel. Previously this surfaced as a tidy Home-to-Home "trip" of 0 km.
        dao.appendPoint(PointEntity(0, parked, 1_000_000L, 12.9700, 77.5900, 0f, null, 905.0, 5f, false))
        dao.appendPoint(PointEntity(0, parked, 1_060_000L, 12.9703, 77.5903, 0f, null, 905.0, 5f, false))
        dao.appendPoint(PointEntity(0, parked, 1_120_000L, 12.9699, 77.5902, 0f, null, 905.0, 5f, false))

        TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)

        assertThat(dao.tripById(parked)).isNull()
        assertThat(dao.pointsFor(parked)).isEmpty()
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

    @Test
    fun `a ride is billed at the blended rate of the fills that began before it`() {
        val dao = db.dao()
        // Two fills before the drive: 10 kWh at ₹8 (cost 80) and 5 kWh at ₹12 (cost 60). The
        // blended rate is 140/15, so a 4.5 kWh ride costs 4.5 * 140/15 = 42.0.
        dao.insertChargeEvent(ChargeEventEntity(startTime = 1_000L, energyKwh = 10.0, kind = 0, costInr = 80.0))
        dao.insertChargeEvent(ChargeEventEntity(startTime = 2_000L, energyKwh = 5.0, kind = 1, costInr = 60.0))

        val cost = TripRecovery.driveCost(dao, energy = 4.5, tripStart = 10_000L)

        assertThat(cost).isEqualTo(42.0)
    }

    @Test
    fun `a ride has no quoted cost until something priced the energy`() {
        val dao = db.dao()

        assertThat(TripRecovery.driveCost(dao, energy = 4.5, tripStart = 10_000L)).isNull()
    }

    @Test
    fun `energy that a charging pause put back in is never billed as a ride cost`() {
        val dao = db.dao()
        dao.insertChargeEvent(ChargeEventEntity(startTime = 1_000L, energyKwh = 10.0, kind = 0, costInr = 80.0))

        // A net-negative trip (charged more than it drove) finances nothing.
        assertThat(TripRecovery.driveCost(dao, energy = -3.0, tripStart = 10_000L)).isEqualTo(0.0)

        // Unknown energy is an honest null, not a zero.
        assertThat(TripRecovery.driveCost(dao, energy = null, tripStart = 10_000L)).isNull()
    }
}
