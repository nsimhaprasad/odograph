package `in`.odograph.tracker.server

import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyEffRow
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.ParkedBatteryRow
import `in`.odograph.tracker.data.PeriodCharges
import `in`.odograph.tracker.data.PeriodCost
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.RouteTripEff
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.record.TripRecorderService
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ApiJsonTest {

    @Test
    fun `live payload carries the driving numbers`() {
        val json = ApiJson.live(
            TripRecorderService.LiveState(
                hasFix = true,
                speedMps = 10.0f,
                distanceM = 12_345.6789,
                elapsedS = 1234,
                batterySocPercent = 77.5,
                batteryCharging = false,
                batteryRangeKm = 208.0,
                batteryRangeAtFullKm = 270.0,
                telematicsConnected = true
            )
        )
        assertThat(json).contains("\"hasFix\":true")
        assertThat(json).contains("\"speedKmh\":36")
        assertThat(json).contains("\"distanceKm\":12.3457")
        assertThat(json).contains("\"batterySocPercent\":77.5")
        assertThat(json).contains("\"batteryCharging\":false")
        assertThat(json).contains("\"batteryRangeKm\":208")
        assertThat(json).contains("\"telematicsConnected\":true")
    }

    @Test
    fun `live payload emits nulls when nothing is instrumented yet`() {
        val json = ApiJson.live(TripRecorderService.LiveState())
        assertThat(json).contains("\"batterySocPercent\":null")
        assertThat(json).contains("\"batteryRangeKm\":null")
        assertThat(json).contains("\"telematicsConnected\":null")
        assertThat(json).contains("\"hasFix\":false")
    }

    @Test
    fun `trips payload carries id and km per closed trip`() {
        val json = ApiJson.trips(
            listOf(
                TripEntity(id = 7, startedAt = 1_000, endedAt = 2_000, distanceM = 10_000.0, durationS = 600, movingS = 590, energyKwh = 2.1, costInr = 85.0),
                TripEntity(id = 8, startedAt = 3_000, endedAt = null, distanceM = 0.0)
            )
        )
        assertThat(json).contains("\"id\":7")
        assertThat(json).contains("\"distanceKm\":10")
        assertThat(json).contains("\"energyKwh\":2.1")
        assertThat(json).contains("\"costInr\":85")
        assertThat(json).contains("\"endPlaceId\":null")
        assertThat(json).startsWith("[").endsWith("]")
    }

    @Test
    fun `charges payload carries kind labels and cost`() {
        val json = ApiJson.charges(
            listOf(
                ChargeEventEntity(id = 3, startTime = 1_000, endTime = 2_000, energyKwh = 12.5, kind = 1, costInr = 210.0, enteredRateInr = 15.0),
                ChargeEventEntity(id = 4, startTime = 5_000, endTime = null, energyKwh = 3.0, kind = null)
            )
        )
        assertThat(json).contains("\"kind\":\"fast\"")
        assertThat(json).contains("\"costInr\":210")
        assertThat(json).contains("\"enteredRateInr\":15")
        assertThat(json).contains("\"kind\":null")
        assertThat(json).contains("\"startTime\":1000")
        // Wall power and loss are null when neither was entered.
        assertThat(json).contains("\"deliveredKwh\":null")
        assertThat(json).contains("\"lossPct\":null")
        assertThat(json).contains("\"placeId\":null")
    }

    @Test
    fun `charges payload carries wall power and loss when the driver entered them`() {
        val json = ApiJson.charges(
            listOf(
                ChargeEventEntity(id = 5, startTime = 1_000, energyKwh = 10.0, deliveredKwh = 12.0, placeId = 7)
            )
        )
        assertThat(json).contains("\"deliveredKwh\":12")
        assertThat(json).contains("\"lossPct\":16.6667")
        assertThat(json).contains("\"placeId\":7")
    }

    @Test
    fun `places payload escapes labels and falls back to coords`() {
        val json = ApiJson.places(
            listOf(
                PlaceEntity(id = 1, lat = 18.1234, lon = 72.9876, visits = 12, label = "Home", autoName = "Avenue"),
                PlaceEntity(id = 2, lat = 19.5, lon = 73.5, visits = 0)
            )
        )
        assertThat(json).contains("\"name\":\"Home\"")
        assertThat(json).contains("\"lat\":18.1234")
        assertThat(json).contains("\"visits\":12")
        assertThat(json).contains("\"lon\":73.5")
    }

    @Test
    fun `routes payload joins place display names`() {
        val trips = listOf(RouteTripEff(startId = 1, endId = 2, distanceM = 30_000.0, energyKwh = 10.0))
        val places = mapOf(
            1L to PlaceEntity(id = 1, lat = 18.1, lon = 72.9, label = "Home"),
            2L to PlaceEntity(id = 2, lat = 18.3, lon = 73.1)
        )
        val json = ApiJson.routes(trips, places)
        assertThat(json).contains("\"startName\":\"Home\"")
        assertThat(json).contains("\"distanceKm\":30")
        assertThat(json).contains("\"startPlaceId\":1")
    }

    @Test
    fun `cost payload carries both drive and charge buckets`() {
        val json = ApiJson.cost(
            "7d",
            PeriodCost(drives = 4, distanceM = 88_000.0, energyKwh = 17.6, costInr = 562.0),
            PeriodCharges(sessions = 3, energyKwh = 15.0, costInr = 300.0)
        )
        assertThat(json).contains("\"period\":\"7d\"")
        assertThat(json).contains("\"tripDrives\":4")
        assertThat(json).contains("\"tripKm\":88")
        assertThat(json).contains("\"tripCostInr\":562")
        assertThat(json).contains("\"chargeSessions\":3")
        assertThat(json).contains("\"chargeCostInr\":300")
    }

    @Test
    fun `range serializes daily rows`() {
        val json = ApiJson.range(listOf(DailyEffRow(day = 2_025_030, drives = 2, distanceM = 50_000.0, energyKwh = 12.0)))
        assertThat(json).contains("\"day\":2025030")
        assertThat(json).contains("\"distanceKm\":50")
        assertThat(json).contains("\"energyKwh\":12")
    }

    @Test
    fun `drain serializes parked samples with nullable soc`() {
        val json = ApiJson.drain(
            listOf(
                ParkedBatteryRow(t = 1_700_000_000, socPercent = 60.0),
                ParkedBatteryRow(t = 1_700_003_600, socPercent = null)
            )
        )
        assertThat(json).contains("\"socPercent\":60")
        assertThat(json).contains("\"socPercent\":null")
        assertThat(json).contains("\"t\":1700000000")
    }

    @Test
    fun `telemetry serializes poll windows`() {
        val json = ApiJson.telemetry(
            listOf(DailyTelemetryEntity(day = 2_025_030, firstPollAt = 100, lastPollAt = 200))
        )
        assertThat(json).contains("\"day\":2025030")
        assertThat(json).contains("\"firstPollAt\":100")
        assertThat(json).contains("\"lastPollAt\":200")
    }

    @Test
    fun `numbers drop trailing zeros and empty stays zero`() {
        assertThat(ApiJson.trips(listOf(TripEntity(id = 1, startedAt = 0, distanceM = 12_340.0)))).contains("\"distanceKm\":12.34")
        assertThat(ApiJson.trips(listOf(TripEntity(id = 1, startedAt = 0, distanceM = 12_000.0)))).contains("\"distanceKm\":12")
        assertThat(ApiJson.trips(listOf(TripEntity(id = 1, startedAt = 0, distanceM = 0.0)))).contains("\"distanceKm\":0")
    }

    @Test
    fun `strings escape quotes backslashes and newlines`() {
        val json = ApiJson.places(
            listOf(
                PlaceEntity(id = 1, lat = 1.0, lon = 2.0, label = "D's \"place\""),
                PlaceEntity(id = 2, lat = 1.0, lon = 2.0, autoName = "Road\nbridge")
            )
        )
        assertThat(json).contains("D's \\\"place\\\"")
        assertThat(json).contains("Road bridge")
        assertThat(json).doesNotContain("\n")
        assertThat(json).doesNotContain("Road\\n")
    }
}