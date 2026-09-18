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

    // ---------------------------------------------------------------- closing on arrival

    /**
     * Parking now writes the trip, rather than leaving it open for the next boot to find. The two
     * paths are the same code, so a drive ended by parking and one ended by a power cut are
     * recorded identically.
     */
    @Test
    fun `closing a trip that moved writes it with its totals and endpoints`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        val ended = TripRecovery.close(dao, id)

        val trip = dao.tripById(id)!!
        assertThat(trip.endedAt).isEqualTo(1_060_000L)
        assertThat(trip.distanceM).isGreaterThan(0.0)
        assertThat(trip.endLat).isEqualTo(12.9800)
        assertThat(ended.seedLat).`as`("the next trip starts from here").isEqualTo(12.9800)
    }

    @Test
    fun `closing a trip that never moved discards it rather than writing a zero km drive`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))

        val ended = TripRecovery.close(dao, id)

        assertThat(dao.tripById(id)).isNull()
        assertThat(dao.allTrips()).isEmpty()
        assertThat(ended.seedLat).isNull()
    }

    @Test
    fun `closing a trip that does not exist is harmless`() {
        assertThat(TripRecovery.close(db.dao(), 9_999L).seedLat).isNull()
    }

    /**
     * The point of closing on arrival: an outing with a stop in the middle becomes two drives
     * between three places, not one drive that begins and ends at home. Every "most visited route"
     * answer depends on this being right.
     */
    @Test
    fun `an outing with a stop becomes two drives rather than one round trip`() {
        val dao = db.dao()

        val out = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, out, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, out, 1_060_000L, 12.9800, 77.6100, 15f, null, 905.0, 5f, false))
        TripRecovery.close(dao, out)

        val back = dao.startTrip(2_000_000L)
        dao.appendPoint(PointEntity(0, back, 2_000_000L, 12.9800, 77.6100, 0f, null, 905.0, 5f, false))
        dao.appendPoint(PointEntity(0, back, 2_060_000L, 12.9700, 77.5900, 15f, null, 900.0, 5f, false))
        TripRecovery.close(dao, back)

        val trips = dao.allTrips()
        assertThat(trips).hasSize(2)
        assertThat(trips.all { it.endedAt != null }).`as`("both are closed").isTrue()
        val outbound = dao.tripById(out)!!
        assertThat(outbound.startLat).isNotEqualTo(outbound.endLat)
    }

    // ---------------------------------------------------------------- opening on movement

    /**
     * The whole point of splitting recovery from starting: booting must not put a drive in the
     * history. A box that powers up in a parked car used to open a 0 km trip immediately, and
     * nothing removed it until the *next* boot noticed the car had never moved — so the dummy row
     * sat at the top of the list for as long as the car stayed still.
     */
    @Test
    fun `recovering does not open a trip`() {
        val dao = db.dao()

        TripRecovery.recover(dao)

        assertThat(dao.openTrip()).`as`("a parked boot must leave no open trip").isNull()
        assertThat(dao.allTrips()).isEmpty()
    }

    @Test
    fun `recovering still closes whatever the last run left open`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        TripRecovery.recover(dao)

        assertThat(dao.tripById(old)!!.endedAt).isNotNull()
        assertThat(dao.openTrip()).isNull()
    }

    @Test
    fun `recovering discards an orphan that never moved`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))

        TripRecovery.recover(dao)

        assertThat(dao.tripById(old)).`as`("a parked session is not a drive").isNull()
        assertThat(dao.allTrips()).isEmpty()
    }

    @Test
    fun `a trip opened on movement is back-dated to the first held fix`() {
        val dao = db.dao()

        val id = TripRecovery.startOnMove(dao, at = 5_000L, originLat = 12.97, originLon = 77.59)

        val trip = dao.tripById(id)!!
        assertThat(trip.startedAt).`as`("starts where the car was, not where it got to").isEqualTo(5_000L)
        assertThat(trip.startLat).isEqualTo(12.97)
        assertThat(trip.startLon).isEqualTo(77.59)
        assertThat(trip.endedAt).isNull()
    }

    /**
     * The previous drive's endpoint is a settled position; the first fix of a departure may still
     * be converging, so where one exists it wins.
     */
    @Test
    fun `the previous drive's endpoint seeds the origin when there is one`() {
        val dao = db.dao()
        val recovery = TripRecovery.Recovery(seedLat = 12.9999, seedLon = 77.5555)

        val id = TripRecovery.startOnMove(dao, 5_000L, originLat = 12.97, originLon = 77.59, recovery = recovery)

        val trip = dao.tripById(id)!!
        assertThat(trip.startLat).isEqualTo(12.9999)
        assertThat(trip.startLon).isEqualTo(77.5555)
    }

    @Test
    fun `recovery carries the last drive's endpoint forward`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        val recovered = TripRecovery.recover(dao)

        assertThat(recovered.seedLat).isEqualTo(12.9800)
        assertThat(recovered.seedLon).isEqualTo(77.5900)
    }

    @Test
    fun `a discarded orphan carries nothing forward`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))

        val recovered = TripRecovery.recover(dao)

        assertThat(recovered.seedLat).isNull()
        assertThat(recovered.seedLon).isNull()
    }

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

    // ---------------------------------------------------------------- interrupted, not finished

    /**
     * The bug these exist for: the recorder shares a process with the screen, so anything that
     * kills the process mid-drive — memory pressure from the map, a crash, Android reclaiming the
     * app — closed the drive on the way back up and opened a fresh one at the next movement. One
     * outing became two and the readout went back to zero, which from the driver's seat is a trip
     * that reset itself for no reason.
     */
    @Test
    fun `a restart moments into a drive picks the same trip back up`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        // Five seconds later, near enough to where it was: the process restarted, the car did not.
        val outcome = TripRecovery.resumeOrClose(dao, fixAt(1_065_000L, 12.9800, 77.5901))

        assertThat(outcome).isInstanceOf(TripRecovery.Interrupted.Resume::class.java)
        val resume = outcome as TripRecovery.Interrupted.Resume
        assertThat(resume.tripId).isEqualTo(id)
        assertThat(resume.startedAt).`as`("the drive keeps the time it actually began").isEqualTo(1_000_000L)
        assertThat(dao.tripById(id)!!.endedAt).`as`("it is still running").isNull()
    }

    /** The readout has to come back with the drive, or the odometer loses what it already covered. */
    @Test
    fun `a resumed drive carries the points it had already recorded`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        val resume = TripRecovery.resumeOrClose(dao, fixAt(1_065_000L, 12.9800, 77.5901))
            as TripRecovery.Interrupted.Resume

        assertThat(resume.fixes).hasSize(2)
        val replayed = `in`.odograph.tracker.core.LiveTrack().also { t -> resume.fixes.forEach(t::add) }
        assertThat(replayed.distanceM)
            .`as`("the kilometres already driven survive the restart")
            .isGreaterThan(1_000.0)
    }

    /**
     * The other half of the same rule. A gap longer than the shortest stillness that ends a drive
     * cannot be called an interruption, because the existing rules would have closed the trip
     * during it — resuming would silently merge two outings into one.
     */
    @Test
    fun `a gap longer than the resume window closes the trip as an orphan`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        val later = 1_060_000L + TripRecovery.RESUME_WINDOW_MS + 1_000L
        val outcome = TripRecovery.resumeOrClose(dao, fixAt(later, 12.9800, 77.5901))

        assertThat(outcome).isInstanceOf(TripRecovery.Interrupted.Closed::class.java)
        assertThat(dao.tripById(id)!!.endedAt).isEqualTo(1_060_000L)
    }

    /**
     * A drive with a hole in it is worse than two drives that meet at the hole: the distance
     * across the gap becomes a straight line where the road was not, and nothing downstream can
     * tell that it was invented.
     */
    @Test
    fun `a car that travelled during the gap is not resumed across it`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        // Well inside the time window, but several kilometres away.
        val outcome = TripRecovery.resumeOrClose(dao, fixAt(1_070_000L, 13.0400, 77.5900))

        assertThat(outcome).isInstanceOf(TripRecovery.Interrupted.Closed::class.java)
    }

    /** A clock that runs backwards is a broken reading, not a drive that has not happened yet. */
    @Test
    fun `a fix older than the last recorded point does not resume`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, id, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        val outcome = TripRecovery.resumeOrClose(dao, fixAt(1_000_500L, 12.9800, 77.5901))

        assertThat(outcome).isInstanceOf(TripRecovery.Interrupted.Closed::class.java)
    }

    @Test
    fun `a boot with nothing left open has nothing to decide`() {
        val outcome = TripRecovery.resumeOrClose(db.dao(), fixAt(1_000_000L, 12.97, 77.59))

        assertThat(outcome).isInstanceOf(TripRecovery.Interrupted.Closed::class.java)
        assertThat(db.dao().allTrips()).isEmpty()
    }

    /** An open trip with no points is still the parked session it always was, not a drive. */
    @Test
    fun `an open trip that never got a fix is still discarded`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)

        val outcome = TripRecovery.resumeOrClose(dao, fixAt(1_000_500L, 12.97, 77.59))

        assertThat(outcome).isInstanceOf(TripRecovery.Interrupted.Closed::class.java)
        assertThat(dao.tripById(id)).isNull()
    }

    private fun fixAt(t: Long, lat: Double, lon: Double) =
        `in`.odograph.tracker.core.Fix(t, lat, lon, 15f, 5f, false, 905.0)
}
