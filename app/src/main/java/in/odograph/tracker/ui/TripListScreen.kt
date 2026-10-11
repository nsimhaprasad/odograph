package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.JourneyAgreement
import `in`.odograph.tracker.core.RangeCalibration
import `in`.odograph.tracker.core.EfficiencyStats
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.TripSegments
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.toSample
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.data.TRIP_PAGE_SIZE
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.ui.map.BareRouteTrace
import `in`.odograph.tracker.ui.map.RouteMap
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    /** The drive cut into consumption stretches, or null when too few frames to say. */
    val segments: TripSegments.Breakdown? = null,
    val capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
    /** Energy to show: measured where there is one, the indicative figure otherwise. */
    val shownEnergyKwh: Double? = null,
    val shownCostInr: Double? = null,
    /** How a measured figure was arrived at: `counter`, `soc` or `backfill`. */
    val energySource: String? = null,
    /** Whether the figures shown are reckoned rather than measured. */
    val estimated: Boolean = false,
    /**
     * Whether the car agreed this was one journey.
     *
     * [JourneyAgreement.Verdict.UNKNOWN] for most drives, because the telematics link is down more
     * often than it is up. That is displayed rather than hidden: a drive the car said nothing
     * about is evidence of nothing, and showing a blank where the answer should be is how the
     * driver learns the difference between agreement and silence.
     */
    val boundary: JourneyAgreement.Verdict = JourneyAgreement.Verdict.UNKNOWN
)

private sealed interface TripRowItem {
    data class Header(val date: Long, val label: String, val count: Int) : TripRowItem
    data class Drive(val trip: TripEntity) : TripRowItem
}

@Composable
fun TripListScreen(showTiles: Boolean, palette: Palette) {
    val ctx = LocalContext.current
    val editScope = rememberCoroutineScope()
    var trips by remember { mutableStateOf<List<TripEntity>>(emptyList()) }
    var selected by remember { mutableStateOf<TripEntity?>(null) }
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var sort by remember { mutableStateOf(TripSort.RECENT) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var view by remember { mutableStateOf(TripView.DETAILS) }
    var detail by remember { mutableStateOf<TripDetail?>(null) }
    // Drives picked by long-press, for joining. Two at most is the useful number; a set because
    // the second long-press on a chosen drive un-chooses it.
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectMode by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val live by TripRecorderService.state.collectAsState()

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

    suspend fun reloadList() {
        trips = emptyList(); loadedPages = 0; allLoaded = false
        loadNextPage()
    }

    LaunchedEffect(Unit) {
        loadNextPage()
        selected = trips.firstOrNull()
    }
    // The recorder bumps this when it changes history under us — a merge, a hand-ended trip.
    val dataRev by TripRecorderService.dataChanged.collectAsState()
    LaunchedEffect(dataRev) {
        if (dataRev > 0) { reloadList(); selected = null; chosen = emptySet(); selectMode = false }
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
            // The estimate only stands in where nothing measured the drive. It never replaces a
            // measurement, and the prediction verdict below deliberately ignores it — scoring the
            // estimate against a figure derived from the same model would be marking its own work.
            val shownEnergy = energyKwh ?: t.estimatedEnergyKwh
            val shownCost = t.costInr ?: t.estimatedCostInr
            val kwhPer100 = BatteryMath.kwhPer100Km(shownEnergy, t.distanceM)
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
                impliedRateInr = if (shownEnergy != null && shownEnergy > 0 && shownCost != null) {
                    BatteryMath.round2(shownCost / shownEnergy)
                } else null,
                shownEnergyKwh = shownEnergy,
                shownCostInr = shownCost,
                energySource = t.energySource,
                // True when nothing measured this drive and the figures above are reckoned from
                // its distance and the app's learned consumption.
                estimated = energyKwh == null && t.estimatedEnergyKwh != null,
                // Scored against the drives that came before this one and nothing else, so the
                // figure is what the app would have told the driver that morning rather than one
                // worked out afterwards with the answer already in hand.
                verdict = energyKwh?.let {
                    RangeCalibration.verdict(
                        drive = EfficiencyStats.Sample(
                            t.startedAt, t.distanceM, t.movingS, it, t.avgTempC
                        ),
                        before = dao.efficiencySamplesBefore(t.startedAt).map { it.toSample() },
                        zone = Settings(ctx).zone
                    )
                },
                // The drive read as stretches, from the car's own odometer and energy counter.
                segments = TripSegments.of(dao.batteryRangeFor(t.id), Settings(ctx).batteryCapacityKwh),
                capacityKwh = Settings(ctx).batteryCapacityKwh,
                // Read-only for now, on purpose. This records whether the car drew the same
                // boundary the app inferred; it does not move the boundary. Acting on the
                // signal before there is enough history to know how this car numbers a
                // journey — per ignition, per drive, or something else — is the mistake that
                // `locked` and `canBusActive` already made twice.
                boundary = JourneyAgreement.verdict(dao.journeyIdsFor(t.id))
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
                // Enter a mode where tapping picks drives to join, instead of opening them. Far
                // more findable than a long-press nobody discovers, and it lives right beside the
                // list it acts on rather than in the far pane.
                if (!selectMode) {
                    Chip("SELECT TO JOIN", false, palette, m) {
                        selectMode = true; chosen = emptySet()
                    }
                }
            }
            // The join bar: visible only in select mode, right above the list.
            if (selectMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
                    modifier = Modifier.padding(start = m.pad, bottom = m.gap)
                ) {
                    val n = chosen.size
                    Chip(
                        if (merging) "JOINING…" else if (n == 2) "JOIN THESE 2" else "PICK 2 ($n)",
                        n == 2 && !merging, palette, m
                    ) {
                        if (merging || n != 2) return@Chip
                        merging = true
                        val (a, b) = chosen.toList()
                        // Recorder-thread merge: it closes the live drive first if one was picked,
                        // then joins. The list reloads when dataChanged fires.
                        TripRecorderService.requestMergeTrips(a, b)
                    }
                    Chip("CANCEL", false, palette, m) {
                        selectMode = false; chosen = emptySet(); merging = false
                    }
                }
                Text(
                    text = "Tap two drives to join — the current drive counts, so a stop can rejoin the last one.",
                    color = palette.dim, fontSize = m.label,
                    modifier = Modifier.padding(start = m.pad, bottom = m.gap / 2)
                )
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
                        is TripRowItem.Drive -> {
                            val id = item.trip.id
                            fun toggle() {
                                chosen = when {
                                    id in chosen -> chosen - id
                                    chosen.size >= 2 -> chosen // already two; ignore extra taps
                                    else -> chosen + id
                                }
                            }
                            TripRow(
                                item.trip, item.trip.id == selected?.id, id in chosen,
                                palette, m, fmt,
                                onClick = { if (selectMode) toggle() else selected = item.trip },
                                // Long-press still works as a shortcut into select mode.
                                onLongClick = { selectMode = true; toggle() }
                            )
                        }
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
                // The driver's own end to the drive. The arrival rule needs twelve minutes of
                // stillness; a driver who knows they have arrived does not.
                if (live.tripId >= 0) {
                    Chip("END TRIP", false, palette, m) { TripRecorderService.requestTripEnd() }
                }
            }
            when {
                view == TripView.DETAILS && (selected == null || detail != null) -> {
                    TripDetailPane(
                        trip = selected,
                        detail = detail,
                        palette = palette,
                        m = m,
                        fmt = fmt,
                        onSetMode = { id, mode ->
                            editScope.launch {
                                withContext(Dispatchers.IO) {
                                    OdographDb.get(ctx).dao().setDriveMode(id, mode)
                                    TripRecorderService.dataChanged.value =
                                        TripRecorderService.dataChanged.value + 1
                                }
                            }
                            if (selected?.id == id) selected = selected?.copy(driveMode = mode)
                        }
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
@OptIn(ExperimentalFoundationApi::class)
private fun TripRow(
    trip: TripEntity,
    active: Boolean,
    chosen: Boolean,
    palette: Palette,
    m: Metrics,
    fmt: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val hhmm = remember(fmt.timeZone) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = fmt.timeZone }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    chosen -> palette.track
                    active -> palette.trackSoft
                    else -> palette.ground
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = m.pad, vertical = m.gap / 2)
    ) {
        // When it began and when it ended, on one line. The end used to live three taps away.
        Text(
            text = fmt.format(Date(trip.startedAt)) +
                (trip.endedAt?.let { "  →  " + hhmm.format(Date(it)) } ?: "  →  …"),
            color = if (active || chosen) palette.accent else palette.numeral,
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
        // The outside temperature the drive was made in, as a range: a morning that started at
        // 19° and ended at 34° is not a 26° drive, and heat is the largest cost after speed.
        if (trip.minTempC != null && trip.maxTempC != null) {
            Text(
                text = if (trip.minTempC == trip.maxTempC) "outside %.0f°".format(trip.minTempC)
                else "outside %.0f–%.0f°".format(trip.minTempC, trip.maxTempC),
                color = palette.dim, fontSize = m.label
            )
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
    fmt: SimpleDateFormat,
    onSetMode: ((Long, String) -> Unit)? = null
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

        // Route as one line under the date — the question "where was this?" answered first.
        if (detail.startPlace != null || detail.endPlace != null) {
            Text(
                "${detail.startPlace ?: coords(t.startLat, t.startLon)}   →   ${detail.endPlace ?: coords(t.endLat, t.endLon)}",
                color = palette.accent, fontSize = m.body, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = m.gap / 2)
            )
        }

        // The one number the drive is judged by, and whether it beat what the car expected.
        TripHero(BatteryMath.kmPerKwh(detail.shownEnergyKwh, t.distanceM), detail.estimated, detail.verdict, palette, m)
        StatStrip(t, detail, palette, m)

        // The drive mode is entered by hand (the car does not report it) and can be set or changed
        // here at any time; the popup only asks when a mode is still blank.
        onSetMode?.let { set ->
            SectionLabel("DRIVE MODE", palette, m)
            DriveModeRow(t.driveMode, palette, m) { set(t.id, it) }
        }

        // The heart of the view: the drive read as stretches, so a long frugal opening and a
        // thirsty highway leg are told apart instead of averaged into one flat figure.
        detail.segments?.takeIf { it.segments.isNotEmpty() }?.let { bd ->
            SectionLabel("CONSUMPTION BY STRETCH", palette, m)
            ConsumptionSection(bd, palette, m)
        }

        SectionLabel("DETAILS", palette, m)
        TripDetailsGrid(t, palette, m)
        Spacer(Modifier.height(m.pad))
    }
}

/** The hero: overall km/kWh, and — when the drive was scored — how it did against what was expected. */
@Composable
private fun TripHero(kmPerKwh: Double?, estimated: Boolean, verdict: RangeCalibration.Verdict?, palette: Palette, m: Metrics) {
    Row(
        Modifier.padding(top = m.gap),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        Text(
            kmPerKwh?.let { "%.1f".format(it) } ?: "—",
            color = if (estimated) palette.caution else palette.numeral,
            fontSize = m.hero, fontWeight = FontWeight.Bold
        )
        Column(Modifier.padding(bottom = m.gap / 2)) {
            Text("km / kWh", color = palette.dim, fontSize = m.body, fontWeight = FontWeight.Medium)
            Text(
                if (estimated) "estimated — no link" else "overall efficiency",
                color = palette.dim, fontSize = m.label, letterSpacing = 1.sp
            )
            verdict?.let { v ->
                val projected = 100.0 / v.predictedKwhPer100Km
                val deltaPct = (v.predictedKwhPer100Km / v.actualKwhPer100Km - 1.0) * 100.0
                val up = deltaPct >= 0
                Text(
                    "${if (up) "▲" else "▼"} %.0f%% ${if (up) "over" else "under"} your usual %.1f".format(kotlin.math.abs(deltaPct), projected),
                    color = if (up) palette.good else palette.warn,
                    fontSize = m.body, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = m.gap / 4)
                )
            }
        }
    }
}

/** The supporting figures, as a wrapping strip of small label/value pairs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatStrip(t: TripEntity, detail: TripDetail, palette: Palette, m: Metrics) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = m.gap),
        horizontalArrangement = Arrangement.spacedBy(m.pad),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        MiniStat("DISTANCE", "${formatKm(t.distanceM)} km", palette, m)
        MiniStat("TIME", formatHhMm(t.durationS), palette, m)
        detail.shownEnergyKwh?.let { MiniStat(if (detail.estimated) "ENERGY EST" else "ENERGY", "%.1f kWh".format(it), palette, m) }
        detail.shownCostInr?.let { MiniStat("COST", "₹%.0f".format(it), palette, m) }
        MiniStat("AVG SPEED", "${mpsToKmh(t.avgSpeedMps.toFloat()).toInt()} km/h", palette, m)
    }
}

/** The secondary facts, demoted to one quiet wrapping grid instead of a page of rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripDetailsGrid(t: TripEntity, palette: Palette, m: Metrics) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = m.gap / 2),
        horizontalArrangement = Arrangement.spacedBy(m.pad),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        if (t.socStart != null && t.socEnd != null) MiniStat("CHARGE", "%.0f→%.0f%%".format(t.socStart, t.socEnd), palette, m)
        MiniStat("TOP SPEED", "${mpsToKmh(t.maxSpeedMps).toInt()} km/h", palette, m)
        if (t.minTempC != null && t.maxTempC != null) {
            MiniStat("OUTSIDE", if (t.minTempC == t.maxTempC) "%.0f°".format(t.minTempC) else "%.0f–%.0f°".format(t.minTempC, t.maxTempC), palette, m)
        }
        if (t.elevGainM > 0 || t.elevLossM > 0) {
            MiniStat("CLIMB", "↑%.0f m".format(t.elevGainM), palette, m)
            MiniStat("DESCENT", "↓%.0f m".format(t.elevLossM), palette, m)
        }
        t.driveMode?.let { MiniStat("MODE", it, palette, m) }
    }
}

@Composable
private fun MiniStat(label: String, value: String, palette: Palette, m: Metrics) {
    Column {
        Text(value, color = palette.numeral, fontSize = m.read, fontWeight = FontWeight.SemiBold)
        Text(label, color = palette.dim, fontSize = m.label, letterSpacing = 1.sp)
    }
}

private fun coords(lat: Double?, lon: Double?): String =
    if (lat != null && lon != null) "%.4f, %.4f".format(lat, lon) else "—"

// SectionLabel and DetailRow live in Widgets.kt: Insights draws the same rows.