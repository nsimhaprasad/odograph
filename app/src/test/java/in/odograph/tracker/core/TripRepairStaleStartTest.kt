package `in`.odograph.tracker.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Re-deriving drives that began from a stale cached fix.
 *
 * Written against the real case: a trip timer reading 7508:59, because a cached "last known"
 * position stamped with the image's build date — months before the car was bought — arrived
 * first and became the origin.
 */
@RunWith(RobolectricTestRunner::class)
class TripRepairStaleStartTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()

    private val nov2025 = 1_763_164_800_000L
    private val sep2026 = 1_790_208_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun point(id: Long, t: Long, lat: Double, speed: Float = 10f) =
        PointEntity(0, id, t, lat, 77.59, speed, null, 900.0, 5f, false)

    /** A 6 km drive in September whose first point is the cached fix from November. */
    private fun driveBegunFromStaleFix(): Long {
        val id = dao.startTrip(nov2025)
        dao.appendPoint(point(id, nov2025, 12.9700, speed = 0f))
        (0..6).forEach { k -> dao.appendPoint(point(id, sep2026 + k * 60_000L, 12.9700 + k * 0.009)) }
        // Stored as the old code stored it: duration measured from the stale point.
        dao.finishTrip(id, sep2026 + 6 * 60_000L, 6_000.0, (sep2026 + 6 * 60_000L - nov2025) / 1000, 360, 10f, 1.0, 5.0, 0.0, 0.0)
        dao.setOrigin(id, 12.9700, 77.59)
        return id
    }

    @Test
    fun `a drive begun from a stale fix is re-anchored to its first real fix`() {
        val id = driveBegunFromStaleFix()
        assertThat(dao.tripById(id)!!.durationS).`as`("stored as 313 days").isGreaterThan(300L * 86_400)

        val r = TripRepair.repairStaleStarts(dao)

        assertThat(r.reanchored).isEqualTo(1)
        assertThat(r.worstDays).isBetween(312.0, 314.0)
        val t = dao.tripById(id)!!
        assertThat(t.startedAt).isEqualTo(sep2026)
        assertThat(t.durationS).isEqualTo(6 * 60L)
        assertThat(t.endedAt).isEqualTo(sep2026 + 6 * 60_000L)
    }

    @Test
    fun `the stale point is removed from the track`() {
        val id = driveBegunFromStaleFix()

        TripRepair.repairStaleStarts(dao)

        val pts = dao.pointsFor(id)
        assertThat(pts).hasSize(7)
        assertThat(pts.first().t).isEqualTo(sep2026)
    }

    /** Average speed is distance over duration; with a 313-day duration it was effectively zero. */
    @Test
    fun `figures derived from the track are worked out again`() {
        val id = driveBegunFromStaleFix()
        assertThat(dao.tripById(id)!!.avgSpeedMps).isLessThan(2.0)

        TripRepair.repairStaleStarts(dao)

        assertThat(dao.tripById(id)!!.avgSpeedMps).isGreaterThan(10.0)
    }

    @Test
    fun `an ordinary drive is untouched`() {
        val id = dao.startTrip(sep2026)
        (0..6).forEach { k -> dao.appendPoint(point(id, sep2026 + k * 60_000L, 12.9700 + k * 0.009)) }
        dao.finishTrip(id, sep2026 + 6 * 60_000L, 6_000.0, 360, 360, 10f, 16.7, 5.0, 0.0, 0.0)

        val r = TripRepair.repairStaleStarts(dao)

        assertThat(r.reanchored).isZero()
        assertThat(dao.tripById(id)!!.startedAt).isEqualTo(sep2026)
        assertThat(dao.pointsFor(id)).hasSize(7)
    }

    /** A real halt of under an hour inside a drive is a halt, not a stale start. */
    @Test
    fun `a long traffic halt at the origin is not mistaken for a stale fix`() {
        val id = dao.startTrip(sep2026)
        dao.appendPoint(point(id, sep2026, 12.9700, speed = 0f))
        (0..6).forEach { k -> dao.appendPoint(point(id, sep2026 + 40 * 60_000L + k * 60_000L, 12.9700 + k * 0.009)) }
        dao.finishTrip(id, sep2026 + 46 * 60_000L, 6_000.0, 46 * 60, 360, 10f, 2.2, 5.0, 0.0, 0.0)

        val r = TripRepair.repairStaleStarts(dao)

        assertThat(r.reanchored).isZero()
    }
}
