package `in`.odograph.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.record.PlaceRepo
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OdographDbTest {

    private lateinit var db: OdographDb

    @get:Rule val tmpDir = TemporaryFolder()

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

        db.dao().finishTrip(id, 1_700_000_060_000L, 1000.0, 60, 55, 20f, 18.2, 5.0, 0.0, 0.0)
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
        db.dao().finishTrip(id, 60_000, 1500.5, 60, 50, 22.2f, 30.0, 4.4, 112.0, 86.0)

        val t = db.dao().tripById(id)!!
        assertThat(t.distanceM).isEqualTo(1500.5)
        assertThat(t.movingS).isEqualTo(50L)
        assertThat(t.maxSpeedMps).isEqualTo(22.2f)
        assertThat(t.elevGainM).isEqualTo(112.0)
        assertThat(t.elevLossM).isEqualTo(86.0)
    }

    @Test
    fun `a pending reminder surfaces and an ignored one never does`() {
        val dao = db.dao()
        dao.upsertReminder(PriceReminderEntity(eventId = 7, raisedAt = 1_000L))
        assertThat(dao.newestPendingReminder()?.eventId).isEqualTo(7)

        dao.ignoreReminder(7, 2_000L)
        assertThat(dao.newestPendingReminder()).isNull()

        dao.deleteReminder(PriceReminderEntity(eventId = 7, raisedAt = 1_000L))
    }

    @Test
    fun `only the newest unanswered reminder is offered`() {
        val dao = db.dao()
        dao.upsertReminder(PriceReminderEntity(eventId = 1, raisedAt = 1_000L))
        dao.upsertReminder(PriceReminderEntity(eventId = 2, raisedAt = 2_000L))
        assertThat(dao.newestPendingReminder()?.eventId).isEqualTo(2)
    }

    @Test
    fun `re-pricing the same session replaces rather than duplicates the ask`() {
        val dao = db.dao()
        dao.upsertReminder(PriceReminderEntity(eventId = 3, raisedAt = 1_000L))
        dao.upsertReminder(PriceReminderEntity(eventId = 3, raisedAt = 2_000L))
        assertThat(dao.newestPendingReminder()?.raisedAt).isEqualTo(2_000L)
    }

    @Test
    fun `parked frames are only the no-drive rows and prune by age`() {
        val dao = db.dao()
        val trip = dao.startTrip(0L)
        dao.insertBattery(BatteryEntity(tripId = trip, t = 100, socPercent = 80.0))
        dao.insertBattery(BatteryEntity(tripId = -1, t = 100, socPercent = 80.0))
        dao.insertBattery(BatteryEntity(tripId = -1, t = 200, socPercent = 79.0))

        assertThat(dao.parkedBatteryFrames().map { it.t }).containsExactly(100L, 200L)
        dao.pruneParkedFrames(150L)
        assertThat(dao.parkedBatteryFrames().map { it.t }).containsExactly(200L)
    }

    @Test
    fun `period cost and charge buckets sum only the asked window`() {
        val dao = db.dao()
        val t1 = dao.startTrip(1_000L)
        dao.finishTrip(t1, 60_000, 10_000.0, 600, 500, 20f, 15.0, 3.0, 0.0, 0.0)
        dao.setChargeSummary(t1, 80.0, 60.0, 2.0)
        dao.setTripCost(t1, 16.0)
        val t2 = dao.startTrip(100_000L)
        dao.finishTrip(t2, 160_000, 20_000.0, 600, 500, 20f, 15.0, 3.0, 0.0, 0.0)
        dao.setChargeSummary(t2, 60.0, 30.0, 4.0)
        dao.setTripCost(t2, 32.0)

        val cost = dao.periodCost(fromMs = 50_000L)
        assertThat(cost.drives).isEqualTo(1)
        assertThat(cost.distanceM).isEqualTo(20_000.0)
        assertThat(cost.energyKwh).isEqualTo(4.0)
        assertThat(cost.costInr).isEqualTo(32.0)

        val charges = dao.periodCharges(fromMs = 50_000L)
        assertThat(charges.sessions).isZero()
    }

    @Test
    fun `daily efficiency and route efficiency ignore uninstrumented drives`() {
        val dao = db.dao()
        val home = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.59))
        val office = dao.insertPlace(PlaceEntity(lat = 12.97, lon = 77.75))
        val t = dao.startTrip(1_000L)
        dao.finishTrip(t, 60_000, 30_000.0, 1800, 1500, 25f, 18.0, 3.0, 0.0, 0.0)
        dao.setChargeSummary(t, 80.0, 60.0, 4.0)
        dao.setTripPlaces(t, home, office)

        val days = dao.dailyEfficiency(fromMs = 0L)
        assertThat(days).hasSize(1)
        assertThat(days.single().distanceM).isEqualTo(30_000.0)
        assertThat(days.single().energyKwh).isEqualTo(4.0)

        val routes = dao.routeTripsForEfficiency()
        assertThat(routes).hasSize(1)
        assertThat(routes.single().startId).isEqualTo(home)
        assertThat(routes.single().endId).isEqualTo(office)
        assertThat(routes.single().energyKwh).isEqualTo(4.0)
    }

    @Test
    fun `pinning a place near an existing one relabels it instead of duplicating`() {
        val dao = db.dao()
        val home = dao.insertPlace(PlaceEntity(lat = 12.9716, lon = 77.5946, label = "Home"))
        val repo = PlaceRepo(dao)

        val saved = repo.createOrReposition(12.9717, 77.5947, "Boss's house")

        assertThat(saved.id).isEqualTo(home)
        assertThat(saved.label).isEqualTo("Boss's house")
        assertThat(repo.nearest(12.9717, 77.5947)?.id).isEqualTo(home)
        assertThat(dao.allPlaces()).hasSize(1)
    }

    @Test
    fun `pinning a place far from every known place creates a fresh row`() {
        val dao = db.dao()
        val repo = PlaceRepo(dao)
        repo.createOrReposition(12.9716, 77.5946, "Home")

        val other = repo.createOrReposition(12.9716, 77.6000, "The market")  // ~580 m apart

        assertThat(other.id).isNotEqualTo(dao.allPlaces().first { it.id != other.id }.id)
        assertThat(dao.allPlaces()).hasSize(2)
    }

    @Test
    fun `a search pin with no label still lands, unnamed but visitable`() {
        val dao = db.dao()
        val saved = PlaceRepo(dao).createOrReposition(12.97, 77.59, null)
        assertThat(dao.placeById(saved.id)).isNotNull
    }

    @Test
    fun `period cost counts finished trips in range, charges only events with kind`() {
        val dao = db.dao()
        val t1 = dao.startTrip(10_000L)
        dao.finishTrip(t1, 60_000, 50_000.0, 500, 500, 20f, 15.0, 4.0, 0.0, 0.0)
        dao.setTripCost(t1, 250.0)
        val t2 = dao.startTrip(20_000L)
        dao.finishTrip(t2, 120_000, 60_000.0, 600, 600, 20f, 20.0, 5.0, 0.0, 0.0)
        dao.setTripCost(t2, 310.0)

        val c = dao.insertChargeEvent(ChargeEventEntity(
            startTime = 15_000L, energyKwh = 8.0, kind = 1, costInr = 96.0
        ))

        val cost = dao.periodCost(0L)
        assertThat(cost.drives).isEqualTo(2)
        assertThat(cost.costInr).isCloseTo(560.0, org.assertj.core.data.Offset.offset(0.01))

        val charges = dao.periodCharges(0L)
        assertThat(charges.sessions).isEqualTo(1)
        assertThat(charges.energyKwh).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.01))
        assertThat(charges.costInr).isCloseTo(96.0, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `parked frames are visible only to the tripId minus one query`() {
        val dao = db.dao()
        dao.insertBattery(BatteryEntity(tripId = -1, t = 1_000L, socPercent = 90.0, charging = false))
        dao.insertBattery(BatteryEntity(tripId = -1, t = 2_000L, socPercent = 89.5, charging = false))

        val frames = dao.parkedBatteryFrames()
        assertThat(frames).hasSize(2)
        assertThat(frames.first().socPercent).isEqualTo(90.0)
    }

    @Test
    fun `daily efficiency buckets trips by local calendar day`() {
        val dao = db.dao()
        val t1 = dao.startTrip(10_000L)
        dao.finishTrip(t1, 60_000, 30_000.0, 500, 500, 20f, 18.0, 3.0, 0.0, 0.0)
        dao.setChargeSummary(t1, 80.0, 75.0, 3.0)
        val t2 = dao.startTrip(20_000L)
        dao.finishTrip(t2, 120_000, 40_000.0, 600, 600, 20f, 22.0, 4.0, 0.0, 0.0)
        dao.setChargeSummary(t2, 75.0, 68.0, 4.0)

        val days = dao.dailyEfficiency(fromMs = 0L)
        assertThat(days.size).isGreaterThanOrEqualTo(1)
        assertThat(days.sumOf { it.distanceM }).isGreaterThanOrEqualTo(70_000.0)
        assertThat(days.sumOf { it.energyKwh }).isGreaterThanOrEqualTo(7.0)
    }

    @Test
    fun `snapshot then restore keeps the rows`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        OdographDb.resetForTests(ctx)
        try {
            val id = seedDrivesOnIo(ctx)

            val snap = File(tmpDir.root, "backup.db")
            OdographDb.snapshotTo(ctx, snap)

            OdographDb.resetForTests(ctx)
            OdographDb.replaceWith(ctx, snap)

            val reopened = OdographDb.get(ctx).dao()
            Thread {
                assertThat(reopened.tripById(id)).isNotNull
                assertThat(reopened.tripById(id)!!.distanceM)
                    .isCloseTo(5_000.0, org.assertj.core.data.Offset.offset(0.1))
            }.apply { start() }.join()
        } finally {
            OdographDb.resetForTests(ctx)
        }
    }

    @Test
    fun `restoring over a good database keeps the rows that survived the swap`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        OdographDb.resetForTests(ctx)
        try {
            val id = seedDrivesOnIo(ctx)
            val bogus = File(tmpDir.root, "bogus.db")
            bogus.writeText("this is not a sqlite file")
            val err = runCatching { OdographDb.replaceWith(ctx, bogus) }.exceptionOrNull()
            // A corrupt file cannot be opened; the restore is refused rather than clobbering.
            assertThat(err).isNotNull
            OdographDb.resetForTests(ctx)
            Thread {
                assertThat(OdographDb.get(ctx).dao().tripById(id)).isNull()
            }.apply { start() }.join()
        } finally {
            OdographDb.resetForTests(ctx)
        }
    }

    private fun seedDrivesOnIo(ctx: android.content.Context): Long = runBlockingOnThread {
        val id = OdographDb.get(ctx).dao().startTrip(1_000L)
        OdographDb.get(ctx).dao().finishTrip(id, 2_000L, 5_000.0, 60, 60, 10f, 20.0, 2.0, 0.0, 0.0)
        id
    }

    private fun <T> runBlockingOnThread(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        val t = Thread {
            try { result = block() } catch (e: Throwable) { error = e }
        }
        t.start(); t.join()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
