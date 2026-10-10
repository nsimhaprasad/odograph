package `in`.odograph.tracker.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.BatteryEntity
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

    // ---------------------------------------------------------------- the boot-clock block
    //
    // The Hassan drive's shape, and the one the gap-based repair missed: the box woke on the
    // firmware's build date and the whole opening recorded on it — points two seconds apart, not an
    // hour — before GNSS corrected the clock mid-drive and it jumped months forward. The positions
    // were always right; only the time was wrong, so the opening must be carried onto the corrected
    // clock, not thrown away.

    /** Five points on the boot clock, two seconds apart and moving, then the clock jumps to September. */
    private fun driveWithBootClockBlock(): Long {
        val id = dao.startTrip(nov2025)
        (0..4).forEach { k -> dao.appendPoint(point(id, nov2025 + k * 2_000L, 12.97 + k * 0.009)) }
        (0..4).forEach { k -> dao.appendPoint(point(id, sep2026 + k * 2_000L, 12.97 + (5 + k) * 0.009)) }
        val end = sep2026 + 4 * 2_000L
        // Stored as the recorder stored it live: end on the corrected clock, duration measured from
        // the stale start — months, not the eighteen seconds the drive actually took so far.
        dao.finishTrip(id, end, 0.0, (end - nov2025) / 1000, 0, 10f, 0.0, 0.0, 0.0, 0.0)
        dao.setOrigin(id, 12.97, 77.59)
        return id
    }

    @Test
    fun `a boot-clock opening block is carried onto the corrected clock, not dropped`() {
        val id = driveWithBootClockBlock()

        val r = TripRepair.repairStaleStarts(dao)

        assertThat(r.reanchored).isEqualTo(1)
        val t = dao.tripById(id)!!
        // Start = first corrected point - the block's real duration (ten seconds across five fixes).
        assertThat(t.startedAt).isEqualTo(sep2026 - 10_000L)
        assertThat(t.endedAt).isEqualTo(sep2026 + 8_000L)
        assertThat(t.durationS).isEqualTo(18L)
        // Every point kept — the opening kilometres are not lost, unlike a dropped stale fix.
        assertThat(dao.pointsFor(id)).hasSize(10)
        assertThat(dao.pointsFor(id).first().t).isEqualTo(sep2026 - 10_000L)
        // And the track is chronological again, with real distance.
        val ts = dao.pointsFor(id).map { it.t }
        assertThat(ts).isSorted
        assertThat(t.distanceM).isGreaterThan(0.0)
    }

    @Test
    fun `battery frames in the stale opening are shifted by the same jump`() {
        val id = driveWithBootClockBlock()
        dao.insertBattery(BatteryEntity(tripId = id, t = nov2025 + 4_000L, socPercent = 80.0))
        dao.insertBattery(BatteryEntity(tripId = id, t = sep2026 + 4_000L, socPercent = 78.0))

        TripRepair.repairStaleStarts(dao)

        val frames = dao.batteryRangeFor(id)
        assertThat(frames).hasSize(2)
        // The boot-clock frame lands where its point did — ten seconds of block shifted forward,
        // so nov2025+4s becomes sep2026-6s — while the already-corrected frame does not move.
        assertThat(frames.first().t).isEqualTo(sep2026 - 6_000L)
        assertThat(frames.first().socPercent).isEqualTo(80.0)
        assertThat(frames.last().t).isEqualTo(sep2026 + 4_000L)
    }

    @Test
    fun `detectClockSkew finds the jump and the shift that closes it`() {
        val times = (0..4).map { nov2025 + it * 2_000L } + (0..4).map { sep2026 + it * 2_000L }
        val skew = TripRepair.detectClockSkew(times, anchor = sep2026 + 8_000L)!!
        assertThat(skew.firstTrueIndex).isEqualTo(5)
        assertThat(skew.lastStaleT).isEqualTo(nov2025 + 8_000L)
        // The last stale fix, shifted, sits one ordinary gap before the first corrected one.
        assertThat(times[4] + skew.offsetMs).isEqualTo(sep2026 - 2_000L)
    }

    @Test
    fun `detectClockSkew leaves a clean drive alone`() {
        val times = (0..6).map { sep2026 + it * 2_000L }
        assertThat(TripRepair.detectClockSkew(times, anchor = sep2026 + 12_000L)).isNull()
    }

    @Test
    fun `a drive whose whole track is stale cannot be placed in time`() {
        // The clock never corrected: the end is stale too, so there is no corrected point to
        // measure the jump against. Nothing to do but leave it for the sheet-side cleanup.
        val times = (0..6).map { nov2025 + it * 2_000L }
        assertThat(TripRepair.detectClockSkew(times, anchor = nov2025 + 12_000L)).isNull()
    }

    @Test
    fun `clockSynced tells the boot clock from a corrected one`() {
        assertThat(TripRepair.clockSynced(nov2025)).isFalse()
        assertThat(TripRepair.clockSynced(sep2026)).isTrue()
    }
}
