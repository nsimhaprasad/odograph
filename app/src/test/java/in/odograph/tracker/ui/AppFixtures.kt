package `in`.odograph.tracker.ui

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.core.MgFixtures
import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PlaceEntity

/**
 * A database that looks like a car has been driven for a while.
 *
 * Most screens take nothing but a palette and load themselves from Room, so rendering them against
 * an empty database only ever proves the empty state draws. The interesting failures — a route
 * name too long for its row, a charge list that outgrows a split-screen band, a cost column that
 * pushes the date off the edge — need rows behind them.
 *
 * The numbers are the car's own, carried over from [MgFixtures]: a 37.3 kWh pack, home charging at
 * 1.6 kW, a fast session at 6.5 kW, an odometer in the 23,000s.
 */
object AppFixtures {

    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    data class Seeded(val home: Long, val office: Long, val airport: Long, val mall: Long)

    /** Empties every table, off the main thread, leaving the connection open. */
    fun clearTables(ctx: Context = ApplicationProvider.getApplicationContext()) {
        val t = Thread { OdographDb.get(ctx).clearAllTables() }
        t.start()
        t.join()
    }

    /**
     * Seeds places, trips, charge sessions and battery samples.
     *
     * Runs on its own thread and blocks until done, because Room refuses main-thread access and
     * the screens themselves load on Dispatchers.IO — so seeding has to be finished before the
     * composition starts or the screen races the fixture.
     */
    fun seed(ctx: Context = ApplicationProvider.getApplicationContext()): Seeded {
        var out: Seeded? = null
        val t = Thread {
            val dao = OdographDb.get(ctx).dao()
            val now = System.currentTimeMillis()

            val home = dao.insertPlace(
                PlaceEntity(lat = 12.9716, lon = 77.5946, visits = 47, label = "Home")
            )
            val office = dao.insertPlace(
                PlaceEntity(lat = 12.9698, lon = 77.7500, visits = 44, label = "Office")
            )
            val airport = dao.insertPlace(
                PlaceEntity(lat = 13.1986, lon = 77.7066, visits = 6, autoName = "Devanahalli")
            )
            // A deliberately long name: a row that fits "Home" tells you nothing about a row that
            // has to carry what the geocoder actually returns.
            val mall = dao.insertPlace(
                PlaceEntity(
                    lat = 12.9250, lon = 77.6750, visits = 11,
                    autoName = "Phoenix Marketcity, Whitefield Main Road, Mahadevapura"
                )
            )

            fun trip(from: Long, to: Long, n: Int, metres: Double, seconds: Long, kwh: Double) {
                repeat(n) { i ->
                    val startedAt = now - (i + 1) * HOUR_MS
                    val id = dao.startTrip(startedAt)
                    dao.finishTrip(
                        id, startedAt + seconds * 1000, metres, seconds,
                        (seconds * 0.82).toLong(), 27.5f, metres / seconds, 3.4,
                        184.0, 142.0
                    )
                    dao.setTripPlaces(id, from, to)
                    dao.setChargeSummary(id, 63.0, 63.0 - kwh / MgFixtures.PACK_KWH * 100, kwh)
                    dao.insertBattery(
                        BatteryEntity(
                            tripId = id, t = startedAt, socPercent = 63.0, charging = false,
                            rangeKm = 201.0, odometerKm = MgFixtures.ODO_KM,
                            batteryEnergyKwh = 23.5
                        )
                    )
                }
            }
            trip(home, office, 24, 18_432.0, 1_484, 2.87)
            trip(office, home, 22, 19_010.0, 1_702, 3.10)
            trip(home, airport, 6, 41_200.0, 2_940, 6.90)
            trip(home, mall, 5, 12_100.0, 1_050, 1.95)

            // Charge sessions across the shapes the ledger can produce: a slow home fill, a priced
            // fast session, one still open, and one whose wall reading was entered later.
            fun charge(
                startAgo: Long, hours: Long, startSoc: Double, endSoc: Double?,
                kwh: Double, peakKw: Double, placeId: Long?, kind: Int?,
                cost: Double?, delivered: Double?
            ) {
                val start = now - startAgo
                dao.insertChargeEvent(
                    ChargeEventEntity(
                        startTime = start,
                        startSoc = startSoc,
                        endTime = endSoc?.let { start + hours * HOUR_MS },
                        endSoc = endSoc,
                        energyKwh = kwh,
                        peakPowerKw = peakKw,
                        samplesTotal = 40,
                        samplesAbove = if (peakKw > 3.3) 34 else 0,
                        kind = kind,
                        costInr = cost,
                        deliveredKwh = delivered,
                        placeId = placeId
                    )
                )
            }
            // Home, slow, 382 V x 4.2 A, wall reading entered afterwards.
            charge(2 * DAY_MS, 7, 42.0, 100.0, 21.6, 1.6, home, 0, 194.4, 24.1)
            // A public fast charger, priced.
            charge(5 * DAY_MS, 1, 45.0, 80.0, 13.1, 6.5, mall, 1, 288.2, 14.0)
            // Still charging: no end, no cost yet.
            charge(1 * HOUR_MS, 0, 70.0, null, 4.2, 1.6, home, 0, null, null)
            // Priced but never given a wall reading.
            charge(9 * DAY_MS, 6, 30.0, 95.0, 24.2, 1.6, home, 0, 217.8, null)

            out = Seeded(home, office, airport, mall)
        }
        t.start()
        t.join()
        return out ?: error("seeding produced nothing")
    }

    /**
     * Waits for a screen that loads itself.
     *
     * These screens fetch on Dispatchers.IO inside a LaunchedEffect, so the first composition is
     * always the empty state and `waitForIdle` returns long before the rows arrive.
     *
     * Used only where there is nothing specific to wait for. Prefer [settleUntil]: a fixed number
     * of sleeps is a race dressed up as a wait, and it fails the way races do — three cases one
     * run, four the next, on a screen that was never broken.
     */
    fun settle(compose: ComposeContentTestRule, rounds: Int = 20, pauseMs: Long = 25) {
        repeat(rounds) {
            compose.waitForIdle()
            Thread.sleep(pauseMs)
        }
        compose.waitForIdle()
    }

    /**
     * Waits until the screen has actually loaded, on a real clock.
     *
     * Deliberately not [ComposeContentTestRule.waitUntil]. That measures its timeout against the
     * *virtual* test clock, which it advances itself — so ten seconds of it can pass in a few
     * milliseconds of wall time and a query sitting on Dispatchers.IO never gets a chance to run.
     * It times out on a screen that would have loaded perfectly well.
     *
     * A real deadline gives the IO thread real time, and pumping the composition between polls
     * lets the result reach the UI. Condition-based, so it returns the moment the data arrives
     * rather than always paying the full wait.
     */
    fun settleUntil(
        compose: ComposeContentTestRule,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (condition()) return true
            Thread.sleep(25)
        }
        compose.waitForIdle()
        return condition()
    }
}
