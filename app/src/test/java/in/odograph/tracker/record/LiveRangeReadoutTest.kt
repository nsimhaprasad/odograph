package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.MgFixtures
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.OdographDao
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
 * The figures the driving screen shows, worked out from one frame.
 *
 * This was the middle of the telematics poll and had no test at all; the only way to see what it
 * did was to drive. Now the same arithmetic runs over an in-memory history.
 */
@RunWith(RobolectricTestRunner::class)
class LiveRangeReadoutTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()
    private val capacity = 52.9

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** A closed drive of [km] that used [kwh], started at [at]. */
    private fun closedDrive(at: Long, km: Double, kwh: Double): Long {
        val id = dao.startTrip(at)
        dao.appendPoint(PointEntity(0, id, at, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(
            PointEntity(0, id, at + 600_000L, 12.9700 + km / 111.32, 77.5900, 15f, null, 900.0, 5f, false)
        )
        TripRecovery.close(dao, id)
        dao.setChargeSummary(id, 80.0, 70.0, kwh)
        return id
    }

    private fun readout(tripId: Long, distanceM: Double, energy: Double?, soc: Double = 60.0) =
        LiveRangeReadout.compute(
            dao, tripId, distanceM, soc, MgFixtures.charge(soc = soc, rangeKm = 200.0),
            capacity, accuracy = null, energy = energy
        )

    /**
     * Below the gate the app has nothing of its own to say and quotes the car. That is not a
     * fallback for a failure; it is the honest answer while there is no history.
     */
    @Test
    fun `with too few drives the car's own range at full is quoted`() {
        val open = dao.startTrip(5_000_000L)

        val r = readout(open, 4_000.0, energy = 0.7)

        // The car's range at full: 200 km at 60% -> 333 km.
        assertThat(r.rangeAtFullKm!!).isCloseTo(200.0 / 0.6, within(1.0))
        assertThat(r.smartRangeKm).isNull()
    }

    @Test
    fun `with enough drives the range comes from what those drives cost`() {
        // Five drives at 16 kWh/100km: 25 km for 4 kWh each.
        repeat(BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE) {
            closedDrive(1_000_000L + it * 3_600_000L, km = 25.0, kwh = 4.0)
        }
        val open = dao.startTrip(90_000_000L)

        // The live drive is on the same rate, so it does not move the estimate.
        val r = readout(open, 10_000.0, energy = 1.6)

        assertThat(r.rangeAtFullKm!!).isCloseTo(capacity / 16.0 * 100.0, within(2.0))
        assertThat(r.smartRangeKm!!).isCloseTo(capacity * 0.6 / 16.0 * 100.0, within(2.0))
    }

    @Test
    fun `this drive's own mileage is what it has done over what it has used`() {
        val open = dao.startTrip(5_000_000L)

        val r = readout(open, 8_000.0, energy = 1.0)

        assertThat(r.kmPerKwh!!).isCloseTo(8.0, within(0.01))
    }

    @Test
    fun `no energy yet means no mileage and no cost, not zero`() {
        val open = dao.startTrip(5_000_000L)

        val r = readout(open, 3_000.0, energy = null)

        assertThat(r.kmPerKwh).isNull()
        assertThat(r.tripCostInr).isNull()
    }

    /**
     * Priced as the archive will price it: from the fills that preceded the drive, not from a
     * flat tariff. The same function bills both, so the two cannot disagree.
     */
    @Test
    fun `the running cost is billed from the fills before the drive`() {
        // One fill before the drive: 10 kWh for 80 rupees. Classified, because a session with no
        // kind is one the ledger has not finished with, and fillsBefore rightly ignores it.
        dao.insertChargeEvent(
            ChargeEventEntity(
                startTime = 1_000_000L, endTime = 2_000_000L, startSoc = 50.0, endSoc = 70.0,
                energyKwh = 10.0, costInr = 80.0, kind = 0
            )
        )
        val open = dao.startTrip(5_000_000L)

        val r = readout(open, 10_000.0, energy = 2.0)

        assertThat(r.tripCostInr!!).isCloseTo(16.0, within(0.01))
    }

    @Test
    fun `lifetime energy counts every instrumented drive`() {
        closedDrive(1_000_000L, km = 25.0, kwh = 4.0)
        closedDrive(5_000_000L, km = 25.0, kwh = 3.5)
        val open = dao.startTrip(9_000_000L)

        val r = readout(open, 1_000.0, energy = null)

        assertThat(r.totalKwh).isCloseTo(7.5, within(0.01))
    }
}
