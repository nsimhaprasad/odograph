package `in`.odograph.tracker.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Removing a fill that was booked twice.
 *
 * Written against the two rows on the real box: session 8, watched, 65→100, and session 9,
 * reconstructed ten seconds earlier from the charge level, 65→100, ending at the same instant.
 */
@RunWith(RobolectricTestRunner::class)
class TripRepairDuplicateFillTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()

    private val pluggedIn = 1_758_628_757_749L      // 23 Sep 11:59:17Z
    private val full = 1_758_671_066_631L           // 23 Sep 23:44:26Z

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun fill(start: Long, end: Long, from: Double, to: Double, reconstructed: Boolean) =
        dao.insertChargeEvent(
            ChargeEventEntity(
                startTime = start, endTime = end, startSoc = from, endSoc = to,
                energyKwh = 18.06, kind = 0, reconstructed = reconstructed
            )
        )

    @Test
    fun `the reconstruction that duplicates a watched fill is removed, the watched one kept`() {
        val watched = fill(pluggedIn, full, 65.0, 100.0, reconstructed = false)
        val duplicate = fill(pluggedIn - 10_000L, full, 65.0, 100.0, reconstructed = true)

        val r = TripRepair.repairDuplicateReconstructions(dao)

        assertThat(r).isEqualTo(TripRepair.Deduped(examined = 1, removed = 1))
        val left = dao.allChargeEvents().map { it.id }
        assertThat(left).containsExactly(watched)
        assertThat(left).doesNotContain(duplicate)
    }

    /** A reconstruction with no watched twin is a real fill nobody saw, and stays. */
    @Test
    fun `a lone reconstruction is kept`() {
        fill(pluggedIn, full, 39.0, 83.0, reconstructed = true)

        val r = TripRepair.repairDuplicateReconstructions(dao)

        assertThat(r.removed).isZero()
        assertThat(dao.allChargeEvents()).hasSize(1)
    }

    /** Two watched sessions are never each other's duplicates; only inferences are removed. */
    @Test
    fun `watched sessions are never removed`() {
        fill(pluggedIn, full, 65.0, 100.0, reconstructed = false)
        fill(pluggedIn - 10_000L, full, 65.0, 100.0, reconstructed = false)

        val r = TripRepair.repairDuplicateReconstructions(dao)

        assertThat(r.removed).isZero()
        assertThat(dao.allChargeEvents()).hasSize(2)
    }

    /** Ending a minute apart at a different level is a different fill. */
    @Test
    fun `a reconstruction ending at a different level is a different fill`() {
        fill(pluggedIn, full, 65.0, 100.0, reconstructed = false)
        fill(pluggedIn - 10_000L, full - 5 * 60_000L, 65.0, 90.0, reconstructed = true)

        assertThat(TripRepair.repairDuplicateReconstructions(dao).removed).isZero()
    }
}
