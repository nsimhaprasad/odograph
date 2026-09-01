package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.gauge.Gauge
import `in`.odograph.tracker.ui.map.BareRouteTrace
import `in`.odograph.tracker.ui.map.RouteMap
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette

/**
 * The passenger view. Attention budget is not a constraint here, so it can be dense: live gauge,
 * the route so far, and the full stat set.
 */
@Composable
fun DetailScreen(
    live: TripRecorderService.LiveState,
    smoothedKmh: Float,
    route: List<Pair<Double, Double>>,
    showTiles: Boolean,
    direction: Direction,
    palette: Palette
) {
    Column(Modifier.fillMaxSize().background(palette.ground).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Gauge(
                speedKmh = smoothedKmh,
                hasFix = live.hasFix,
                direction = direction,
                palette = palette,
                modifier = Modifier.weight(0.9f).fillMaxHeight()
            )
            if (showTiles) {
                RouteMap(route, palette, Modifier.weight(2f).fillMaxHeight())
            } else {
                BareRouteTrace(route, palette, Modifier.weight(2f).fillMaxHeight())
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, size = 34)
            Stat(formatHhMm(live.elapsedS), "H:MM   ELAPSED", palette, size = 34)
            Stat(formatHhMm(live.movingS), "H:MM   MOVING", palette, size = 34)
            Stat("${mpsToKmh(live.maxSpeedMps).toInt()}", "KM/H   MAX", palette, size = 34)
            Stat("${route.size}", "FIXES   LOGGED", palette, size = 34)
        }
    }
}
