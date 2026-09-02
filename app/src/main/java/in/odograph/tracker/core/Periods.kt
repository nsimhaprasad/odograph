package `in`.odograph.tracker.core

import java.util.Calendar
import java.util.TimeZone

enum class Period(val label: String) {
    WEEK("WEEK"),
    MONTH("MONTH"),
    LAST_MONTH("LAST MTH"),
    YEAR("YEAR"),
    ALL("ALL")
}

data class Range(val fromMs: Long, val toMs: Long)

/**
 * Calendar ranges in the device's own timezone.
 *
 * Kept pure and clock-injected so the boundaries can be tested. "This month" has to mean the
 * calendar month rather than the last thirty days, otherwise a total quietly disagrees with what
 * the odometer says when you compare them at month end.
 */
object Periods {

    fun rangeFor(period: Period, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Range {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return when (period) {
            Period.ALL -> Range(0L, Long.MAX_VALUE)

            Period.WEEK -> {
                val start = startOfDay(cal, tz).apply {
                    // Calendar's first day of week is locale-dependent; anchor it explicitly.
                    val delta = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
                    add(Calendar.DAY_OF_YEAR, -delta)
                }
                Range(start.timeInMillis, nowMs)
            }

            Period.MONTH -> {
                val start = startOfDay(cal, tz).apply { set(Calendar.DAY_OF_MONTH, 1) }
                Range(start.timeInMillis, nowMs)
            }

            Period.LAST_MONTH -> {
                val thisMonth = startOfDay(cal, tz).apply { set(Calendar.DAY_OF_MONTH, 1) }
                val end = thisMonth.timeInMillis
                thisMonth.add(Calendar.MONTH, -1)
                Range(thisMonth.timeInMillis, end)
            }

            Period.YEAR -> {
                val start = startOfDay(cal, tz).apply { set(Calendar.DAY_OF_YEAR, 1) }
                Range(start.timeInMillis, nowMs)
            }
        }
    }

    private fun startOfDay(from: Calendar, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply {
            timeInMillis = from.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
