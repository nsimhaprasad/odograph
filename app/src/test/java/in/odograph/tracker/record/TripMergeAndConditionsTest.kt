package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two things a closing drive now records and one thing a driver can now do to two of them.
 *
 * The temperature range is written at close from the drive's own frames. The merge joins two
 * drives the recorder split into the one they were, and closes the survivor again from the
 * combined evidence rather than adding the halves up.
 */
@RunWith(RobolectricTestRunner::class)
class TripMergeAndConditionsTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()
    private val capacity = 51.6

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** A drive of [km] from [at], with [n] points a minute apart, closed. */
    private fun drive(at: Long, km: Double, n: Int = 6, lat0: Double = 12.9700): Long {
        val id = dao.startTrip(at)
        (0 until n).forEach { k ->
            dao.appendPoint(PointEntity(0, id, at + k * 60_000L, lat0 + (km / 111.32) * k / (n - 1), 77.59, 12f, null, 900.0, 5f, false))
        }
        TripRecovery.close(dao, id, capacity)
        return id
    }

    private fun frame(trip: Long, t: Long, temp: Int, soc: Double = 70.0) =
        dao.insertBattery(BatteryEntity(tripId = trip, t = t, socPercent = soc, charging = false, exteriorTempC = temp))

    // ------------------------------------------------------------- temperature range

    @Test
    fun `a closing drive records the coolest and warmest it saw`() {
        val at = 1_000_000L
        val id = dao.startTrip(at)
        (0 until 4).forEach { k -> dao.appendPoint(PointEntity(0, id, at + k * 60_000L, 12.97 + 0.01 * k, 77.59, 12f, null, 900.0, 5f, false)) }
        frame(id, at + 10_000L, 19); frame(id, at + 70_000L, 26); frame(id, at + 130_000L, 34)

        TripRecovery.close(dao, id, capacity)

        val t = dao.tripById(id)!!
        assertThat(t.minTempC).isEqualTo(19.0)
        assertThat(t.maxTempC).isEqualTo(34.0)
        assertThat(t.avgTempC!!).isCloseTo(26.33, within(0.01))
    }

    @Test
    fun `a drive with no readings has no range, not a zero`() {
        val id = drive(1_000_000L, km = 5.0)

        assertThat(dao.tripById(id)!!.minTempC).isNull()
        assertThat(dao.tripById(id)!!.maxTempC).isNull()
    }

    // ------------------------------------------------------------------------ merge

    @Test
    fun `merging keeps the earlier drive and hands it everything the later one had`() {
        val a = drive(1_000_000L, km = 5.0)                              // 5 km
        val b = drive(1_000_000L + 10 * 60_000L, km = 3.0, lat0 = 12.97 + 5.0 / 111.32)   // 3 km, continuing
        frame(a, 1_000_000L + 30_000L, 24); frame(b, 1_000_000L + 11 * 60_000L, 31)

        val survivor = TripRecovery.mergeTrips(dao, b, a, capacity)

        assertThat(survivor).isEqualTo(a)
        assertThat(dao.tripById(b)).`as`("the later drive is gone").isNull()
        val t = dao.tripById(a)!!
        assertThat(t.distanceM / 1000.0).`as`("distance re-measured across the join, not added").isCloseTo(8.0, within(0.3))
        assertThat(dao.pointsFor(a)).hasSize(12)
        assertThat(dao.batteryRangeFor(a)).hasSize(2)
        assertThat(t.minTempC).isEqualTo(24.0)
        assertThat(t.maxTempC).isEqualTo(31.0)
        assertThat(t.endedAt).isEqualTo(1_000_000L + 10 * 60_000L + 5 * 60_000L)
    }

    @Test
    fun `the argument order does not decide which drive survives`() {
        val a = drive(1_000_000L, km = 4.0)
        val b = drive(2_000_000L, km = 4.0)

        assertThat(TripRecovery.mergeTrips(dao, a, b, capacity)).isEqualTo(a)
    }

    @Test
    fun `a drive cannot be merged with itself or with one still open`() {
        val a = drive(1_000_000L, km = 4.0)
        val open = dao.startTrip(3_000_000L)

        assertThat(TripRecovery.mergeTrips(dao, a, a, capacity)).isNull()
        assertThat(TripRecovery.mergeTrips(dao, a, open, capacity)).isNull()
        assertThat(dao.tripById(a)).isNotNull()
    }

    /**
     * The closed-drive merge refuses an open drive by design: joining onto a *live* drive is a
     * different operation ([foldIntoOpenTrip], which keeps it open). This pins that mergeTrips
     * itself only ever joins two finished drives.
     */
    @Test
    fun `mergeTrips refuses an open drive, but joins it once closed`() {
        val closed = drive(1_000_000L, km = 5.0)                     // 5 km
        // An open drive of 3 km, continuing from where the closed one ended (no endedAt).
        val open = dao.startTrip(1_000_000L + 10 * 60_000L)
        val openStart = 1_000_000L + 10 * 60_000L
        val lat0 = 12.9700 + 5.0 / 111.32
        (0 until 6).forEach { k ->
            dao.appendPoint(PointEntity(0, open, openStart + k * 60_000L, lat0 + (3.0 / 111.32) * k / 5, 77.59, 12f, null, 900.0, 5f, false))
        }

        // While open (endedAt null) the merge is a no-op.
        assertThat(TripRecovery.mergeTrips(dao, closed, open, capacity)).isNull()
        assertThat(dao.tripById(open)).isNotNull()

        // Close it as arrival would, then the same merge joins them.
        TripRecovery.close(dao, open, capacity)
        val survivor = TripRecovery.mergeTrips(dao, closed, open, capacity)

        assertThat(survivor).isEqualTo(closed)
        assertThat(dao.tripById(open)).isNull()
        assertThat(dao.tripById(closed)!!.distanceM / 1000.0).isCloseTo(8.0, within(0.5))
    }

    // -------------------------------------------------- joining a stop onto the live drive

    /**
     * The case that was wrong: joining a finished stop onto the drive being recorded must keep
     * that drive going, not end it. The open drive keeps its id and its null end; the stop's
     * points move onto it; the start back-dates to the earlier piece.
     */
    @Test
    fun `folding a finished drive into the open one keeps it open and recording`() {
        val earlier = drive(1_000_000L, km = 5.0)                       // finished
        val liveStart = 1_000_000L + 10 * 60_000L
        val live = dao.startTrip(liveStart)                             // open, no end
        val lat0 = 12.9700 + 5.0 / 111.32
        (0 until 6).forEach { k ->
            dao.appendPoint(PointEntity(0, live, liveStart + k * 60_000L, lat0 + (3.0 / 111.32) * k / 5, 77.59, 12f, null, 900.0, 5f, false))
        }

        val survivor = TripRecovery.foldIntoOpenTrip(dao, openId = live, closedId = earlier)

        assertThat(survivor).isEqualTo(live)
        val t = dao.tripById(live)!!
        assertThat(t.endedAt).`as`("the drive is still being recorded").isNull()
        assertThat(t.startedAt).`as`("start back-dated to the earlier piece").isEqualTo(1_000_000L)
        assertThat(dao.pointsFor(live)).hasSize(12)             // 6 + 6
        assertThat(dao.tripById(earlier)).`as`("the finished stop is absorbed").isNull()
    }

    @Test
    fun `folding refuses when the target is not open`() {
        val a = drive(1_000_000L, km = 4.0)                             // closed
        val b = drive(2_000_000L, km = 4.0)                             // closed

        assertThat(TripRecovery.foldIntoOpenTrip(dao, openId = a, closedId = b)).isNull()
        assertThat(dao.tripById(b)).`as`("nothing absorbed when the target is closed").isNotNull()
    }

    @Test
    fun `an open drive that started later than the stop still keeps its own end-open state`() {
        val earlier = drive(1_000_000L, km = 5.0)
        val live = dao.startTrip(5_000_000L)
        dao.appendPoint(PointEntity(0, live, 5_000_000L, 12.99, 77.60, 5f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, live, 5_060_000L, 12.995, 77.60, 12f, null, 900.0, 5f, false))

        TripRecovery.foldIntoOpenTrip(dao, openId = live, closedId = earlier)

        assertThat(dao.tripById(live)!!.endedAt).isNull()
        assertThat(dao.tripById(live)!!.startedAt).isEqualTo(1_000_000L)
    }

}