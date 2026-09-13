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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDb
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

    LaunchedEffect(Unit) {
        trips = withContext(Dispatchers.IO) { OdographDb.get(ctx).dao().allTrips() }
        selected = trips.firstOrNull()
    }
    LaunchedEffect(selected?.id) {
        val id = selected?.id ?: return@LaunchedEffect
        route = withContext(Dispatchers.IO) {
            val fixes = OdographDb.get(ctx).dao().pointsFor(id).map {
                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated, it.altitudeM)
            }
            buildSmoothRoute(fixes)
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
            LazyColumn {
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
            Modifier.weight(0.66f).fillMaxHeight().background(palette.ground).clipToBounds(),
            verticalArrangement = Arrangement.Center
        ) {
            if (route.isEmpty()) {
                Text(
                    text = "Select a drive",
                    color = palette.label,
                    fontSize = m.body,
                    modifier = Modifier.padding(m.pad),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else if (showTiles) {
                RouteMap(route, palette, Modifier.fillMaxSize())
            } else {
                BareRouteTrace(route, palette, Modifier.fillMaxSize())
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
        val usedPct = trip.socStart?.let { start ->
            trip.socEnd?.let { end -> start - end }
        }
        trip.batteryLine(usedPct)?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(it, color = palette.accent, fontSize = m.label)
            }
        }
    }
}

/**
 * One spare line under a drive describing what it did to the battery: percentage points used
 * (or gained, when it charged), kW·h, the cost when a rate is known, and the achieved mileage.
 * Every piece stays silent until it exists — no blanks are ever prettified into a fabricated 0.
 */
private fun TripEntity.batteryLine(usedPct: Double?): String? {
    val parts = mutableListOf<String>()
    if (usedPct != null) {
        parts += if (usedPct >= 0.05) "used %.0f%%".format(usedPct)
        else if (usedPct <= -0.05) "charged %.0f%%".format(-usedPct)
        else "SOC flat"
    }
    energyKwh?.let {
        if (it != 0.0) parts += "%.1f kWh".format(it)
    }
    if (usedPct != null && energyKwh != null && energyKwh > 0 && distanceM > 0) {
        parts += "%.1f km/kWh".format(BatteryMath.kmPerKwh(energyKwh, distanceM) ?: 0.0)
    }
    costInr?.let { parts += "₹ %.0f".format(it) }
    return if (parts.isEmpty()) null else parts.joinToString("  ·  ")
}