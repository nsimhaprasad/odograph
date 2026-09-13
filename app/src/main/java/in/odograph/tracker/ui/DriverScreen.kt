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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.gauge.Gauge
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import kotlin.math.roundToInt

/**
 * The window this draws into is not fixed. The box supports split screen with a divider the
 * driver can put anywhere, so the same 1920x1080 panel yields anything from 1291x726 dp down to
 * 1291x181 dp, or a narrow 645x726 dp column when split the other way.
 *
 * Scaling every dimension from the viewport keeps text and padding sane, but it cannot fix a
 * layout whose *structure* is wrong for the shape: a gauge beside a stats column is right at 2:1,
 * stranded either side of a void at 7:1, and worse than a vertical stack at 0.9:1. So the
 * arrangement branches on aspect ratio, and only the arrangement does — every size still comes
 * from the same metrics.
 */
@Composable
fun DriverScreen(
    live: TripRecorderService.LiveState,
    smoothedKmh: Float,
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

        when {
            aspect < 1.2f -> TallLayout(live, palette, m, gauge)
            aspect > 3.0f -> WideLayout(live, palette, m, gauge)
            else -> BalancedLayout(live, palette, m, gauge)
        }
    }
}

/** Narrow column: stack the instrument above the numbers rather than squeezing them side by side. */
@Composable
private fun TallLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    gauge: @Composable (Modifier) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        gauge(Modifier.fillMaxWidth().weight(1.4f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m, size = m.stat)
            Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m, size = m.stat)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SmallStat("${mpsToKmh(live.maxSpeedMps).toInt()}", "MAX", palette, m)
            SmallStat(formatHhMm(live.movingS), "MOVING", palette, m)
            if (live.speedLimitKmh > 0) {
                SmallStat("${live.speedLimitKmh}", "LIMIT", palette, m)
            }
            live.batterySocPercent?.let {
                SmallStat(
                    "${it.roundToInt()}",
                    if (live.batteryCharging == true) "CHARGING" else "BATTERY",
                    palette, m,
                    onClick = { TripRecorderService.requestTelematicsRefresh() }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            live.batteryMileageKmPerKwh?.let {
                SmallStat("%.1f km/kWh".format(it), "MILEAGE", palette, m)
            }
            live.batteryRangeAtFullKm?.let {
                SmallStat("%.0f km".format(it), "RANGE@100", palette, m)
            }
            SmallStat("%.1f kWh".format(live.batteryTotalKwh), "LIFETIME", palette, m)
            if (live.elevGainM > 0 || live.elevLossM > 0) {
                SmallStat(
                    "↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "CLIMB  M", palette, m
                )
            }
        }
    }
}

/** A thin band across the top or bottom of the screen: spread the numbers along the width. */
@Composable
private fun WideLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    gauge: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        gauge(Modifier.fillMaxHeight().weight(0.9f))
        Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat("${mpsToKmh(live.maxSpeedMps).toInt()}", "KM/H   MAX", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat(formatHhMm(live.movingS), "H:MM   MOVING", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        live.batterySocPercent?.let {
            Stat(
                "${it.roundToInt()}",
                if (live.batteryCharging == true) "SOC   CHARGING" else "SOC   BATTERY",
                palette, m, size = m.stat,
                modifier = Modifier
                    .weight(1f)
                    .clickable { TripRecorderService.requestTelematicsRefresh() }
            )
        }
        live.batteryRangeAtFullKm?.let {
            Stat("%.0f".format(it), "KM RANGE@100", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
        live.batteryMileageKmPerKwh?.let {
            Stat("%.1f".format(it), "KM/KWH", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
        Stat("%.1f".format(live.batteryTotalKwh), "KWH  LIFETIME", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        if (live.elevGainM > 0 || live.elevLossM > 0) {
            Stat(
                "↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "M  CLIMB",
                palette, m, size = m.stat, modifier = Modifier.weight(1f)
            )
        }
        if (live.speedLimitKmh > 0) {
            Stat("${live.speedLimitKmh}", "KM/H   LIMIT", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
    }
}

/** The ordinary case: instrument on the left, the two numbers that matter stacked on the right. */
@Composable
private fun BalancedLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    gauge: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        gauge(Modifier.weight(1f).fillMaxHeight())
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
                if (live.speedLimitKmh > 0) {
                    SmallStat("${live.speedLimitKmh}", "LIMIT", palette, m)
                }
                live.batterySocPercent?.let {
                    SmallStat(
                        "${it.roundToInt()}",
                        if (live.batteryCharging == true) "CHARGING" else "BATTERY",
                        palette, m,
                        onClick = { TripRecorderService.requestTelematicsRefresh() }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = m.gap),
                horizontalArrangement = Arrangement.spacedBy(m.gap)
            ) {
                live.batteryMileageKmPerKwh?.let {
                    SmallStat("%.1f km/kWh".format(it), "MILEAGE", palette, m)
                }
                live.batteryRangeAtFullKm?.let {
                    SmallStat("%.0f km".format(it), "RANGE@100", palette, m)
                }
                SmallStat("%.1f kWh".format(live.batteryTotalKwh), "LIFETIME", palette, m)
                if (live.elevGainM > 0 || live.elevLossM > 0) {
                    SmallStat(
                        "↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "CLIMB  M", palette, m
                    )
                }
            }
        }
    }
}
