package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Charge sessions are a stream-of-frames problem; these tests walk the stream one frame at a time
 * and assert what the app would have persisted after each.
 */
@RunWith(RobolectricTestRunner::class)
class ChargeLedgerTest {

    private lateinit var db: OdographDb
    private val min = 60_000L
    private val gap = BatteryMath.CHARGE_SESSION_GAP_MS
    private val homeRate = 8.0
    private val outsideRate = 25.0

    private fun ledger(home: Double = homeRate, outside: Double = outsideRate) =
        ChargeLedger(db.dao(), BatteryMath.DEFAULT_CAPACITY_KWH, home, outside)

    private fun sessions() = db.dao().allChargeEvents()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a plain full charge overnight lands as one slow home session at the home rate`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 30.0, 7.4, 0L)
        led.observe(true, 45.0, 7.4, 120 * min)
        led.observe(true, 60.0, 7.4, 240 * min)
        // Next frame says the plug pulled the session to an end.
        led.observe(false, 60.0, 0.0, 241 * min)

        val session = sessions().single()
        assertThat(session.startSoc).isEqualTo(30.0)
        assertThat(session.endSoc).isEqualTo(60.0)
        assertThat(session.energyKwh).isEqualTo(BatteryMath.round2(BatteryMath.rechargeEnergyKwh(30.0, 60.0, BatteryMath.DEFAULT_CAPACITY_KWH)))
        assertThat(session.kind).isEqualTo(BatteryMath.ChargeKind.SLOW.ordinal)
        // SOC swing × home rate, both rounded to two decimals.
        val expectedCost = BatteryMath.round2(session.energyKwh * homeRate)
        assertThat(session.costInr).isCloseTo(expectedCost, within(1e-9))
    }

    @Test
    fun `a partial charge - plugged in at 80, pulled at 95`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 80.0, 5.0, 0L)
        led.observe(true, 95.0, 5.0, 90 * min)
        led.observe(false, 95.0, 0.0, 91 * min)

        val s = sessions().single()
        assertThat(s.energyKwh).isEqualTo(BatteryMath.round2(BatteryMath.rechargeEnergyKwh(80.0, 95.0, BatteryMath.DEFAULT_CAPACITY_KWH)))
        assertThat(s.kind).isEqualTo(BatteryMath.ChargeKind.SLOW.ordinal)
    }

    @Test
    fun `a fast charge is priced at the outside rate`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 20.0, 42.0, 0L)
        led.observe(true, 70.0, 42.0, 50 * min)
        led.observe(false, 70.0, 0.0, 51 * min)

        val s = sessions().single()
        assertThat(s.kind).isEqualTo(BatteryMath.ChargeKind.FAST.ordinal)
        assertThat(s.peakPowerKw).isEqualTo(42.0)
        assertThat(s.costInr).isEqualTo(BatteryMath.round2(BatteryMath.round2(BatteryMath.rechargeEnergyKwh(20.0, 70.0, BatteryMath.DEFAULT_CAPACITY_KWH)) * outsideRate))
    }

    @Test
    fun `a box that slept through most of the charge still gets a fair session`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 20.0, 7.0, 0L)
        // The box slept; the next frame wakes eight hours later mid-charge.
        led.observe(true, 95.0, 7.0, 8 * 60 * min)
        led.observe(false, 95.0, 0.0, 8 * 60 * min + min)

        val s = sessions().single()
        // Energy is the SOC swing, not an integration of slept-through hours, so it is exact.
        assertThat(s.energyKwh).isEqualTo(BatteryMath.round2(BatteryMath.rechargeEnergyKwh(20.0, 95.0, BatteryMath.DEFAULT_CAPACITY_KWH)))
        assertThat(s.kind).isEqualTo(BatteryMath.ChargeKind.SLOW.ordinal)
    }

    @Test
    fun `a charge whose final frames were slept through still books its energy on wake`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 30.0, 6.0, 0L)
        led.observe(true, 40.0, 6.0, 10 * min)
        // The box died mid-charge and woke after the session was long over: the closing frame's
        // SOC books the final quarter of the charge the box never saw.
        led.observe(false, 95.0, 0.0, gap + 20 * min)

        val all = sessions()
        assertThat(all).hasSize(1)
        val s = all.single()
        assertThat(s.kind).isNotNull()
        assertThat(s.energyKwh).isEqualTo(BatteryMath.round2(BatteryMath.rechargeEnergyKwh(30.0, 95.0, BatteryMath.DEFAULT_CAPACITY_KWH)))
        assertThat(s.endSoc).isEqualTo(95.0)
    }

    @Test
    fun `a charge that genuinely persisted across a sleep is one honest session`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 30.0, 6.0, 0L)
        led.observe(true, 40.0, 6.0, 10 * min)
        // The box slept through the night but the car was still charging when it woke: the SOC
        // swing from the wake frame captures exactly what went in.
        led.observe(true, 70.0, 6.0, gap + 20 * min)
        led.observe(false, 70.0, 0.0, gap + 21 * min)

        val all = sessions()
        assertThat(all).hasSize(1)
        assertThat(all.single().startSoc).isEqualTo(30.0)
        assertThat(all.single().energyKwh).isEqualTo(
            BatteryMath.round2(BatteryMath.rechargeEnergyKwh(30.0, 70.0, BatteryMath.DEFAULT_CAPACITY_KWH))
        )
    }

    @Test
    fun `a reboot that keeps the charge alive within the gap continues the same session`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 30.0, 6.0, 0L)
        // Rebooted at 3am, back up at 3:04 and still charging: same night, same session.
        led.observe(true, 33.0, 6.0, 4 * min)
        led.observe(true, 45.0, 6.0, 30 * min)
        led.observe(false, 45.0, 0.0, 31 * min)

        assertThat(sessions()).hasSize(1)
        assertThat(sessions().single().startSoc).isEqualTo(30.0)
        assertThat(sessions().single().energyKwh).isEqualTo(
            BatteryMath.round2(BatteryMath.rechargeEnergyKwh(30.0, 45.0, BatteryMath.DEFAULT_CAPACITY_KWH))
        )
    }

    @Test
    fun `a pause mid charge reads as two sessions, both slow, never merged across the pause`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 20.0, 3.0, 0L)
        led.observe(true, 30.0, 3.0, 60 * min)
        led.observe(false, 30.0, 0.0, 61 * min)
        led.observe(true, 30.0, 3.0, 120 * min)
        led.observe(true, 40.0, 3.0, 180 * min)
        led.observe(false, 40.0, 0.0, 181 * min)

        val all = sessions()
        assertThat(all).hasSize(2)
        all.forEach { assertThat(it.kind).isEqualTo(BatteryMath.ChargeKind.SLOW.ordinal) }
        assertThat(all.sumOf { it.energyKwh }).isEqualTo(
            BatteryMath.round2(BatteryMath.rechargeEnergyKwh(20.0, 30.0, BatteryMath.DEFAULT_CAPACITY_KWH)) +
            BatteryMath.round2(BatteryMath.rechargeEnergyKwh(30.0, 40.0, BatteryMath.DEFAULT_CAPACITY_KWH))
        )
    }

    @Test
    fun `a charging frame without a soc keeps the session open but adds no energy`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 50.0, 8.0, 0L)
        led.observe(true, null, 8.0, 5 * min)

        val s = sessions().single()
        assertThat(s.kind).isNull()
        assertThat(s.energyKwh).isEqualTo(0.0)
    }

    @Test
    fun `a session with no energy costs zero yet is still closed and classified slow`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 50.0, 0.0, 0L)
        led.observe(false, 50.0, 0.0, min)

        val s = sessions().single()
        assertThat(s.kind).isEqualTo(BatteryMath.ChargeKind.SLOW.ordinal)
        assertThat(s.costInr).isEqualTo(0.0)
    }

    @Test
    fun `say some fills were at home and some outside - the blended rate prices later drives`() {
        val dao = db.dao()
        val led = ledger()
        // An all-night 4 kW home fill: 10 -> 60 over three hours stays a slow (home) session.
        led.observe(true, 10.0, 4.0, 0L)
        led.observe(true, 60.0, 4.0, 200 * min)
        led.observe(false, 60.0, 0.0, 201 * min)
        // A highway fast fill later the same day: 60 -> 95 at 40 kW.
        led.observe(true, 60.0, 40.0, 240 * min)
        led.observe(true, 95.0, 40.0, 264 * min)
        led.observe(false, 95.0, 0.0, 265 * min)

        val fills = dao.fillsBefore(500 * min)
        assertThat(fills).hasSize(2)
        val weighted = fills.sumOf { it.costInr!! } / fills.sumOf { it.energyKwh }
        // Build expected from the same rounding the ledger uses.
        val homeKwh = BatteryMath.round2(BatteryMath.rechargeEnergyKwh(10.0, 60.0, BatteryMath.DEFAULT_CAPACITY_KWH))
        val fastKwh = BatteryMath.round2(BatteryMath.rechargeEnergyKwh(60.0, 95.0, BatteryMath.DEFAULT_CAPACITY_KWH))
        val homeCost = BatteryMath.round2(homeKwh * homeRate)
        val fastCost = BatteryMath.round2(fastKwh * outsideRate)
        val expected = (homeCost + fastCost) / (homeKwh + fastKwh)
        assertThat(weighted).isCloseTo(expected, within(1e-9))
        // Only priced fills are offered; an open session never prices a drive.
        assertThat(dao.fillsBefore(500 * min).count { it.costInr == null }).isZero()
    }

    @Test
    fun `a later drive prices itself from the fills that started before it`() {
        val dao = db.dao()
        val led = ledger()
        led.observe(true, 30.0, 6.0, 0L)
        led.observe(true, 60.0, 6.0, 120 * min)
        led.observe(false, 60.0, 0.0, 121 * min)

        // Drive starts at 50% SOC and finishes at 30% over 40 real km.
        val trip = dao.startTrip(500 * min)
        dao.appendPoint(`in`.odograph.tracker.data.PointEntity(0, trip, 500 * min, 12.97, 77.59, 0f, null, 900.0, 5f, false))
        dao.appendPoint(`in`.odograph.tracker.data.PointEntity(0, trip, 520 * min, 13.0, 77.6, 15f, null, 900.0, 5f, false))
        dao.insertBattery(`in`.odograph.tracker.data.BatteryEntity(0, trip, 500 * min, socPercent = 50.0, charging = false))
        dao.insertBattery(`in`.odograph.tracker.data.BatteryEntity(0, trip, 520 * min, socPercent = 30.0, charging = false))

        TripRecovery.recoverAndStart(dao, nowFromGnss = 600 * min)

        val closed = dao.tripById(trip)!!
        assertThat(closed.energyKwh).isCloseTo(
            BatteryMath.round2(BatteryMath.consumedKwh(dao.batteryRangeFor(trip), BatteryMath.DEFAULT_CAPACITY_KWH) ?: 0.0), within(1e-9))
        assertThat(closed.costInr).isCloseTo(BatteryMath.round2(closed.energyKwh!! * homeRate), within(1e-9))
    }

    @Test
    fun `a drive that puts energy back in is never billed for a refund`() {
        val dao = db.dao()
        val trip = dao.startTrip(500 * min)
        dao.appendPoint(`in`.odograph.tracker.data.PointEntity(0, trip, 500 * min, 12.97, 77.59, 0f, null, 900.0, 5f, false))
        dao.appendPoint(`in`.odograph.tracker.data.PointEntity(0, trip, 520 * min, 13.0, 77.6, 15f, null, 900.0, 5f, false))
        dao.insertBattery(
            `in`.odograph.tracker.data.BatteryEntity(0, trip, 500 * min, socPercent = 40.0, charging = false)
        )
        dao.insertBattery(
            `in`.odograph.tracker.data.BatteryEntity(0, trip, 520 * min, socPercent = 43.0, charging = false)
        )
        TripRecovery.recoverAndStart(dao, nowFromGnss = 600 * min)

        val closed = dao.tripById(trip)!!
        assertThat(closed.energyKwh).isLessThan(0.0)
        assertThat(closed.costInr).isEqualTo(0.0)
    }

    // ---- daily coverage ----

    @Test
    fun `coverage upsert widens the day window from first to last poll`() {
        val dao = db.dao()
        dao.insertTelemetryDayIfAbsent(20260913, first = 1_000L, last = 1_000L)
        dao.setTelemetryDayLast(20260913, 5_000L)
        dao.setTelemetryDayLast(20260913, 12_000L)
        dao.insertTelemetryDayIfAbsent(20260914, first = 100_000L, last = 110_000L)

        val days = dao.allTelemetryDays()
        assertThat(days).hasSize(2)
        assertThat(days[0].firstPollAt).isEqualTo(1_000L)
        assertThat(days[0].lastPollAt).isEqualTo(12_000L)
    }

    private fun within(tolerance: Double) = org.assertj.core.data.Offset.offset(tolerance)
}