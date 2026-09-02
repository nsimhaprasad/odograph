package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.Period
import `in`.odograph.tracker.core.Periods
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PeriodTotals
import `in`.odograph.tracker.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RoutesUi(
    val totals: PeriodTotals = PeriodTotals(0, 0.0, 0, 0, 0f),
    val rows: List<RouteRow> = emptyList(),
    val loaded: Boolean = false
)

data class RouteRow(
    val from: String,
    val to: String,
    val drives: Int,
    val avgDistanceM: Double,
    val avgDurationS: Long,
    val totalDistanceM: Double
)

/**
 * Totals and most-visited routes over a chosen calendar period.
 *
 * Periods are calendar-aligned rather than rolling windows: "this month" has to agree with what
 * the odometer says when you compare them at month end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutesScreen(palette: Palette) {
    val ctx = LocalContext.current
    var period by remember { mutableStateOf(Period.MONTH) }
    var ui by remember { mutableStateOf(RoutesUi()) }

    LaunchedEffect(period) {
        val range = Periods.rangeFor(period, System.currentTimeMillis())
        val next = withContext(Dispatchers.IO) {
            runCatching {
                val dao = OdographDb.get(ctx).dao()
                val places = dao.allPlaces().associateBy { it.id }
                RoutesUi(
                    totals = dao.periodTotals(range.fromMs, range.toMs),
                    rows = dao.routeSummariesBetween(range.fromMs, range.toMs).map { s ->
                        RouteRow(
                            from = places[s.startId]?.displayName ?: "Unknown",
                            to = places[s.endId]?.displayName ?: "Unknown",
                            drives = s.drives,
                            avgDistanceM = s.avgDistanceM,
                            avgDurationS = s.avgDurationS.toLong(),
                            totalDistanceM = s.totalDistanceM
                        )
                    },
                    loaded = true
                )
            }.getOrElse { RoutesUi(loaded = true) }
        }
        // One assignment, so the headline and the list can never disagree. Two separate state
        // writes from a background coroutine are two snapshot mutations, and a recomposition
        // triggered by one can read the other before it has been applied.
        ui = next
    }

    val totals = ui.totals
    val rows = ui.rows
    val loaded = ui.loaded

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        val countWidth = maxWidth * 0.17f

        Column(Modifier.fillMaxSize().padding(m.pad)) {

            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                Period.entries.forEach { p ->
                    Chip(p.label, p == period, palette, m) { period = p }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = m.gap, bottom = m.gap),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Stat(formatKm(totals.distanceM), "KM   TOTAL", palette, m, size = m.stat)
                Stat("${totals.drives}", "DRIVES", palette, m, size = m.stat)
                Stat(formatHhMm(totals.durationS), "H:MM   ELAPSED", palette, m, size = m.stat)
                Stat(formatHhMm(totals.movingS), "H:MM   MOVING", palette, m, size = m.stat)
                Stat(
                    "${mpsToKmh(totals.bestMaxSpeedMps).toInt()}",
                    "KM/H   TOP", palette, m, size = m.stat
                )
            }

            Text(
                text = "MOST VISITED ROUTES",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 2.2.sp,
                modifier = Modifier.padding(bottom = m.gap / 2)
            )

            if (loaded && rows.isEmpty()) {
                Text(
                    text = if (totals.drives == 0) {
                        "No drives in this period."
                    } else {
                        "Drives recorded, but none grouped into a route yet. A trip is grouped " +
                            "once it ends, and it ends when the ignition does."
                    },
                    color = palette.dim,
                    fontSize = m.body,
                    lineHeight = m.body * 1.6f
                )
            }

            LazyColumn {
                items(rows) { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 3),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${r.from}  →  ${r.to}",
                                color = palette.numeral,
                                fontSize = m.stat,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = "avg ${formatKm(r.avgDistanceM)} km · " +
                                    "${formatHhMm(r.avgDurationS)} · " +
                                    "${formatKm(r.totalDistanceM)} km total",
                                color = palette.dim,
                                fontSize = m.body,
                                maxLines = 1
                            )
                        }
                        Column(Modifier.width(countWidth), horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${r.drives}",
                                color = palette.accent,
                                fontSize = m.stat,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (r.drives == 1) "DRIVE" else "DRIVES",
                                color = palette.label,
                                fontSize = m.label,
                                letterSpacing = 1.4.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
