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
}
