package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.RangeCalibration
import `in`.odograph.tracker.core.EfficiencyStats
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.TRIP_PAGE_SIZE
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.ui.map.BareRouteTrace
import `in`.odograph.tracker.ui.map.RouteMap
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TripSort { RECENT, KM }

/** What the right-hand pane shows for the selected drive: its route or its logged numbers. */
private enum class TripView { MAP, DETAILS }

/** Technical/functional facts about one drive, resolved off the UI thread. */
/**
 * How close to the end of the loaded rows the scroll must come before the next page is fetched.
 *
 * Far enough ahead that the list never visibly stops, close enough that opening the screen does
 * not quietly pull the whole history in anyway.
 */
private const val PREFETCH_ROWS = 10

private data class TripDetail(
    val startPlace: String?,
    val endPlace: String?,
    val points: Int,
    val kwhPer100: Double?,
    val rangeAtFullKm: Double?,
    val impliedRateInr: Double?,
    /** How the range estimate did on this drive, or null when there was nothing to score. */
    val verdict: RangeCalibration.Verdict? = null,
    val capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH
)

private sealed interface TripRowItem {
    data class Header(val date: Long, val label: String, val count: Int) : TripRowItem
    data class Drive(val trip: TripEntity) : TripRowItem
}

@Composable
fun TripListScreen(showTiles: Boolean, palette: Palette) {
    val ctx = LocalContext.current
    var trips by remember { mutableStateOf<List<TripEntity>>(emptyList()) }
    var selected by remember { mutableStateOf<TripEntity?>(null) }
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var sort by remember { mutableStateOf(TripSort.RECENT) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var view by remember { mutableStateOf(TripView.DETAILS) }
    var detail by remember { mutableStateOf<TripDetail?>(null) }

    // The box has no SIM and therefore no NITZ, so its own timezone may be UTC. Render against
    // the configured zone rather than trusting the device.
    val fmt = remember {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            .apply { timeZone = Settings(ctx).zone }
    }
    val fmtDay = remember {
        SimpleDateFormat("EEE, d MMM", Locale.getDefault())
            .apply { timeZone = Settings(ctx).zone }
    }

    // Paged, and fetched as the list is scrolled rather than all at once. Rendering was always
    // lazy — LazyColumn only composes what fits — but the loading was not: every drive ever made
    // came into memory to show the dozen on the glass. Fine for a year of driving and not for a
    // decade, and the cost falls exactly where it is least welcome, on opening the screen.
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var loadedPages by remember { mutableStateOf(0) }
    var allLoaded by remember { mutableStateOf(false) }
    var loadingPage by remember { mutableStateOf(false) }

    suspend fun loadNextPage() {
        if (allLoaded || loadingPage) return
        loadingPage = true
        // A page that cannot be read stops the list rather than the app. The failure is kept so
        // the screen can say which part is broken instead of sitting blank and looking empty.
        when (val page = withContext(Dispatchers.IO) {
            loaded { OdographDb.get(ctx).dao().tripsPage(TRIP_PAGE_SIZE, loadedPages * TRIP_PAGE_SIZE) }
        }) {
            is Loaded.Failed -> { failure = page.error; allLoaded = true }
            is Loaded.Ready -> {
                trips = trips + page.value
                loadedPages += 1
                if (page.value.size < TRIP_PAGE_SIZE) allLoaded = true
            }
            Loaded.Loading -> Unit
        }
        loadingPage = false
    }

    LaunchedEffect(Unit) {
        loadNextPage()
        selected = trips.firstOrNull()
    }
    LaunchedEffect(selected?.id) {
        val id = selected?.id ?: return@LaunchedEffect
        // The route and the detail pane fail independently of the list and of each other: a drive
        // whose points cannot be read should still show its numbers, and one whose numbers cannot
        // be worked out should still draw its route.
        route = withContext(Dispatchers.IO) {
            loaded {
                val fixes = OdographDb.get(ctx).dao().pointsFor(id).map {
                    Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
                }
                buildSmoothRoute(fixes)
            }
        }.let { if (it is Loaded.Ready) it.value else emptyList() }
        detail = withContext(Dispatchers.IO) {
            loaded {
            val dao = OdographDb.get(ctx).dao()
            val t = dao.tripById(id) ?: return@withContext null
            val energyKwh = t.energyKwh
            val kwhPer100 = BatteryMath.kwhPer100Km(energyKwh, t.distanceM)
            TripDetail(
                startPlace = t.startPlaceId?.let { dao.placeById(it)?.displayName },
                endPlace = t.endPlaceId?.let { dao.placeById(it)?.displayName },
                points = dao.pointCountFor(t.id),
                kwhPer100 = kwhPer100?.let { BatteryMath.round2(it) },
                rangeAtFullKm = kwhPer100?.let {
                    BatteryMath.round2(
                        BatteryMath.rangeAtFullKwh(Settings(ctx).batteryCapacityKwh, it)
                    )
                },
                impliedRateInr = if (energyKwh != null && energyKwh > 0 && t.costInr != null) {
                    BatteryMath.round2(t.costInr!! / energyKwh)
                } else null,
                // Scored against the drives that came before this one and nothing else, so the
                // figure is what the app would have told the driver that morning rather than one
                // worked out afterwards with the answer already in hand.
                verdict = energyKwh?.let {
                    RangeCalibration.verdict(
                        drive = EfficiencyStats.Sample(
                            t.startedAt, t.distanceM, t.movingS, it, t.avgTempC
                        ),
                        before = dao.efficiencySamplesBefore(t.startedAt).map { row ->
                            EfficiencyStats.Sample(
                                row.startedAt, row.distanceM, row.movingS, row.energyKwh,
                                row.avgTempC, row.climateShare
                            )
                        },
                        zone = Settings(ctx).zone
                    )
                },
                capacityKwh = Settings(ctx).batteryCapacityKwh
            )
            }.let { if (it is Loaded.Ready) it.value else null }
        }
    }
    // A new sort mode resets the collapsible group state so nothing is hidden by surprise.
    LaunchedEffect(sort) { collapsed = emptySet() }

    val rows = remember(trips, sort, collapsed) {
        val ordered = when (sort) {
            TripSort.KM -> trips.sortedByDescending { it.distanceM }
            TripSort.RECENT -> trips.sortedByDescending { it.startedAt }
        }
        if (sort == TripSort.KM) {
            ordered.map { TripRowItem.Drive(it) }
        } else {
            val groups = LinkedHashMap<Long, MutableList<TripEntity>>()
            ordered.forEach { groups.getOrPut(it.startedAt) { mutableListOf() }.add(it) }
            groups.entries.flatMap { (day, list) ->
                val key = day.toString()
                buildList<TripRowItem> {
                    add(TripRowItem.Header(day, fmtDay.format(Date(day)), list.size))
                    if (key !in collapsed) addAll(list.map(TripRowItem::Drive))
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
    val m = rememberMetrics(maxWidth, maxHeight)
    val broken = failure
    if (broken != null) {
        FeatureUnavailable("DRIVES", broken, palette, m) {
            failure = null; trips = emptyList(); loadedPages = 0; allLoaded = false
        }
        return@BoxWithConstraints
    }
    Row(Modifier.fillMaxSize()) {
        // Opaque pane so nothing from the map (or anything else) ever shows through the list.
        Column(Modifier.weight(0.34f).fillMaxHeight().background(palette.ground)) {
            Text(
                text = "DRIVES  ·  ${trips.size}",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = m.pad, top = m.pad, bottom = m.gap / 2)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
                modifier = Modifier.padding(start = m.pad, bottom = m.gap)
            ) {
                Chip("RECENT", sort == TripSort.RECENT, palette, m) { sort = TripSort.RECENT }
                Chip("KM", sort == TripSort.KM, palette, m) { sort = TripSort.KM }
            }
            if (trips.isEmpty()) {
                Text(
                    text = "No drives recorded yet.\nStart the car and go somewhere.",
                    color = palette.dim,
                    fontSize = m.body,
                    modifier = Modifier.padding(m.pad)
                )
            }
            val listState = rememberLazyListState()

            // Fetch the next page once the tail is in sight, so the list never stops under a
            // finger. Keyed on the last visible index, so it fires when scrolling arrives rather
            // than on every frame of the scroll.
            val lastVisible by remember {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                }
            }
            LaunchedEffect(lastVisible, rows.size) {
                if (rows.isNotEmpty() && lastVisible >= rows.size - PREFETCH_ROWS) loadNextPage()
            }

            LazyColumn(state = listState) {
                items(rows, key = {
                    when (it) {
                        is TripRowItem.Header -> "h:${it.date}"
                        is TripRowItem.Drive -> "t:${it.trip.id}"
                    }
                }) { item ->
                    when (item) {
                        is TripRowItem.Header -> {
                            val key = item.date.toString()
                            val open = key !in collapsed
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        collapsed = if (open) collapsed + key else collapsed - key
                                    }
                                    .padding(horizontal = m.pad, vertical = m.gap / 3),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (open) "\u25be" else "\u25b8",
                                    color = palette.accent,
                                    fontSize = m.body
                                )
                                Text(
                                    text = item.label.uppercase(Locale.getDefault()),
                                    color = palette.label,
                                    fontSize = m.label,
                                    letterSpacing = 1.4.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = item.count.toString(),
                                    color = palette.dim,
                                    fontSize = m.body
                                )
                            }
                        }
                        is TripRowItem.Drive -> TripRow(
                            item.trip, item.trip.id == selected?.id, palette, m, fmt
                        ) { selected = item.trip }
                    }
                }
            }
        }
        Column(
            Modifier.weight(0.66f).fillMaxHeight().background(palette.ground).clipToBounds()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
                modifier = Modifier.padding(start = m.pad, top = m.pad, bottom = m.gap / 2)
            ) {
                Chip("MAP", view == TripView.MAP, palette, m) { view = TripView.MAP }
                Chip("DETAILS", view == TripView.DETAILS, palette, m) { view = TripView.DETAILS }
            }
            when {
                view == TripView.DETAILS && (selected == null || detail != null) -> {
                    TripDetailPane(
                        trip = selected,
                        detail = detail,
                        palette = palette,
                        m = m,
                        fmt = fmt
                    )
                }
                route.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Select a drive",
                            color = palette.label,
                            fontSize = m.body,
                            modifier = Modifier.fillMaxWidth().padding(m.pad),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                showTiles -> RouteMap(route, palette, Modifier.fillMaxSize())
                else -> BareRouteTrace(route, palette, Modifier.fillMaxSize())
            }
        }
    }
    }
}

@Composable
private fun TripRow(
    trip: TripEntity,
    active: Boolean,
    palette: Palette,
    m: Metrics,
    fmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) palette.trackSoft else palette.ground)
            .clickable(onClick = onClick)
            .padding(horizontal = m.pad, vertical = m.gap / 2)
    ) {
        Text(
            text = fmt.format(Date(trip.startedAt)),
            color = if (active) palette.accent else palette.numeral,
            fontSize = m.stat,
            fontWeight = FontWeight.Medium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${formatKm(trip.distanceM)} km", color = palette.dim, fontSize = m.body)
            Text(formatHhMm(trip.durationS), color = palette.dim, fontSize = m.body)
            Text(
                "${mpsToKmh(trip.maxSpeedMps).toInt()} max",
                color = palette.dim, fontSize = m.body
            )
            // Every climb and every descent, never netted: each up costs battery, each down
            // regenerates, so the driver wants both numbers.
            if (trip.elevGainM > 0 || trip.elevLossM > 0) {
                Text(
                    "↑%.0f ↓%.0f m".format(trip.elevGainM, trip.elevLossM),
                    color = palette.accent, fontSize = m.body
                )
            }
        }
        trip.batteryLine()?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(it, color = palette.accent, fontSize = m.label)
            }
        }
    }
}

/**
 * One spare line under a drive describing what it did to the battery: the starting and ending
 * charge side by side (80→64%), kW·h, the cost when a rate is known, and the achieved mileage.
 * Every piece stays silent until it exists — no blanks are ever prettified into a fabricated 0.
 */
private fun TripEntity.batteryLine(): String? {
    val parts = mutableListOf<String>()
    if (socStart != null && socEnd != null) {
        parts += "%.0f→%.0f%%".format(socStart, socEnd)
    }
    energyKwh?.let {
        if (it != 0.0) parts += "%.2f kWh".format(it)
    }
    if (socStart != null && socEnd != null && energyKwh != null && energyKwh > 0 && distanceM > 0) {
        parts += "%.2f km/kWh".format(BatteryMath.kmPerKwh(energyKwh, distanceM) ?: 0.0)
    }
    costInr?.let { parts += "₹ %.2f".format(it) }
    return if (parts.isEmpty()) null else parts.joinToString("  ·  ")
}

/**
 * The "details" face of the trip tab: no map, just the numbers a drive actually logged. Every
 * row stays silent until the value is real — the same honesty the battery line uses.
 */
@Composable
private fun TripDetailPane(
    trip: TripEntity?,
    detail: TripDetail?,
    palette: Palette,
    m: Metrics,
    fmt: SimpleDateFormat
) {
    val t = trip
    if (t == null || detail == null) {
        Text(
            text = "Select a drive",
            color = palette.label,
            fontSize = m.body,
            modifier = Modifier.fillMaxSize().padding(m.pad),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = m.pad)
    ) {
        Text(
            text = fmt.format(Date(t.startedAt)),
            color = palette.numeral,
            fontSize = m.stat,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (t.endedAt != null) {
                "to ${fmt.format(Date(t.endedAt))}  ·  ${formatHhMm(t.durationS)}"
            } else {
                "${formatHhMm(t.durationS)}  ·  ${formatKm(t.distanceM)} km"
            },
            color = palette.dim,
            fontSize = m.body,
            modifier = Modifier.padding(top = m.gap / 3)
        )

        // Above the route, because it is the question the driver actually arrives with. Everything
        // below is what happened; this is whether we saw it coming.
        detail.verdict?.let { v ->
            val capacity = detail.capacityKwh
            val predictedKm = v.predictedRangeAtFullKm(capacity)
            val actualKm = v.actualRangeAtFullKm(capacity)
            val off = kotlin.math.abs(v.errorPercent)
            val close = off < RangeCalibration.CLOSE_ENOUGH_PERCENT

            SectionLabel("PREDICTION", palette, m)
            DetailRow(
                "We expected",
                "%.1f kWh/100km  ·  %.0f km at 100%%".format(v.predictedKwhPer100Km, predictedKm),
                palette, m
            )
            DetailRow(
                "It actually cost",
                "%.1f kWh/100km  ·  %.0f km at 100%%".format(v.actualKwhPer100Km, actualKm),
                palette, m
            )
            DetailRow(
                "Verdict",
                when {
                    close -> "within %.0f%% — the estimate held".format(off)
                    // Signed on purpose. "Out by 12%" leaves the driver to work out which way,
                    // and only one of the two directions strands anybody.
                    v.errorPercent > 0 -> "%.0f%% optimistic — %.0f km short of the promise"
                        .format(off, predictedKm - actualKm)
                    else -> "%.0f%% cautious — %.0f km further than promised"
                        .format(off, actualKm - predictedKm)
                },
                palette, m,
                valueColor = if (close) palette.good else palette.warn
            )
            DetailRow(
                "Based on",
                "%d earlier drives%s".format(
                    v.basedOnDrives,
                    if (v.corrected) ", bias-corrected" else ", uncorrected"
                ),
                palette, m
            )
        }

        SectionLabel("ROUTE", palette, m)
        DetailRow("Start", detail.startPlace ?: coords(t.startLat, t.startLon), palette, m)
        DetailRow("End", detail.endPlace ?: coords(t.endLat, t.endLon), palette, m)
        DetailRow("Track", "${formatKm(t.distanceM)} km  ·  ${detail.points} points", palette, m)

        SectionLabel("BATTERY", palette, m)
        if (t.socStart != null && t.socEnd != null) {
            DetailRow("Charge", "%.0f → %.0f %%".format(t.socStart, t.socEnd), palette, m)
        }
        t.energyKwh?.let { DetailRow("Energy", "%.2f kWh".format(it), palette, m) }
        BatteryMath.kmPerKwh(t.energyKwh, t.distanceM)?.let {
            DetailRow("Mileage", "%.2f km/kWh".format(it), palette, m)
        }
        detail.kwhPer100?.let { DetailRow("Consumption", "%.2f kWh/100km".format(it), palette, m) }
        detail.rangeAtFullKm?.let { DetailRow("Range at 100%", "%.0f km".format(it), palette, m) }
        t.costInr?.let { DetailRow("Cost", "₹ %.2f".format(it), palette, m) }
        detail.impliedRateInr?.let {
            DetailRow("Effective rate", "₹ %.2f /kWh".format(it), palette, m)
        }

        // What the car's own counters made of the same drive. Shown beside ours rather than
        // instead of it because the two disagree and it is not yet settled which is right: ours
        // is quantised to whole percent of the pack — 0.53 kWh a step — while the car's counter
        // has no such floor, and the only captured frames of it are physically implausible. Enough
        // drives with both figures side by side will answer that without anybody guessing.
        val carEnergy = t.carEnergyKwh
        val carDistance = t.carDistanceKm
        if (carEnergy != null || carDistance != null) {
            SectionLabel("WHAT THE CAR COUNTED", palette, m)
            carEnergy?.let { DetailRow("Car's energy", "%.2f kWh".format(it), palette, m) }
            carDistance?.let { DetailRow("Car's distance", "%.1f km".format(it), palette, m) }
            if (carEnergy != null && carEnergy > 0 && carDistance != null && carDistance > 0.5) {
                DetailRow(
                    "Car's consumption",
                    "%.2f kWh/100km".format(carEnergy / carDistance * 100.0),
                    palette, m
                )
                detail.kwhPer100?.let { ours ->
                    val theirs = carEnergy / carDistance * 100.0
                    val gap = if (ours > 0) (theirs / ours - 1.0) * 100.0 else 0.0
                    DetailRow(
                        "Against ours",
                        if (kotlin.math.abs(gap) < 1) "the same"
                        else "%+.0f%% (%s)".format(gap, if (gap > 0) "car says worse" else "car says better"),
                        palette, m,
                        valueColor = if (kotlin.math.abs(gap) < 10) palette.good else palette.warn
                    )
                }
            }
        }

        SectionLabel("SPEED", palette, m)
        DetailRow("Max", "${mpsToKmh(t.maxSpeedMps).toInt()} km/h", palette, m)
        DetailRow("Average", "${mpsToKmh(t.avgSpeedMps.toFloat()).toInt()} km/h", palette, m)
        DetailRow("Moving", formatHhMm(t.movingS), palette, m)
        DetailRow("Stationary", formatHhMm((t.durationS - t.movingS).coerceAtLeast(0L)), palette, m)
        if (t.slowestKmMps > 0f) {
            DetailRow("Slowest km", "${mpsToKmh(t.slowestKmMps.toFloat()).toInt()} km/h", palette, m)
        }

        SectionLabel("TERRAIN", palette, m)
        if (t.elevGainM > 0 || t.elevLossM > 0) {
            DetailRow("Climb / descend", "↑%.0f m  ·  ↓%.0f m".format(t.elevGainM, t.elevLossM), palette, m)
        }
    }
}

private fun coords(lat: Double?, lon: Double?): String =
    if (lat != null && lon != null) "%.4f, %.4f".format(lat, lon) else "—"

@Composable
private fun SectionLabel(title: String, palette: Palette, m: Metrics) {
    Text(
        text = title,
        color = palette.label,
        fontSize = m.label,
        letterSpacing = 2.2.sp,
        modifier = Modifier.padding(top = m.gap, bottom = m.gap / 3)
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    palette: Palette,
    m: Metrics,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = m.gap / 4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, color = palette.dim, fontSize = m.body)
        Text(
            value,
            color = valueColor ?: palette.numeral,
            fontSize = m.body,
            fontWeight = FontWeight.Medium
        )
    }
}