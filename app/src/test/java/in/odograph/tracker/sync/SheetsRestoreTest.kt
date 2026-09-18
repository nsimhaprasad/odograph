package `in`.odograph.tracker.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading the backup sheet back into a database.
 *
 * The half of a backup that decides whether the other half was worth doing. Everything here leans
 * on the sheet being a document a person can open and edit — which is most of its value over a
 * binary snapshot, and the reason a restore has to assume a cell has been cleared by accident, a
 * number turned into text by a spreadsheet being helpful, and a column renamed by a tidy mind.
 */
@RunWith(RobolectricTestRunner::class)
class SheetsRestoreTest {

    private lateinit var db: OdographDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun backup(
        schema: Int = SheetsJson.SCHEMA_VERSION,
        places: String = "",
        trips: String = "",
        points: String = "",
        battery: String = "",
        charges: String = "",
        telemetry: String = ""
    ) = """
        {"kind":"odograph-backup","schema":$schema,
         "places":[$places],"trips":[$trips],"points":[$points],
         "battery":[$battery],"charges":[$charges],"telemetry":[$telemetry]}
    """.trimIndent()

    private val homePlace = """{"id":1,"lat":12.9716,"lon":77.5946,"visits":47,"label":"Home"}"""
    private val oneTrip = """{"id":5,"start":1000,"end":2000,"km":18.4,"duration_s":1200,
        "moving_s":1000,"max_kmh":72,"energy_kwh":2.9,"cost_inr":23.4,"climb_m":40}""".trimIndent()

    // ---------------------------------------------------------------- a good backup

    @Test
    fun `a backup restores the history it carries`() {
        val snapshot = SheetsRestore.parse(
            backup(places = homePlace, trips = oneTrip)
        ).getOrThrow()

        val result = SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        assertThat(result.ok).isTrue()
        assertThat(db.dao().allTrips()).hasSize(1)
        assertThat(db.dao().allPlaces().first().displayName).isEqualTo("Home")
    }

    /** The sheet stores kilometres and km/h because a person reads it; the app stores neither. */
    @Test
    fun `the sheet's human units are converted back`() {
        val snapshot = SheetsRestore.parse(backup(trips = oneTrip)).getOrThrow()
        SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        val trip = db.dao().allTrips().first()
        assertThat(trip.distanceM).isEqualTo(18_400.0, within(1.0))
        assertThat(trip.maxSpeedMps * 3.6).isEqualTo(72.0, within(0.1))
    }

    @Test
    fun `places are restored before the trips that reference them`() {
        val tripAtHome = """{"id":5,"start":1000,"end":2000,"km":5.0}"""
        val snapshot = SheetsRestore.parse(
            backup(places = homePlace, trips = tripAtHome)
        ).getOrThrow()

        assertThat(SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }.ok).isTrue()
        assertThat(db.dao().placeById(1)).isNotNull
    }

    @Test
    fun `battery frames come back with the conditions they were taken in`() {
        val frame = """{"id":3,"trip_id":-1,"t_ms":5000,"soc_pct":96,"charging":true,
            "battery_kwh":50.8,"exterior_temp_c":34}""".trimIndent()
        val snapshot = SheetsRestore.parse(backup(battery = frame)).getOrThrow()
        SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        val restored = db.dao().highSocBattery(90.0).first()
        assertThat(restored.batteryEnergyKwh).isEqualTo(50.8, within(0.01))
        assertThat(restored.exteriorTempC).isEqualTo(34)
        assertThat(restored.charging).isTrue()
    }

    // ---------------------------------------------------------------- surviving a spreadsheet

    /** Sheets hands back a string where a number was written, and the restore must not care. */
    @Test
    fun `a number that came back as text is still a number`() {
        val asText = """{"id":5,"start":"1000","end":"2000","km":"18.4","max_kmh":"72"}"""
        val snapshot = SheetsRestore.parse(backup(trips = asText)).getOrThrow()
        SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        assertThat(db.dao().allTrips().first().distanceM).isEqualTo(18_400.0, within(1.0))
    }

    /**
     * A blank cell means "not measured" and must not become zero. A restored car that was measured
     * when it was not is worse than one with an admitted gap.
     */
    @Test
    fun `an empty cell stays unknown rather than becoming zero`() {
        val noReading = """{"id":3,"trip_id":-1,"t_ms":5000,"soc_pct":null,"battery_kwh":null}"""
        val snapshot = SheetsRestore.parse(backup(battery = noReading)).getOrThrow()
        SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        val restored = db.dao().batteryRangeFor(-1).first()
        assertThat(restored.socPercent).isNull()
        assertThat(restored.batteryEnergyKwh).isNull()
    }

    @Test
    fun `a boolean written as yes or no is understood`() {
        val yes = """{"id":1,"trip_id":-1,"t_ms":1,"soc_pct":95,"battery_kwh":50,"charging":"yes"}"""
        val snapshot = SheetsRestore.parse(backup(battery = yes)).getOrThrow()
        SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }
        assertThat(db.dao().highSocBattery(90.0).first().charging).isTrue()
    }

    // ---------------------------------------------------------------- refusing rather than guessing

    /**
     * Refusing is the useful behaviour far more often than salvaging. A half-understood restore
     * leaves a database that looks populated and is quietly wrong, which is worse than the empty
     * one it replaced because nothing afterwards suggests looking at it.
     */
    @Test
    fun `something that is not a backup is refused`() {
        assertThat(SheetsRestore.parse("""{"hello":"world"}""").isFailure).isTrue()
        assertThat(SheetsRestore.parse("not json at all").isFailure).isTrue()
    }

    @Test
    fun `a schema this build does not understand is refused`() {
        val future = backup(schema = 99, trips = oneTrip)
        val failure = SheetsRestore.parse(future).exceptionOrNull()
        assertThat(failure).isNotNull
        assertThat(failure!!.message).contains("99")
    }

    @Test
    fun `an empty backup changes nothing`() {
        db.dao().startTrip(999L)
        val snapshot = SheetsRestore.parse(backup()).getOrThrow()

        val result = SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        assertThat(result.ok).isFalse()
        assertThat(db.dao().allTrips())
            .`as`("an empty backup must not wipe a working history")
            .hasSize(1)
    }

    /**
     * Replace, not merge, and stated plainly. Merging two histories that share row ids and
     * reference each other by them produces a third belonging to neither — a trip pointing at
     * someone else's place, a charge attached to a drive it did not happen on.
     */
    @Test
    fun `a restore replaces what was there rather than merging with it`() {
        db.dao().startTrip(999L)
        val snapshot = SheetsRestore.parse(backup(trips = oneTrip)).getOrThrow()

        SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }

        val trips = db.dao().allTrips()
        assertThat(trips).hasSize(1)
        assertThat(trips.first().startedAt).isEqualTo(1000L)
    }

    // ---------------------------------------------------------------- reaching the export

    /**
     * Apps Script /exec URLs are routinely pasted with a query already on them — a deployment id,
     * a trailing parameter from the browser. Assuming a bare URL turns the export request into a
     * malformed one and the restore reports a broken sheet.
     */
    @Test
    fun `the export parameter is appended to whatever query the link already has`() {
        assertThat(SheetsSync.exportUrl("https://script.google.com/a/macros/x/exec"))
            .isEqualTo("https://script.google.com/a/macros/x/exec?export=all")
        assertThat(SheetsSync.exportUrl("https://script.google.com/macros/s/AK/exec?v=3"))
            .isEqualTo("https://script.google.com/macros/s/AK/exec?v=3&export=all")
    }

    @Test
    fun `the result says what it restored`() {
        val snapshot = SheetsRestore.parse(
            backup(places = homePlace, trips = oneTrip)
        ).getOrThrow()
        val result = SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }
        assertThat(result.message).contains("1 drives")
        assertThat(result.message).contains("1 places")
    }
}
