package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.BatteryEntity
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
import java.util.TimeZone

/**
 * Revisiting drives that finished with no energy figure.
 *
 * The distinction under test: backfill is measurement and lands in `energyKwh`; estimation is a
 * guess and lands somewhere the efficiency model cannot read. A test that let the two blur would
 * be a test that let the model learn from itself.
 */
@RunWith(RobolectricTestRunner::class)
class EnergySweepTest {

    private lateinit var db: OdographDb
    private val dao: OdographDao get() = db.dao()
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** A closed drive of [km] starting at [at], with no battery frames of its own. */
    private fun unlinkedDrive(at: Long, km: Double): Long {
        val id = dao.startTrip(at)
        dao.appendPoint(PointEntity(0, id, at, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(
            PointEntity(0, id, at + 600_000L, 12.9700 + km / 111.32, 77.5900, 15f, null, 900.0, 5f, false)
        )
        TripRecovery.close(dao, id)
        return id
    }

    /** A measured drive, so the model has something to learn from. */
    private fun measuredDrive(at: Long, km: Double, kwh: Double): Long {
        val id = unlinkedDrive(at, km)
        dao.setChargeSummary(id, 80.0, 70.0, kwh)
        return id
    }

    /** A parked-bucket frame carrying the car's running counters at [t]. */
    private fun counters(t: Long, kwhSinceCharge: Double, kmSinceCharge: Double) {
        dao.insertBattery(
            BatteryEntity(
                tripId = -1, t = t, socPercent = 70.0, charging = false,
                powerUsageSinceLastChargeKwh = kwhSinceCharge,
                distanceSinceLastChargeKm = kmSinceCharge
            )
        )
    }

    // ------------------------------------------------------------------ backfill

    /**
     * The case this exists for. A drive with no frames inside it, bracketed by a reading from
     * before it set off and one from after it arrived. The car's counters ran the whole time.
     */
    @Test
    fun `a drive bracketed by counter readings gets a measured figure`() {
        val start = 5_000_000L
        counters(start - 60_000L, kwhSinceCharge = 10.0, kmSinceCharge = 100.0)
        val id = unlinkedDrive(start, km = 25.0)
        counters(start + 700_000L, kwhSinceCharge = 14.0, kmSinceCharge = 125.0)

        val result = EnergySweep.run(dao, zone)

        assertThat(result.backfilled).isEqualTo(1)
        val trip = dao.tripById(id)!!
        assertThat(trip.energyKwh!!).isCloseTo(4.0, within(0.01))
        assertThat(trip.energySource).isEqualTo("backfill")
        assertThat(trip.estimatedEnergyKwh).`as`("measured, so nothing to estimate").isNull()
    }

    /** Once measured, a drive leaves the candidate list and is not reworked on the next sweep. */
    @Test
    fun `a backfilled drive is not visited again`() {
        val start = 5_000_000L
        counters(start - 60_000L, 10.0, 100.0)
        unlinkedDrive(start, km = 25.0)
        counters(start + 700_000L, 14.0, 125.0)
        EnergySweep.run(dao, zone)

        val again = EnergySweep.run(dao, zone)

        assertThat(again.backfilled).isZero()
        assertThat(again.estimated).isZero()
    }

    /**
     * The check that makes backfill safe. The counters moved 60 km while this drive covered 25,
     * so the bracket spans more than it and must be refused — that energy belongs to something
     * else too.
     */
    @Test
    fun `a bracket that spans more than the drive is refused`() {
        val start = 5_000_000L
        counters(start - 60_000L, 10.0, 100.0)
        val id = unlinkedDrive(start, km = 25.0)
        counters(start + 700_000L, 19.0, 160.0)

        EnergySweep.run(dao, zone)

        assertThat(dao.tripById(id)!!.energyKwh).isNull()
    }

    // ---------------------------------------------------------------- estimation

    @Test
    fun `with no bracket and enough history, the drive gets an indicative figure`() {
        // Five measured drives at 16 kWh/100km.
        repeat(BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE) {
            measuredDrive(1_000_000L + it * 3_600_000L, km = 25.0, kwh = 4.0)
        }
        val id = unlinkedDrive(50_000_000L, km = 40.0)

        val result = EnergySweep.run(dao, zone)

        assertThat(result.estimated).isEqualTo(1)
        val trip = dao.tripById(id)!!
        assertThat(trip.estimatedEnergyKwh!!).isCloseTo(6.4, within(0.1))
        assertThat(trip.energyKwh).`as`("a guess never lands in the measured column").isNull()
        assertThat(trip.energySource).isNull()
    }

    /**
     * The whole reason the two columns exist. After estimating, the learning set must be exactly
     * the measured drives — the estimate must not have joined it.
     */
    @Test
    fun `an estimate never enters the set the model learns from`() {
        repeat(BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE) {
            measuredDrive(1_000_000L + it * 3_600_000L, km = 25.0, kwh = 4.0)
        }
        unlinkedDrive(50_000_000L, km = 40.0)

        EnergySweep.run(dao, zone)

        assertThat(dao.tripEnergies()).hasSize(BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE)
    }

    /** No history, no estimate. A default would be indexed and averaged like a measurement. */
    @Test
    fun `with nothing learned yet, an unbracketed drive is left alone`() {
        val id = unlinkedDrive(5_000_000L, km = 40.0)

        val result = EnergySweep.run(dao, zone)

        assertThat(result.estimated).isZero()
        assertThat(result.untouched).isEqualTo(1)
        assertThat(dao.tripById(id)!!.estimatedEnergyKwh).isNull()
    }

    /**
     * Measurement outranks the guess. A drive estimated on one sweep and bracketed by the next
     * is upgraded, and the estimate it carried is left behind as the record of what was shown.
     */
    @Test
    fun `an estimated drive is upgraded to measured when a bracket appears`() {
        repeat(BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE) {
            measuredDrive(1_000_000L + it * 3_600_000L, km = 25.0, kwh = 4.0)
        }
        val start = 50_000_000L
        counters(start - 60_000L, 10.0, 100.0)
        val id = unlinkedDrive(start, km = 25.0)
        EnergySweep.run(dao, zone)
        assertThat(dao.tripById(id)!!.energyKwh).isNull()

        counters(start + 700_000L, 14.0, 125.0)
        val result = EnergySweep.run(dao, zone)

        assertThat(result.backfilled).isEqualTo(1)
        assertThat(dao.tripById(id)!!.energyKwh!!).isCloseTo(4.0, within(0.01))
        assertThat(dao.tripById(id)!!.energySource).isEqualTo("backfill")
    }

    @Test
    fun `an empty history is not an error`() {
        val result = EnergySweep.run(dao, zone)

        assertThat(result).isEqualTo(EnergySweep.Result(0, 0, 0))
    }
}
