package `in`.odograph.tracker.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TRIP_PAGE_SIZE
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the screens ask the database for once there is a lot of it.
 *
 * Rendering was never the worry — a LazyColumn only composes what fits. The loading was: the trip
 * list read every drive ever made to show the dozen on the glass, the rolling efficiency figure
 * read every instrumented drive to average ten of them, and counting a drive's points loaded all
 * of them. None of it is noticeable at a hundred drives and all of it is at ten thousand.
 *
 * These assert the shape of the work rather than a stopwatch reading: a query that returns a page
 * stays a page whatever the table holds, which is the property that actually protects the app.
 */
@RunWith(RobolectricTestRunner::class)
class ScaleTest {

    private lateinit var db: OdographDb

    /** Roughly four years of driving at a dozen trips a week. */
    private val manyTrips = 2_500

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()

        val dao = db.dao()
        val start = 1_600_000_000_000L
        repeat(manyTrips) { i ->
            val t0 = start + i * 3_600_000L
            val id = dao.startTrip(t0)
            dao.finishTrip(id, t0 + 1_200_000L, 18_400.0, 1_200, 1_000, 25f, 15.3, 3.4, 40.0, 30.0)
            dao.setChargeSummary(id, 80.0, 74.0, 2.9)
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the trip list reads a page, not the whole history`() {
        val page = db.dao().tripsPage(TRIP_PAGE_SIZE, 0)
        assertThat(page).hasSize(TRIP_PAGE_SIZE)
        assertThat(db.dao().tripCount()).isEqualTo(manyTrips)
    }

    @Test
    fun `paging walks the whole history without ever holding it`() {
        var offset = 0
        var seen = 0
        while (true) {
            val page = db.dao().tripsPage(TRIP_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            assertThat(page.size).`as`("no page exceeds its size").isLessThanOrEqualTo(TRIP_PAGE_SIZE)
            seen += page.size
            offset += TRIP_PAGE_SIZE
        }
        assertThat(seen).isEqualTo(manyTrips)
    }

    @Test
    fun `pages do not overlap and stay newest-first`() {
        val first = db.dao().tripsPage(TRIP_PAGE_SIZE, 0)
        val second = db.dao().tripsPage(TRIP_PAGE_SIZE, TRIP_PAGE_SIZE)
        assertThat(first.map { it.id }.intersect(second.map { it.id }.toSet())).isEmpty()
        assertThat(first.first().startedAt).isGreaterThan(first.last().startedAt)
        assertThat(first.last().startedAt).isGreaterThan(second.first().startedAt)
    }

    /**
     * The rolling figure averages ten drives. Reading every instrumented drive to do it is the
     * kind of cost that is invisible for a year and then is not.
     */
    @Test
    fun `the rolling efficiency figure reads tens of drives, not thousands`() {
        val recent = db.dao().tripEnergies()
        assertThat(recent.size)
            .`as`("bounded regardless of how much history exists")
            .isLessThanOrEqualTo(50)
        assertThat(db.dao().allTripEnergies().size)
            .`as`("the whole history is still reachable when something genuinely needs it")
            .isEqualTo(manyTrips)
    }

    @Test
    fun `counting a drive's points does not read them`() {
        val dao = db.dao()
        val id = dao.startTrip(1L)
        repeat(3_000) { i ->
            dao.appendPoint(PointEntity(0, id, i.toLong(), 12.97, 77.59, 10f, null, 900.0, 5f, false))
        }
        assertThat(dao.pointCountFor(id)).isEqualTo(3_000)
    }
}
