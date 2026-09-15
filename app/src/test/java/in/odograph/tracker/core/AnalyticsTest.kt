package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.DailyEffRow
import `in`.odograph.tracker.data.ParkedBatteryRow
import `in`.odograph.tracker.data.RouteTripEff
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AnalyticsTest {

    @Test
    fun `daily range series turns qualifying days into range at full charge`() {
        val days = listOf(
            DailyEffRow(day = 10, drives = 1, distanceM = 40_000.0, energyKwh = 8.0),
            DailyEffRow(day = 11, drives = 2, distanceM = 60_000.0, energyKwh = 15.0)
        )
        val series = Analytics.dailyRangeSeries(days, capacityKwh = 49.2)

        assertThat(series).hasSize(2)
        // 8 kWh / 40 km = 20 kWh/100 km  ->  range = 49.2 / 20 * 100 = 246 km
        assertThat(series[0].rangeAtFullKm).isCloseTo(246.0, within(0.01))
        // 15 kWh / 60 km = 25 kWh/100 km ->  range = 49.2 / 25 * 100 = 196.8 km
        assertThat(series[1].rangeAtFullKm).isCloseTo(196.8, within(0.01))
        assertThat(series.map { it.day }).isEqualTo(listOf(10, 11))
    }

    @Test
    fun `days that are too short or too meagre to measure are skipped`() {
        val days = listOf(
            DailyEffRow(day = 1, drives = 1, distanceM = 500.0, energyKwh = 0.1),
            DailyEffRow(day = 2, drives = 1, distanceM = 40_000.0, energyKwh = 0.1),
            DailyEffRow(day = 3, drives = 1, distanceM = 500.0, energyKwh = 4.0),
            DailyEffRow(day = 4, drives = 1, distanceM = 40_000.0, energyKwh = 8.0)
        )
        assertThat(Analytics.dailyRangeSeries(days, capacityKwh = 49.2))
            .hasSize(1)
            .first()
            .extracting { it.day }
            .isEqualTo(4)
    }

    @Test
    fun `a zero or unknown capacity never produces range`() {
        assertThat(Analytics.dailyRangeSeries(
            listOf(DailyEffRow(1, 1, 40_000.0, 8.0)), capacityKwh = 0.0)).isEmpty()
    }

    @Test
    fun `drain windows group consecutive parked frames and need 12 hours`() {
        // One overnight: frames every 10 minutes for 13 hours, SOC falling ~0.2%/h.
        val t0 = 1_000_000_000L
        val frames = (0..78).map { i ->
            ParkedBatteryRow(t = t0 + i * 10 * 60_000L, socPercent = 90.0 - i * 0.02)
        }
        val windows = Analytics.drainWindows(frames, capacityKwh = 49.2)

        assertThat(windows).hasSize(1)
        assertThat(windows.single().dropPercent).isCloseTo(1.56, within(0.01))
        assertThat(windows.single().hours).isCloseTo(13.0, within(0.01))
    }

    @Test
    fun `a gain in a parked window is a misclassified charge, not negative drain`() {
        val t0 = 1_000_000_000L
        val frames = (0..78).map { i ->
            ParkedBatteryRow(t0 + i * 10 * 60_000L, 60.0 + i * 0.02)
        }
        assertThat(Analytics.drainWindows(frames, capacityKwh = 49.2)).isEmpty()
    }

    @Test
    fun `route rankings only count routes long enough, often enough`() {
        val trips = listOf(
            RouteTripEff(1, 2, 35_000.0, 7.0),   // qualifies
            RouteTripEff(1, 2, 35_000.0, 7.0),
            RouteTripEff(1, 2, 35_000.0, 7.0),
            RouteTripEff(3, 4, 45_000.0, 20.0),  // qualifies, worse efficiency
            RouteTripEff(3, 4, 45_000.0, 20.0),
            RouteTripEff(3, 4, 45_000.0, 20.0),
            RouteTripEff(5, 6, 60_000.0, 9.0),   // only twice -> dropped
            RouteTripEff(5, 6, 60_000.0, 9.0),
            RouteTripEff(7, 8, 15_000.0, 2.0)    // too short -> dropped
        )
        val r = Analytics.routeRankings(trips)

        assertThat(r.best).hasSize(2)
        assertThat(r.best.map { it.from }).containsExactly(1L, 3L)
        assertThat(r.best[0].kwhPer100Km).isCloseTo(20.0, within(0.01))
        assertThat(r.worst.map { it.from }).containsExactly(3L, 1L)
        assertThat(r.worst[0].kwhPer100Km)
            .isCloseTo(20.0 / 45.0 * 100.0, within(0.01))
    }

    @Test
    fun `route efficiency is the average of the route's drives`() {
        val trips = listOf(
            RouteTripEff(1, 2, 30_000.0, 7.0),
            RouteTripEff(1, 2, 30_000.0, 9.0),
            RouteTripEff(1, 2, 30_000.0, 6.0)
        )
        val r = Analytics.routeRankings(trips)
        assertThat(r.best).hasSize(1)
        // 22 kWh over 90 km = 24.44 kWh/100 km
        assertThat(r.best.single().kwhPer100Km).isCloseTo(24.44, within(0.01))
        assertThat(r.best.single().km).isEqualTo(90.0)
        assertThat(r.best.single().drives).isEqualTo(3)
    }
}

private fun within(tolerance: Double) = org.assertj.core.data.Offset.offset(tolerance)