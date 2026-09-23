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

    // ------------------------------------------------------------- thinning points

    private fun pt(tMs: Long) = `in`.odograph.tracker.data.PointEntity(
        0, 1, tMs, 12.97, 77.59, 5f, null, 900.0, 5f, false
    )

    /** A fix a second for a minute becomes seven rows: both ends and one every ten seconds. */
    @Test
    fun `a second-by-second track is thinned to one point every ten seconds`() {
        val track = (0..60).map { pt(it * 1_000L) }

        val kept = DocsDelta.thinPoints(track)

        assertThat(kept.map { it.t }).containsExactly(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L)
    }

    /** The end is kept even when it falls inside the spacing, so a drive still ends where it did. */
    @Test
    fun `the last point always survives`() {
        val track = (0..23).map { pt(it * 1_000L) }

        val kept = DocsDelta.thinPoints(track)

        assertThat(kept.last().t).isEqualTo(23_000L)
        assertThat(kept.map { it.t }).containsExactly(0L, 10_000L, 20_000L, 23_000L)
    }

    @Test
    fun `a track already sparser than the spacing is untouched`() {
        val track = listOf(pt(0), pt(15_000), pt(31_000), pt(50_000))

        assertThat(DocsDelta.thinPoints(track)).isEqualTo(track)
    }

    @Test
    fun `two points or fewer are never thinned`() {
        assertThat(DocsDelta.thinPoints(listOf(pt(0), pt(500)))).hasSize(2)
        assertThat(DocsDelta.thinPoints(listOf(pt(0)))).hasSize(1)
        assertThat(DocsDelta.thinPoints(emptyList())).isEmpty()
    }
}
