package `in`.odograph.tracker.server

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
        assertThat(html).contains("const TRIPS = []")
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
}
