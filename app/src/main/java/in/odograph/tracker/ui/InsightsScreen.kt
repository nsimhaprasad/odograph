package `in`.odograph.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.Analytics
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PeriodCharges
import `in`.odograph.tracker.data.PeriodCost
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class CostBucket(val label: String, val days: Long) {
    TODAY("TODAY", 1), WEEK("7D", 7), MONTH("30D", 30)
}

private data class InsightsUi(
    val cost: PeriodCost = PeriodCost(0, 0.0, 0.0, 0.0),
    val charges: PeriodCharges = PeriodCharges(0, 0.0, 0.0),
    val range: List<Analytics.RangePoint> = emptyList(),
    val rankings: Analytics.Rankings = Analytics.Rankings(emptyList(), emptyList()),
    val drains: List<Analytics.DrainWindow> = emptyList(),
    val domains: Map<Long, String> = emptyMap(),
    val loaded: Boolean = false
)

/**
 * The INSIGHTS tab: money, range-at-full efficiency, route quality, and the cost of sitting still.
 *
 * Every number comes from a guarded estimate (see Analytics), so a screen with nothing worth
 * quoting says so honestly instead of printing a fabricated zero.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsightsScreen(palette: Palette) {
    val ctx = LocalContext.current
    val zone = Settings(ctx).zone
    var bucket by remember { mutableStateOf(CostBucket.MONTH) }
    var ui by remember { mutableStateOf(InsightsUi()) }

    LaunchedEffect(bucket) {
        val now = System.currentTimeMillis()
        val fromMs = when (bucket) {
            CostBucket.TODAY -> {
                val cal = java.util.Calendar.getInstance(zone)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            CostBucket.WEEK -> now - bucket.days * 24 * 3_600_000L
            CostBucket.MONTH -> now - bucket.days * 24 * 3_600_000L
        }
        val next = withContext(Dispatchers.IO) {
            runCatching {
                val dao = OdographDb.get(ctx).dao()
                val capacity = BatteryMath.DEFAULT_CAPACITY_KWH
                InsightsUi(
                    cost = dao.periodCost(fromMs),
                    charges = dao.periodCharges(fromMs),
                    range = Analytics.dailyRangeSeries(dao.dailyEfficiency(fromMs), capacity).takeLast(60),
                    rankings = Analytics.routeRankings(dao.routeTripsForEfficiency()),
                    drains = Analytics.drainWindows(dao.parkedBatteryFrames(), capacity),
                    domains = dao.allPlaces().associate { it.id to it.displayName },
                    loaded = true
                )
            }.getOrElse { InsightsUi(loaded = true) }
        }
        ui = next
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val m = rememberMetrics(maxWidth, maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(m.pad),
            verticalArrangement = Arrangement.spacedBy(m.gap)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                CostBucket.entries.forEach { b ->
                    Chip(b.label, b == bucket, palette, m) { bucket = b }
                }
            }

            costSection(ui, palette, m)
            rangeSection(ui, palette, m)
            routeSection(ui, palette, m)
            drainSection(ui, palette, m)
        }
    }
}

@Composable
private fun costSection(ui: InsightsUi, palette: Palette, m: Metrics) {
    val cost = ui.cost
    val charges = ui.charges
    val totalKm = cost.distanceM / 1000.0
    val totalInr = cost.costInr + charges.costInr

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat(rupees(totalInr), "TOTAL ₹", palette, m, size = m.stat)
        Stat(formatKm(totalKm), "KM", palette, m, size = m.stat)
        Stat(
            if (totalKm >= 1.0) "₹%.2f".format(totalInr / totalKm) else "—",
            "₹ / KM", palette, m, size = m.stat
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(m.gap)) {
        SmallStat(rupees(cost.costInr), "TRIP ₹", palette, m)
        SmallStat(rupees(charges.costInr), "CHARGE ₹", palette, m)
        SmallStat("%.0f".format(cost.energyKwh), "TRIP KWH", palette, m)
        SmallStat(chargeEfficiency(charges), "₹ / KWH", palette, m)
    }
}

@Composable
private fun rangeSection(ui: InsightsUi, palette: Palette, m: Metrics) {
    Text(
        text = "RANGE AT 100%",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap)
    )
    if (ui.range.isEmpty()) {
        Text(
            text = "No instrumented days yet.",
            color = palette.dim,
            fontSize = m.body
        )
        return
    }
    val latest = ui.range.last()
    val earliest = ui.range.first()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("${latest.rangeAtFullKm.toInt()}", "KM NOW", palette, m, size = m.stat)
        Stat("${earliest.rangeAtFullKm.toInt()}", "KM EARLIEST", palette, m, size = m.stat)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(top = m.gap)
    ) {
        val pts = ui.range.map { it.rangeAtFullKm.toFloat() }
        val min = pts.minOrNull() ?: 0f
        val max = pts.maxOrNull() ?: 1f
        val span = (max - min).takeIf { it > 0 } ?: 1f
        val step = size.width / (pts.size - 1)
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height * (1f - (v - min) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = palette.accent, style = Stroke(width = 2.5.dp.toPx()))
    }
}

@Composable
private fun routeSection(ui: InsightsUi, palette: Palette, m: Metrics) {
    if (ui.rankings.best.isEmpty() && ui.rankings.worst.isEmpty()) return
    Text(
        text = "ROUTE EFFICIENCY  ·  KWH / 100KM",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap)
    )
    rankList(ui.rankings.best, "BEST", palette, m, ui.domains)
    Text(
        text = "WORST",
        color = palette.warn,
        fontSize = m.label,
        modifier = Modifier.padding(top = m.gap / 2)
    )
    rankList(ui.rankings.worst, null, palette, m, ui.domains)
}

@Composable
private fun rankList(
    ranks: List<Analytics.RouteRank>,
    header: String?,
    palette: Palette,
    m: Metrics,
    domains: Map<Long, String>
) {
    if (header != null) {
        Text(
            text = header,
            color = palette.accent,
            fontSize = m.label,
            modifier = Modifier.padding(top = m.gap / 2)
        )
    }
    ranks.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = m.gap / 3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${domains[r.from] ?: "?"}  →  ${domains[r.to] ?: "?"}",
                    color = palette.numeral,
                    fontSize = m.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = "%.0f km · %d drives".format(r.km, r.drives),
                    color = palette.dim,
                    fontSize = m.body,
                    maxLines = 1
                )
            }
            Text(
                text = "%.1f".format(r.kwhPer100Km),
                color = palette.accent2,
                fontSize = m.stat,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun drainSection(ui: InsightsUi, palette: Palette, m: Metrics) {
    if (ui.drains.isEmpty()) return
    val latest = ui.drains.maxByOrNull { it.startMs }
    val worst = ui.drains.maxByOrNull { it.dropPercent }
    Text(
        text = "OVERNIGHT BATTERY DRAIN",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("%.2f%%".format(latest!!.dropPercent), "LAST", palette, m, size = m.stat)
        Stat("%.2f%%".format(worst!!.dropPercent), "WORST", palette, m, size = m.stat)
    }
}

private fun rupees(inr: Double): String = if (inr >= 1.0) "₹%.0f".format(inr) else "₹0"

private fun chargeEfficiency(c: PeriodCharges): String =
    if (c.energyKwh >= 0.5 && c.costInr >= 1.0) "₹%.2f".format(c.costInr / c.energyKwh) else "—"