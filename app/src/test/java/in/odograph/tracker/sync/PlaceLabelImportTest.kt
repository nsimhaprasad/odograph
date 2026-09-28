package `in`.odograph.tracker.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PlaceEntity
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Names typed into the sheet, flowing back to the app.
 *
 * The point of the reverse sync: a driver types "Office" against a place in the Places tab, and
 * the box adopts it. The sheet is explicit input, so it wins over whatever the app had; a blank
 * cell is left alone, because a driver empties a field far less often than they leave one empty.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceLabelImportTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun body(vararg pairs: Pair<Long, String?>): JSONObject {
        val arr = pairs.joinToString(",") { (id, label) ->
            """{"id":$id,"label":${label?.let { "\"$it\"" } ?: "\"\""}}"""
        }
        return JSONObject("""{"placeLabels":[$arr]}""")
    }

    @Test
    fun `a sheet label names an unnamed place`() {
        val id = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59, visits = 3))

        val n = SheetsSync.applyPlaceLabels(dao, body(id to "Office"))

        assertThat(n).isEqualTo(1)
        assertThat(dao.placeById(id)!!.label).isEqualTo("Office")
    }

    @Test
    fun `the sheet wins over an existing app label`() {
        val id = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59, visits = 1, label = "old"))

        SheetsSync.applyPlaceLabels(dao, body(id to "Work"))

        assertThat(dao.placeById(id)!!.label).isEqualTo("Work")
    }

    @Test
    fun `a blank sheet cell leaves the app label alone`() {
        val id = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59, visits = 1, label = "Home"))

        val n = SheetsSync.applyPlaceLabels(dao, body(id to null))

        assertThat(n).isZero()
        assertThat(dao.placeById(id)!!.label).isEqualTo("Home")
    }

    @Test
    fun `an unchanged label is not rewritten`() {
        val id = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59, visits = 1, label = "Office"))

        assertThat(SheetsSync.applyPlaceLabels(dao, body(id to "Office"))).isZero()
    }

    @Test
    fun `a label for a place that does not exist is ignored`() {

        assertThat(SheetsSync.applyPlaceLabels(dao, body(9999L to "Nowhere"))).isZero()
    }

    @Test
    fun `no placeLabels field is a no-op`() {
        dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59, visits = 1))

        assertThat(SheetsSync.applyPlaceLabels(dao, JSONObject("""{"capacityKwh":51.6}"""))).isZero()
    }
}
