package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    slowestKmMps: Double,
    showTiles: Boolean,
    direction: Direction,
    palette: Palette
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        Column(Modifier.fillMaxSize().padding(m.pad)) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Gauge(
                    speedKmh = smoothedKmh,
                    hasFix = live.hasFix,
                    direction = direction,
                    palette = palette,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    overLimit = live.overLimit
                )
                if (showTiles) {
                    RouteMap(route, palette, Modifier.weight(2f).fillMaxHeight())
                } else {
                    BareRouteTrace(route, palette, Modifier.weight(2f).fillMaxHeight())
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = m.gap),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Stat(formatKm(live.distanceM), "KM   DIST", palette, m, size = m.stat)
                Stat(formatHhMm(live.elapsedS), "ELAPSED", palette, m, size = m.stat)
                Stat(formatHhMm(live.movingS), "MOVING", palette, m, size = m.stat)
                Stat("${mpsToKmh(live.maxSpeedMps).toInt()}", "KM/H MAX", palette, m, size = m.stat)
                // The honest replacement for "lowest speed", which is always zero at a signal:
                // the worst rolling kilometre of the drive.
                Stat(
                    if (slowestKmMps > 0) "${mpsToKmh(slowestKmMps.toFloat()).toInt()}" else "—",
                    "KM/H SLOWEST KM", palette, m, size = m.stat
                )
            }
        }
    }
}
