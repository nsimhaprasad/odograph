package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PeriodsTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    /** 2026-09-17, 14:30 IST — a Thursday mid-month. */
    private val now = Calendar.getInstance(ist).apply {
        set(2026, Calendar.SEPTEMBER, 17, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun fieldsOf(ms: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(ist).apply { timeInMillis = ms }
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `month starts on the first of the calendar month, not thirty days ago`() {
        val r = Periods.rangeFor(Period.MONTH, now, ist)
        assertThat(fieldsOf(r.fromMs)).isEqualTo(Triple(2026, Calendar.SEPTEMBER, 1))
        assertThat(r.toMs).isEqualTo(now)
    }

    @Test
    fun `month starts at midnight local time`() {
        val c = Calendar.getInstance(ist).apply {
            timeInMillis = Periods.rangeFor(Period.MONTH, now, ist).fromMs
        }
        assertThat(c.get(Calendar.HOUR_OF_DAY)).isEqualTo(0)
        assertThat(c.get(Calendar.MINUTE)).isEqualTo(0)
        assertThat(c.get(Calendar.MILLISECOND)).isEqualTo(0)
    }

    @Test
    fun `last month is a closed range ending where this month begins`() {
        val last = Periods.rangeFor(Period.LAST_MONTH, now, ist)
        val thisMonth = Periods.rangeFor(Period.MONTH, now, ist)
        assertThat(fieldsOf(last.fromMs)).isEqualTo(Triple(2026, Calendar.AUGUST, 1))
        assertThat(last.toMs).isEqualTo(thisMonth.fromMs)
    }

    @Test
    fun `last month does not overlap this month`() {
        val last = Periods.rangeFor(Period.LAST_MONTH, now, ist)
        val thisMonth = Periods.rangeFor(Period.MONTH, now, ist)
        assertThat(last.toMs).isLessThanOrEqualTo(thisMonth.fromMs)
    }

    @Test
    fun `year starts on the first of January`() {
        val r = Periods.rangeFor(Period.YEAR, now, ist)
        assertThat(fieldsOf(r.fromMs)).isEqualTo(Triple(2026, Calendar.JANUARY, 1))
    }

    @Test
    fun `week starts no more than seven days back and at midnight`() {
        val r = Periods.rangeFor(Period.WEEK, now, ist)
        assertThat(now - r.fromMs).isLessThan(7 * 24 * 3600_000L)
        val c = Calendar.getInstance(ist).apply { timeInMillis = r.fromMs }
        assertThat(c.get(Calendar.HOUR_OF_DAY)).isEqualTo(0)
    }

    @Test
    fun `all time spans everything`() {
        val r = Periods.rangeFor(Period.ALL, now, ist)
        assertThat(r.fromMs).isEqualTo(0L)
        assertThat(r.toMs).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `a drive on the first of the month falls inside this month`() {
        val firstOfMonth = Calendar.getInstance(ist).apply {
            set(2026, Calendar.SEPTEMBER, 1, 0, 5, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val r = Periods.rangeFor(Period.MONTH, now, ist)
        assertThat(firstOfMonth).isBetween(r.fromMs, r.toMs)
    }

    @Test
    fun `a drive late on the last day of last month does not leak into this month`() {
        val lastDay = Calendar.getInstance(ist).apply {
            set(2026, Calendar.AUGUST, 31, 23, 55, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val thisMonth = Periods.rangeFor(Period.MONTH, now, ist)
        assertThat(lastDay).isLessThan(thisMonth.fromMs)
        val last = Periods.rangeFor(Period.LAST_MONTH, now, ist)
        assertThat(lastDay).isBetween(last.fromMs, last.toMs)
    }
}
