package `in`.odograph.tracker.server

import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.MonthTotal
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.RouteSummary
import `in`.odograph.tracker.data.TripEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DashboardHtmlTest {

    private val trip = TripEntity(id = 42, startedAt = 0, endedAt = 1000, distanceM = 5000.0)

    @Test
    fun `the archive is self contained with no external references`() {
        val html = DashboardHtml.render(listOf(trip), emptyMap())
        assertThat(html).startsWith("<!doctype html>")
        assertThat(html).doesNotContain("http://")
        assertThat(html).doesNotContain("https://")
        assertThat(html).doesNotContain("<script src=")
        assertThat(html).doesNotContain("<link ")
    }

    @Test
    fun `trip data is inlined as json`() {
        assertThat(DashboardHtml.render(listOf(trip), emptyMap())).contains("\"id\":42")
    }

    @Test
    fun `route points are inlined per trip`() {
        val html = DashboardHtml.render(
            listOf(trip),
            mapOf(42L to listOf(
                `in`.odograph.tracker.data.PointEntity(1, 42, 0, 12.97, 77.59, 0f, null, null, 5f, false)
            ))
        )
        assertThat(html).contains("[12.97,77.59]")
    }

    @Test
    fun `an empty history still renders a valid page`() {
        val html = DashboardHtml.render(emptyList(), emptyMap())
        assertThat(html).contains("var TRIPS = []")
        assertThat(html).contains("No drives recorded yet")
    }

    @Test
    fun `the config page shows the current values and escapes quotes`() {
        val html = DashboardHtml.configPage("""https://x/"onerror="y""", "windsor")
        assertThat(html).contains("windsor")
        assertThat(html).doesNotContain("""value="https://x/"onerror="y"""")
        assertThat(html).contains("&quot;")
    }

    @Test
    fun `the badge reflects whether off device delivery is configured`() {
        assertThat(DashboardHtml.render(emptyList(), emptyMap(), webhookConfigured = false))
            .contains("local only")
        assertThat(DashboardHtml.render(emptyList(), emptyMap(), webhookConfigured = true))
            .contains("sync on")
    }

    // ---- places page ----

    private val home = PlaceEntity(1, 12.9716, 77.5946, visits = 47, label = "Home")
    private val office = PlaceEntity(2, 12.9698, 77.7500, visits = 44, autoName = "Whitefield")

    @Test
    fun `a user label is shown and beats the geocoded suggestion`() {
        val html = DashboardHtml.placesPage(listOf(home, office), emptyList())
        assertThat(html).contains("value=\"Home\"")
        assertThat(html).contains("Whitefield")
    }

    @Test
    fun `routes render with their place names rather than coordinates`() {
        val html = DashboardHtml.placesPage(
            listOf(home, office),
            listOf(RouteSummary(1, 2, 47, 18_432.0, 1_484.0, 1_219.0, 31.9f, 866_304.0))
        )
        assertThat(html).contains("Home &rarr; Whitefield")
        assertThat(html).contains(">47<")
    }

    @Test
    fun `an unnamed place falls back to its coordinates`() {
        val bare = PlaceEntity(3, 12.9352, 77.6245, visits = 2)
        assertThat(bare.displayName).isEqualTo("12.9352, 77.6245")
    }

    @Test
    fun `a label containing markup cannot break the page`() {
        val nasty = home.copy(label = """Home" onerror="alert(1)""")
        val html = DashboardHtml.placesPage(listOf(nasty), emptyList())
        assertThat(html).doesNotContain("""onerror="alert(1)"""")
        assertThat(html).contains("&quot;")
    }

    @Test
    fun `empty state explains why nothing is listed yet`() {
        val html = DashboardHtml.placesPage(emptyList(), emptyList())
        assertThat(html).contains("No places yet")
        assertThat(html).contains("ends when the ignition does")
    }

    @Test
    fun `monthly totals render as their own table`() {
        val html = DashboardHtml.render(
            listOf(trip), emptyMap(), months = listOf(
                MonthTotal("2026-09", 41, 764_320.0, 51_400),
                MonthTotal("2026-08", 38, 701_110.0, 47_900)
            )
        )
        assertThat(html).contains("2026-09")
        assertThat(html).contains("764.3")
        assertThat(html).contains("By month")
    }

    @Test
    fun `an empty month list still renders the section`() {
        val html = DashboardHtml.render(emptyList(), emptyMap())
        assertThat(html).contains("By month")
        assertThat(html).contains("No completed drives yet")
    }

    @Test
    fun `trip battery and cost fields reach the archive json`() {
        val equipped = trip.copy(socStart = 90.0, socEnd = 64.2, energyKwh = 12.7, costInr = 101.6)
        val html = DashboardHtml.render(listOf(equipped), emptyMap())
        assertThat(html).contains("\"energyKwh\":12.7")
        assertThat(html).contains("\"costInr\":101.6")
        assertThat(html).contains("\"socStart\":90.0")
    }

    @Test
    fun `an un-instrumented trip serializes nulls in the archive json`() {
        val html = DashboardHtml.render(listOf(trip), emptyMap())
        assertThat(html).contains("\"energyKwh\":null")
    }

    @Test
    fun `the archive renders the x-y energy chart and charge events`() {
        val html = DashboardHtml.render(
            emptyList(), emptyMap(),
            chargeEvents = listOf(
                ChargeEventEntity(1, 1_000L, startSoc = 30.0, endTime = 3_600_000L, endSoc = 60.0,
                    energyKwh = 14.76, peakPowerKw = 7.4, kind = 0, costInr = 118.08)
            ),
            telemetryDays = listOf(DailyTelemetryEntity(20260913, 1_000L, 12_000L))
        )
        assertThat(html).contains("Energy per drive")
        assertThat(html).contains("\"kwh\":14.76")
        assertThat(html).contains("Capture coverage")
        assertThat(html).contains("\"d\":20260913")
    }

    @Test
    fun `the config page exposes the electricity rates`() {
        val html = DashboardHtml.configPage("", "windsor", batteryCapacityKwh = "49.2",
            homeRateInr = "8.00", outsideRateInr = "25.00")
        assertThat(html).contains("value=\"8.00\"")
        assertThat(html).contains("value=\"25.00\"")
        assertThat(html).contains("name=\"capacity\"")
    }

    @Test
    fun `the planner renders real learned numbers and falls back when nothing is learned yet`() {
        val learned = DashboardHtml.plannerPage(
            socPercent = 62.0, capacityKwh = 49.2, homeRateInr = 8.0, outsideRateInr = 25.0,
            cityEfficiencyKwhPer100Km = 11.5, longEfficiencyKwhPer100Km = 14.2,
            totalKwh = 240.0, lastPollAt = 1_700_000_000_000L
        )
        assertThat(learned).contains("265 km")
        assertThat(learned).contains("city: 8.70")
        assertThat(learned).contains("outRate = 25.0")

        val bare = DashboardHtml.plannerPage(
            socPercent = null, capacityKwh = 49.2, homeRateInr = 8.0, outsideRateInr = 25.0,
            cityEfficiencyKwhPer100Km = null, longEfficiencyKwhPer100Km = null,
            totalKwh = 0.0, lastPollAt = null
        )
        assertThat(bare).contains("never")
        assertThat(bare).contains("Not enough real driving yet")
    }
}
