package `in`.odograph.tracker.sync

import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SheetsJsonTest {

    @Test
    fun statsCarriesEverySectionAndDevice() {
        val out = SheetsJson.stats(
            deviceId = "windsor",
            trips = listOf(sampleTrip()),
            points = listOf(samplePoint()),
            charges = listOf(sampleCharge()),
            days = listOf(DailyTelemetryEntity(day = 20260101, firstPollAt = 1, lastPollAt = 2)),
            places = listOf(samplePlace()),
            battery = listOf(sampleBattery()),
            capacityKwh = 49.2, homeRateInr = 8.0, outsideRateInr = 25.0, gstRatePct = 18.0
        )
        assertThat(out).contains("\"kind\":\"odograph\"")
        assertThat(out).contains("\"device\":\"windsor\"")
        assertThat(out).contains("\"capacityKwh\":49.2")
        assertThat(out).contains("\"homeRateInr\":8")
        assertThat(out).doesNotContain("totalEnergyKwh")
        assertThat(out).contains("\"trips\":[")
        assertThat(out).contains("\"points\":[")
        assertThat(out).contains("\"charges\":[")
        assertThat(out).contains("\"telemetry\":[")
        assertThat(out).contains("\"day\":20260101")
    }

    @Test
    fun deviceIdIsEscapedNotQuoted() {
        val out = SheetsJson.stats(
            """my "car" \ box""", emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), 1.0, 1.0, 1.0, 1.0
        )
        assertThat(out).doesNotContain("my \"car\"")
        assertThat(out).contains("\\\"car\\\"")
    }

    @Test
    fun tripRowCarriesTheFieldsTheViewerWants() {
        val out = SheetsJson.stats(
            "d", listOf(sampleTrip()), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), 1.0, 1.0, 1.0, 1.0
        )
        assertThat(out).contains("\"id\":7")
        assertThat(out).contains("\"distanceM\":12000.0")
        assertThat(out).contains("\"energyKwh\":4.62")
        assertThat(out).contains("\"costInr\":41.58")
        assertThat(out).contains("\"socStart\":82.0")
        assertThat(out).contains("\"startLat\":12.9")
        assertThat(out).contains("\"startPlaceId\":null")
    }

    @Test
    fun fullDatasetStaysUnderAppsScriptPayloadLimit() {
        val trips = List(2000) { sampleTrip() }
        val points = List(1000) { samplePoint() }
        val charges = List(200) { sampleCharge() }
        val days = List(500) { DailyTelemetryEntity(day = it, firstPollAt = it.toLong(), lastPollAt = it.toLong() + 1) }
        val byt = SheetsJson.stats(
            "windsor", trips, points, charges, days,
            List(40) { samplePlace() }, List(2000) { sampleBattery() },
            49.2, 8.0, 25.0, 18.0
        ).toByteArray(Charsets.UTF_8).size
        assertThat(byt).isLessThan(6 * 1024 * 1024)
    }

    private fun sampleTrip() = TripEntity(
        id = 7, startedAt = 1000, endedAt = 2000, distanceM = 12000.0, durationS = 1200,
        movingS = 1100, maxSpeedMps = 22.0f, avgSpeedMps = 10.0, slowestKmMps = 1.5,
        startLat = 12.9, startLon = 77.6, endLat = 12.98, endLon = 77.7,
        elevGainM = 12.0, elevLossM = 34.0,
        socStart = 82.0, socEnd = 71.0, energyKwh = 4.62, costInr = 41.58
    )

    private fun samplePoint() = PointEntity(
        tripId = 7, t = 1234, lat = 12.9, lon = 77.6, speedMps = 12.3f,
        bearingDeg = 90f, altitudeM = 920.5, accuracyM = 4f, interpolated = false
    )

    private fun sampleCharge() = ChargeEventEntity(
        id = 1, startTime = 172_000_000L, endTime = 172_003_600L,
        startSoc = 40.0, endSoc = 85.0, energyKwh = 23.0, peakPowerKw = 7.2,
        enteredRateInr = 25.0, gstRatePct = 18.0,
        kind = BatteryMath.ChargeKind.FAST.ordinal, costInr = 678.5
    )

    private fun samplePlace() = PlaceEntity(
        id = 7, lat = 12.9716, lon = 77.5946, visits = 47, label = "Home", geocodedAt = 1_000L
    )

    private fun sampleBattery() = BatteryEntity(
        id = 3, tripId = -1, t = 2_000L, socPercent = 96.0, charging = true,
        batteryEnergyKwh = 50.8, exteriorTempC = 34
    )

    // ------------------------------------------------ the sheet has to be restorable, not just readable

    /**
     * A backup that silently omits a table is the worst kind: it looks like it worked, and the
     * loss only shows up on the day it is needed. Places and battery frames were both missing —
     * without places a restored trip knows where it went but not what that place is called and
     * every route grouping is gone, and without battery frames the pack has no measured health.
     */
    @Test
    fun `the payload carries everything a restore needs`() {
        val out = SheetsJson.stats(
            deviceId = "windsor",
            trips = listOf(sampleTrip()),
            points = listOf(samplePoint()),
            charges = listOf(sampleCharge()),
            days = listOf(DailyTelemetryEntity(day = 20260101, firstPollAt = 1, lastPollAt = 2)),
            places = listOf(samplePlace()),
            battery = listOf(sampleBattery()),
            capacityKwh = 52.9, homeRateInr = 8.0, outsideRateInr = 25.0, gstRatePct = 18.0
        )
        listOf("trips", "points", "charges", "telemetry", "places", "battery").forEach {
            assertThat(out).`as`("section $it").contains("\"$it\":[")
        }
    }

    /** A restore that cannot tell which shape it is reading will one day read it wrongly. */
    @Test
    fun `the payload states the schema it came from`() {
        val out = SheetsJson.stats(
            deviceId = "windsor", trips = emptyList(), points = emptyList(),
            charges = emptyList(), days = emptyList(),
            places = emptyList(), battery = emptyList(),
            capacityKwh = 52.9, homeRateInr = 8.0, outsideRateInr = 25.0, gstRatePct = 18.0
        )
        assertThat(out).contains("\"schema\":${SheetsJson.SCHEMA_VERSION}")
    }

    @Test
    fun `a place carries the name a route grouping depends on`() {
        val out = SheetsJson.stats(
            deviceId = "w", trips = emptyList(), points = emptyList(),
            charges = emptyList(), days = emptyList(),
            places = listOf(samplePlace()), battery = emptyList(),
            capacityKwh = 52.9, homeRateInr = 8.0, outsideRateInr = 25.0, gstRatePct = 18.0
        )
        assertThat(out).contains("\"label\":\"Home\"")
        assertThat(out).contains("\"visits\":47")
    }

    /**
     * "No reading" and "a reading of zero" are different facts, and a backup that flattens the
     * first into the second restores a car that was measured when it was not.
     */
    @Test
    fun `a missing reading stays missing rather than becoming zero`() {
        val out = SheetsJson.stats(
            deviceId = "w", trips = emptyList(), points = emptyList(),
            charges = emptyList(), days = emptyList(), places = emptyList(),
            battery = listOf(BatteryEntity(id = 1, tripId = -1, t = 1L, socPercent = null)),
            capacityKwh = 52.9, homeRateInr = 8.0, outsideRateInr = 25.0, gstRatePct = 18.0
        )
        assertThat(out).contains("\"socPercent\":null")
        assertThat(out).doesNotContain("\"socPercent\":0")
    }

    @Test
    fun `the temperature the pack was measured in is carried`() {
        val out = SheetsJson.stats(
            deviceId = "w", trips = emptyList(), points = emptyList(),
            charges = emptyList(), days = emptyList(), places = emptyList(),
            battery = listOf(sampleBattery()),
            capacityKwh = 52.9, homeRateInr = 8.0, outsideRateInr = 25.0, gstRatePct = 18.0
        )
        assertThat(out).contains("\"exteriorTempC\":34")
        assertThat(out).contains("\"batteryEnergyKwh\":50.8")
    }
}
