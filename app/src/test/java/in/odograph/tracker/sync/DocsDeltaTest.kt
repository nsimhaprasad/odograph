package `in`.odograph.tracker.sync

import `in`.odograph.tracker.data.DailyTelemetryEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DocsDeltaTest {

    private fun day(d: Int) = DailyTelemetryEntity(day = d, firstPollAt = 0, lastPollAt = 0)

    @Test
    fun finalizedHistoryShipsButTodayIsResentForRefresh()
    {
        val selected = DocsDelta.selectDays(listOf(day(3), day(4), day(5)), today = 5)
        assertThat(selected.map { it.day }).containsExactly(3, 4, 5)
    }

    @Test
    fun nothingBeforeTheWatermarkComesAgain()
    {
        // The DAO already filtered to day > 2, so selectDays only ever sees [3] and today's row.
        var selected = DocsDelta.selectDays(listOf(day(3), day(4)), today = 4)
        assertThat(selected.map { it.day }).containsExactly(3, 4)
        assertThat(DocsDelta.nextDayWatermark(prev = 2, sent = selected, today = 4)).isEqualTo(3)
        selected = DocsDelta.selectDays(listOf(day(4)), today = 4)
        assertThat(selected.map { it.day }).containsExactly(4)
        assertThat(DocsDelta.nextDayWatermark(prev = 3, sent = selected, today = 4)).isEqualTo(3)
    }

    @Test
    fun todayIsNeverMarkedDelivered()
    {
        val sent = DocsDelta.selectDays(listOf(day(9), day(10)), today = 10)
        assertThat(DocsDelta.nextDayWatermark(prev = 0, sent = sent, today = 10)).isEqualTo(9)
    }

    @Test
    fun aDayInTheMiddleOfAHoleIsNotLost()
    {
        // Box was offline around day 6, so history jumps 5 → 8.
        val selected = DocsDelta.selectDays(listOf(day(8)), today = 10)
        assertThat(selected.map { it.day }).containsExactly(8)
        // Day 8 is uploaded; the watermark clears everything before today.
        assertThat(DocsDelta.nextDayWatermark(prev = 5, sent = selected, today = 10)).isEqualTo(9)
    }
}