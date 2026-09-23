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
 * Forgetting energies the car cannot have produced.
 *
 * Written against the two real cases on the box — trip 435 at 138 km/kWh and trip 426 at 14 —
 * both stored before the ceiling existed and both unreachable by anything that runs at close.
 */
@RunWith(RobolectricTestRunner::class)
class TripRepairEnergyTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** A closed drive of [km] carrying [kwh], as the old rule would have stored it. */
    private fun closed(km: Double, kwh: Double?, at: Long = 1_000_000L): Long {
        val id = dao.startTrip(at)
        dao.appendPoint(PointEntity(0, id, at, 12.97, 77.59, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, id, at + 600_000L, 12.97 + km / 111.32, 77.59, 12f, null, 900.0, 5f, false))
        dao.finishTrip(id, at + 600_000L, km * 1000.0, 600, 550, 12f, 8.0, 5.0, 0.0, 0.0)
        if (kwh != null) {
            dao.setChargeSummary(id, 70.0, 69.0, kwh)
            dao.setTripCost(id, kwh * 8.0)
        }
        return id
    }

    @Test
    fun `trip 435 - a counter that ticked once over fourteen kilometres - is forgotten`() {
        val id = closed(km = 13.8, kwh = 0.1)

        val r = TripRepair.repairImplausibleEnergies(dao)

        assertThat(r).isEqualTo(TripRepair.Cleared(examined = 1, cleared = 1))
        val t = dao.tripById(id)!!
        assertThat(t.energyKwh).isNull()
        assertThat(t.costInr).`as`("nothing can be billed for an energy nobody measured").isNull()
        assertThat(t.socStart).`as`("the readings it was derived from are kept").isEqualTo(70.0)
    }

    @Test
    fun `trip 426 - one percent of charge across a short errand - is forgotten`() {
        val id = closed(km = 7.4, kwh = 0.53)

        TripRepair.repairImplausibleEnergies(dao)

        assertThat(dao.tripById(id)!!.energyKwh).isNull()
    }

    @Test
    fun `an ordinary drive is left exactly as it was`() {
        val id = closed(km = 25.0, kwh = 4.0)

        val r = TripRepair.repairImplausibleEnergies(dao)

        assertThat(r.cleared).isZero()
        assertThat(dao.tripById(id)!!.energyKwh).isEqualTo(4.0)
        assertThat(dao.tripById(id)!!.costInr).isEqualTo(32.0)
    }

    /** Regeneration on a descent is a measurement, and the one that keeps a drive from being billed. */
    @Test
    fun `a drive that put energy back is a measurement, not an error`() {
        val id = closed(km = 12.0, kwh = -0.4)

        TripRepair.repairImplausibleEnergies(dao)

        assertThat(dao.tripById(id)!!.energyKwh).isEqualTo(-0.4)
    }

    /** A drive with no figure has nothing to forget, and must not be counted as examined. */
    @Test
    fun `drives with no energy are not examined`() {
        closed(km = 9.0, kwh = null)

        assertThat(TripRepair.repairImplausibleEnergies(dao)).isEqualTo(TripRepair.Cleared(0, 0))
    }

    /** Once forgotten, the drive is exactly what the sweep looks for. */
    @Test
    fun `a forgotten drive becomes a candidate for the sweep`() {
        closed(km = 13.8, kwh = 0.1)
        assertThat(dao.tripsMissingEnergy()).isEmpty()

        TripRepair.repairImplausibleEnergies(dao)

        assertThat(dao.tripsMissingEnergy()).hasSize(1)
    }
}
