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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.Period
import `in`.odograph.tracker.core.Periods
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PeriodTotals
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RoutesUi(
    val totals: PeriodTotals = PeriodTotals(0, 0.0, 0, 0, 0f),
    val rows: List<RouteRow> = emptyList(),
    val places: List<PlaceRow> = emptyList(),
    val loaded: Boolean = false
)

data class PlaceRow(val id: Long, val name: String, val visits: Int, val named: Boolean)

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
    var editingId by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    var reloadToken by remember { mutableStateOf(0) }
    var showPlaces by remember { mutableStateOf(false) }

    LaunchedEffect(period, reloadToken) {
        val range = Periods.rangeFor(period, System.currentTimeMillis(), Settings(ctx).zone)
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
                    places = places.values
                        .sortedByDescending { it.visits }
                        .map { PlaceRow(it.id, it.displayName, it.visits, it.label != null) },
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

        // The period chips stay put and only what is under them scrolls. The root deliberately
        // does NOT scroll: this screen embeds the whole of PlacesScreen, which scrolls itself, and
        // a scrollable inside a scrollable is measured with an infinite height and throws.
        Column(Modifier.fillMaxSize().padding(m.pad)) {

            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                Period.entries.forEach { p ->
                    Chip(p.label, p == period && !showPlaces, palette, m) {
                        period = p
                        showPlaces = false
                    }
                }
                Chip("PLACES", showPlaces, palette, m) { showPlaces = true }
            }

            if (showPlaces) {
                // Already scrolling, and bounded by the space the chips left it.
                PlacesScreen(palette)
                return@BoxWithConstraints
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

            // The list is the scroller, so it takes the height the header left rather than its
            // natural size. Without the weight a short split-screen band gave the header every
            // pixel and measured the list at nothing: the routes were in the tree, drawn nowhere,
            // and unreachable. Wrapping the whole screen in a scroller instead would nest one
            // scrollable inside another, which Compose measures with an infinite height and throws.
            LazyColumn(Modifier.weight(1f)) {
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

                item {
                    Text(
                        text = "PLACES  ·  TAP TO NAME",
                        color = palette.label,
                        fontSize = m.label,
                        letterSpacing = 2.2.sp,
                        modifier = Modifier.padding(top = m.gap, bottom = m.gap / 2)
                    )
                    if (ui.places.isEmpty() && loaded) {
                        Text(
                            "Places appear once a drive has finished.",
                            color = palette.dim, fontSize = m.body
                        )
                    }
                }

                items(ui.places, key = { it.id }) { place ->
                    if (editingId == place.id) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 3),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                singleLine = true,
                                placeholder = { Text("Home, Office, Farm...", color = palette.dim, fontSize = m.body) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    savePlaceLabel(ctx, place.id, draft)
                                    editingId = null
                                    reloadToken++
                                }),
                                colors = textFieldColors(palette),
                                modifier = Modifier.weight(1f)
                            )
                            Chip("SAVE", true, palette, m) {
                                savePlaceLabel(ctx, place.id, draft)
                                editingId = null
                                reloadToken++
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingId = place.id
                                    draft = if (place.named) place.name else ""
                                }
                                .padding(vertical = m.gap / 3),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    color = if (place.named) palette.numeral else palette.dim,
                                    fontSize = m.stat,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (place.named) "tap to rename" else "tap to name this place",
                                    color = palette.label,
                                    fontSize = m.body
                                )
                            }
                            Column(Modifier.width(countWidth), horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${place.visits}",
                                    color = palette.accent2,
                                    fontSize = m.stat,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "VISITS",
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
}

/** Room refuses main-thread writes, and this is called straight from a tap. */
private fun savePlaceLabel(ctx: android.content.Context, id: Long, label: String) {
    val cleaned = label.trim().takeIf { it.isNotBlank() }
    Thread { runCatching { OdographDb.get(ctx).dao().setPlaceLabel(id, cleaned) } }.start()
}
