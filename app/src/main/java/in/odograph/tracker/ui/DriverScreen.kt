package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    Row(
        modifier = Modifier.fillMaxSize().background(palette.ground).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Gauge(
            speedKmh = smoothedKmh,
            hasFix = live.hasFix,
            direction = direction,
            palette = palette,
            modifier = Modifier.weight(1.1f).fillMaxHeight()
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Stat(formatKm(live.distanceM), "KM   DISTANCE", palette)
            Spacer(Modifier.height(34.dp))
            Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette)
            Spacer(Modifier.height(34.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                SmallStat("${mpsToKmh(live.maxSpeedMps).toInt()} km/h", "MAX", palette)
                SmallStat(formatHhMm(live.movingS), "MOVING", palette)
            }
        }
    }
}
