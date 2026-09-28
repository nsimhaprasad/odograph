package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.PointEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Named places capture a wide radius, and swallow the fragments a neighbourhood produced.
 *
 * ~0.009° of latitude is about 1 km near Bengaluru, which the tests use to place points a known
 * distance from an anchor.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceRadiusAndMergeTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()
    private val home = 12.9716 to 77.5946
    private fun km(n: Double) = n * 0.009   // degrees latitude ≈ n km

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    // ------------------------------------------------------------- the wide capture

    @Test
    fun `a drive ending a kilometre from a named place still resolves to it`() {
        val id = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 5, label = "Home"))
        val resolver = PlaceResolver(dao, radiusM = 150.0, labeledRadiusM = 1200.0)

        val got = resolver.resolve(home.first + km(1.0), home.second)   // ~1 km away

        assertThat(got).isEqualTo(id)
        assertThat(dao.allPlaces()).hasSize(1)                          // no new fragment created
    }

    @Test
    fun `an unnamed place only captures within the tight radius`() {
        val id = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 5)) // no label
        val resolver = PlaceResolver(dao, radiusM = 150.0, labeledRadiusM = 1200.0)

        val got = resolver.resolve(home.first + km(1.0), home.second)   // 1 km — beyond 150 m

        assertThat(got).`as`("a fresh auto-place, not the far unnamed one").isNotEqualTo(id)
        assertThat(dao.allPlaces()).hasSize(2)
    }

    @Test
    fun `beyond the wide radius a named place does not capture`() {
        val id = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 5, label = "Home"))
        val resolver = PlaceResolver(dao, radiusM = 150.0, labeledRadiusM = 1200.0)

        val got = resolver.resolve(home.first + km(3.0), home.second)   // 3 km away

        assertThat(got).isNotEqualTo(id)
    }

    @Test
    fun `a named place keeps its anchor - the centroid is not averaged away`() {
        val id = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 5, label = "Home"))
        PlaceResolver(dao, labeledRadiusM = 1200.0).resolve(home.first + km(1.0), home.second)

        val p = dao.placeById(id)!!
        assertThat(p.lat).isEqualTo(home.first)                        // unmoved
        assertThat(p.visits).isEqualTo(6)                              // but counted
    }

    // ------------------------------------------------------------- absorbing fragments

    @Test
    fun `naming a place folds its unnamed neighbours into it`() {
        val homeId = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 40, label = "Home"))
        val frag1 = dao.insertPlace(PlaceEntity(lat = home.first + km(0.3), lon = home.second, visits = 3))
        val frag2 = dao.insertPlace(PlaceEntity(lat = home.first + km(0.8), lon = home.second, visits = 2))
        val faraway = dao.insertPlace(PlaceEntity(lat = home.first + km(5.0), lon = home.second, visits = 7))
        // A trip ending at a fragment.
        val trip = dao.startTrip(1_000L)
        dao.appendPoint(PointEntity(0, trip, 1_000L, home.first, home.second, 5f, null, 900.0, 5f, false))
        dao.finishTrip(trip, 2_000L, 5000.0, 600, 500, 10f, 8.0, 5.0, 0.0, 0.0)
        dao.setTripPlaces(trip, startId = faraway, endId = frag1)

        val folded = PlaceMerge.absorbNearby(dao, homeId, radiusM = 1200.0)

        assertThat(folded).isEqualTo(2)                                // frag1, frag2
        assertThat(dao.placeById(frag1)).isNull()
        assertThat(dao.placeById(frag2)).isNull()
        assertThat(dao.placeById(faraway)).`as`("outside the radius, untouched").isNotNull()
        // the trip that ended at frag1 now ends at Home
        assertThat(dao.tripById(trip)!!.endPlaceId).isEqualTo(homeId)
        assertThat(dao.tripById(trip)!!.startPlaceId).isEqualTo(faraway)
        // visits rolled up
        assertThat(dao.placeById(homeId)!!.visits).isEqualTo(45)       // 40 + 3 + 2
    }

    @Test
    fun `a named neighbour is never absorbed`() {
        val homeId = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 40, label = "Home"))
        val office = dao.insertPlace(PlaceEntity(lat = home.first + km(0.5), lon = home.second, visits = 20, label = "Office"))

        val folded = PlaceMerge.absorbNearby(dao, homeId, radiusM = 1200.0)

        assertThat(folded).isZero()
        assertThat(dao.placeById(office)).isNotNull()
    }

    @Test
    fun `absorb is idempotent - a second run folds nothing`() {
        val homeId = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 40, label = "Home"))
        dao.insertPlace(PlaceEntity(lat = home.first + km(0.3), lon = home.second, visits = 3))
        PlaceMerge.absorbNearby(dao, homeId, radiusM = 1200.0)

        assertThat(PlaceMerge.absorbNearby(dao, homeId, radiusM = 1200.0)).isZero()
    }

    @Test
    fun `an unnamed anchor absorbs nothing`() {
        val id = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 5))  // no label
        dao.insertPlace(PlaceEntity(lat = home.first + km(0.3), lon = home.second, visits = 3))

        assertThat(PlaceMerge.absorbNearby(dao, id, radiusM = 1200.0)).isZero()
    }

    /**
     * Two named places close together — a house and a friend's house down the road — each keep
     * their own side. A fragment between them goes to whichever it is nearer to, whichever place
     * was named first.
     */
    @Test
    fun `two nearby named places split the fragments between them by nearness`() {
        // ~1.5 km apart on the same road.
        val houseA = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 10, label = "House A"))
        val houseB = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second + km(1.5) / 0.974, visits = 10, label = "House B"))
        // A fragment 0.4 km from A (so ~1.1 km from B).
        val nearA = dao.insertPlace(PlaceEntity(lat = home.first + km(0.4), lon = home.second, visits = 2))
        // A fragment 0.4 km from B.
        val nearB = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second + (km(1.5) - km(0.4)) / 0.974, visits = 2))

        // Absorb from A first, then B — order must not decide the outcome.
        PlaceMerge.absorbNearby(dao, houseA, radiusM = 1200.0)
        PlaceMerge.absorbNearby(dao, houseB, radiusM = 1200.0)

        // nearA folded into A, nearB into B — neither crossed over.
        assertThat(dao.placeById(nearA)).isNull()
        assertThat(dao.placeById(nearB)).isNull()
        assertThat(dao.placeById(houseA)!!.visits).isEqualTo(12)   // 10 + nearA's 2
        assertThat(dao.placeById(houseB)!!.visits).isEqualTo(12)   // 10 + nearB's 2
    }

    /** Order independence: absorbing B first gives the same split. */
    @Test
    fun `the split does not depend on which named place absorbs first`() {
        val houseA = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second, visits = 10, label = "A"))
        val houseB = dao.insertPlace(PlaceEntity(lat = home.first, lon = home.second + km(1.5) / 0.974, visits = 10, label = "B"))
        val nearA = dao.insertPlace(PlaceEntity(lat = home.first + km(0.4), lon = home.second, visits = 2))

        PlaceMerge.absorbNearby(dao, houseB, radiusM = 1200.0)   // B first
        assertThat(dao.placeById(nearA)).`as`("B must not claim A's fragment").isNotNull()

        PlaceMerge.absorbNearby(dao, houseA, radiusM = 1200.0)
        assertThat(dao.placeById(nearA)).isNull()
        assertThat(dao.placeById(houseA)!!.visits).isEqualTo(12)
    }
}
