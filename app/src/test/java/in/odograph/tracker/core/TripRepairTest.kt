package `in`.odograph.tracker.core

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
}
