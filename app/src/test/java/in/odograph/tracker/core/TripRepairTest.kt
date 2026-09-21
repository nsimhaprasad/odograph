package `in`.odograph.tracker.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.BatteryEntity
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
 * Repairing top speeds a GNSS spike wrote into drives already recorded.
 *
 * The plausibility rules stop new ones, but every trip from before keeps whatever the worst single
 * fix claimed, because the figure is stored rather than derived. The points are still on disk, so
 * the honest number can be worked out again — carefully, and touching nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class TripRepairTest {

    private lateinit var db: OdographDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun kmh(v: Float) = v / 3.6f

    /** A closed drive with the given per-fix speeds, and whatever maximum was stored at the time. */
    private fun drive(speedsKmh: List<Float>, storedMaxKmh: Float): Long {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        speedsKmh.forEachIndexed { i, s ->
            dao.appendPoint(
                PointEntity(
                    0, id, 1_000_000L + i * 1_000L,
                    12.9716 + i * 0.0004, 77.5946,
                    kmh(s), null, 900.0, 5f, false
                )
            )
        }
        dao.finishTrip(
            id, 1_000_000L + speedsKmh.size * 1_000L, 5_000.0, speedsKmh.size.toLong(),
            speedsKmh.size.toLong(), kmh(storedMaxKmh), 14.0, 3.4, 0.0, 0.0
        )
        return id
    }

    @Test
    fun `a spike stored against an old drive is corrected`() {
        val id = drive(listOf(55f, 58f, 195f, 60f, 57f), storedMaxKmh = 195f)

        val outcome = TripRepair.repairMaxSpeeds(db.dao())

        assertThat(outcome.corrected).isEqualTo(1)
        assertThat(db.dao().tripById(id)!!.maxSpeedMps * 3.6f)
            .isEqualTo(60f, within(1f))
    }

    @Test
    fun `an honest drive is left exactly as it was`() {
        val id = drive(listOf(40f, 55f, 70f, 85f, 95f), storedMaxKmh = 95f)
        val before = db.dao().tripById(id)!!.maxSpeedMps

        val outcome = TripRepair.repairMaxSpeeds(db.dao())

        assertThat(outcome.corrected).isEqualTo(0)
        assertThat(db.dao().tripById(id)!!.maxSpeedMps).isEqualTo(before)
    }

    /**
     * The repair only ever lowers. A stored figure below what the points imply is not evidence of
     * a spike — it may be a deliberately conservative value from an older rule — and re-deriving
     * history is not what this is for.
     */
    @Test
    fun `a conservative stored figure is never raised`() {
        val id = drive(listOf(40f, 60f, 90f), storedMaxKmh = 50f)

        TripRepair.repairMaxSpeeds(db.dao())

        assertThat(db.dao().tripById(id)!!.maxSpeedMps * 3.6f).isEqualTo(50f, within(1f))
    }

    /**
     * Only the top speed moves. A repair that quietly rewrote what a drive cost would be a second,
     * larger bug wearing the first one's clothes.
     */
    @Test
    fun `nothing but the top speed is touched`() {
        val id = drive(listOf(55f, 195f, 60f), storedMaxKmh = 195f)
        val before = db.dao().tripById(id)!!

        TripRepair.repairMaxSpeeds(db.dao())

        val after = db.dao().tripById(id)!!
        assertThat(after.distanceM).isEqualTo(before.distanceM)
        assertThat(after.durationS).isEqualTo(before.durationS)
        assertThat(after.movingS).isEqualTo(before.movingS)
        assertThat(after.startedAt).isEqualTo(before.startedAt)
        assertThat(after.endedAt).isEqualTo(before.endedAt)
        assertThat(after.maxSpeedMps).isNotEqualTo(before.maxSpeedMps)
    }

    /** With the points gone there is no evidence, and a repair must never invent any. */
    @Test
    fun `a drive whose points have gone is left alone`() {
        val id = drive(listOf(55f, 195f, 60f), storedMaxKmh = 195f)
        db.dao().deletePointsFor(id)

        val outcome = TripRepair.repairMaxSpeeds(db.dao())

        assertThat(outcome.examined).isEqualTo(0)
        assertThat(db.dao().tripById(id)!!.maxSpeedMps * 3.6f).isEqualTo(195f, within(1f))
    }

    @Test
    fun `running it twice changes nothing the second time`() {
        drive(listOf(55f, 195f, 60f), storedMaxKmh = 195f)

        val first = TripRepair.repairMaxSpeeds(db.dao())
        val second = TripRepair.repairMaxSpeeds(db.dao())

        assertThat(first.corrected).isEqualTo(1)
        assertThat(second.corrected).`as`("idempotent").isEqualTo(0)
    }

    @Test
    fun `it reports what it found`() {
        drive(listOf(55f, 195f, 60f), storedMaxKmh = 195f)
        drive(listOf(40f, 50f, 60f), storedMaxKmh = 60f)

        val outcome = TripRepair.repairMaxSpeeds(db.dao())

        assertThat(outcome.examined).isEqualTo(2)
        assertThat(outcome.corrected).isEqualTo(1)
        assertThat(outcome.worstBeforeMps * 3.6f).isEqualTo(195f, within(1f))
    }

    @Test
    fun `an empty history is not a problem`() {
        val outcome = TripRepair.repairMaxSpeeds(db.dao())
        assertThat(outcome.examined).isEqualTo(0)
        assertThat(outcome.corrected).isEqualTo(0)
    }

    // ------------------------------------------- drives a restarting recorder tore apart

    /**
     * Builds the damage as it actually happened: a car driving a straight road while the recorder
     * restarts every three seconds, so each trip holds a couple of fixes and measures almost
     * nothing of the ground it covered.
     */
    private fun shredADrive(fragments: Int = 12, metresApart: Double = 40.0): List<Long> {
        val dao = db.dao()
        val ids = mutableListOf<Long>()
        var lat = 12.9716
        var t = 1_000_000L
        repeat(fragments) {
            val id = dao.startTrip(t)
            // Two fixes per fragment, the car moving the whole time.
            repeat(2) {
                dao.appendPoint(PointEntity(0, id, t, lat, 77.5946, 13f, null, 900.0, 5f, false))
                lat += metresApart / 111_320.0
                t += 1_500L
            }
            dao.finishTrip(id, t, 0.0, 3, 3, 1f, 1.0, 0.0, 0.0, 0.0)
            ids += id
            t += 1_500L
        }
        return ids
    }

    /**
     * The point of the whole thing. The fragments each recorded nearly nothing, but their fixes
     * are real and the kilometres are in the gaps *between* them — which is exactly what deleting
     * the rows would have thrown away.
     */
    @Test
    fun `stitching a shredded drive recovers the distance the fragments lost`() {
        val dao = db.dao()
        shredADrive(fragments = 12, metresApart = 40.0)
        assertThat(dao.allTrips().sumOf { it.distanceM }).`as`("shredded").isLessThan(1.0)

        val stitched = TripRepair.stitchShreddedDrives(dao)

        assertThat(stitched.drivesRecovered).isEqualTo(1)
        assertThat(stitched.fragmentsAbsorbed).isEqualTo(11)
        val drive = dao.allTrips().single()
        assertThat(drive.distanceM)
            .`as`("23 hops of 40 m, back from the dead")
            .isGreaterThan(800.0)
    }

    /** One drive out of three hundred rows, not three hundred rows deleted. */
    @Test
    fun `the fragments become one drive rather than disappearing`() {
        val dao = db.dao()
        shredADrive(fragments = 20)

        TripRepair.stitchShreddedDrives(dao)

        assertThat(dao.allTrips()).hasSize(1)
    }

    /** Every fix survives the stitch: the route is the reason for doing this at all. */
    @Test
    fun `no point is lost or left orphaned`() {
        val dao = db.dao()
        val ids = shredADrive(fragments = 10)
        val before = ids.sumOf { dao.pointCountFor(it) }

        TripRepair.stitchShreddedDrives(dao)

        val keeper = dao.allTrips().single()
        assertThat(dao.pointCountFor(keeper.id)).isEqualTo(before)
        ids.drop(1).forEach {
            assertThat(dao.tripById(it)).`as`("absorbed row $it").isNull()
        }
    }

    /**
     * Two separate outings, each shredded, must not become one drive with a straight line across
     * the hours between them.
     */
    @Test
    fun `fragments separated by a real gap stay separate drives`() {
        val dao = db.dao()
        shredADrive(fragments = 6)
        // Hours later, another shredded outing.
        val t = 1_000_000L + 6 * 3_600_000L
        var lat = 13.10
        repeat(6) {
            val id = dao.startTrip(t + it * 3_000L)
            dao.appendPoint(PointEntity(0, id, t + it * 3_000L, lat, 77.6, 13f, null, 900.0, 5f, false))
            lat += 0.0004
            dao.finishTrip(id, t + it * 3_000L + 2_000L, 0.0, 2, 2, 1f, 1.0, 0.0, 0.0, 0.0)
        }

        val stitched = TripRepair.stitchShreddedDrives(dao)

        assertThat(stitched.drivesRecovered).isEqualTo(2)
        assertThat(dao.allTrips()).hasSize(2)
    }

    /** A genuine short errand is not a fragment of anything and is left exactly alone. */
    @Test
    fun `a real short drive is not stitched into its neighbours`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.finishTrip(id, 1_000_600L, 1_400.0, 600, 540, 12f, 9.0, 2.0, 0.0, 0.0)

        TripRepair.stitchShreddedDrives(dao)

        assertThat(dao.allTrips()).hasSize(1)
        assertThat(dao.tripById(id)!!.distanceM).isEqualTo(1_400.0)
    }

    /** A lone stub is not a shredded drive; it takes a run to make one. */
    @Test
    fun `an isolated stub is left where it is`() {
        val dao = db.dao()
        val id = dao.startTrip(1_000_000L)
        dao.finishTrip(id, 1_000_002L, 12.0, 2, 2, 1f, 1.0, 0.0, 0.0, 0.0)

        val stitched = TripRepair.stitchShreddedDrives(dao)

        assertThat(stitched.drivesRecovered).isZero()
        assertThat(dao.tripById(id)).isNotNull
    }

    /** The drive in progress has no distance written yet and must never be absorbed. */
    @Test
    fun `the open drive is never stitched into anything`() {
        val dao = db.dao()
        shredADrive(fragments = 5)
        val open = dao.startTrip(2_000_000L)

        TripRepair.stitchShreddedDrives(dao)

        assertThat(dao.tripById(open)).`as`("still driving it").isNotNull
    }
}
