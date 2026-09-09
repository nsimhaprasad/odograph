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
 * The passenger view: live instrument, the route so far, and the full stat set.
 *
 * Arranged by aspect ratio for the same reason as [DriverScreen]. The map is the element that
 * suffers most from a wrong arrangement, because it is the only one whose usefulness scales with
 * the area it gets. In a short window, putting the stats in a row underneath steals the height
 * the map needs, so they move to a column beside it instead.
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
        val aspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)

        val gauge: @Composable (Modifier) -> Unit = { mod ->
            Gauge(
                speedKmh = smoothedKmh,
                hasFix = live.hasFix,
                direction = direction,
                palette = palette,
                modifier = mod,
                overLimit = live.overLimit
            )
        }
        val map: @Composable (Modifier) -> Unit = { mod ->
            if (showTiles) RouteMap(route, palette, mod) else BareRouteTrace(route, palette, mod)
        }

        when {
            aspect < 1.2f -> Column(
                modifier = Modifier.fillMaxSize().padding(m.pad),
                verticalArrangement = Arrangement.spacedBy(m.gap)
            ) {
                gauge(Modifier.fillMaxWidth().weight(1f))
                map(Modifier.fillMaxWidth().weight(1.6f))
                StatRow(live, slowestKmMps, palette, m, compact = true)
            }

            // Short and wide: the stats go beside the map so they do not eat its height.
            aspect > 3.0f -> Row(
                modifier = Modifier.fillMaxSize().padding(m.pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.gap)
            ) {
                gauge(Modifier.fillMaxHeight().weight(0.8f))
                map(Modifier.fillMaxHeight().weight(2.2f))
                Column(
                    modifier = Modifier.fillMaxHeight().weight(1.1f),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Stat(formatKm(live.distanceM), "KM   DIST", palette, m, size = m.stat)
                    Stat(formatHhMm(live.elapsedS), "ELAPSED", palette, m, size = m.stat)
                    Stat(
                        "${mpsToKmh(live.maxSpeedMps).toInt()}", "KM/H MAX",
                        palette, m, size = m.stat
                    )
                }
            }

            else -> Column(Modifier.fillMaxSize().padding(m.pad)) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.gap)
                ) {
                    gauge(Modifier.fillMaxHeight().weight(1f))
                    map(Modifier.fillMaxHeight().weight(2f))
                }
                StatRow(live, slowestKmMps, palette, m, compact = false)
            }
        }
    }
}

@Composable
private fun StatRow(
    live: TripRecorderService.LiveState,
    slowestKmMps: Double,
    palette: Palette,
    m: Metrics,
    compact: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = m.gap / 2),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Stat(formatKm(live.distanceM), "KM   DIST", palette, m, size = m.stat)
        Stat(formatHhMm(live.elapsedS), "ELAPSED", palette, m, size = m.stat)
        Stat(formatHhMm(live.movingS), "MOVING", palette, m, size = m.stat)
        Stat("${mpsToKmh(live.maxSpeedMps).toInt()}", "KM/H MAX", palette, m, size = m.stat)
        if (!compact) {
            // The honest replacement for "lowest speed", which is always zero at a traffic signal.
            Stat(
                if (slowestKmMps > 0) "${mpsToKmh(slowestKmMps.toFloat()).toInt()}" else "—",
                "KM/H SLOWEST", palette, m, size = m.stat
            )
        }
    }
}
