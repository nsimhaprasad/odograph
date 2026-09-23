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
import `in`.odograph.tracker.core.BatteryHealth
import `in`.odograph.tracker.core.DriveContext
import `in`.odograph.tracker.core.EfficiencyStats
import `in`.odograph.tracker.core.JourneyAgreement
import `in`.odograph.tracker.core.RangeCalibration
import `in`.odograph.tracker.core.Telematics
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
    /** What the car costs by when it was driven, how it was driven, and how hot it was. */
    val byTimeOfDay: Map<DriveContext.TimeOfDay, EfficiencyStats.Bucket> = emptyMap(),
    val byCharacter: Map<DriveContext.Character, EfficiencyStats.Bucket> = emptyMap(),
    val byTemperature: Map<DriveContext.TempBand, EfficiencyStats.Bucket> = emptyMap(),
    val byClimate: Map<DriveContext.Climate, EfficiencyStats.Bucket> = emptyMap(),
    /** What the pack measures, and how far the estimate has been missing. */
    val health: BatteryHealth.Health? = null,
    /**
     * How the measured pack compares with the same measurement taken earlier.
     *
     * A single capacity reading says what the pack holds today, which is only half the question a
     * driver is actually asking. Degradation is a direction, not a value, and the readings to
     * compare have been on disk all along.
     */
    val healthTrendPercent: Double? = null,
    /** The pack size the car's own frames imply, against the one configured on the box. */
    val impliedPackKwh: Double? = null,
    val accuracy: RangeCalibration.Accuracy? = null,
    /**
     * How often the app's trip boundaries matched the car's own journey numbering.
     *
     * Reported, not acted on. Every boundary in this app is inferred from stillness and every
     * threshold in that inference is a guess; this is the only independent check on those guesses
     * that exists. It has to earn trust before anything is allowed to depend on it.
     */
    val boundaries: JourneyAgreement.Tally? = null,
    val capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
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
                val capacity = Settings(ctx).batteryCapacityKwh
                val samples = dao.efficiencySamples().map {
                    EfficiencyStats.Sample(
                        it.startedAt, it.distanceM, it.movingS, it.energyKwh, it.avgTempC,
                        it.climateShare
                    )
                }
                InsightsUi(
                    cost = dao.periodCost(fromMs),
                    charges = dao.periodCharges(fromMs),
                    range = Analytics.dailyRangeSeries(dao.dailyEfficiency(fromMs), capacity).takeLast(60),
                    rankings = Analytics.routeRankings(dao.routeTripsForEfficiency()),
                    drains = Analytics.drainWindows(dao.parkedBatteryFrames(), capacity),
                    domains = dao.allPlaces().associate { it.id to it.displayName },
                    byTimeOfDay = EfficiencyStats.byTimeOfDay(samples, zone),
                    byCharacter = EfficiencyStats.byCharacter(samples),
                    byTemperature = EfficiencyStats.byTemperature(samples),
                    byClimate = EfficiencyStats.byClimate(samples),
                    health = BatteryHealth.measure(
                        dao.highSocBattery(BatteryHealth.MIN_SOC_PERCENT), capacity
                    ),
                    healthTrendPercent = run {
                        // The same measurement over the older and newer halves of the near-full
                        // readings. Split rather than windowed by date because what matters is
                        // having enough readings either side to mean anything, and near-full
                        // readings arrive whenever the car happens to be charged, not evenly.
                        val near = dao.highSocBattery(BatteryHealth.MIN_SOC_PERCENT)
                            .sortedBy { it.t }
                        val half = near.size / 2
                        if (half < BatteryHealth.MIN_READINGS) null
                        else BatteryHealth.trendPercent(
                            BatteryHealth.measure(near.take(half), capacity),
                            BatteryHealth.measure(near.drop(half), capacity)
                        )
                    },
                    impliedPackKwh = Telematics.impliedPackKwh(
                        dao.capacityClues(BatteryHealth.MIN_SOC_PERCENT)
                            .map { it.socPercent to it.batteryEnergyKwh }
                    ),
                    accuracy = RangeCalibration.accuracy(RangeCalibration.backtest(samples, zone)),
                    boundaries = run {
                        // Drives the car never commented on produce no row, so they are counted
                        // against the total rather than dropped. Dropping them would turn a
                        // worsening telematics link into an improving agreement figure.
                        val counted = dao.journeyCountsPerTrip()
                        JourneyAgreement.tallyOfCounts(
                            countsPerDrive = counted.map { it.journeys },
                            silentDrives = dao.tripCount() - counted.size
                        )
                    },
                    capacityKwh = capacity,
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
            conditionsSection(ui, palette, m)
            healthSection(ui, palette, m)
            accuracySection(ui, palette, m)
            boundarySection(ui, palette, m)
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

/** One conditioned figure: what it costs, how far that goes, and how much is behind it. */
@Composable
private fun conditionRow(
    label: String,
    bucket: EfficiencyStats.Bucket,
    capacityKwh: Double,
    palette: Palette,
    m: Metrics
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = palette.dim, fontSize = m.body, maxLines = 1)
        Text(
            text = "%.1f kWh/100km  ·  %.0f km  ·  %.0f km/h  ·  %d drives".format(
                bucket.kwhPer100Km, bucket.rangeAtFullKm(capacityKwh),
                bucket.avgMovingKmh, bucket.drives
            ),
            color = palette.numeral,
            fontSize = m.body,
            maxLines = 1
        )
    }
}

/**
 * What the car costs under particular conditions, and how far a full pack goes under each.
 *
 * The whole point of splitting it: a single figure describes the average of a midnight run and a
 * 2 p.m. crawl and is right about neither.
 */
@Composable
private fun conditionsSection(ui: InsightsUi, palette: Palette, m: Metrics) {
    if (ui.byTimeOfDay.isEmpty() && ui.byCharacter.isEmpty() &&
        ui.byTemperature.isEmpty() && ui.byClimate.isEmpty()
    ) return

    Text(
        text = "WHAT IT COSTS  ·  BY CONDITIONS",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap, bottom = m.gap / 2)
    )
    ui.byTimeOfDay[DriveContext.TimeOfDay.DAY]?.let {
        conditionRow("DAY", it, ui.capacityKwh, palette, m)
    }
    ui.byTimeOfDay[DriveContext.TimeOfDay.NIGHT]?.let {
        conditionRow("NIGHT", it, ui.capacityKwh, palette, m)
    }
    ui.byCharacter[DriveContext.Character.CITY]?.let {
        conditionRow("CITY CRAWL", it, ui.capacityKwh, palette, m)
    }
    ui.byCharacter[DriveContext.Character.MIXED]?.let {
        conditionRow("MIXED", it, ui.capacityKwh, palette, m)
    }
    ui.byCharacter[DriveContext.Character.HIGHWAY]?.let {
        conditionRow("OPEN ROAD", it, ui.capacityKwh, palette, m)
    }
    listOf(
        DriveContext.TempBand.COOL to "UNDER 20°",
        DriveContext.TempBand.MILD to "20–28°",
        DriveContext.TempBand.WARM to "28–35°",
        DriveContext.TempBand.HOT to "OVER 35°"
    ).forEach { (band, label) ->
        ui.byTemperature[band]?.let { conditionRow(label, it, ui.capacityKwh, palette, m) }
    }
    // The largest thing the car does with energy that is not moving, and until now it was in the
    // history only as scatter nobody could explain.
    ui.byClimate[DriveContext.Climate.ON]?.let {
        conditionRow("CLIMATE ON", it, ui.capacityKwh, palette, m)
    }
    ui.byClimate[DriveContext.Climate.OFF]?.let {
        conditionRow("CLIMATE OFF", it, ui.capacityKwh, palette, m)
    }
}

/**
 * What the pack measures against what it was sold as.
 *
 * The car never states its capacity, but near a full charge it reports the energy held and the
 * percentage that represents, and those two together are a direct measurement.
 */
@Composable
private fun healthSection(ui: InsightsUi, palette: Palette, m: Metrics) {
    val health = ui.health ?: return
    val trend = ui.healthTrendPercent
    Text(
        text = "BATTERY HEALTH",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap, bottom = m.gap / 2)
    )
    // Which way it is going, above what it is. A pack that holds 51 kWh is unremarkable; a pack
    // that held 52 a month ago and holds 51 now is the whole question, and both readings were
    // already on disk waiting to be subtracted.
    // What the car's own frames imply the pack is, against the number configured here. Everything
    // energy-related is scaled by that constant, so a disagreement is not a curiosity: it moves
    // every range, cost and efficiency figure in the app by the same proportion.
    ui.impliedPackKwh?.let { implied ->
        val off = (ui.capacityKwh - implied) / implied * 100.0
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("THE CAR IMPLIES", color = palette.dim, fontSize = m.body)
            Text(
                text = "%.1f kWh".format(implied) +
                    if (kotlin.math.abs(off) < 1.0) "  ·  matches yours"
                    else "  ·  yours is %.1f%% %s".format(
                        kotlin.math.abs(off), if (off > 0) "high" else "low"
                    ),
                color = if (kotlin.math.abs(off) < 3.0) palette.numeral else palette.warn,
                fontSize = m.body
            )
        }
    }
    trend?.let {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("SINCE THE EARLIER HALF", color = palette.dim, fontSize = m.body)
            Text(
                text = when {
                    kotlin.math.abs(it) < 1.0 -> "holding steady"
                    it < 0 -> "%.1f%% down".format(-it)
                    else -> "%.1f%% up — more readings needed".format(it)
                },
                color = when {
                    kotlin.math.abs(it) < 1.0 -> palette.good
                    it < -5.0 -> palette.warn
                    else -> palette.numeral
                },
                fontSize = m.body
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("MEASURED AT FULL", color = palette.dim, fontSize = m.body)
        Text(
            text = "%.1f kWh of %.1f  ·  %.0f%%".format(
                health.capacityKwh, ui.capacityKwh, health.sohPercent ?: 0.0
            ),
            // Scattered readings are quoted with a warning colour rather than silently, because a
            // capacity that will not sit still is a reason to distrust the figure, not to round it.
            color = if (health.consistent) palette.numeral else palette.caution,
            fontSize = m.body
        )
    }
    Text(
        text = if (health.consistent) {
            "from %d readings near full".format(health.readings)
        } else {
            "from %d readings near full, spread %.1f kWh — treat as provisional".format(
                health.readings, health.spreadKwh
            )
        },
        color = palette.label,
        fontSize = m.label
    )
}

/**
 * How wrong the range estimate has been, measured against drives it had not yet seen.
 *
 * Shown rather than only applied, because a correction quietly folded into the number would hide
 * the thing worth knowing: whether the estimate can be trusted at all.
 */
@Composable
private fun accuracySection(ui: InsightsUi, palette: Palette, m: Metrics) {
    val accuracy = ui.accuracy ?: return
    Text(
        text = "RANGE ESTIMATE  ·  SELF-CHECK",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap, bottom = m.gap / 2)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("BIAS", color = palette.dim, fontSize = m.body)
        Text(
            text = if (accuracy.medianErrorPercent > 0)
                "%.0f%% optimistic".format(accuracy.medianErrorPercent)
            else "%.0f%% cautious".format(-accuracy.medianErrorPercent),
            color = if (kotlin.math.abs(accuracy.medianErrorPercent) < 10) palette.good
            else palette.caution,
            fontSize = m.body
        )
    }
    Text(
        text = "typical miss %.0f%% over %d drives, corrected automatically".format(
            accuracy.typicalMissPercent, accuracy.scored
        ),
        color = palette.label,
        fontSize = m.label
    )
}

/**
 * Whether the app's idea of where a drive begins and ends matches the car's.
 *
 * Its own section rather than a line in the range self-check, because it answers a different
 * question and survives the range estimate having nothing to say. The two together are the whole
 * of what the app knows about its own reliability: one measures the number it predicts, this
 * measures the drives it draws.
 *
 * Nothing acts on this. It is here to accumulate evidence about how this car numbers a journey,
 * which is the thing that has to be known before the signal can be trusted to move a boundary.
 */
@Composable
private fun boundarySection(ui: InsightsUi, palette: Palette, m: Metrics) {
    val t = ui.boundaries ?: return
    // Nothing to report until the car has commented on something. An empty section would read as
    // a finding, and "no data yet" is not one.
    if (t.answered == 0 && t.unknown == 0) return
    Text(
        text = "TRIP BOUNDARIES  ·  SELF-CHECK",
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap, bottom = m.gap / 2)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("THE CAR AGREED", color = palette.dim, fontSize = m.body)
        Text(
            text = t.agreementPercent?.let {
                "%.0f%% of %d drives".format(it, t.answered)
            } ?: "not yet — the car has said nothing",
            color = when {
                t.agreementPercent == null -> palette.dim
                t.agreementPercent!! >= 90.0 -> palette.good
                else -> palette.caution
            },
            fontSize = m.body
        )
    }
    // The split count is the actionable half: those are drives the app ran together that the car
    // considered separate, which is the shape of a boundary set too loose.
    if (t.carSplit > 0) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("THE CAR SPLIT", color = palette.dim, fontSize = m.body)
            Text(
                text = "%d drive%s".format(t.carSplit, if (t.carSplit == 1) "" else "s"),
                color = palette.caution,
                fontSize = m.body
            )
        }
    }
    Text(
        text = "%d drive%s had no telematics link, so the car could not say".format(
            t.unknown, if (t.unknown == 1) "" else "s"
        ),
        color = palette.label,
        fontSize = m.label
    )
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