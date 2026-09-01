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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.ui.map.BareRouteTrace
import `in`.odograph.tracker.ui.map.RouteMap
import `in`.odograph.tracker.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripListScreen(showTiles: Boolean, palette: Palette) {
    val ctx = LocalContext.current
    var trips by remember { mutableStateOf<List<TripEntity>>(emptyList()) }
    var selected by remember { mutableStateOf<TripEntity?>(null) }
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        trips = withContext(Dispatchers.IO) { OdographDb.get(ctx).dao().allTrips() }
        selected = trips.firstOrNull()
    }
    LaunchedEffect(selected?.id) {
        val id = selected?.id ?: return@LaunchedEffect
        route = withContext(Dispatchers.IO) {
            OdographDb.get(ctx).dao().pointsFor(id).map { it.lat to it.lon }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
    val m = rememberMetrics(maxWidth, maxHeight)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.34f).fillMaxHeight()) {
            Text(
                text = "DRIVES  ·  ${trips.size}",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = m.pad, top = m.pad, bottom = m.gap / 2)
            )
            if (trips.isEmpty()) {
                Text(
                    text = "No drives recorded yet.\nStart the car and go somewhere.",
                    color = palette.dim,
                    fontSize = m.body,
                    modifier = Modifier.padding(m.pad)
                )
            }
            LazyColumn {
                items(trips, key = { it.id }) { trip ->
                    val active = trip.id == selected?.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (active) palette.trackSoft else palette.ground)
                            .clickable { selected = trip }
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
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(0.66f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
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
