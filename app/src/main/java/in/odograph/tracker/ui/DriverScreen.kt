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
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette

@Composable
fun DriverScreen(
    live: TripRecorderService.LiveState,
    smoothedKmh: Float,
    direction: Direction,
    palette: Palette
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        Row(
            modifier = Modifier.fillMaxSize().padding(m.pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Gauge(
                speedKmh = smoothedKmh,
                hasFix = live.hasFix,
                direction = direction,
                palette = palette,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m)
                Column(Modifier.padding(top = m.gap)) {
                    Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = m.gap),
                    horizontalArrangement = Arrangement.spacedBy(m.gap)
                ) {
                    SmallStat("${mpsToKmh(live.maxSpeedMps).toInt()}", "MAX", palette, m)
                    SmallStat(formatHhMm(live.movingS), "MOVING", palette, m)
                }
            }
        }
    }
}
