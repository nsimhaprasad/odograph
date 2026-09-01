package `in`.odograph.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OdographDbTest {

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
    fun `a started trip stays open until it is finished`() {
        val id = db.dao().startTrip(1_700_000_000_000L)
        assertThat(db.dao().openTrip()?.id).isEqualTo(id)

        db.dao().finishTrip(id, 1_700_000_060_000L, 1000.0, 60, 55, 20f, 18.2, 5.0)
        assertThat(db.dao().openTrip()).isNull()
    }

    @Test
    fun `points come back in time order regardless of insert order`() {
        val id = db.dao().startTrip(0L)
        db.dao().appendPoint(PointEntity(0, id, 2000, 12.98, 77.60, 10f, 90f, 900.0, 5f, false))
        db.dao().appendPoint(PointEntity(0, id, 1000, 12.97, 77.59, 8f, 90f, 899.0, 5f, false))

        assertThat(db.dao().pointsFor(id).map { it.t }).containsExactly(1000L, 2000L)
    }

    @Test
    fun `points belong only to their own trip`() {
        val a = db.dao().startTrip(0L)
        val b = db.dao().startTrip(1L)
        db.dao().appendPoint(PointEntity(0, a, 100, 12.97, 77.59, 0f, null, null, 5f, false))
        db.dao().appendPoint(PointEntity(0, b, 200, 12.98, 77.60, 0f, null, null, 5f, false))
        db.dao().appendPoint(PointEntity(0, b, 300, 12.99, 77.61, 0f, null, null, 5f, false))

        assertThat(db.dao().pointCount(a)).isEqualTo(1)
        assertThat(db.dao().pointCount(b)).isEqualTo(2)
    }

    @Test
    fun `finishing a trip stores the computed totals`() {
        val id = db.dao().startTrip(0L)
        db.dao().finishTrip(id, 60_000, 1500.5, 60, 50, 22.2f, 30.0, 4.4)

        val t = db.dao().tripById(id)!!
        assertThat(t.distanceM).isEqualTo(1500.5)
        assertThat(t.movingS).isEqualTo(50L)
        assertThat(t.maxSpeedMps).isEqualTo(22.2f)
    }
}
